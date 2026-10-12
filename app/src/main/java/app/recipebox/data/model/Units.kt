package app.recipebox.data.model

import java.net.URI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** How amounts are shown. Recipes are stored as imported; conversion happens on the way to the screen. */
enum class UnitSystem { METRIC, IMPERIAL }

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

/** Grams per cup for dry ingredients people weigh in metric. Checked in order, so "brown sugar" beats "sugar". */
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

/** Millilitres for a volume unit, or null if it isn't one we convert. tsp and tbsp are left alone in both systems. */
private fun mlPer(unit: String, m: Measures): Double? = when (unit) {
    "ml" -> 1.0
    "l" -> 1000.0
    "cup" -> m.cupMl
    "fl oz" -> if (m.britishVolumes) 28.413 else 29.5735
    "pint" -> if (m.britishVolumes) 568.261 else 473.176
    "quart" -> if (m.britishVolumes) 1136.52 else 946.353
    "gallon" -> if (m.britishVolumes) 4546.09 else 3785.41
    else -> null
}

private fun gramsPer(unit: String): Double? = when (unit) {
    "g" -> 1.0
    "kg" -> 1000.0
    "oz" -> G_PER_OZ
    "lb" -> G_PER_LB
    else -> null
}

private val metricUnits = setOf("g", "kg", "ml", "l")
private val imperialUnits = setOf("oz", "lb", "cup", "fl oz", "pint", "quart", "gallon")

/** An amount converted for display: low end, optional high end, and unit. */
data class Converted(val quantity: Double, val quantityMax: Double?, val unit: String)

/**
 * Converts [quantity] (and [quantityMax]) of [unit] into [measures]' system, rounded to
 * kitchen-friendly numbers. Returns null when nothing needs converting: already in the
 * right system, a spoon measure, or a count like "2 cloves".
 */
fun convertAmount(quantity: Double, quantityMax: Double?, unit: String?, name: String, measures: Measures): Converted? {
    val u = unit?.lowercase() ?: return null
    val lowerName = name.lowercase()
    return when (measures.system) {
        UnitSystem.METRIC -> {
            if (u in metricUnits) return null
            // A stick of butter is a weight in disguise.
            if (u == "stick" && "butter" in lowerName) return grams(quantity * G_PER_BUTTER_STICK, quantityMax?.times(G_PER_BUTTER_STICK))
            gramsPer(u)?.let { return grams(quantity * it, quantityMax?.times(it)) }
            val ml = mlPer(u, measures) ?: return null
            // Cups of flour and the like are weighed in metric kitchens, not poured.
            if (u == "cup") {
                cupWeights.firstOrNull { (key, _) -> key in lowerName }?.let { (_, perCup) ->
                    val perMl = perCup / measures.cupMl
                    return grams(quantity * ml * perMl, quantityMax?.times(ml * perMl))
                }
            }
            millilitres(quantity * ml, quantityMax?.times(ml))
        }
        UnitSystem.IMPERIAL -> {
            if (u in imperialUnits) return null
            gramsPer(u)?.let { return ounces(quantity * it, quantityMax?.times(it)) }
            val ml = mlPer(u, measures) ?: return null
            cups(quantity * ml, quantityMax?.times(ml))
        }
    }
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

/** Small amounts as spoons (fine in either system), bigger ones in cups to the nearest handy fraction. */
private fun cups(ml: Double, mlMax: Double?): Converted {
    val top = mlMax ?: ml
    val cup = Measures.US_CUP_ML
    return when {
        top < 15 -> Converted(roundTo(ml / 5, 0.25), mlMax?.let { roundTo(it / 5, 0.25) }, "tsp")
        top < cup / 4 -> Converted(roundTo(ml / 15, 0.5), mlMax?.let { roundTo(it / 15, 0.5) }, "tbsp")
        else -> Converted(roundCups(ml / cup), mlMax?.let { roundCups(it / cup) }, "cup")
    }
}

/** 7 → 7, 23 → 25, 237 → 240, 3.3 → 3.5. Precise enough to cook with, without false precision. */
private fun roundMetric(v: Double): Double = when {
    v < 5 -> roundTo(v, 0.5)
    v < 20 -> roundTo(v, 1.0)
    v < 100 -> roundTo(v, 5.0)
    else -> roundTo(v, 10.0)
}.coerceAtLeast(if (v > 0) 0.5 else 0.0)

private val cupFractions = listOf(0.0, 0.25, 1.0 / 3, 0.5, 2.0 / 3, 0.75, 1.0)

private fun roundCups(v: Double): Double {
    val whole = floor(v)
    val frac = cupFractions.minBy { abs(it - (v - whole)) }
    return (whole + frac).coerceAtLeast(0.25)
}

private fun roundTo(v: Double, step: Double): Double = ((v / step).roundToInt() * step).let { if (it == 0.0 && v > 0) step else it }

// ---------- Temperatures in method text ----------

private val temperature = Regex(
    """(?<![\d.(])(\d{2,3})\s*(?:°\s*|º\s*|degrees?\s+)?(C|F|Celsius|Fahrenheit|centigrade)\b(?!\s*\()""",
    RegexOption.IGNORE_CASE,
)

/**
 * Converts cooking temperatures in step text to [system], keeping the original in brackets:
 * "Bake at 350°F" → "Bake at 180°C (350°F)". Only numbers written with a C/F marker are touched,
 * so step numbers and times are safe. Temperatures already in the right scale are left as they are.
 */
fun convertTemperatures(text: String, system: UnitSystem): String = temperature.replace(text) { m ->
    val value = m.groupValues[1].toInt()
    val fahrenheit = m.groupValues[2].first().uppercaseChar() == 'F'
    when {
        system == UnitSystem.METRIC && fahrenheit && value in 150..550 -> {
            val c = ((value - 32) * 5.0 / 9 / 10).roundToInt() * 10
            "$c°C (${m.value.trim()})"
        }
        system == UnitSystem.IMPERIAL && !fahrenheit && value in 60..290 -> {
            val f = ((value * 9.0 / 5 + 32) / 25).roundToInt() * 25
            "$f°F (${m.value.trim()})"
        }
        else -> m.value
    }
}

/** The recipe's step and Get ready text with temperatures in [system]. Ingredients are converted line by line instead. */
fun Recipe.textIn(system: UnitSystem): Recipe = copy(
    instructions = instructions.map { s -> s.copy(steps = s.steps.map { convertTemperatures(it, system) }) },
    originalInstructions = originalInstructions.map { s -> s.copy(steps = s.steps.map { convertTemperatures(it, system) }) },
    prep = prep.map { it.copy(text = convertTemperatures(it.text, system)) },
)
