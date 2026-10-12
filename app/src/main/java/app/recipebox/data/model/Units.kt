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

/**
 * A number as recipes write it: "2", "1.5", "1/2", "1 1/2", "1½", "1 ½", "½", and in words
 * "a", "an", "half a", "one" to "twelve" (words only when a space follows, so "again" isn't "a g").
 */
private const val NUM = """(?:\d+\s+\d+/\d+|\d+\s+[½¼¾⅓⅔]|\d+/\d+|\d+(?:\.\d+)?[½¼¾⅓⅔]?|[½¼¾⅓⅔]|""" +
    """(?:half(?:\s+an?)?|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)(?=\s))"""

/** Reads a [NUM] match: digits, fractions or words. */
private fun readNumber(text: String): Double? {
    val t = text.trim().lowercase()
    return when {
        t == "a" || t == "an" -> 1.0
        t.startsWith("half") -> 0.5
        else -> numberWords[t] ?: parseAmountNumber(t)
    }
}

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
fun convertAmountsInText(text: String, measures: Measures, factor: Double = 1.0): String {
    val prepared = preferTargetInPairs(text, measures)
    return textAmount.replace(prepared) { m -> convertOne(m, prepared, measures, factor) }
}

private fun convertOne(m: MatchResult, text: CharSequence, measures: Measures, factor: Double): String {
    val low = readNumber(m.groupValues[1])?.times(factor) ?: return m.value
    val high = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { (readNumber(it) ?: return m.value) * factor }
    val unit = unitOf(m.groupValues[3]) ?: return m.value
    // The amount as written, scaled: what goes in brackets, or on its own if there's nothing to convert.
    val original = if (factor == 1.0) m.value.trim() else formatAmount(tidy(low, high, unit))
    if (!pairedWithConversion(text, m.range)) {
        convertAmount(low, high, unit, wordsAfter(text, m.range), measures)?.let { return "${formatAmount(it)} ($original)" }
    }
    return original
}

private fun unitOf(written: String): String? {
    val u = written.lowercase()
    return if (u.startsWith("fl")) "fl oz" else textUnits[u]
}

/** What's being measured, for cups of flour vs cups of milk: the next few words, up to any punctuation. */
private fun wordsAfter(text: CharSequence, range: IntRange): String =
    text.substring(range.last + 1).split(',', '.', ';', ':', '(').first()
        .trim().split(Regex("\\s+")).take(4).joinToString(" ")

/** True for either half of "200g (7oz)" or "200g/7oz". */
private fun pairedWithConversion(text: CharSequence, range: IntRange): Boolean {
    val before = text.substring(0, range.first).trimEnd().lastOrNull()
    val after = text.substring(range.last + 1).trimStart().firstOrNull()
    return before == '(' || before == '/' || after == '(' || after == '/'
}

/** Whether [unit] already belongs to the system being shown. Spoons belong to none: they're never swapped in. */
private fun inSystem(unit: String, measures: Measures): Boolean = when (measures.system) {
    UnitSystem.METRIC -> unit in setOf("g", "kg", "ml", "l")
    UnitSystem.IMPERIAL_UK -> unit in setOf("oz", "lb") || (unit in britishOrAmericanVolumes && measures.britishVolumes)
    UnitSystem.IMPERIAL_US -> unit in setOf("oz", "lb", "cup", "stick") || (unit in britishOrAmericanVolumes && !measures.britishVolumes)
}

private val textAmountPair by lazy {
    val one = textAmount.pattern.removePrefix("(?<![\\w.])")
    Regex("""(?<![\w.])($one)\s*(\(\s*|/\s*)($one)(\s*\))?""", RegexOption.IGNORE_CASE)
}

/**
 * Where the recipe gives an amount both ways, puts the one in the chosen system first:
 * in metric, "1 cup (240ml) milk" reads "240ml (1 cup) milk". If neither side is in the
 * chosen system ("1 cup (240ml)" for UK imperial), the first is converted and the second dropped.
 */
private fun preferTargetInPairs(text: String, measures: Measures): String = textAmountPair.replace(text) { m ->
    val first = textAmount.matchEntire(m.groupValues[1]) ?: return@replace m.value
    // Groups: 1 = first amount (2–4 inside it), 5 = "(" or "/", 6 = second amount (7–9 inside), 10 = ")".
    val second = textAmount.matchEntire(m.groupValues[6]) ?: return@replace m.value
    val firstUnit = unitOf(first.groupValues[3]) ?: return@replace m.value
    val secondUnit = unitOf(second.groupValues[3]) ?: return@replace m.value
    val bracketed = m.groupValues[5].trim().startsWith("(")
    fun join(a: String, b: String) = if (bracketed) "$a ($b)" else "$a/$b"
    when {
        inSystem(firstUnit, measures) -> m.value
        inSystem(secondUnit, measures) -> join(second.value.trim(), first.value.trim())
        else -> {
            val low = readNumber(first.groupValues[1]) ?: return@replace m.value
            val high = first.groupValues[2].takeIf { it.isNotEmpty() }?.let { readNumber(it) ?: return@replace m.value }
            val after = IntRange(m.range.last, m.range.last)
            convertAmount(low, high, firstUnit, wordsAfter(text, after), measures)
                ?.let { join(formatAmount(it), first.value.trim()) } ?: m.value
        }
    }
}

/** A scaled amount rounded the way its unit is usually written. */
private fun tidy(low: Double, high: Double?, unit: String): Converted {
    // Metric moves between g and kg (ml and l) as it grows or shrinks: 500 g doubled is 1 kg.
    when (unit) {
        "g" -> return grams(low, high)
        "kg" -> return grams(low * 1000, high?.times(1000))
        "ml" -> return millilitres(low, high)
        "l" -> return millilitres(low * 1000, high?.times(1000))
    }
    val round: (Double) -> Double = when (unit) {
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

// ---------- Counted ingredients in method text ----------

private val numberWords = mapOf(
    "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0,
    "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0, "twelve" to 12.0,
)

/** Words that can sit between a count and what's counted: "3 large eggs", "2 of the onions". */
private val countFiller = setOf(
    "of", "the", "large", "small", "medium", "whole", "fresh", "free-range", "ripe", "big", "extra",
    "more", "remaining", "beaten", "peeled", "red", "white", "brown", "yellow", "green", "spring", "baby",
)

private fun pluralOf(word: String): String = when {
    word.endsWith("s") -> word
    word.endsWith("o") || word.endsWith("ch") || word.endsWith("sh") || word.endsWith("x") -> word + "es"
    word.endsWith("y") && word.length > 2 && word[word.length - 2] !in "aeiou" -> word.dropLast(1) + "ies"
    else -> word + "s"
}

private fun singularOf(word: String): String = when {
    word.endsWith("ies") -> word.dropLast(3) + "y"
    word.endsWith("oes") || word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes") -> word.dropLast(2)
    word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
    else -> word
}

/**
 * Other names a method commonly counts an ingredient by, built in so recipes read without
 * Claude (and older imports) still scale them: eggs become "yolks" and "whites". Claude finds
 * the rest on import ([Recipe.countWords]), and logs a "counts" lesson for any it finds that
 * aren't here, so this list can grow.
 */
val countAliases: Map<String, List<String>> = mapOf(
    "egg" to listOf("yolk", "white"),
    "chicken" to listOf("breast", "thigh", "leg", "drumstick", "fillet"),
    "salmon" to listOf("fillet"), "cod" to listOf("fillet"), "haddock" to listOf("fillet"),
    "hake" to listOf("fillet"), "fish" to listOf("fillet"),
    "pork" to listOf("chop", "steak"), "lamb" to listOf("chop", "shank"), "beef" to listOf("steak"),
    "bread" to listOf("slice"), "tortilla" to listOf("wrap"),
)

private fun nameWords(ing: Ingredient): List<String> =
    ing.name.lowercase().split(Regex("[^a-z-]+")).filter { it.length > 2 && it !in countFiller }

/**
 * The words that name a counted ingredient in this recipe, singular and plural: for "3 large eggs"
 * and "2 garlic cloves", {egg, eggs, yolk, yolks, white, whites, garlic, clove, cloves, …}.
 * Weighed and measured ingredients only bring their built-in aliases ("500g chicken thighs" lets
 * "4 thighs" scale) — not their own names, since their amounts in the method carry a unit.
 */
fun countedIngredientWords(ingredients: List<Ingredient>, extra: List<String> = emptyList()): Set<String> {
    val withAmounts = ingredients.filter { it.quantity != null }
    val counted = withAmounts.filter { it.unit == null || it.unit.lowercase() !in textUnits }
    // The name's words ("eggs" in "large free-range eggs") and a counted unit ("cloves").
    val own = counted.flatMap { nameWords(it) + listOfNotNull(it.unit?.lowercase()?.substringAfterLast(' ')) }
    val aliases = withAmounts.flatMap(::nameWords).map(::singularOf).flatMap { countAliases[it].orEmpty() }
    return (own + aliases + extra.map { it.lowercase().trim() }.filter { it.length > 2 })
        .flatMap { listOf(it, pluralOf(it), singularOf(it)) }
        .toSet()
}

private fun countPattern(words: Set<String>): Regex {
    // "an onion" and "half a lemon" are counts too.
    val num = """(\d+(?:\.\d+)?[½¼¾⅓⅔]?|[½¼¾⅓⅔]|half\s+an?|an?|${numberWords.keys.joinToString("|")})"""
    val filler = (countFiller + words).sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    val target = words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    return Regex(
        """(?<![\w.])$num(?:\s*(?:-|–|to)\s*$num)?\s+((?:(?:$filler)\s+){0,2})($target)\b""",
        RegexOption.IGNORE_CASE,
    )
}

/**
 * Scales counts of the recipe's own counted ingredients: doubled, "crack in 3 eggs" becomes
 * "crack in 6 eggs" and "add 1 onion" becomes "add 2 onions". Counts of anything else
 * ("cook in 2 batches", "cut into 4 pieces") are left alone.
 */
fun scaleCountsInText(text: String, countedWords: Set<String>, factor: Double): String {
    if (factor == 1.0 || countedWords.isEmpty()) return text
    return countPattern(countedWords).replace(text) { m ->
        val low = readNumber(m.groupValues[1]) ?: return@replace m.value
        val high = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { readNumber(it) ?: return@replace m.value }
        val newLow = low * factor
        val newHigh = high?.times(factor)
        val top = newHigh ?: newLow
        val word = m.groupValues[4]
        // "1 onion" doubled is "2 onions"; "2 eggs" halved is "1 egg".
        val noun = when {
            (high ?: low) <= 1.0 && top > 1.0 -> pluralOf(word)
            (high ?: low) > 1.0 && top <= 1.0 -> singularOf(word)
            else -> word
        }
        val amount = formatQuantity(newLow) + (newHigh?.let { "–" + formatQuantity(it) } ?: "")
        "$amount ${m.groupValues[3]}$noun"
    }
}

/** Step text with amounts scaled by [factor] and, like oven temperatures, in [measures]' units. */
fun convertText(text: String, measures: Measures, factor: Double = 1.0, countedWords: Set<String> = emptySet()): String =
    convertTemperatures(convertAmountsInText(scaleCountsInText(text, countedWords, factor), measures, factor), measures.system)

/** The recipe's step and Get ready text, scaled and in [measures]' units. Ingredients are handled line by line instead. */
fun Recipe.textIn(measures: Measures, factor: Double = 1.0): Recipe {
    val counted = countedIngredientWords(ingredients, countWords)
    fun convert(text: String) = convertText(text, measures, factor, counted)
    return copy(
        instructions = instructions.map { s -> s.copy(steps = s.steps.map(::convert)) },
        originalInstructions = originalInstructions.map { s -> s.copy(steps = s.steps.map(::convert)) },
        prep = prep.map { it.copy(text = convert(it.text)) },
    )
}

// ---------- Ingredient lines, keeping the recipe's own wording ----------

/** A line split around its amount, so the amount can be shown in bold: "" + "4" + " medium onions, finely chopped". */
data class LineParts(val before: String, val amount: String, val after: String) {
    override fun toString(): String = before + amount + after
}

/** An amount with a unit at the start of a line: "1 lb", "250g", "1 ½ cups". */
private val leadingAmountWithUnit by lazy {
    Regex("""^(\s*)""" + textAmount.pattern.removePrefix("""(?<![\w.])"""), RegexOption.IGNORE_CASE)
}

/** A bare number at the start of a line: "2 medium onions", "a pinch of salt", "2 x 400g tins". */
private val leadingCount = Regex("""^(\s*)($NUM)(?:\s*(?:-|–|to)\s*($NUM))?(?=[\s,(]|$)""", RegexOption.IGNORE_CASE)

/**
 * The line as the recipe wrote it, with only its amount changed: scaled by [factor] and, if
 * [measures] is given, converted. "2 medium onions, finely chopped" doubled is
 * "4 medium onions, finely chopped"; "1 lb beef mince" in metric is "450 g beef mince";
 * "1 onion" doubled is "2 onions". Null means nothing changes: show the line as imported.
 * Lines the user corrected by hand show their correction, built from its parts.
 */
fun Ingredient.scaledLine(factor: Double = 1.0, measures: Measures? = null): LineParts? {
    if (edited) {
        val shown = forCard(factor, measures) ?: return null
        val amount = shown.amountLabel()?.ifBlank { null }
        return LineParts("", amount.orEmpty(), (if (amount != null) " " else "") + shown.name + (shown.note?.let { ", $it" } ?: ""))
    }

    leadingAmountWithUnit.find(raw)?.let { m ->
        val unit = unitOf(m.groupValues[4]) ?: return@let
        val amountRange = m.groups[2]!!.range.first..m.range.last
        // The recipe gives it both ways ("250g/9oz plain flour"): scale both sides, chosen system first.
        if (pairedWithConversion(raw, amountRange)) {
            val out = convertAmountsInText(raw, measures ?: return@let, factor)
            return if (out == raw) null else LineParts("", "", out)
        }
        val low = readNumber(m.groupValues[2])?.times(factor) ?: return@let
        val high = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { (readNumber(it) ?: return@let) * factor }
        val rest = raw.substring(m.range.last + 1)
        val converted = measures?.let { convertAmount(low, high, unit, rest, it) }
        if (converted == null && factor == 1.0) return null
        return LineParts(m.groupValues[1], formatAmount(converted ?: tidy(low, high, unit)), rest)
    }

    leadingCount.find(raw)?.let { m ->
        if (factor == 1.0) return null
        val low = readNumber(m.groupValues[2]) ?: return@let
        val high = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { readNumber(it) ?: return@let }
        val newLow = low * factor
        val newHigh = high?.times(factor)
        val amount = formatQuantity(newLow) + (newHigh?.let { "–" + formatQuantity(it) } ?: "")
        var rest = raw.substring(m.range.last + 1)
        // "1 onion" doubled is "2 onions"; "2 eggs" halved is "1 egg". Fix the first word that names
        // what's counted: the unit ("pinch", "clove") if there is one, else the ingredient itself.
        val was = high ?: low
        val now = newHigh ?: newLow
        if ((was <= 1.0) != (now <= 1.0)) {
            val candidates = listOfNotNull(unit?.substringAfterLast(' ')) + nameWords(this).map(::singularOf)
            for (word in candidates) {
                val form = if (now > 1.0) singularOf(word) else pluralOf(word)
                val hit = Regex("""\b${Regex.escape(form)}\b""", RegexOption.IGNORE_CASE).find(rest) ?: continue
                val replacement = if (now > 1.0) pluralOf(hit.value) else singularOf(hit.value)
                rest = rest.replaceRange(hit.range, replacement)
                break
            }
        }
        return LineParts(m.groupValues[1], amount, rest)
    }

    // The amount isn't at the start ("juice of 2 lemons"): rebuild the line from its parts.
    val shown = forCard(factor, measures) ?: return null
    val amount = shown.amountLabel()?.ifBlank { null }
    return LineParts("", amount.orEmpty(), (if (amount != null) " " else "") + shown.name + (shown.note?.let { ", $it" } ?: ""))
}
