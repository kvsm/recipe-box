package app.recipebox.importer

import androidx.core.net.toUri
import app.recipebox.data.RecipePhotos
import app.recipebox.data.RecipeRepository
import app.recipebox.data.model.RawSnapshot
import app.recipebox.data.model.ReadBy
import app.recipebox.data.model.Recipe
import app.recipebox.data.model.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException

/**
 * The import pipeline: fetch → the page's own recipe data if present → Claude
 * if there's a key → the built-in reader if not (or if Claude fails) → prep
 * pass → save with an untouched snapshot of the source.
 *
 * Claude makes imports better, never possible: everything has a path without it.
 */
class RecipeImporter(
    private val http: OkHttpClient,
    private val extractor: ClaudeExtractor,
    private val prepPlanner: PrepPlanner,
    private val repository: RecipeRepository,
    private val photos: RecipePhotos,
    private val lessons: LessonLog,
) {

    /**
     * [canRetry] is false on the worker's last attempt: then a Claude hiccup
     * (network, busy) falls back to the built-in reader instead of failing.
     */
    suspend fun import(request: ImportRequest, canRetry: Boolean = true): Long = when (request) {
        is ImportRequest.Url -> importUrl(request.url, canRetry)
        is ImportRequest.TextFile -> importText(File(request.path), canRetry)
        is ImportRequest.ImageFile -> importImage(File(request.path), canRetry)
    }

    private suspend fun importUrl(url: String, canRetry: Boolean): Long {
        val doc = fetch(url)
        // Photo candidates, best first: the recipe's own image, YouTube's thumbnail, then the page's share image
        // (for Instagram, TikTok and Facebook that's the post's photo or the video's cover frame).
        val pagePhotos = RecipePhotos.youTubeThumbnails(url) +
            listOfNotNull(doc.metaContent("og:image"), doc.metaContent("og:image:secure_url"), doc.metaContent("twitter:image"))

        if (!isInstagram(url)) {
            JsonLdExtractor.find(doc)?.let { schema ->
                val recipe = SchemaOrgMapper.toRecipe(schema, url, SourceType.WEB_JSONLD).withLocalPhoto(pagePhotos)
                return save(recipe, RawSnapshot("schema_org", schema.toString(), url))
            }
            // No JSON-LD, but microdata or a recipe plugin's markup: still structured, still free.
            BuiltInReader.fromMarkup(doc)?.let { schema ->
                val recipe = SchemaOrgMapper.toRecipe(schema, url, SourceType.WEB_JSONLD).withLocalPhoto(pagePhotos)
                return save(recipe, RawSnapshot("schema_org", schema.toString(), url))
            }
        }

        val text = PageText.extract(doc)
        if (text.isBlank()) {
            throw ImportException("Couldn't read that page. Try sharing a screenshot or pasting the text instead.")
        }
        val (schema, readBy) = readText(text, url, doc.metaContent("og:title") ?: doc.title(), canRetry)
        val type = if (isInstagram(url)) SourceType.INSTAGRAM else SourceType.WEB_AI
        val recipe = SchemaOrgMapper.toRecipe(schema, url, type).copy(readBy = readBy).withLocalPhoto(pagePhotos)
        return save(recipe, RawSnapshot("page_text", text, url))
    }

    private suspend fun importText(file: File, canRetry: Boolean): Long {
        val text = withContext(Dispatchers.IO) { file.readText() }
        val (schema, readBy) = readText(text, null, null, canRetry)
        val id = save(SchemaOrgMapper.toRecipe(schema, null, SourceType.TEXT).copy(readBy = readBy), RawSnapshot("text", text, null))
        withContext(Dispatchers.IO) { file.delete() }
        return id
    }

    private suspend fun importImage(file: File, canRetry: Boolean): Long {
        if (extractor.available()) {
            try {
                val bytes = withContext(Dispatchers.Default) { ImageUtils.loadScaledJpeg(file) }
                val schema = extractor.extract(ClaudeExtractor.Input.Image(bytes))
                // The screenshot only becomes the card photo if it actually shows the food.
                val showsDish = schema["shows_dish"]?.jsonPrimitive?.booleanOrNull == true
                val cover = if (showsDish) photos.copyFrom(file.toUri()) else null
                val recipe = SchemaOrgMapper.toRecipe(schema, null, SourceType.IMAGE).copy(imageUrl = cover, readBy = ReadBy.CLAUDE)
                // Also read it the no-key way, to learn how on-device text from screenshots trips the reader up.
                runCatching { OnDeviceText.read(file) }.getOrNull()?.let { ocr ->
                    recordReaderLesson("screenshot", key = ocr, input = ocr, claude = schema, fallbackTitle = null)
                }
                return save(recipe, RawSnapshot("image_file", file.absolutePath, null))
            } catch (e: Exception) {
                rethrowIfRetryable(e, canRetry)
            }
        }
        // Without Claude: read the text on the phone, then the built-in reader. No photo, as we can't tell
        // whether the screenshot shows the food or just words.
        val text = OnDeviceText.read(file)
        val schema = BuiltInReader.fromText(text) ?: throw noRecipe()
        val recipe = SchemaOrgMapper.toRecipe(schema, null, SourceType.IMAGE).copy(readBy = ReadBy.BUILT_IN)
        return save(recipe, RawSnapshot("image_file", file.absolutePath, null))
    }

    /** Claude if there's a key and it works; the built-in reader otherwise. */
    private suspend fun readText(text: String, url: String?, fallbackTitle: String?, canRetry: Boolean): Pair<JsonObject, ReadBy> {
        if (extractor.available()) {
            try {
                val schema = extractor.extract(ClaudeExtractor.Input.Text(text, url))
                recordReaderLesson("reader", key = url ?: text, input = text, claude = schema, fallbackTitle = fallbackTitle)
                return schema to ReadBy.CLAUDE
            } catch (e: Exception) {
                rethrowIfRetryable(e, canRetry)
            }
        }
        BuiltInReader.fromText(text, fallbackTitle)?.let { return it to ReadBy.BUILT_IN }
        throw noRecipe()
    }

    /**
     * Runs the built-in reader over the same input Claude just read, and keeps a lesson if it would have
     * missed the recipe, or got a different set of ingredients or a very different method.
     */
    private fun recordReaderLesson(area: String, key: String, input: String, claude: JsonObject, fallbackTitle: String?) {
        runCatching {
            val good = SchemaOrgMapper.toRecipe(claude, null, SourceType.TEXT)
            val builtInSchema = BuiltInReader.fromText(input, fallbackTitle)
            val built = builtInSchema?.let { SchemaOrgMapper.toRecipe(it, null, SourceType.TEXT) }
            fun norm(s: String) = s.lowercase().replace(Regex("""[^a-z0-9½¼¾⅓⅔ ]"""), " ").replace(Regex("""\s+"""), " ").trim()
            val goodLines = good.ingredients.map { norm(it.raw) }.toSet()
            val builtLines = built?.ingredients?.map { norm(it.raw) }?.toSet().orEmpty()
            val goodSteps = good.instructions.sumOf { it.steps.size }
            val builtSteps = built?.instructions?.sumOf { it.steps.size } ?: 0
            val disagrees = built == null || goodLines != builtLines ||
                kotlin.math.abs(goodSteps - builtSteps) > maxOf(1, goodSteps / 3)
            if (disagrees) {
                lessons.record(area, key.take(200), LessonLog.text(input), summarise(built), summarise(good))
            }
        }
    }

    private fun summarise(r: Recipe?): JsonElement = if (r == null) JsonNull else buildJsonObject {
        put("title", r.title)
        putJsonArray("ingredients") { r.ingredients.forEach { add(JsonPrimitive(it.raw)) } }
        putJsonArray("steps") { r.instructions.flatMap { it.steps }.forEach { add(JsonPrimitive(it)) } }
    }

    /** Lets the worker retry a temporary Claude problem; anything else falls through to the built-in reader. */
    private fun rethrowIfRetryable(e: Exception, canRetry: Boolean) {
        if (e is CancellationException) throw e
        val temporary = e is IOException || (e is ImportException && e.retryable)
        if (temporary && canRetry) throw e
    }

    private fun noRecipe() = ImportException(
        if (extractor.available()) {
            "Couldn't find a recipe in that. Try sharing just the recipe text, or a screenshot of it."
        } else {
            "Couldn't find a recipe in that. Posts without clear Ingredients and Method sections read better with a Claude key (Settings)."
        },
    )

    /**
     * "Tidy up with Claude": re-reads a recipe from its saved original, using Claude for the reading and
     * the prep pass. Keeps the recipe's place in the tin and its photo. Hand fixes to ingredients are replaced.
     */
    suspend fun tidyUp(recipe: Recipe): Recipe {
        if (!extractor.available()) throw ImportException("Add a Claude key in Settings first.")
        val raw = repository.raw(recipe.id) ?: throw ImportException("There's no saved original for this one to re-read.")
        val (schema, readBy) = when (raw.kind) {
            "schema_org" -> Json.parseToJsonElement(raw.payload).jsonObject to ReadBy.STRUCTURED
            "page_text", "text" -> extractor.extract(ClaudeExtractor.Input.Text(raw.payload, raw.sourceUrl)) to ReadBy.CLAUDE
            "image_file" -> {
                val file = File(raw.payload).takeIf { it.exists() } ?: throw ImportException("The original screenshot is gone.")
                val bytes = withContext(Dispatchers.Default) { ImageUtils.loadScaledJpeg(file) }
                extractor.extract(ClaudeExtractor.Input.Image(bytes)) to ReadBy.CLAUDE
            }
            else -> throw ImportException("Can't re-read this one.")
        }
        val fresh = SchemaOrgMapper.toRecipe(schema, recipe.sourceUrl ?: raw.sourceUrl, recipe.sourceType)
        val planned = prepPlanner.plan(fresh.copy(readBy = readBy))
        val tidied = planned.copy(id = recipe.id, imageUrl = recipe.imageUrl, createdAt = recipe.createdAt)
        repository.update(tidied)
        return tidied
    }

    private suspend fun save(recipe: Recipe, raw: RawSnapshot): Long =
        repository.save(prepPlanner.plan(recipe), raw)

    private suspend fun fetch(url: String): Document = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-GB,en;q=0.9")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ImportException("That page returned an error (${response.code}).", retryable = response.code >= 500)
            }
            Jsoup.parse(response.body?.string().orEmpty(), response.request.url.toString())
        }
    }

    /** Swaps the photo link for a copy kept on the phone (links expire). Falls back to the link if nothing downloads. */
    private suspend fun Recipe.withLocalPhoto(pagePhotos: List<String>): Recipe {
        val candidates = listOfNotNull(imageUrl) + pagePhotos
        val local = photos.download(candidates)
        return copy(imageUrl = local ?: candidates.firstOrNull())
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}
