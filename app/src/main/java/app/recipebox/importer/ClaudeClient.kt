package app.recipebox.importer

import app.recipebox.settings.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Minimal Claude Messages API client using the user's own API key.
 * Every call forces a single tool so the reply is always structured JSON.
 */
class ClaudeClient(
    private val http: OkHttpClient,
    private val secrets: SecretStore,
) {
    fun hasKey(): Boolean = !secrets.get(SecretStore.CLAUDE_API_KEY).isNullOrBlank()

    /** The Settings switch: on unless turned off. Turning it off keeps the key. */
    var enabled: Boolean
        get() = secrets.get(SecretStore.CLAUDE_ENABLED) != "false"
        set(value) = secrets.put(SecretStore.CLAUDE_ENABLED, if (value) null else "false")

    /** Whether to use Claude right now: there's a key, and the switch is on. Everything Claude-powered checks this. */
    fun isOn(): Boolean = enabled && hasKey()

    /** Sends [content] and returns the forced tool's `input` object. */
    suspend fun callTool(
        system: String,
        content: JsonArray,
        tool: JsonObject,
        maxTokens: Int = 4096,
    ): JsonObject = withContext(Dispatchers.IO) {
        val key = secrets.get(SecretStore.CLAUDE_API_KEY)
            ?: throw ImportException("This one needs Claude, but there's no API key yet. Add one in Settings.")
        val toolName = tool["name"]!!.jsonPrimitive.content

        val body = buildJsonObject {
            put("model", MODEL)
            put("max_tokens", maxTokens)
            put("system", system)
            putJsonArray("tools") { add(tool) }
            putJsonObject("tool_choice") {
                put("type", "tool")
                put("name", toolName)
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", content)
                }
            }
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFor(response.code, text)
            val json = Json.parseToJsonElement(text).jsonObject
            json["content"]?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["type"]?.jsonPrimitive?.content == "tool_use" }
                ?.get("input")?.jsonObject
                ?: throw ImportException("Claude didn't send back anything usable.")
        }
    }

    private fun errorFor(code: Int, body: String): ImportException {
        val message = runCatching {
            Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        }.getOrNull()
        return when (code) {
            401 -> ImportException("Claude rejected the API key. Check it in Settings.")
            429, 500, 502, 503, 529 -> ImportException("Claude is busy right now. Trying again shortly.", retryable = true)
            else -> ImportException("Claude returned an error ($code)${message?.let { ": $it" } ?: ""}")
        }
    }

    companion object {
        // Small, fast and cheap is plenty for extraction. Swap for a bigger model if quality suffers.
        const val MODEL = "claude-haiku-5-5"
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
