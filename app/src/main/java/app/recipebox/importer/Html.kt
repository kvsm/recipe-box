package app.recipebox.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jsoup.nodes.Document

class ImportException(message: String, val retryable: Boolean = false) : Exception(message)

internal fun Document.metaContent(key: String): String? =
    (selectFirst("meta[property=$key]") ?: selectFirst("meta[name=$key]"))
        ?.attr("content")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

internal fun isInstagram(url: String): Boolean =
    runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").let {
        it == "instagram.com" || it.endsWith(".instagram.com") || it == "instagr.am"
    }

/** Finds a schema.org Recipe in any <script type="application/ld+json"> block. */
object JsonLdExtractor {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun find(doc: Document): JsonObject? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val element = runCatching { json.parseToJsonElement(script.data().trim()) }.getOrNull() ?: continue
            SchemaOrgMapper.findRecipe(element)?.let { return it }
        }
        return null
    }
}

/** Readable text from a page, for Claude when there's no structured data. */
object PageText {
    private const val MAX_CHARS = 15_000

    fun extract(doc: Document): String {
        val parts = mutableListOf<String>()
        doc.title().takeIf { it.isNotBlank() }?.let { parts += "Title: $it" }
        listOf("og:title", "og:description", "description")
            .mapNotNull { doc.metaContent(it) }
            .distinct()
            .forEach { parts += it }
        val main = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
        main?.let { el ->
            el.select("script, style, nav, footer, header, aside, form, noscript").remove()
            el.text().takeIf { it.isNotBlank() }?.let { parts += it }
        }
        return parts.joinToString("\n\n").take(MAX_CHARS).trim()
    }
}
