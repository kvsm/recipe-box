package app.recipebox.data.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Ingredient text scaled by [factor]. Unscaled or unparsed lines show exactly as imported. */
fun Ingredient.display(factor: Double = 1.0): String {
    val q = quantity ?: return raw
    if (factor == 1.0) return raw
    val qty = formatQuantity(q * factor) + (quantityMax?.let { "–" + formatQuantity(it * factor) } ?: "")
    return listOfNotNull(qty, unit?.let { pluralUnit(it, quantityMax ?: (q * factor)) }, name).joinToString(" ") +
        (note?.let { ", $it" } ?: "")
}

/** Name and quantity for a shopping list: ("floury potatoes", "1 kg"), ("garlic", "2 cloves"). */
fun Ingredient.groceryParts(factor: Double = 1.0): Pair<String, String?> {
    val q = quantity ?: return name to amountText
    val amount = q * factor
    val qty = formatQuantity(amount) + (quantityMax?.let { "–" + formatQuantity(it * factor) } ?: "")
    return name to listOfNotNull(qty, unit?.let { pluralUnit(it, quantityMax ?: amount) }).joinToString(" ")
}

private val countableUnits = setOf("clove", "stick", "rasher", "sprig", "tin", "slice", "handful", "bunch", "pinch", "cup")

/** "2 clove" reads wrong on a shopping list; "2 cloves" doesn't. Weights and volumes stay as they are. */
fun pluralUnit(unit: String, amount: Double): String {
    if (amount <= 1.0) return unit
    val last = unit.substringAfterLast(' ')
    if (last !in countableUnits) return unit
    val plural = if (last.endsWith("ch") || last.endsWith("sh")) "${last}es" else "${last}s"
    return unit.removeSuffix(last) + plural
}

private val niceFractions = listOf(
    0.0 to "", 0.25 to "¼", 1.0 / 3 to "⅓", 0.5 to "½", 2.0 / 3 to "⅔", 0.75 to "¾", 1.0 to "",
)

/** 1.5 → "1½", 0.333 → "⅓", 600.0 → "600", 2.4 → "2.4". */
fun formatQuantity(value: Double): String {
    if (value < 20) {
        val whole = floor(value).toInt()
        val frac = value - whole
        val (target, glyph) = niceFractions.minBy { abs(it.first - frac) }
        if (abs(target - frac) < 0.04) {
            val w = whole + if (target == 1.0) 1 else 0
            return when {
                glyph.isEmpty() -> w.toString()
                w == 0 -> glyph
                else -> "$w$glyph"
            }
        }
    }
    if (value >= 10) return value.roundToInt().toString()
    return String.format(Locale.UK, "%.1f", value).trimEnd('0').trimEnd('.')
}

fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

/** The amount as one editable string: "2", "2–3", "½", "small pack", or "" if none. */
fun Ingredient.amountForEditing(): String = when {
    quantity != null -> formatQuantity(quantity) + (quantityMax?.let { "–" + formatQuantity(it) } ?: "")
    else -> amountText.orEmpty()
}

private val editRange = Regex("""^\s*([\d.,/½¼¾⅓⅔ ]+?)\s*(?:-|–|to)\s*([\d.,/½¼¾⅓⅔ ]+)\s*$""")
private val glyphs = mapOf('½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3)

/** "1½" → 1.5, "1 1/2" → 1.5, "0,5" → 0.5. Null if it isn't a number. */
fun parseAmountNumber(text: String): Double? {
    val t = text.trim()
    if (t.isEmpty()) return null
    var total = 0.0
    var rest = t
    glyphs.forEach { (c, v) ->
        if (rest.endsWith(c)) {
            total += v
            rest = rest.dropLast(1).trim()
        }
    }
    if (rest.isEmpty()) return total.takeIf { it > 0 }
    val parts = rest.split(Regex("\\s+"))
    for (part in parts) {
        total += if ('/' in part) {
            val (a, b) = part.split('/', limit = 2)
            val d = b.toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
            (a.toDoubleOrNull() ?: return null) / d
        } else {
            part.replace(',', '.').toDoubleOrNull() ?: return null
        }
    }
    return total.takeIf { it > 0 }
}

/** Applies an edited amount string: numbers and ranges become quantities, anything else is kept as words. */
fun Ingredient.withEditedAmount(text: String): Ingredient {
    val t = text.trim()
    if (t.isEmpty()) return copy(quantity = null, quantityMax = null, amountText = null)
    editRange.matchEntire(t)?.let { m ->
        val lo = parseAmountNumber(m.groupValues[1])
        val hi = parseAmountNumber(m.groupValues[2])
        if (lo != null && hi != null) return copy(quantity = lo, quantityMax = hi, amountText = null)
    }
    parseAmountNumber(t)?.let { return copy(quantity = it, quantityMax = null, amountText = null) }
    return copy(quantity = null, quantityMax = null, amountText = t.lowercase())
}

/** How a corrected line reads on the card: "2 cloves garlic, finely chopped". */
fun Ingredient.editedDisplay(factor: Double = 1.0): String {
    val amount = when {
        quantity != null -> formatQuantity(quantity * factor) + (quantityMax?.let { "–" + formatQuantity(it * factor) } ?: "")
        else -> amountText
    }
    val u = unit?.let { if (quantity != null) pluralUnit(it, (quantityMax ?: quantity) * factor) else it }
    return listOfNotNull(amount?.ifBlank { null }, u?.ifBlank { null }, name).joinToString(" ") + (note?.let { ", $it" } ?: "")
}

/** The line as the recipe card shows it: the user's fix, the scaled amount, or the original text. */
fun Ingredient.lineFor(factor: Double = 1.0): String = when {
    edited -> editedDisplay(factor)
    quantity == null || factor == 1.0 -> raw
    else -> display(factor)
}
