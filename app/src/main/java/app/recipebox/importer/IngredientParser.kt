package app.recipebox.importer

import app.recipebox.data.model.Ingredient

/**
 * Best-effort split of "1½ tbsp olive oil, plus extra" into quantity, unit,
 * name and note. Anything it can't parse keeps quantity = null and is shown
 * exactly as imported.
 *
 * Rules added from the first lessons export (docs/lessons.md), each with a test:
 *  - Anything in brackets is a note, and commas inside brackets don't split the line:
 *    "2 cups chicken stock (unsalted, preferably homemade)", "((see note 1))".
 *  - Describing words before the unit or name are notes: "3 large cloves of garlic",
 *    "2 heaped tbsp", "12 thin slices galangal", "1 large white onion".
 *  - Prep words without a comma are notes: "1 onion finely sliced", "1 egg beaten",
 *    "50g finely grated cheddar cheese".
 *  - A recipe that gives two amounts ("2.2lbs / 1kg", "1 cup / 240ml", "2x 14oz/400g cans")
 *    is read as the metric one.
 *  - "a sheet of puff pastry" is 1 sheet; stalks and sheets are units.
 *  - Names written in Title Case are lower-cased, keeping proper names ("Thai").
 *  - "Olive oil, (as needed)": "as needed" and "to taste" are the amount, in words.
 */
object IngredientParser {

    private val unicodeFractions = mapOf(
        '¼' to "1/4", '½' to "1/2", '¾' to "3/4", '⅓' to "1/3", '⅔' to "2/3",
        '⅛' to "1/8", '⅜' to "3/8", '⅝' to "5/8", '⅞' to "7/8", '⅕' to "1/5", '⅙' to "1/6",
    )

    private const val NUM = """(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)"""
    private val quantityRegex = Regex("""^$NUM(?:\s*(?:-|–|to)\s*$NUM)?\s*""")
    private val unitWordRegex = Regex("""^([A-Za-z]+)\.?(?=\s|$|,|\)|/)\s*""")
    private val whitespace = Regex("\\s+")
    /** "2 x 400g tins plum tomatoes", "2x 14oz/400g cans": a count of packs of a given size. */
    private val multiplierRegex = Regex(
        """^[x×]\s*(\d+(?:[.,]\d+)?)\s*(g|kg|ml|l|oz)(?:\s*/\s*(\d+(?:[.,]\d+)?)\s*(g|kg|ml|l))?\s+""",
        RegexOption.IGNORE_CASE,
    )
    /** The second of two amounts: the "/ 1kg" in "2.2lbs / 1kg minced beef". */
    private val alternativeAmount = Regex("""^/\s*(\d+(?:[.,]\d+)?)\s*([A-Za-z]+)\.?(?=\s|$|,|\))\s*""")
    /** Amounts in words: "small pack basil", "a knob of butter", "pinch of salt". */
    private val wordAmountRegex = Regex(
        """^(?:an?\s+)?((?:(?:small|medium|large|big|generous|good)\s+)?""" +
            """(?:packs?|packets?|bunch(?:es)?|bags?|handfuls?|pots?|jars?|punnets?|knobs?|pinch(?:es)?|splash|dash|drizzle|sprinkle))""" +
            """\s+(?:of\s+)?""",
        RegexOption.IGNORE_CASE,
    )
    /** "a sheet of puff pastry", "an onion": one of something. */
    private val singleRegex = Regex("""^an?\s+""", RegexOption.IGNORE_CASE)
    /** Words that describe the form, not the food: "2 garlic cloves" is 2 cloves of garlic. */
    private val trailingForms = mapOf(
        "clove" to "clove", "cloves" to "clove", "stick" to "stick", "sticks" to "stick",
        "rasher" to "rasher", "rashers" to "rasher", "sprig" to "sprig", "sprigs" to "sprig",
        "stalk" to "stalk", "stalks" to "stalk",
    )
    /** Words that describe the amount or size, which belong in the note: "3 large cloves", "2 heaped tbsp". */
    private val describingWords = setOf(
        "large", "medium", "small", "big", "heaped", "level", "rounded", "scant", "generous", "thin", "thick",
    )
    private const val ADVERBS = "finely|roughly|thinly|coarsely|freshly|lightly|well|very"
    private const val PREP_WORDS = "chopped|diced|sliced|grated|minced|crushed|peeled|cubed|shredded|trimmed|halved|" +
        "quartered|zested|juiced|beaten|melted|softened|toasted|drained|rinsed|deseeded|torn|sifted|picked"
    /** Prep at the end of a name with no comma: "onion finely sliced", "egg beaten". */
    private val trailingPrep = Regex("""\s+((?:(?:$ADVERBS)\s+)?(?:$PREP_WORDS))$""", RegexOption.IGNORE_CASE)
    /**
     * Prep at the start of a name, only with an adverb ("finely grated cheddar"), since
     * "chopped tomatoes" and "minced beef" are things you buy.
     */
    private val leadingPrep = Regex("""^((?:$ADVERBS)\s+(?:$PREP_WORDS))\s+""", RegexOption.IGNORE_CASE)
    /** Amounts given in words, when there's no number: "olive oil, (as needed)", "salt, to taste". */
    private val amountPhrases = listOf("as needed", "to taste")
    /** Capitalised for a reason, so kept when a Title Case name is lower-cased. */
    private val properWords = setOf(
        "thai", "italian", "french", "greek", "mexican", "indian", "chinese", "japanese", "spanish", "dijon",
        "worcestershire", "parmesan", "parmigiano", "reggiano", "maldon", "tabasco", "sriracha", "granny", "smith",
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
        alias("stalk", "stalk", "stalks")
        alias("sheet", "sheet", "sheets")
    }
    private val metricUnits = setOf("g", "kg", "ml", "l")

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
        var quantity = match?.let { parseNumber(it.groupValues[1]) }
        if (match == null || quantity == null) {
            // One of something with a unit: "a sheet of ready rolled puff pastry".
            singleRegex.find(original)?.let { a ->
                val after = original.substring(a.range.last + 1)
                val unitMatch = unitWordRegex.find(after)
                val canonical = unitMatch?.let { units[it.groupValues[1].lowercase()] }
                if (unitMatch != null && canonical != null && wordAmountRegex.find(original) == null) {
                    val rest = after.substring(unitMatch.range.last + 1).trim().removePrefix("of ").trim()
                    return finish(original, rest, 1.0, null, canonical)
                }
            }
            // An amount in words: "small pack basil" is basil, small pack.
            wordAmountRegex.find(original)?.let { words ->
                return finish(original, original.substring(words.range.last + 1), null, null, null, amountText = words.groupValues[1].lowercase())
            }
            // No quantity at all ("crusty bread, to serve"): still split off the note.
            return finish(original, original, quantity = null, quantityMax = null, unit = null)
        }
        var quantityMax = match.groupValues[2].takeIf { it.isNotEmpty() }?.let(::parseNumber)
        text = text.substring(match.range.last + 1)

        // "3 large cloves", "2 heaped tbsp": describing words before the unit go in the note.
        val described = mutableListOf<String>()
        while (true) {
            val word = text.substringBefore(' ')
            if (word.lowercase() !in describingWords || word == text) break
            described += word.lowercase()
            text = text.substringAfter(' ')
        }

        var unit: String? = null
        val multiplier = multiplierRegex.find(text)
        if (multiplier != null) {
            // "2x 14oz/400g cans": the metric pack size if there's one.
            val (size, sizeUnit) = if (multiplier.groupValues[3].isNotEmpty()) {
                multiplier.groupValues[3] to multiplier.groupValues[4]
            } else {
                multiplier.groupValues[1] to multiplier.groupValues[2]
            }
            unit = "x $size${sizeUnit.lowercase()}"
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

        // "2.2lbs / 1kg minced beef", "1 cup / 240ml red wine": read the metric amount.
        alternativeAmount.find(text)?.let { alt ->
            val altUnit = units[alt.groupValues[2].lowercase()]
            val altQuantity = parseNumber(alt.groupValues[1])
            if (altUnit != null && altQuantity != null) {
                if (altUnit in metricUnits && unit !in metricUnits) {
                    quantity = altQuantity
                    quantityMax = null
                    unit = altUnit
                }
                text = text.substring(alt.range.last + 1)
            }
        }

        text = text.trim().removePrefix("of ").trim()
        return finish(original, text, quantity, quantityMax, unit, described = described)
    }

    private fun finish(
        original: String,
        rest: String,
        quantity: Double?,
        quantityMax: Double?,
        unit: String?,
        amountText: String? = null,
        described: List<String> = emptyList(),
    ): Ingredient {
        // Brackets come out first, so commas inside them don't split the line.
        val (outside, bracketed) = splitBrackets(rest)
        val notes = mutableListOf<String>()
        var name = outside.substringBefore(',').trim().trimEnd(',').trim()
        outside.substringAfter(',', "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.let { notes += it }

        // "large white onion": a describing word at the start of the name.
        val leadingWords = mutableListOf<String>()
        while (true) {
            val word = name.substringBefore(' ')
            if (word.lowercase() !in describingWords || word == name) break
            leadingWords += word.lowercase()
            name = name.substringAfter(' ')
        }
        // "finely grated cheddar cheese", "onion finely sliced", "egg beaten".
        leadingPrep.find(name)?.let { m ->
            leadingWords += m.groupValues[1].lowercase()
            name = name.substring(m.range.last + 1)
        }
        trailingPrep.find(name)?.takeIf { it.range.first > 0 }?.let { m ->
            notes.add(0, m.groupValues[1].trim())
            name = name.substring(0, m.range.first)
        }

        var finalUnit = unit
        val words = name.split(' ')
        if (quantity != null && finalUnit == null && words.size > 1) {
            trailingForms[words.last().lowercase()]?.let {
                finalUnit = it
                name = words.dropLast(1).joinToString(" ")
            }
        }

        val allNotes = (described + leadingWords + notes + bracketed).filter { it.isNotBlank() }.toMutableList()
        // "Olive oil, (as needed)": with no number, that note is the amount.
        var amount = amountText
        if (quantity == null && amount == null) {
            allNotes.firstOrNull { it.lowercase() in amountPhrases }?.let {
                amount = it.lowercase()
                allNotes.remove(it)
            }
        }
        return Ingredient(
            raw = original,
            quantity = quantity,
            amountText = amount,
            quantityMax = quantityMax,
            unit = finalUnit,
            name = tidyCase(name.trim().ifEmpty { original }),
            note = allNotes.joinToString(", ").ifEmpty { null },
        )
    }

    /**
     * Separates bracketed text from the rest: "chicken stock (unsalted, preferably homemade)" is
     * ("chicken stock", ["unsalted, preferably homemade"]). Handles doubled brackets ("((see note 1))")
     * and a bracket that's never closed (the rest of the line is the note).
     */
    private fun splitBrackets(text: String): Pair<String, List<String>> {
        val outside = StringBuilder()
        val groups = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        text.forEach { c ->
            when {
                c == '(' -> { if (depth > 0) current.append(' '); depth++ }
                c == ')' && depth > 0 -> {
                    depth--
                    if (depth == 0) {
                        groups += current.toString()
                        current.clear()
                    }
                }
                c == ')' -> Unit // a stray closing bracket
                depth > 0 -> current.append(c)
                else -> outside.append(c)
            }
        }
        if (current.isNotBlank()) groups += current.toString()
        return outside.toString().replace(whitespace, " ").trim() to
            groups.map { it.replace(whitespace, " ").trim().trim(',').trim() }.filter { it.isNotEmpty() }
    }

    /** "Ground/Minced Beef" → "ground/minced beef", but "Thai chilies" keeps its capital. Mixed case is left alone. */
    private fun tidyCase(name: String): String {
        val words = name.split(' ').filter { it.isNotEmpty() }
        val titleCase = words.count { it.first().isUpperCase() } >= words.size.coerceAtMost(2) && words.any { it.first().isUpperCase() }
        if (!titleCase) return name
        return words.joinToString(" ") { w ->
            if (w.lowercase() in properWords) w.lowercase().replaceFirstChar { it.uppercase() } else w.lowercase()
        }
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
