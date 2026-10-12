package app.recipebox.groceries

import android.net.Uri
import app.recipebox.settings.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class GroceryException(message: String) : Exception(message)

/**
 * Talks to OurGroceries' official MCP server (https://www.ourgroceries.com/mcp).
 *
 * Auth is standard MCP OAuth: dynamic client registration, authorization code
 * with PKCE, public client (no secret). The user signs in on OurGroceries' own
 * page in a browser tab; the app only ever holds the access token.
 *
 * Tools used (OurGroceries labels the server experimental, so these may change):
 *  - get_lists {type}: the user's lists with id, name and list_type
 *  - get_categories: the user's category names (items are matched to these, never new ones)
 *  - get_list_items {list_id, active_only: false}: each list's items and history, to reuse the user's own categories
 *  - edit_items {operations: [{action: "add", list_id, name, note, category}]}: batch add
 */
class OurGroceriesClient(
    private val http: OkHttpClient,
    private val secrets: SecretStore,
    private val categories: CategoryMatcher,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun isConnected(): Boolean {
        if (secrets.get(SecretStore.OG_ACCESS_TOKEN).isNullOrEmpty()) return false
        val expiresAt = secrets.get(SecretStore.OG_TOKEN_EXPIRES_AT)?.toLongOrNull() ?: return true
        return System.currentTimeMillis() < expiresAt
    }

    fun signOut() {
        secrets.put(SecretStore.OG_ACCESS_TOKEN, null)
        secrets.put(SecretStore.OG_TOKEN_EXPIRES_AT, null)
    }

    // ---------- OAuth ----------

    /** Registers the app if needed and returns the sign-in page to open in a browser tab. */
    suspend fun beginSignIn(): Uri = withContext(Dispatchers.IO) {
        val clientId = secrets.get(SecretStore.OG_CLIENT_ID) ?: register().also { secrets.put(SecretStore.OG_CLIENT_ID, it) }
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(24)
        secrets.put(SecretStore.OG_PKCE_VERIFIER, verifier)
        secrets.put(SecretStore.OG_STATE, state)

        Uri.parse(AUTHORIZE).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge", challengeFor(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("resource", RESOURCE)
            .build()
    }

    /** Handles the redirect back into the app and swaps the code for a token. */
    suspend fun completeSignIn(redirect: Uri) = withContext(Dispatchers.IO) {
        redirect.getQueryParameter("error")?.let { throw GroceryException("OurGroceries sign-in was cancelled ($it).") }
        val code = redirect.getQueryParameter("code") ?: throw GroceryException("OurGroceries didn't send a sign-in code.")
        if (redirect.getQueryParameter("state") != secrets.get(SecretStore.OG_STATE)) {
            throw GroceryException("Sign-in check failed. Try connecting again.")
        }
        val clientId = secrets.get(SecretStore.OG_CLIENT_ID) ?: throw GroceryException("Sign-in expired. Try connecting again.")
        val verifier = secrets.get(SecretStore.OG_PKCE_VERIFIER) ?: throw GroceryException("Sign-in expired. Try connecting again.")

        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .add("resource", RESOURCE)
            .build()
        val body = http.newCall(Request.Builder().url(TOKEN).post(form).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GroceryException("OurGroceries sign-in failed (${response.code}${errorDescription(text)?.let { ": $it" } ?: ""}).")
            }
            json.parseToJsonElement(text).jsonObject
        }
        val token = body["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw GroceryException("OurGroceries didn't return a token.")
        secrets.put(SecretStore.OG_ACCESS_TOKEN, token)
        secrets.put(
            SecretStore.OG_TOKEN_EXPIRES_AT,
            body["expires_in"]?.jsonPrimitive?.longOrNull?.let { (System.currentTimeMillis() + it * 1000).toString() },
        )
        secrets.put(SecretStore.OG_PKCE_VERIFIER, null)
        secrets.put(SecretStore.OG_STATE, null)
    }

    /** OAuth servers explain a refusal in error_description (or at least error); null if the body isn't that. */
    private fun errorDescription(body: String): String? = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        (obj["error_description"] ?: obj["error"])?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun register(): String {
        val payload = buildJsonObject {
            put("client_name", "Recipe Box")
            putJsonArray("redirect_uris") { add(JsonPrimitive(REDIRECT_URI)) }
            putJsonArray("grant_types") { add(JsonPrimitive("authorization_code")) }
            putJsonArray("response_types") { add(JsonPrimitive("code")) }
            put("token_endpoint_auth_method", "none")
        }
        val request = Request.Builder().url(REGISTER).post(payload.toString().toRequestBody(JSON_MEDIA)).build()
        return http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = errorDescription(text)
                throw GroceryException(
                    if (reason?.contains("client_name", ignoreCase = true) == true) {
                        "OurGroceries hasn't approved Recipe Box yet. Until it does, Add to list copies your items to paste in."
                    } else {
                        "OurGroceries wouldn't register the app (${response.code}${reason?.let { ": $it" } ?: ""})."
                    },
                )
            }
            json.parseToJsonElement(text).jsonObject["client_id"]?.jsonPrimitive?.contentOrNull
                ?: throw GroceryException("OurGroceries didn't return a client ID.")
        }
    }

    // ---------- MCP ----------

    /**
     * Adds [items] to the shopping list called [listName] (matched case-insensitively).
     * Uses OurGroceries' get_lists to find the list's ID, then one edit_items batch.
     * Returns how many were added.
     */
    suspend fun addItems(items: List<GroceryItem>, listName: String): Int = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext 0
        val session = McpSession()
        session.initialize()

        // All lists (shopping and recipes): the target is matched among shopping lists,
        // and every list's items feed the category history.
        val lists = session.callTool("get_lists", buildJsonObject { put("type", "all") }).asArray().map { it.jsonObject }
        val shopping = lists.filter { it["list_type"]?.jsonPrimitive?.contentOrNull.equals("shopping", ignoreCase = true) }
        val match = shopping.firstOrNull {
            it["name"]?.jsonPrimitive?.contentOrNull.equals(listName.trim(), ignoreCase = true)
        } ?: throw GroceryException(
            "There's no OurGroceries list called \"$listName\". Your lists: " +
                shopping.mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull }.joinToString(),
        )
        val listId = match["id"]!!.jsonPrimitive.content

        // Put each item in one of the user's existing categories, so nothing lands "Uncategorised".
        val existing = session.callTool("get_categories", buildJsonObject {}).let { result ->
            (result as? JsonObject)?.get("categories")?.jsonArray ?: result.asArray()
        }.mapNotNull { it.jsonPrimitive.contentOrNull }
        val history = categoryHistory(session, listId, lists)
        val assigned = categories.assign(items, existing, history)

        val reply = session.callTool("edit_items", buildJsonObject {
            putJsonArray("operations") {
                items.forEach { item ->
                    addJsonObject {
                        put("action", "add")
                        put("list_id", listId)
                        // OurGroceries' convention is lower case except proper nouns.
                        put("name", item.name.replaceFirstChar { it.lowercase() })
                        item.note?.let { put("note", it) }
                        assigned[item.name.lowercase()]?.let { put("category", it) }
                    }
                }
            }
        })
        // Each op reports back, e.g. {"action":"ADDED","item_id":...}; count what actually landed.
        reply.asArray().count { (it as? JsonObject)?.get("action")?.jsonPrimitive?.contentOrNull == "ADDED" }
    }

    /**
     * The categories the user has actually given items, read from every list
     * including crossed-off history. Item name (normalised) → category.
     * Precedence when the same item appears more than once: the target list
     * beats other shopping lists, which beat recipes; within a list, an item
     * still on the list beats a crossed-off one.
     */
    private fun categoryHistory(session: McpSession, targetListId: String, lists: List<JsonObject>): Map<String, String> {
        fun rank(list: JsonObject): Int {
            val id = list["id"]?.jsonPrimitive?.contentOrNull
            val type = list["list_type"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            return when {
                id == targetListId -> 0
                type == "shopping" -> 1
                else -> 2
            }
        }
        val history = mutableMapOf<String, String>()
        // Lowest precedence first, so later writes overwrite earlier ones.
        lists.sortedByDescending { rank(it) }.forEach { list ->
            val id = list["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val items = runCatching {
                session.callTool("get_list_items", buildJsonObject {
                    put("list_id", id)
                    put("active_only", false)
                })
            }.getOrNull() ?: return@forEach
            val entries = ((items as? JsonObject)?.get("items")?.jsonArray ?: items.asArray()).map { it.jsonObject }
            entries.sortedByDescending { it["is_crossed_off"]?.jsonPrimitive?.booleanOrNull == true }.forEach { item ->
                val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val category = item["category"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@forEach
                history[CategoryMatcher.normalise(name)] = category
            }
        }
        return history
    }

    /** One Streamable HTTP MCP session. */
    private inner class McpSession {
        private var sessionId: String? = null
        private var protocolVersion: String? = null
        private var nextId = 1

        fun initialize() {
            val result = request("initialize", buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "recipe-box")
                    put("version", "0.1.0")
                }
            })
            protocolVersion = result["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: PROTOCOL_VERSION
            notify("notifications/initialized")
        }

        /** Calls a tool and returns its result: structured content if given, else the text parsed as JSON. */
        fun callTool(name: String, arguments: JsonObject): JsonElement {
            val result = request("tools/call", buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            })
            if (result["isError"]?.jsonPrimitive?.booleanOrNull == true) {
                throw GroceryException("OurGroceries said: ${result.textContent().ifBlank { "something went wrong" }}")
            }
            result["structuredContent"]?.let { return it }
            val text = result.textContent()
            return runCatching { json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
        }

        fun request(method: String, params: JsonObject): JsonObject {
            val id = nextId++
            val message = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }
            val reply = post(message) ?: throw GroceryException("OurGroceries didn't answer $method.")
            val response = findResponse(reply, id) ?: throw GroceryException("OurGroceries sent an unexpected reply to $method.")
            response["error"]?.jsonObject?.let { err ->
                throw GroceryException("OurGroceries error: ${err["message"]?.jsonPrimitive?.contentOrNull ?: "unknown"}")
            }
            return response["result"]?.jsonObject ?: buildJsonObject {}
        }

        private fun notify(method: String) {
            post(buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
            })
        }

        /** Returns the raw body, or null for 202 Accepted. */
        private fun post(message: JsonObject): Pair<String, String>? {
            val token = secrets.get(SecretStore.OG_ACCESS_TOKEN) ?: throw GroceryException("Connect OurGroceries in Settings first.")
            val builder = Request.Builder()
                .url(MCP_URL)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json, text/event-stream")
                .post(message.toString().toRequestBody(JSON_MEDIA))
            sessionId?.let { builder.header("Mcp-Session-Id", it) }
            protocolVersion?.let { builder.header("MCP-Protocol-Version", it) }

            http.newCall(builder.build()).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    signOut()
                    throw GroceryException("OurGroceries signed you out. Reconnect it in Settings.")
                }
                if (!response.isSuccessful) throw GroceryException("OurGroceries returned an error (${response.code}).")
                response.header("Mcp-Session-Id")?.let { sessionId = it }
                if (response.code == 202) return null
                return (response.header("Content-Type").orEmpty()) to response.body?.string().orEmpty()
            }
        }

        private fun findResponse(reply: Pair<String, String>, id: Int): JsonObject? {
            val (contentType, body) = reply
            val candidates: List<JsonElement> = if (contentType.contains("text/event-stream")) {
                body.lineSequence()
                    .filter { it.startsWith("data:") }
                    .mapNotNull { runCatching { json.parseToJsonElement(it.removePrefix("data:").trim()) }.getOrNull() }
                    .toList()
            } else {
                listOfNotNull(runCatching { json.parseToJsonElement(body) }.getOrNull())
            }
            return candidates
                .flatMap { if (it is JsonArray) it.toList() else listOf(it) }
                .map { it.jsonObject }
                .firstOrNull { it["id"]?.jsonPrimitive?.intOrNull == id }
        }
    }

    companion object {
        const val REDIRECT_SCHEME = "app.recipebox"
        const val REDIRECT_URI = "$REDIRECT_SCHEME:/oauth/ourgroceries"
        private const val BASE = "https://www.ourgroceries.com"
        private const val RESOURCE = BASE
        private const val MCP_URL = "$BASE/mcp"
        private const val AUTHORIZE = "$BASE/oauth2"
        private const val TOKEN = "$BASE/oauth2/token"
        private const val REGISTER = "$BASE/oauth2/register"
        private const val PROTOCOL_VERSION = "2025-06-18"
        private val JSON_MEDIA = "application/json".toMediaType()

        private fun randomUrlSafe(bytes: Int): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

        private fun challengeFor(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}

private fun JsonObject.textContent(): String =
    this["content"]?.jsonArray.orEmpty()
        .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        .joinToString(" ")

/** get_lists may come back as a bare array or wrapped in an object. */
private fun JsonElement.asArray(): JsonArray = when (this) {
    is JsonArray -> this
    is JsonObject -> values.firstOrNull { it is JsonArray } as? JsonArray ?: JsonArray(emptyList())
    else -> JsonArray(emptyList())
}

/** One shopping-list entry: "floury potatoes" with the note "1 kg". */
data class GroceryItem(val name: String, val note: String? = null) {
    override fun toString(): String = if (note.isNullOrBlank()) name else "$name ($note)"
}
