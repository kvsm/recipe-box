package app.recipebox.importer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Reads a recipe without Claude. Produces the same schema.org JSON that Claude
 * does, so everything downstream works the same either way.
 *
 * Two routes:
 *  - [fromMarkup]: recipe pages that skip JSON-LD but still mark their recipe up,
 *    with microdata or one of the big WordPress recipe plugins.
 *  - [fromText]: plain text (an Instagram caption, pasted text, a page's text, or
 *    text recognised from a screenshot), split on its "Ingredients" and "Method"
 *    headings, or on what ingredient lines look like when there are no headings.
 */
object BuiltInReader {

    // ---------- page markup ----------

    private val ingredientSelectors = listOf(
        "[itemprop=recipeIngredient]", "[itemprop=ingredients]",
        ".wprm-recipe-ingredient", ".tasty-recipes-ingredients li", ".mv-create-ingredients li",
        ".easyrecipe .ingredient", ".recipe-ingredients li", ".ingredients li",
    )
    private val stepSelectors = listOf(
        "[itemprop=recipeInstructions] li", "[itemprop=recipeInstructions]",
        ".wprm-recipe-instruction-text", ".tasty-recipes-instructions li", ".mv-create-instructions li",
        ".easyrecipe .instruction", ".recipe-method li", ".recipe-instructions li", ".method li", ".instructions li",
    )

    fun fromMarkup(doc: Document): JsonObject? {
        val ingredients = firstNonEmpty(doc, ingredientSelectors)
        val steps = firstNonEmpty(doc, stepSelectors)
        if (ingredients.size < 2 || steps.isEmpty()) return null
        val title = doc.selectFirst("[itemprop=name]")?.text()?.trim()?.ifEmpty { null }
            ?: doc.metaContent("og:title") ?: doc.title()
        return schema(title, ingredients, steps, textForTimes = doc.select("[itemprop=recipeYield], .wprm-recipe-servings").text())
    }

    private fun firstNonEmpty(doc: Document, selectors: List<String>): List<String> {
        for (selector in selectors) {
            val lines = doc.select(selector).map(Element::text).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (lines.isNotEmpty()) return lines
        }
        return emptyList()
    }

    // ---------- plain text ----------

    private val ingredientHeading = Regex(
        """^(?:the\s+)?(?:ingredients?|you(?:'|’)?ll need|what you(?:'|’)?ll need|what you need|shopping list)\b.{0,20}$""",
        RegexOption.IGNORE_CASE,
    )
    private val methodHeading = Regex(
        """^(?:the\s+)?(?:method|directions|instructions|steps|preparation|how to make(?: it)?|to make)\b.{0,20}$""",
        RegexOption.IGNORE_CASE,
    )
    private val stopHeading = Regex(
        """^(?:notes?|tips?|nutrition|serving suggestions?|storage|enjoy|follow|save this|recipe by)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val bullet = Regex("""^\s*(?:[-–—•*·▪●○◦✓✔☐☑▫️➡️👉]+|\d+[.)]|\(\d+\)|step\s*\d+\s*[:.)-]?)\s*""", RegexOption.IGNORE_CASE)
    private val looksLikeIngredient = Regex(
        """^(?:\d|[¼½¾⅓⅔⅛]|a\s+(?:pinch|handful|knob|splash|few)|an?\s+\w+\s+of\b|(?:small|large|medium)\s+(?:pack|bunch|handful))""",
        RegexOption.IGNORE_CASE,
    )
    private val hashtagLine = Regex("""^(?:[#@]\S+\s*)+$""")
    private val hashtags = Regex("""(?:^|\s)[#@]\S+""")
    private val emoji = Regex("""[\p{So}\p{Cs}️‍]""")
    private val spaces = Regex("""\s+""")
    private val sentence = Regex("""(?<=[.!?])\s+(?=[A-Z])""")

    /** Returns schema.org JSON, or null if no recipe could be found. */
    fun fromText(text: String, fallbackTitle: String? = null): JsonObject? {
        val lines = text.lines()
            .map { it.replace(emoji, "").replace(spaces, " ").trim() }
            .filterNot { it.isEmpty() || hashtagLine.matches(it) }
            .map { it.replace(hashtags, "").trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val ingStart = lines.indexOfFirst { ingredientHeading.matches(it.trimEnd(':', ' ')) }
        val methodStart = lines.indexOfFirst { methodHeading.matches(it.trimEnd(':', ' ')) }

        val ingredients: List<String>
        val steps: List<String>
        if (ingStart >= 0) {
            val ingEnd = listOf(methodStart.takeIf { it > ingStart }, stopIndex(lines, ingStart + 1))
                .filterNotNull().minOrNull() ?: lines.size
            ingredients = lines.subList(ingStart + 1, ingEnd).map(::clean).filter { it.isNotEmpty() }
            steps = if (methodStart > ingStart) {
                lines.subList(methodStart + 1, stopIndex(lines, methodStart + 1) ?: lines.size).map(::clean)
            } else {
                // No method heading: whatever follows the ingredients that reads like instructions.
                lines.subList(ingEnd, lines.size).takeWhile { !stopHeading.containsMatchIn(it) }.map(::clean)
            }
        } else {
            // No headings: the longest run of ingredient-looking lines is the ingredient list.
            val run = longestIngredientRun(lines) ?: return null
            ingredients = lines.subList(run.first, run.last + 1).map(::clean)
            steps = lines.subList(run.last + 1, lines.size).takeWhile { !stopHeading.containsMatchIn(it) }.map(::clean)
        }

        val method = steps.filter { it.isNotEmpty() }.flatMap { splitLongStep(it) }
        if (ingredients.size < 2 || method.isEmpty()) return null

        val firstLine = lines.first().takeIf { it.length in 3..80 && it.indexOf(':') < 0 && !looksLikeIngredient.containsMatchIn(it) }
        val title = firstLine?.takeIf { ingStart != 0 } ?: fallbackTitle ?: "Untitled recipe"
        return schema(title, ingredients, method, textForTimes = text)
    }

    private fun stopIndex(lines: List<String>, from: Int): Int? =
        (from until lines.size).firstOrNull { stopHeading.containsMatchIn(lines[it]) }

    private fun clean(line: String): String = line.replace(bullet, "").trim().trimEnd(',')

    private fun longestIngredientRun(lines: List<String>): IntRange? {
        var best: IntRange? = null
        var start = -1
        lines.forEachIndexed { i, raw ->
            val line = raw.replace(bullet, "").trim()
            val ok = line.length <= 80 && (looksLikeIngredient.containsMatchIn(line) || raw.trimStart().firstOrNull() in setOf('-', '•', '*', '–'))
            if (ok && start < 0) start = i
            if ((!ok || i == lines.lastIndex) && start >= 0) {
                val end = if (ok) i else i - 1
                if (best == null || end - start > best!!.last - best!!.first) best = start..end
                start = -1
            }
        }
        return best?.takeIf { it.last - it.first >= 2 }
    }

    /** One giant paragraph of method becomes sentence-sized steps. */
    private fun splitLongStep(step: String): List<String> =
        if (step.length < 280) listOf(step) else step.split(sentence).map { it.trim() }.filter { it.isNotEmpty() }

    // ---------- times and servings ----------

    private val serves = Regex("""\b(?:serves|servings?:?|makes|feeds)\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val prep = Regex("""\bprep(?:aration)?(?:\s*time)?\s*:?\s*(\d+)\s*(h|hr|hrs|hours?|m|mins?|minutes?)\b""", RegexOption.IGNORE_CASE)
    private val cook = Regex("""\bcook(?:ing)?(?:\s*time)?\s*:?\s*(\d+)\s*(h|hr|hrs|hours?|m|mins?|minutes?)\b""", RegexOption.IGNORE_CASE)

    private fun iso(match: MatchResult?): String? = match?.let {
        val n = it.groupValues[1].toInt()
        if (it.groupValues[2].startsWith("h", ignoreCase = true)) "PT${n}H" else "PT${n}M"
    }

    private fun schema(title: String, ingredients: List<String>, steps: List<String>, textForTimes: String): JsonObject =
        buildJsonObject {
            put("@type", "Recipe")
            put("name", title.trim())
            serves.find(textForTimes)?.let { put("recipeYield", "Serves ${it.groupValues[1]}") }
            iso(prep.find(textForTimes))?.let { put("prepTime", it) }
            iso(cook.find(textForTimes))?.let { put("cookTime", it) }
            putJsonArray("recipeIngredient") { ingredients.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("recipeInstructions") {
                steps.forEach { step ->
                    addJsonObject {
                        put("@type", "HowToStep")
                        put("text", step)
                    }
                }
            }
        }
}
