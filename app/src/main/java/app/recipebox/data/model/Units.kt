package app.recipebox.data.model

import java.net.URI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * How amounts are shown. Recipes are stored as imported; conversion happens on the way to the screen.
 *  - METRIC: g, kg, ml, l and °C.
 *  - IMPERIAL_UK: oz and lb, fluid ounces and British pints, and °C (British ovens are Celsius).
 *  - IMPERIAL_US: oz and lb, cups, and °F.
 */
enum class UnitSystem { METRIC, IMPERIAL_UK, IMPERIAL_US }

/**
 * The settings for converting one recipe: the system to show, plus which flavour of
 * cups, pints and fluid ounces the recipe itself was written in, guessed from where it came from.
 * A British pint is 568 ml and an American one 473 ml; Australian and British cups are 250 ml.
 */
data class Measures(val system: UnitSystem, val britishVolumes: Boolean = false, val cupMl: Double = US_CUP_ML) {
    companion object {
        const val US_CUP_ML = 240.0

        fun forRecipe(system: UnitSystem, sourceUrl: String?): Measures {
            val host = sourceUrl?.let { runCatching { URI(it).host }.getOrNull() }.orEmpty().lowercase()
            val british = listOf(".uk", ".ie").any { host.endsWith(it) }
            val metricCups = british || listOf(".au", ".nz").any { host.endsWith(it) }
            return Measures(system, britishVolumes = british, cupMl = if (metricCups) 250.0 else US_CUP_ML)
        }
    }
}

/** Grams per cup for dry ingredients people weigh rather than scoop. Checked in order, so "brown sugar" beats "sugar". */
private val cupWeights: List<Pair<String, Double>> = listOf(
    "icing sugar" to 120.0, "powdered sugar" to 120.0, "confectioners" to 120.0,
    "brown sugar" to 200.0, "caster sugar" to 200.0, "granulated sugar" to 200.0, "sugar" to 200.0,
    "almond flour" to 100.0, "ground almonds" to 100.0, "cornflour" to 120.0, "cornstarch" to 120.0, "flour" to 125.0,
    "cocoa" to 85.0, "oats" to 90.0, "rice" to 190.0, "couscous" to 175.0, "quinoa" to 170.0,
    "breadcrumbs" to 60.0, "panko" to 50.0, "parmesan" to 100.0, "grated cheese" to 100.0, "cheddar" to 100.0,
    "chocolate chips" to 170.0, "raisins" to 150.0, "sultanas" to 150.0, "nuts" to 130.0, "walnuts" to 120.0,
    "pecans" to 110.0, "desiccated coconut" to 80.0, "lentils" to 190.0, "peanut butter" to 250.0, "butter" to 227.0,
)

private const val G_PER_OZ = 28.3495
private const val G_PER_LB = 453.592
private const val G_PER_BUTTER_STICK = 113.0
private const val UK_PINT_ML = 568.261
private const val UK_FL_OZ_ML = 28.413

private fun gramsPer(unit: String): Double? = when (unit) {
    "g" -> 1.0
    "kg" -> 1000.0
    "oz" -> G_PER_OZ
    "lb" -> G_PER_LB
    else -> null
}

/** Millilitres for a volume unit, or null if it isn't one we convert. tsp and tbsp are left alone in every system. */
private fun mlPer(unit: String, m: Measures): Double? = when (unit) {
    "ml" -> 1.0
    "l" -> 1000.0
    "cup" -> m.cupMl
    "fl oz" -> if (m.britishVolumes) UK_FL_OZ_ML else 29.5735
    "pint" -> if (m.britishVolumes) UK_PINT_ML else 473.176
    "quart" -> if (m.britishVolumes) 1136.52 else 946.353
    "gallon" -> if (m.britishVolumes) 4546.09 else 3785.41
    else -> null
}

private val britishOrAmericanVolumes = setOf("fl oz", "pint", "quart", "gallon")

/** An amount converted for display: low end, optional high end, and unit. */
data class Converted(val quantity: Double, val quantityMax: Double?, val unit: String)

/**
 * Converts [quantity] (and [quantityMax]) of [unit] into [measures]' system, rounded to
 * kitchen-friendly numbers. [name] is what's being measured, which decides whether cups
 * become a weight ("flour") or a volume ("milk"). Returns null when nothing needs
 * converting: already in the right units, a spoon measure, or a count like "2 cloves".
 */
fun convertAmount(quantity: Double, quantityMax: Double?, unit: String?, name: String, measures: Measures): Converted? {
    val u = unit?.lowercase() ?: return null
    val butter = "butter" in name.lowercase()
    fun g(perUnit: Double) = Pair(quantity * perUnit, quantityMax?.times(perUnit))

    return when (measures.system) {
        UnitSystem.METRIC -> {
            if (u in setOf("g", "kg", "ml", "l")) return null
            if (u == "stick" && butter) return g(G_PER_BUTTER_STICK).let { (a, b) -> grams(a, b) }
            gramsPer(u)?.let { return g(it).let { (a, b) -> grams(a, b) } }
            val ml = mlPer(u, measures) ?: return null
            // Cups of flour and the like are weighed in metric kitchens, not poured.
            cupWeight(u, name, measures)?.let { perMl -> return grams(quantity * ml * perMl, quantityMax?.times(ml * perMl)) }
            millilitres(quantity * ml, quantityMax?.times(ml))
        }
        UnitSystem.IMPERIAL_UK -> {
            if (u == "oz" || u == "lb") return null
            if (u == "stick" && butter) return g(G_PER_BUTTER_STICK).let { (a, b) -> ounces(a, b) }
            gramsPer(u)?.let { return g(it).let { (a, b) -> ounces(a, b) } }
            // British pints and fluid ounces are already right; American ones aren't.
            if (u in britishOrAmericanVolumes && measures.britishVolumes) return null
            val ml = mlPer(u, measures) ?: return null
            cupWeight(u, name, measures)?.let { perMl -> return ounces(quantity * ml * perMl, quantityMax?.times(ml * perMl)) }
            britishVolume(quantity * ml, quantityMax?.times(ml))
        }
        UnitSystem.IMPERIAL_US -> {
            if (u in setOf("oz", "lb", "cup", "stick")) return null
            gramsPer(u)?.let { return g(it).let { (a, b) -> ounces(a, b) } }
            // American pints and fluid ounces are already right; British ones aren't.
            if (u in britishOrAmericanVolumes && !measures.britishVolumes) return null
            val ml = mlPer(u, measures) ?: return null
            cups(quantity * ml, quantityMax?.times(ml))
        }
    }
}

/** Grams per millilitre if [unit] is cups of something usually weighed, else null. */
private fun cupWeight(unit: String, name: String, measures: Measures): Double? {
    if (unit != "cup") return null
    val lower = name.lowercase()
    return cupWeights.firstOrNull { (key, _) -> key in lower }?.let { (_, perCup) -> perCup / measures.cupMl }
}

private fun grams(g: Double, gMax: Double?): Converted {
    val top = gMax ?: g
    return if (top >= 1000) Converted(roundTo(g / 1000, 0.1), gMax?.let { roundTo(it / 1000, 0.1) }, "kg")
    else Converted(roundMetric(g), gMax?.let(::roundMetric), "g")
}

private fun millilitres(ml: Double, mlMax: Double?): Converted {
    val top = mlMax ?: ml
    return if (top >= 1000) Converted(roundTo(ml / 1000, 0.1), mlMax?.let { roundTo(it / 1000, 0.1) }, "l")
    else Converted(roundMetric(ml), mlMax?.let(::roundMetric), "ml")
}

/** Below a pound in ounces (to the ¼ or ½), from a pound up in pounds to the ¼. */
private fun ounces(g: Double, gMax: Double?): Converted {
    val top = gMax ?: g
    return if (top >= G_PER_LB) Converted(roundTo(g / G_PER_LB, 0.25), gMax?.let { roundTo(it / G_PER_LB, 0.25) }, "lb")
    else {
        fun oz(v: Double) = (v / G_PER_OZ).let { if (it < 4) roundTo(it, 0.25) else roundTo(it, 0.5) }
        Converted(oz(g), gMax?.let(::oz), "oz")
    }
}

/** Spoons for small amounts (fine in any system), then cups to the nearest handy fraction. */
private fun cups(ml: Double, mlMax: Double?): Converted {
    val top = mlMax ?: ml
    val cup = Measures.US_CUP_ML
    return spoons(ml, mlMax, top, below = cup / 4)
        ?: Converted(roundCups(ml / cup), mlMax?.let { roundCups(it / cup) }, "cup")
}

/** Spoons for small amounts, then fluid ounces, then British pints from half a pint up. */
private fun britishVolume(ml: Double, mlMax: Double?): Converted {
    val top = mlMax ?: ml
    spoons(ml, mlMax, top, below = 45.0)?.let { return it }
    return if (top >= UK_PINT_ML / 2) Converted(roundTo(ml / UK_PINT_ML, 0.25), mlMax?.let { roundTo(it / UK_PINT_ML, 0.25) }, "pint")
    else {
        fun floz(v: Double) = (v / UK_FL_OZ_ML).let { if (it < 4) roundTo(it, 0.5) else roundTo(it, 1.0) }
        Converted(floz(ml), mlMax?.let(::floz), "fl oz")
    }
}

private fun spoons(ml: Double, mlMax: Double?, top: Double, below: Double): Converted? = when {
    top < 15 -> Converted(roundTo(ml / 5, 0.25), mlMax?.let { roundTo(it / 5, 0.25) }, "tsp")
    top < below -> Converted(roundTo(ml / 15, 0.5), mlMax?.let { roundTo(it / 15, 0.5) }, "tbsp")
    else -> null
}

/** 7 → 7, 23 → 25, 237 → 240, 3.3 → 3.5. Precise enough to cook with, without false precision. */
private fun roundMetric(v: Double): Double = when {
    v < 5 -> roundTo(v, 0.5)
    v < 20 -> roundTo(v, 1.0)
    v < 100 -> roundTo(v, 5.0)
    else -> roundTo(v, 10.0)
}

private val cupFractions = listOf(0.0, 0.25, 1.0 / 3, 0.5, 2.0 / 3, 0.75, 1.0)

private fun roundCups(v: Double): Double {
    val whole = floor(v)
    val frac = cupFractions.minBy { abs(it - (v - whole)) }
    return (whole + frac).coerceAtLeast(0.25)
}

private fun roundTo(v: Double, step: Double): Double = ((v / step).roundToInt() * step).let { if (it == 0.0 && v > 0) step else it }

/** "450 g", "1.4 kg", "2 cups", "7 fl oz": metric in decimals, everything else in fractions. */
fun formatAmount(c: Converted): String {
    val format: (Double) -> String = if (c.unit in setOf("g", "kg", "ml", "l")) ::formatDecimal else ::formatQuantity
    val qty = format(c.quantity) + (c.quantityMax?.let { "–" + format(it) } ?: "")
    return "$qty ${pluralUnit(c.unit, c.quantityMax ?: c.quantity)}"
}

// ---------- Amounts and temperatures in method text ----------

private const val NUM = """(?:\d+\s+\d+/\d+|\d+/\d+|\d+(?:\.\d+)?[½¼¾⅓⅔]?|[½¼¾⅓⅔])"""

private val textUnits = mapOf(
    "g" to "g", "gram" to "g", "grams" to "g", "kg" to "kg", "kilo" to "kg", "kilos" to "kg", "kilogram" to "kg", "kilograms" to "kg",
    "ml" to "ml", "millilitre" to "ml", "millilitres" to "ml", "milliliter" to "ml", "milliliters" to "ml",
    "l" to "l", "litre" to "l", "litres" to "l", "liter" to "l", "liters" to "l",
    "oz" to "oz", "ounce" to "oz", "ounces" to "oz", "lb" to "lb", "lbs" to "lb", "pound" to "lb", "pounds" to "lb",
    "cup" to "cup", "cups" to "cup", "pint" to "pint", "pints" to "pint", "quart" to "quart", "quarts" to "quart",
    "stick" to "stick", "sticks" to "stick",
    // Spoons are never converted, but they are scaled with the servings.
    "tsp" to "tsp", "teaspoon" to "tsp", "teaspoons" to "tsp",
    "tbsp" to "tbsp", "tbs" to "tbsp", "tablespoon" to "tbsp", "tablespoons" to "tbsp",
)

/** An amount and unit in running text: "200g", "1 1/2 cups", "2–3 lb", "4 fl oz", "2 tbsp". */
private val textAmount = Regex(
    """(?<![\w.])($NUM)(?:\s*(?:-|–|to)\s*($NUM))?\s*(fl\.?\s?oz|""" +
        textUnits.keys.sortedByDescending { it.length }.joinToString("|") +
        """)\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * Amounts in step text, scaled by [factor] (the servings) and put into [measures]' system:
 * "2 cups flour" → "250 g (2 cups) flour"; doubled, "500 g (4 cups) flour". The bracketed
 * original is scaled too, so the two always agree. Spoons are scaled but never converted.
 * Amounts the recipe already pairs with a conversion, as in "200g/7oz" or "200g (7oz)",
 * are scaled but not converted again, so the recipe's own conversions aren't doubled up.
 * Bare counts ("3 eggs") are left alone: in a method they're as often pieces or batches.
 */
fun convertAmountsInText(text: String, measures: Measures, factor: Double = 1.0): String = textAmount.replace(text) { m ->
    val low = parseAmountNumber(m.groupValues[1])?.times(factor) ?: return@replace m.value
    val high = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { (parseAmountNumber(it) ?: return@replace m.value) * factor }
    val rawUnit = m.groupValues[3].lowercase()
    val unit = if (rawUnit.startsWith("fl")) "fl oz" else textUnits[rawUnit] ?: return@replace m.value
    // The amount as written, scaled: what goes in brackets, or on its own if there's nothing to convert.
    val original = if (factor == 1.0) m.value.trim() else formatAmount(tidy(low, high, unit))
    if (!pairedWithConversion(text, m.range)) {
        // What's being measured, for cups of flour vs cups of milk: the next few words, up to any punctuation.
        val what = text.substring(m.range.last + 1).split(',', '.', ';', ':', '(').first()
            .trim().split(Regex("\\s+")).take(4).joinToString(" ")
        convertAmount(low, high, unit, what, measures)?.let { return@replace "${formatAmount(it)} ($original)" }
    }
    original
}

/** True for either half of "200g (7oz)" or "200g/7oz". */
private fun pairedWithConversion(text: String, range: IntRange): Boolean {
    val before = text.substring(0, range.first).trimEnd().lastOrNull()
    val after = text.substring(range.last + 1).trimStart().firstOrNull()
    return before == '(' || before == '/' || after == '(' || after == '/'
}

/** A scaled amount rounded the way its unit is usually written. */
private fun tidy(low: Double, high: Double?, unit: String): Converted {
    val round: (Double) -> Double = when (unit) {
        "g", "ml" -> ::roundMetric
        "kg", "l" -> { v -> roundTo(v, 0.1) }
        "oz" -> { v -> if (v < 4) roundTo(v, 0.25) else roundTo(v, 0.5) }
        "fl oz" -> { v -> if (v < 4) roundTo(v, 0.5) else roundTo(v, 1.0) }
        "tsp", "tbsp" -> { v -> roundTo(v, 0.25) }
        else -> { v -> v } // cups, pounds, pints: formatQuantity finds the nearest fraction
    }
    return Converted(round(low), high?.let(round), unit)
}

private val temperature = Regex(
    """(?<![\d.(])(\d{2,3})\s*(?:°\s*|º\s*|degrees?\s+)?(C|F|Celsius|Fahrenheit|centigrade)\b(?!\s*[(/])""",
    RegexOption.IGNORE_CASE,
)

/**
 * Converts cooking temperatures in step text, keeping the original in brackets:
 * "Bake at 350°F" → "Bake at 180°C (350°F)". Metric and UK imperial use °C, US imperial °F.
 * Only numbers written with a C/F marker are touched, so step numbers and times are safe.
 */
fun convertTemperatures(text: String, system: UnitSystem): String = temperature.replace(text) { m ->
    val value = m.groupValues[1].toInt()
    val fahrenheit = m.groupValues[2].first().uppercaseChar() == 'F'
    val wantFahrenheit = system == UnitSystem.IMPERIAL_US
    when {
        !wantFahrenheit && fahrenheit && value in 150..550 -> {
            val c = ((value - 32) * 5.0 / 9 / 10).roundToInt() * 10
            "$c°C (${m.value.trim()})"
        }
        wantFahrenheit && !fahrenheit && value in 60..290 -> {
            val f = ((value * 9.0 / 5 + 32) / 25).roundToInt() * 25
            "$f°F (${m.value.trim()})"
        }
        else -> m.value
    }
}

/** Step text with amounts scaled by [factor] and, like oven temperatures, in [measures]' units. */
fun convertText(text: String, measures: Measures, factor: Double = 1.0): String =
    convertTemperatures(convertAmountsInText(text, measures, factor), measures.system)

/** The recipe's step and Get ready text, scaled and in [measures]' units. Ingredients are handled line by line instead. */
fun Recipe.textIn(measures: Measures, factor: Double = 1.0): Recipe = copy(
    instructions = instructions.map { s -> s.copy(steps = s.steps.map { convertText(it, measures, factor) }) },
    originalInstructions = originalInstructions.map { s -> s.copy(steps = s.steps.map { convertText(it, measures, factor) }) },
    prep = prep.map { it.copy(text = convertText(it.text, measures, factor)) },
)
