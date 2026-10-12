package app.recipebox.importer

import app.recipebox.data.model.InstructionSection
import app.recipebox.data.model.Recipe
import app.recipebox.data.model.SourceType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jsoup.parser.Parser
import java.time.Instant

/**
 * Converts between schema.org Recipe JSON (in all its real-world shapes) and our
 * normalised [Recipe]. Used for JSON-LD from websites, Claude's output, and export.
 */
object SchemaOrgMapper {

    // ---------- finding the Recipe node ----------

    /** Finds the Recipe object in a JSON-LD blob: top level, arrays, @graph or mainEntity. */
    fun findRecipe(element: JsonElement?): JsonObject? = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { findRecipe(it) }
        is JsonObject ->
            if (element.isType("Recipe")) element
            else findRecipe(element["@graph"]) ?: findRecipe(element["mainEntity"])
        else -> null
    }

    private fun JsonObject.isType(type: String): Boolean = when (val t = this["@type"]) {
        is JsonArray -> t.any { (it as? JsonPrimitive)?.contentOrNull.equals(type, ignoreCase = true) }
        is JsonPrimitive -> t.contentOrNull.let { it.equals(type, ignoreCase = true) || it?.endsWith("/$type") == true }
        else -> false
    }

    // ---------- schema.org -> Recipe ----------

    fun toRecipe(obj: JsonObject, sourceUrl: String?, sourceType: SourceType): Recipe {
        val yields = obj["recipeYield"].strings()
        val prep = parseDurationMinutes(obj["prepTime"].text())
        val cook = parseDurationMinutes(obj["cookTime"].text())
        val total = parseDurationMinutes(obj["totalTime"].text())
            ?: if (prep != null || cook != null) (prep ?: 0) + (cook ?: 0) else null

        return Recipe(
            title = obj["name"].text() ?: obj["headline"].text() ?: "Untitled recipe",
            description = obj["description"].text(),
            imageUrl = imageUrl(obj["image"]),
            sourceUrl = sourceUrl ?: obj["url"].text(),
            sourceType = sourceType,
            author = obj["author"].text(),
            servings = yields.firstNotNullOfOrNull { Regex("\\d+").find(it)?.value?.toIntOrNull() },
            yieldText = yields.firstOrNull { y -> y.any { it.isLetter() } } ?: yields.firstOrNull(),
            prepMinutes = prep,
            cookMinutes = cook,
            totalMinutes = total,
            cuisine = obj["recipeCuisine"].strings().joinToString(", ").ifBlank { null },
            category = obj["recipeCategory"].strings().joinToString(", ").ifBlank { null },
            keywords = obj["keywords"].strings()
                .flatMap { it.split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct(),
            ingredients = (obj["recipeIngredient"] ?: obj["ingredients"]).strings().map(IngredientParser::parse),
            instructions = parseInstructions(obj["recipeInstructions"]),
        )
    }

    private fun JsonElement?.text(): String? = when (this) {
        null, JsonNull -> null
        is JsonPrimitive -> clean(content)
        is JsonArray -> firstNotNullOfOrNull { it.text() }
        is JsonObject -> (this["name"] ?: this["text"] ?: this["@value"]).text()
    }?.takeIf { it.isNotEmpty() }

    private fun JsonElement?.strings(): List<String> = when (this) {
        null, JsonNull -> emptyList()
        is JsonArray -> flatMap { it.strings() }
        else -> listOfNotNull(text())
    }

    private fun imageUrl(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonArray -> element.firstNotNullOfOrNull { imageUrl(it) }
        is JsonObject -> (element["url"] ?: element["contentUrl"]).text()
        is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.startsWith("http") }
    }

    /**
     * Handles every instructions shape seen in the wild: a single string (maybe
     * HTML), an array of strings, HowToStep objects, HowToSection groups, or a mix.
     */
    private fun parseInstructions(element: JsonElement?): List<InstructionSection> {
        val sections = mutableListOf<InstructionSection>()
        val loose = mutableListOf<String>()

        fun flushLoose() {
            if (loose.isNotEmpty()) {
                sections += InstructionSection(name = null, steps = loose.toList())
                loose.clear()
            }
        }

        fun stepsOf(e: JsonElement?): List<String> = when (e) {
            null, JsonNull -> emptyList()
            is JsonPrimitive -> splitSteps(e.content)
            is JsonArray -> e.flatMap { stepsOf(it) }
            is JsonObject ->
                if (e.isType("HowToSection")) stepsOf(e["itemListElement"])
                else listOfNotNull((e["text"] ?: e["name"]).text())
        }

        fun visit(e: JsonElement?) {
            when (e) {
                null, JsonNull -> Unit
                is JsonArray -> e.forEach { visit(it) }
                is JsonPrimitive -> loose += splitSteps(e.content)
                is JsonObject ->
                    if (e.isType("HowToSection")) {
                        flushLoose()
                        val steps = stepsOf(e["itemListElement"])
                        if (steps.isNotEmpty()) sections += InstructionSection(name = e["name"].text(), steps = steps)
                    } else {
                        loose += stepsOf(e)
                    }
            }
        }

        visit(element)
        flushLoose()
        return sections
    }

    private val tagBreaks = Regex("(?i)<br\\s*/?>|</p>|</li>|</div>")
    private val tags = Regex("<[^>]+>")
    private val leadingNumber = Regex("^\\d+[.)]\\s+")

    private fun cleanMultiline(s: String): String =
        Parser.unescapeEntities(s.replace(tagBreaks, "\n").replace(tags, ""), false)

    private fun clean(s: String): String = cleanMultiline(s).replace(Regex("\\s+"), " ").trim()

    private fun splitSteps(raw: String): List<String> =
        cleanMultiline(raw).lines()
            .map { it.trim().removePrefix("•").trim().replace(leadingNumber, "") }
            .filter { it.isNotEmpty() }

    // ---------- durations ----------

    private val isoDuration =
        Regex("^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:[\\d.]+S)?)?$", RegexOption.IGNORE_CASE)

    /** "PT1H30M" → 90. Returns null for missing, zero or unparseable values. */
    fun parseDurationMinutes(value: String?): Int? {
        val match = isoDuration.find(value?.trim() ?: return null) ?: return null
        val (d, h, m) = match.destructured
        if (d.isEmpty() && h.isEmpty() && m.isEmpty()) return null
        val total = (d.toIntOrNull() ?: 0) * 1440 + (h.toIntOrNull() ?: 0) * 60 + (m.toIntOrNull() ?: 0)
        return total.takeIf { it > 0 }
    }

    fun formatDuration(minutes: Int?): String? = minutes?.takeIf { it > 0 }?.let { m ->
        buildString {
            append("PT")
            if (m >= 60) append("${m / 60}H")
            if (m % 60 > 0) append("${m % 60}M")
        }
    }

    // ---------- Recipe -> schema.org (export) ----------

    fun toSchemaOrg(r: Recipe): JsonObject = buildJsonObject {
        put("@context", "https://schema.org")
        put("@type", "Recipe")
        put("name", r.title)
        r.description?.let { put("description", it) }
        r.imageUrl?.takeIf { it.startsWith("http") }?.let { put("image", it) }
        r.sourceUrl?.let { put("url", it) }
        r.author?.let { name ->
            putJsonObject("author") {
                put("@type", "Person")
                put("name", name)
            }
        }
        (r.yieldText ?: r.servings?.toString())?.let { put("recipeYield", it) }
        formatDuration(r.prepMinutes)?.let { put("prepTime", it) }
        formatDuration(r.cookMinutes)?.let { put("cookTime", it) }
        formatDuration(r.totalMinutes)?.let { put("totalTime", it) }
        r.cuisine?.let { put("recipeCuisine", it) }
        r.category?.let { put("recipeCategory", it) }
        if (r.keywords.isNotEmpty()) put("keywords", r.keywords.joinToString(", "))
        putJsonArray("recipeIngredient") { r.ingredients.forEach { add(JsonPrimitive(it.raw)) } }
        putJsonArray("recipeInstructions") {
            // Prep pulled forward exports as its own "Get ready" section, so other apps see it too.
            if (r.prep.isNotEmpty()) {
                addJsonObject {
                    put("@type", "HowToSection")
                    put("name", "Get ready")
                    putJsonArray("itemListElement") { r.prep.forEach { add(howToStep(it.text)) } }
                }
            }
            val singleUnnamed = r.prep.isEmpty() && r.instructions.size <= 1 && r.instructions.firstOrNull()?.name == null
            if (singleUnnamed) {
                r.instructions.flatMap { it.steps }.forEach { add(howToStep(it)) }
            } else {
                r.instructions.forEach { section ->
                    addJsonObject {
                        put("@type", "HowToSection")
                        put("name", section.name ?: "Method")
                        putJsonArray("itemListElement") { section.steps.forEach { add(howToStep(it)) } }
                    }
                }
            }
        }
        put("dateCreated", Instant.ofEpochMilli(r.createdAt).toString())
        put("dateModified", Instant.ofEpochMilli(r.updatedAt).toString())
    }

    private fun howToStep(text: String) = buildJsonObject {
        put("@type", "HowToStep")
        put("text", text)
    }
}
