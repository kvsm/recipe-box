package app.recipebox.groceries

import app.recipebox.settings.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GroceryException(message: String) : Exception(message)

/**
 * Talks to OurGroceries through the API its own website uses, which OurGroceries
 * support recommended over their MCP server (MCP tools can change at any time;
 * agents cope with that, a compiled app doesn't). Ported from the MIT-licensed
 * Python library py-our-groceries (github.com/ljmerza/py-our-groceries), which
 * Home Assistant also uses. It's unofficial, so neither is supported by OurGroceries.
 *
 * How it works:
 *  - Sign in: POST the sign-in form; the session lives in the "ourgroceries-auth" cookie.
 *  - GET /your-lists/: the page carries the account's team ID and, in its list metadata,
 *    the ID of the account's category list.
 *  - Everything else: POST /your-lists/ with JSON {command, teamId, ...}.
 *    getOverview → {shoppingLists: [{id, name, versionId}], recipes: [...]}
 *    getList {listId} → {list: {items: [{id, name|value, categoryId, crossedOffAt}]}}
 *    insertItems {items: [{listId, value, categoryId?, note?}]}; leaving categoryId out
 *    lets OurGroceries pick the category itself.
 *
 * There's no OAuth, so the email and password are kept (encrypted, via [SecretStore])
 * to sign in again when the session cookie expires. They're only ever sent to ourgroceries.com.
 */
class OurGroceriesClient(
    http: OkHttpClient,
    private val secrets: SecretStore,
    private val categories: CategoryMatcher,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = http.newBuilder().followRedirects(true).build()

    fun isConnected(): Boolean =
        !secrets.get(SecretStore.OG_EMAIL).isNullOrEmpty() && !secrets.get(SecretStore.OG_PASSWORD).isNullOrEmpty()

    fun connectedEmail(): String? = secrets.get(SecretStore.OG_EMAIL)

    fun signOut() {
        listOf(SecretStore.OG_EMAIL, SecretStore.OG_PASSWORD, SecretStore.OG_SESSION, SecretStore.OG_TEAM_ID, SecretStore.OG_CATEGORY_LIST_ID)
            .forEach { secrets.put(it, null) }
    }

    /** Checks the email and password by signing in, and keeps them if they work. */
    suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        val session = login(email.trim(), password)
        secrets.put(SecretStore.OG_EMAIL, email.trim())
        secrets.put(SecretStore.OG_PASSWORD, password)
        secrets.put(SecretStore.OG_SESSION, session)
        loadAccount(session)
    }

    // ---------- Adding items ----------

    /**
     * Adds [items] to the shopping list called [listName] (matched case-insensitively),
     * each in the category the user has used for it before where possible.
     * If there's no such list yet, it's created first.
     */
    suspend fun addItems(items: List<GroceryItem>, listName: String): AddResult = withContext(Dispatchers.IO) {
        val name = listName.trim()
        if (items.isEmpty()) return@withContext AddResult(0, createdList = false)

        var overview = step("Reading your lists") { command("getOverview") }
        var shopping = overview.lists("shoppingLists")
        val recipes = overview.lists("recipes")
        fun find() = shopping.firstOrNull { it.name.equals(name, ignoreCase = true) }
        var target = find()
        val created = target == null
        if (target == null) {
            step("Creating the \"$name\" list") {
                command("createList", mapOf("name" to JsonPrimitive(name), "listType" to JsonPrimitive("SHOPPING")))
                    .string("error")?.let { throw GroceryException("OurGroceries said \"$it\"") }
                // Read the lists again for the new one's ID, rather than relying on createList's reply.
                overview = command("getOverview")
                shopping = overview.lists("shoppingLists")
                target = find() ?: throw GroceryException("OurGroceries didn't show the new list afterwards.")
            }
        }
        val list = target!!

        // The user's own categories, by name → id.
        val categoryIds = step("Reading your categories") {
            categoryListId()?.let { id ->
                command("getList", mapOf("listId" to JsonPrimitive(id))).items()
                    .mapNotNull { item -> item.text?.let { it to item.id } }
                    .toMap()
            }.orEmpty()
        }

        val history = categoryHistory(list, shopping, recipes, categoryIds.entries.associate { (name, id) -> id to name })
        val assigned = step("Picking categories") { categories.assign(items, categoryIds.keys.toList(), history) }

        val reply = step("Adding the items") { command("insertItems", mapOf("items" to JsonArray(items.map { item ->
            buildJsonObject {
                put("listId", list.id)
                // OurGroceries' convention is lower case except proper nouns.
                put("value", item.name.replaceFirstChar { it.lowercase() })
                item.note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
                // Unmatched items have no categoryId, so OurGroceries files them itself.
                assigned[item.name.lowercase()]?.let { categoryIds[it] }?.let { put("categoryId", it) }
            }
        }))) }
        // The website API answers errors in JSON too, rather than with an HTTP error.
        reply.string("error")?.let { throw GroceryException("Adding the items: OurGroceries said \"$it\"") }
        AddResult(items.size, createdList = created)
    }

    data class AddResult(val count: Int, val createdList: Boolean)

    /** Runs one stage of adding, labelling any failure with the stage, so a problem can be pinned down. */
    private inline fun <T> step(label: String, block: () -> T): T = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        val detail = when (e) {
            is GroceryException -> e.message
            else -> "${e::class.simpleName}: ${e.message}"
        }
        throw GroceryException("$label: $detail")
    }

    /**
     * The categories the user has actually given items, from every list including
     * crossed-off history. Item name (normalised) → category name. When an item
     * appears more than once, the target list beats other shopping lists, which beat
     * recipes; within a list, an item still on the list beats a crossed-off one.
     */
    private fun categoryHistory(
        target: OgList,
        shopping: List<OgList>,
        recipes: List<OgList>,
        categoryNames: Map<String, String>,
    ): Map<String, String> {
        val history = mutableMapOf<String, String>()
        // Lowest precedence first, so later writes win.
        val ordered = recipes + shopping.filter { it.id != target.id } + target
        ordered.forEach { list ->
            val items = runCatching { command("getList", mapOf("listId" to JsonPrimitive(list.id))).items() }.getOrNull()
                ?: return@forEach
            items.sortedByDescending { it.crossedOff }.forEach { item ->
                val name = item.text ?: return@forEach
                val category = item.categoryId?.let { categoryNames[it] } ?: return@forEach
                history[CategoryMatcher.normalise(name)] = category
            }
        }
        return history
    }

    // ---------- Session ----------

    /** Runs a command, signing in again once if the session has expired. */
    private fun command(name: String, extra: Map<String, JsonElement> = emptyMap()): JsonObject {
        var session = secrets.get(SecretStore.OG_SESSION) ?: relogin()
        var lastReply = ""
        repeat(2) { attempt ->
            val teamId = secrets.get(SecretStore.OG_TEAM_ID) ?: loadAccount(session)
            val body = buildJsonObject {
                put("command", name)
                put("teamId", teamId)
                extra.forEach { (k, v) -> put(k, v) }
            }
            val request = Request.Builder()
                .url(YOUR_LISTS)
                .header("Cookie", "$COOKIE=$session")
                .header("Accept", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            val reply = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code !in listOf(401, 403)) {
                    throw GroceryException("OurGroceries returned an error (${response.code}).")
                }
                response.body?.string().orEmpty()
            }
            // An expired session gets the sign-in page (HTML) back instead of JSON.
            val parsed = runCatching { json.parseToJsonElement(reply) as? JsonObject }.getOrNull()
            if (parsed != null) return parsed
            if (attempt == 0) session = relogin() else lastReply = reply
        }
        throw GroceryException(
            "OurGroceries didn't send back what the app expected for \"$name\". It started: " +
                lastReply.replace(Regex("\\s+"), " ").take(160),
        )
    }

    private fun relogin(): String {
        val email = secrets.get(SecretStore.OG_EMAIL)
        val password = secrets.get(SecretStore.OG_PASSWORD)
        if (email.isNullOrEmpty() || password.isNullOrEmpty()) throw GroceryException("Sign in to OurGroceries in Settings first.")
        return login(email, password).also {
            secrets.put(SecretStore.OG_SESSION, it)
            secrets.put(SecretStore.OG_TEAM_ID, null)
            secrets.put(SecretStore.OG_CATEGORY_LIST_ID, null)
        }
    }

    /** Posts the sign-in form and returns the session cookie's value. */
    private fun login(email: String, password: String): String {
        val jar = MemoryCookieJar()
        val client = http.newBuilder().cookieJar(jar).build()
        val form = FormBody.Builder()
            .add("emailAddress", email)
            .add("password", password)
            .add("action", "sign-in")
            .build()
        client.newCall(Request.Builder().url(SIGN_IN).post(form).build()).execute().use { response ->
            if (response.code >= 500) throw GroceryException("OurGroceries is having trouble (${response.code}). Try again in a bit.")
        }
        return jar.find(COOKIE) ?: throw GroceryException("OurGroceries didn't recognise that email and password.")
    }

    /** Reads the team ID and category list ID from the lists page. Returns the team ID. */
    private fun loadAccount(session: String): String {
        val page = http.newCall(Request.Builder().url(YOUR_LISTS).header("Cookie", "$COOKIE=$session").build())
            .execute().use { it.body?.string().orEmpty() }
        val teamId = TEAM_ID.find(page)?.groupValues?.get(1)
            ?: throw GroceryException("Couldn't read your OurGroceries account. They may have changed their site.")
        secrets.put(SecretStore.OG_TEAM_ID, teamId)
        STATIC_METALIST.find(page)?.groupValues?.get(1)?.let { metalist ->
            runCatching {
                json.parseToJsonElement(metalist).jsonArray
                    .map { it.jsonObject }
                    .firstOrNull { it.string("listType") == "CATEGORY" }
                    ?.string("id")
            }.getOrNull()?.let { secrets.put(SecretStore.OG_CATEGORY_LIST_ID, it) }
        }
        return teamId
    }

    private fun categoryListId(): String? = secrets.get(SecretStore.OG_CATEGORY_LIST_ID)
        ?: secrets.get(SecretStore.OG_SESSION)?.let { loadAccount(it); secrets.get(SecretStore.OG_CATEGORY_LIST_ID) }

    // ---------- Reply shapes ----------

    private data class OgList(val id: String, val name: String)
    private data class OgItem(val id: String, val text: String?, val categoryId: String?, val crossedOff: Boolean)

    private fun JsonObject.lists(key: String): List<OgList> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id") ?: return@mapNotNull null
            OgList(id, obj.string("name").orEmpty())
        }

    private fun JsonObject.items(): List<OgItem> =
        ((this["list"] as? JsonObject)?.get("items") as? JsonArray).orEmpty().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id") ?: return@mapNotNull null
            OgItem(
                id = id,
                text = (obj.string("value") ?: obj.string("name"))?.takeIf { it.isNotBlank() },
                categoryId = obj.string("categoryId")?.takeIf { it.isNotBlank() },
                crossedOff = obj["crossedOffAt"].let { it != null && it !is JsonNull } ||
                    obj.string("crossedOff") == "true",
            )
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Keeps cookies for one sign-in, across its redirects. */
    private class MemoryCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { this.cookies += cookies }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
        fun find(name: String): String? = cookies.lastOrNull { it.name == name && it.value.isNotEmpty() }?.value
    }

    companion object {
        private const val BASE = "https://www.ourgroceries.com"
        private const val SIGN_IN = "$BASE/sign-in"
        private const val YOUR_LISTS = "$BASE/your-lists/"
        private const val COOKIE = "ourgroceries-auth"
        private val TEAM_ID = Regex("""g_teamId = "(.*?)";""")
        private val STATIC_METALIST = Regex("""g_staticMetalist = (\[.*\]);""")
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}

/** One shopping-list entry: "floury potatoes" with the note "1 kg". */
data class GroceryItem(val name: String, val note: String? = null) {
    override fun toString(): String = if (note.isNullOrBlank()) name else "$name ($note)"
}
