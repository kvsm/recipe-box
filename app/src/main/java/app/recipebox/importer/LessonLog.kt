package app.recipebox.importer

import android.content.Context
import android.net.Uri
import app.recipebox.data.model.Ingredient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.math.abs

/**
 * Lessons for the built-in fallbacks. Whenever Claude (or the user, by hand)
 * gets a different answer from a built-in fallback, the input and both answers
 * are kept on the phone as JSON Lines. Export from Settings, hand the file to
 * Claude, and fold the common patterns into the fallbacks for the next release,
 * so the app keeps getting better for people without a key.
 *
 * Areas (the "area" field on every entry):
 *  - ingredient  ingredient line → name / amount / unit / note  (IngredientParser)
 *  - reader      caption, pasted text or page text → recipe     (BuiltInReader.fromText)
 *  - screenshot  on-device text from a screenshot → recipe      (OnDeviceText + BuiltInReader)
 *  - prep        method step → Get ready tasks                  (HeuristicPrep)
 *  - category    shopping item → category                       (KeywordCategories)
 *
 * "source" says who had the better answer: "claude", or "you" for the user's own
 * fixes and filing, which are the strongest lessons. Entries are de-duplicated per
 * area and input, capped per area, and never leave the phone unless exported.
 */
class LessonLog(context: Context) {
    private val file = File(context.filesDir, "lessons.jsonl")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun count(): Int = readLines().size

    /** How many lessons per area, for Settings. */
    @Synchronized
    fun countsByArea(): Map<String, Int> = readLines()
        .mapNotNull { runCatching { json.parseToJsonElement(it).jsonObject["area"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
        .groupingBy { it }.eachCount()

    /**
     * Records one lesson. [key] identifies the input (an ingredient line, a step, an
     * item name); a later lesson for the same area and key replaces the earlier one.
     * Claude's lessons never replace the user's own.
     */
    @Synchronized
    fun record(area: String, key: String, input: JsonElement, builtIn: JsonElement, better: JsonElement, source: String = "claude") {
        val id = "$area|${key.trim().lowercase()}"
        val lines = readLines()
        val existing = lines.firstOrNull { idOf(it) == id }
        if (existing != null && source == "claude" && sourceOf(existing) == "you") return
        val entry = buildJsonObject {
            put("area", area)
            put("key", key.trim())
            put("source", source)
            put("at", System.currentTimeMillis())
            put("input", input)
            put("built_in", builtIn)
            put(source, better)
        }.toString()
        val others = lines.filter { idOf(it) != id }
        // Cap each area separately so one chatty area can't push the others out.
        val sameArea = others.filter { areaOf(it) == area }
        val trimmed = if (sameArea.size >= MAX_PER_AREA) others - sameArea.first() else others
        file.writeText((trimmed + entry).joinToString("\n", postfix = "\n"))
    }

    // ---------- ingredients ----------

    /** Compares the two readings line by line and records the disagreements. */
    fun recordIngredients(parser: List<Ingredient>, claude: List<Ingredient>) {
        if (parser.size != claude.size) return
        parser.zip(claude).filter { (p, c) -> differs(p, c) }.distinctBy { it.first.raw.lowercase() }.forEach { (p, c) ->
            record("ingredient", p.raw, text(p.raw), describe(p), describe(c))
        }
    }

    /** A line the user fixed by hand: the strongest kind of lesson. */
    fun recordCorrection(fixed: Ingredient) {
        val parser = IngredientParser.parse(fixed.raw)
        if (!differs(parser, fixed)) return
        record("ingredient", fixed.raw, text(fixed.raw), describe(parser), describe(fixed), source = "you")
    }

    private fun describe(i: Ingredient): JsonObject = buildJsonObject {
        put("name", i.name)
        i.quantity?.let { put("quantity", it) }
        i.quantityMax?.let { put("quantity_max", it) }
        i.unit?.let { put("unit", it) }
        i.amountText?.let { put("amount_text", it) }
        i.note?.let { put("note", it) }
    }

    // ---------- housekeeping ----------

    suspend fun exportTo(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val lines = synchronized(this@LessonLog) { readLines() }
        val stream = context.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Couldn't open that file.")
        stream.use { it.write(lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)) }
        lines.size
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun readLines(): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    private fun field(line: String, name: String): String? =
        runCatching { json.parseToJsonElement(line).jsonObject[name]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun areaOf(line: String) = field(line, "area")
    private fun sourceOf(line: String) = field(line, "source")
    private fun idOf(line: String) = "${areaOf(line)}|${field(line, "key")?.lowercase()}"

    companion object {
        private const val MAX_PER_AREA = 500

        /** Long inputs (whole captions, page text) are trimmed; the start is where the structure is. */
        const val MAX_INPUT_CHARS = 8000

        fun text(value: String?): JsonElement =
            if (value == null) JsonNull else JsonPrimitive(value.take(MAX_INPUT_CHARS))

        /**
         * A real disagreement: different item, amount or unit. Differences only
         * in the free-text note, or in capitalisation, don't count.
         */
        fun differs(parser: Ingredient, claude: Ingredient): Boolean {
            fun close(a: Double?, b: Double?) = (a == null && b == null) || (a != null && b != null && abs(a - b) < 0.01)
            return parser.name.trim().lowercase() != claude.name.trim().lowercase() ||
                !close(parser.quantity, claude.quantity) ||
                !close(parser.quantityMax, claude.quantityMax) ||
                parser.unit.orEmpty() != claude.unit.orEmpty() ||
                parser.amountText.orEmpty().lowercase() != claude.amountText.orEmpty().lowercase()
        }
    }
}

