package app.recipebox.importer

import app.recipebox.data.model.Ingredient

/**
 * Best-effort split of "1½ tbsp olive oil, plus extra" into quantity, unit,
 * name and note. Anything it can't parse keeps quantity = null and is shown
 * exactly as imported.
 */
object IngredientParser {

    private val unicodeFractions = mapOf(
        '¼' to "1/4", '½' to "1/2", '¾' to "3/4", '⅓' to "1/3", '⅔' to "2/3",
        '⅛' to "1/8", '⅜' to "3/8", '⅝' to "5/8", '⅞' to "7/8", '⅕' to "1/5", '⅙' to "1/6",
    )

    private const val NUM = """(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)"""
    private val quantityRegex = Regex("""^$NUM(?:\s*(?:-|–|to)\s*$NUM)?\s*""")
    private val unitWordRegex = Regex("""^([A-Za-z]+)\.?(?=\s|$|,|\))\s*""")
    private val whitespace = Regex("\\s+")
    /** "2 x 400g tins plum tomatoes": a count of packs of a given size. */
    private val multiplierRegex = Regex("""^[x×]\s*(\d+(?:[.,]\d+)?)\s*(g|kg|ml|l)\s+""", RegexOption.IGNORE_CASE)
    /** Amounts in words: "small pack basil", "a knob of butter", "pinch of salt". */
    private val wordAmountRegex = Regex(
        """^(?:an?\s+)?((?:(?:small|medium|large|big|generous|good)\s+)?""" +
            """(?:packs?|packets?|bunch(?:es)?|bags?|handfuls?|pots?|jars?|punnets?|knobs?|pinch(?:es)?|splash|dash|drizzle|sprinkle))""" +
            """\s+(?:of\s+)?""",
        RegexOption.IGNORE_CASE,
    )
    private val optionalRegex = Regex("""\s*\(optional\)""", RegexOption.IGNORE_CASE)
    /** Words that describe the form, not the food: "2 garlic cloves" is 2 cloves of garlic. */
    private val trailingForms = mapOf(
        "clove" to "clove", "cloves" to "clove", "stick" to "stick", "sticks" to "stick",
        "rasher" to "rasher", "rashers" to "rasher", "sprig" to "sprig", "sprigs" to "sprig",
    )

    private val units: Map<String, String> = buildMap {
        fun alias(canonical: String, vararg names: String) = names.forEach { put(it, canonical) }
        alias("g", "g", "gr", "gram", "grams")
        alias("kg", "kg", "kgs", "kilo", "kilos", "kilogram", "kilograms")
        alias("mg", "mg")
        alias("ml", "ml", "millilitre", "millilitres", "milliliter", "milliliters")
        alias("l", "l", "litre", "litres", "liter", "liters")
        alias("tsp", "tsp", "tsps", "teaspoon", "teaspoons")
        alias("tbsp", "tbsp", "tbsps", "tbs", "tbl", "tablespoon", "tablespoons")
        alias("cup", "cup", "cups")
        alias("oz", "oz", "ounce", "ounces")
        alias("lb", "lb", "lbs", "pound", "pounds")
        alias("pint", "pint", "pints", "pt", "pts")
        alias("quart", "quart", "quarts", "qt", "qts")
        alias("gallon", "gallon", "gallons", "gal")
        alias("pinch", "pinch", "pinches")
        alias("clove", "clove", "cloves")
        alias("tin", "tin", "tins", "can", "cans")
        alias("handful", "handful", "handfuls")
        alias("bunch", "bunch", "bunches")
        alias("slice", "slice", "slices")
        alias("sprig", "sprig", "sprigs")
        alias("rasher", "rasher", "rashers")
        alias("stick", "stick", "sticks")
    }

    /** Maps "tablespoons", "Tins." etc. to the parser's short forms; leaves anything else as given. */
    fun canonicalUnit(unit: String?): String? {
        val u = unit?.trim()?.lowercase()?.trimEnd('.')?.ifEmpty { null } ?: return null
        return units[u] ?: u
    }

    fun parse(raw: String): Ingredient {
        val original = raw.replace(whitespace, " ").trim()

        var text = buildString {
            original.forEach { c -> unicodeFractions[c]?.let { append(" $it ") } ?: append(c) }
        }.replace(whitespace, " ").trim()

        val match = quantityRegex.find(text)
        val quantity = match?.let { parseNumber(it.groupValues[1]) }
        if (match == null || quantity == null) {
            // An amount in words: "small pack basil" is basil, small pack.
            wordAmountRegex.find(original)?.let { words ->
                return finish(original, original.substring(words.range.last + 1), null, null, null, amountText = words.groupValues[1].lowercase())
            }
            // No quantity at all ("crusty bread, to serve"): still split off the note.
            return finish(original, original, quantity = null, quantityMax = null, unit = null)
        }
        val quantityMax = match.groupValues[2].takeIf { it.isNotEmpty() }?.let(::parseNumber)
        text = text.substring(match.range.last + 1)

        var unit: String? = null
        val multiplier = multiplierRegex.find(text)
        if (multiplier != null) {
            unit = "x ${multiplier.groupValues[1]}${multiplier.groupValues[2].lowercase()}"
            text = text.substring(multiplier.range.last + 1)
            unitWordRegex.find(text)?.takeIf { units[it.groupValues[1].lowercase()] == "tin" }?.let {
                unit += " tin"
                text = text.substring(it.range.last + 1)
            }
        } else if (text.startsWith("fl oz", ignoreCase = true) || text.startsWith("fl. oz", ignoreCase = true)) {
            unit = "fl oz"
            text = text.substringAfter("oz").trimStart('.', ' ')
        } else {
            val unitMatch = unitWordRegex.find(text)
            val canonical = unitMatch?.let { units[it.groupValues[1].lowercase()] }
            if (unitMatch != null && canonical != null) {
                unit = canonical
                text = text.substring(unitMatch.range.last + 1)
            }
        }

        text = text.trim().removePrefix("of ").trim()
        return finish(original, text, quantity, quantityMax, unit)
    }

    private fun finish(
        original: String,
        rest: String,
        quantity: Double?,
        quantityMax: Double?,
        unit: String?,
        amountText: String? = null,
    ): Ingredient {
        val notes = mutableListOf<String>()
        var text = rest
        if (optionalRegex.containsMatchIn(text)) {
            text = text.replace(optionalRegex, "")
            notes += "optional"
        }
        var name = text.substringBefore(',').trim().ifEmpty { original }
        text.substringAfter(',', "").trim().takeIf { it.isNotEmpty() }?.let { notes.add(0, it) }

        var finalUnit = unit
        val words = name.split(' ')
        if (quantity != null && finalUnit == null && words.size > 1) {
            trailingForms[words.last().lowercase()]?.let {
                finalUnit = it
                name = words.dropLast(1).joinToString(" ")
            }
        }
        return Ingredient(
            raw = original,
            quantity = quantity,
            amountText = amountText,
            quantityMax = quantityMax,
            unit = finalUnit,
            name = name,
            note = notes.joinToString(", ").ifEmpty { null },
        )
    }

    private fun parseNumber(s: String): Double? {
        val parts = s.trim().split(whitespace)
        if (parts.size == 2) {
            val whole = parts[0].toDoubleOrNull() ?: return null
            return whole + (fraction(parts[1]) ?: return null)
        }
        return if ('/' in s) fraction(s) else s.replace(',', '.').toDoubleOrNull()
    }

    private fun fraction(s: String): Double? {
        val parts = s.split('/')
        if (parts.size != 2) return null
        val denominator = parts[1].toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
        return parts[0].toDoubleOrNull()?.div(denominator)
    }
}
