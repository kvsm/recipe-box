package app.recipebox.groceries

import android.content.Context
import app.recipebox.importer.ClaudeClient
import app.recipebox.importer.LessonLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Picks an existing OurGroceries category for each item, so things don't land
 * as "Uncategorised". Never invents a category: anything it can't place is
 * left uncategorised rather than cluttering the user's category list.
 *
 * Order: the user's own OurGroceries history for that exact item → packaging
 * (tins and jars go to their tinned category) → the user's history ignoring
 * describing words → items this app has categorised before → Claude (if
 * there's a key) → a built-in keyword guess. The user's choices, and any later
 * corrections they make in OurGroceries, always win over the app's guesses.
 */
class CategoryMatcher(context: Context, private val claude: ClaudeClient, private val lessons: LessonLog) {
    private val learned = context.getSharedPreferences("grocery_categories", Context.MODE_PRIVATE)

    /** Returns item name (lower case) → one of [categories], for every item it could place. */
    suspend fun assign(
        items: List<GroceryItem>,
        categories: List<String>,
        history: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        if (categories.isEmpty()) return emptyMap()
        val byLower = categories.associateBy { it.lowercase() }
        val result = mutableMapOf<String, String>()
        val historyKeys = mutableSetOf<String>()
        val claudeKeys = mutableSetOf<String>()

        // 1. What the user has filed this exact item under before, in OurGroceries itself.
        items.forEach { item ->
            val key = item.name.lowercase()
            exactHistory(item.name, history)?.let { byLower[it.lowercase()] }?.let {
                result[key] = it
                historyKeys += key
            }
        }

        // 2. Packaging beats a loose name match: "plum tomatoes" in tins aren't fresh tomatoes.
        val tinnedCategory = listOf("Canned/Dry Goods", "Canned Goods", "Tins", "Pantry").firstNotNullOfOrNull { byLower[it.lowercase()] }
        if (tinnedCategory != null) {
            items.forEach { item ->
                val key = item.name.lowercase()
                if (key !in result && packaged.containsMatchIn("${item.note.orEmpty()} ${item.name}")) result[key] = tinnedCategory
            }
        }

        // 3. The user's history again, ignoring describing words: "smoked streaky bacon" → "bacon".
        items.forEach { item ->
            val key = item.name.lowercase()
            if (key in result) return@forEach
            looseHistory(item.name, history)?.let { byLower[it.lowercase()] }?.let {
                result[key] = it
                historyKeys += key
            }
        }

        // 4. What this app picked last time.
        items.forEach { item ->
            val key = item.name.lowercase()
            if (key in result) return@forEach
            learned.getString(key, null)?.let { byLower[it.lowercase()] }?.let { result[key] = it }
        }

        val unknown = items.map { it.name.lowercase() }.filter { it !in result }.distinct()
        if (unknown.isNotEmpty() && claude.hasKey()) {
            try {
                val inUse = history.values.mapNotNull { byLower[it.lowercase()] }.toSet()
                val fromClaude = askClaude(unknown, categories, inUse).filterValues { it.lowercase() in byLower }
                result += fromClaude
                claudeKeys += fromClaude.keys
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fall back to keywords below.
            }
        }

        items.map { it.name.lowercase() }.filter { it !in result }.forEach { name ->
            KeywordCategories.guess(name, byLower)?.let { result[name] = it }
        }

        recordLessons(items, byLower, result, historyKeys, claudeKeys)

        // Only remember the app's own guesses; the user's history is re-read every time.
        learned.edit().apply { result.filterKeys { it !in historyKeys }.forEach { (k, v) -> putString(k, v) } }.apply()
        return result
    }

    /**
     * Where the keyword list would have filed an item differently from the user's own filing (their
     * OurGroceries history) or Claude's pick, keep a lesson. The user's filing is the stronger one.
     */
    private fun recordLessons(
        items: List<GroceryItem>,
        byLower: Map<String, String>,
        result: Map<String, String>,
        historyKeys: Set<String>,
        claudeKeys: Set<String>,
    ) {
        runCatching {
            val categoryList = JsonArray(byLower.values.sorted().map { JsonPrimitive(it) })
            items.map { it.name.lowercase() }.distinct().forEach { name ->
                val chosen = result[name] ?: return@forEach
                val source = when (name) {
                    in historyKeys -> "you"
                    in claudeKeys -> "claude"
                    else -> return@forEach
                }
                val guess = KeywordCategories.guess(name, byLower)
                if (guess != chosen) {
                    val input = buildJsonObject {
                        put("item", name)
                        put("categories", categoryList)
                    }
                    lessons.record("category", name, input, LessonLog.text(guess), JsonPrimitive(chosen), source)
                }
            }
        }
    }

    /** Looks [name] up in the history, also trying it singular/plural ("onion" vs "onions"). */
    private fun exactHistory(name: String, history: Map<String, String>): String? = lookup(normalise(name), history)

    /** Drops leading words one at a time: "medium onions" → "onions". */
    private fun looseHistory(name: String, history: Map<String, String>): String? {
        val words = normalise(name).split(' ')
        return (1 until words.size).firstNotNullOfOrNull { lookup(words.drop(it).joinToString(" "), history) }
    }

    private fun lookup(key: String, history: Map<String, String>): String? =
        history[key] ?: history[key.removeSuffix("s")] ?: history[key + "s"] ?: history[key.removeSuffix("es")]

    companion object {
        private val trailingCount = Regex("""\s*\(\d+\)$""")
        private val optional = Regex("""\s*\(optional\)$""")
        private val spaces = Regex("""\s+""")
        private val accents = Regex("""\p{Mn}+""")
        private val packaged = Regex("""\b(tins?|cans?|tinned|canned|jars?)\b""", RegexOption.IGNORE_CASE)

        /** "Tomato Purée" → "tomato puree"; "kidney beans (2)" → "kidney beans". */
        fun normalise(name: String): String =
            java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(accents, "")
                .replace(trailingCount, "")
                .replace(optional, "")
                .replace(spaces, " ")
                .trim()
    }

    private suspend fun askClaude(items: List<String>, categories: List<String>, inUse: Set<String>): Map<String, String> {
        val tool = buildJsonObject {
            put("name", "assign_categories")
            put("description", "Assign each shopping item to one of the user's existing categories.")
            putJsonObject("input_schema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("assignments") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("item") { put("type", "string") }
                                putJsonObject("category") {
                                    put("type", "string")
                                    putJsonArray("enum") {
                                        categories.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                                        add(kotlinx.serialization.json.JsonPrimitive(""))
                                    }
                                }
                            }
                            putJsonArray("required") {
                                add(kotlinx.serialization.json.JsonPrimitive("item"))
                                add(kotlinx.serialization.json.JsonPrimitive("category"))
                            }
                        }
                    }
                }
                putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("assignments")) }
            }
        }
        val content = buildJsonArray {
            addJsonObject {
                put("type", "text")
                put(
                    "text",
                    "Categories: ${categories.joinToString(" | ")}\n\n" +
                        (if (inUse.isNotEmpty()) "Categories the user actually files things in: ${inUse.joinToString(" | ")}\n\n" else "") +
                        "Items:\n" + items.joinToString("\n") { "- $it" },
                )
            }
        }
        val system = """
            Put each shopping item in the supermarket category where it would be found, choosing only from the user's
            existing categories. Some categories are shop names or non-food; ignore those for food. When two categories
            overlap (for example "Fruit & Veg" and "Produce"), pick the one the user actually files things in, if you're told;
            otherwise the more specific or more grocery-like one, consistently.
            Use an empty string if nothing fits. Return every item exactly as given.
        """.trimIndent()

        val result: JsonObject = claude.callTool(system, content, tool, maxTokens = 1024)
        return result["assignments"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            val item = o["item"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return@mapNotNull null
            val category = o["category"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            item to category
        }.toMap()
    }
}

/** Offline fallback: common ingredients → likely category names, used only if the user has that category. */
internal object KeywordCategories {
    private val rules: List<Pair<List<String>, List<String>>> = listOf(
        // Checked first: things named after another ingredient but sold elsewhere. "chicken stock" isn't
        // meat, "coconut milk" isn't dairy, "sundried tomatoes" aren't a dried spice. (From the first lessons export.)
        listOf("stock", "bouillon", "gravy")
            to listOf("Canned/Dry Goods", "Pantry"),
        listOf("coconut milk", "coconut cream")
            to listOf("Canned/Dry Goods", "International Foods", "Pantry"),
        listOf("sundried tomato", "sun-dried tomato", "sun dried tomato")
            to listOf("Fruit & Veg", "Produce"),
        listOf("ground ", "black pepper", "white pepper", "dried ", "paprika", "cumin", "turmeric", "cinnamon", "nutmeg",
            "oregano", "chilli flakes", "chili flakes", "garam masala", "curry powder", "bay leaves")
            to listOf("Herbs & Spices", "Pantry"),
        listOf("beef", "chicken", "pork", "lamb", "mince", "bacon", "sausage", "chorizo", "steak", "ham", "turkey", "venison")
            to listOf("Meat"),
        listOf("salmon", "cod", "haddock", "prawn", "tuna steak", "mackerel", "mussels", "scallop", "sea bass")
            to listOf("Fish", "Seafood"),
        listOf("milk", "butter", "cheese", "cheddar", "parmesan", "cream", "yoghurt", "yogurt", "crème fraîche",
            "creme fraiche", "egg", "mozzarella", "feta")
            to listOf("Dairy", "Chilled"),
        listOf("flour", "sugar", "baking powder", "bicarbonate", "yeast", "cocoa", "vanilla", "icing", "pastry")
            to listOf("Baking", "Baking & Food Prep", "Pantry"),
        listOf("tinned", "tin of", "canned", "chickpeas", "kidney beans", "lentils", "rice", "pasta", "spaghetti",
            "noodles", "stock", "passata", "chopped tomatoes", "coconut milk", "oats")
            to listOf("Canned/Dry Goods", "Pantry"),
        listOf("soy sauce", "ketchup", "mayonnaise", "mustard", "vinegar", "worcestershire", "oil", "honey", "jam", "pesto")
            to listOf("Condiments", "Pantry"),
        listOf("bread", "rolls", "wraps", "tortilla", "pitta", "naan", "baguette")
            to listOf("Bread", "Bakery"),
        listOf("frozen", "peas", "ice cream")
            to listOf("Frozen Food"),
        listOf("potato", "onion", "garlic", "carrot", "leek", "pepper", "tomato", "lettuce", "spinach", "mushroom",
            "courgette", "aubergine", "broccoli", "cabbage", "celery", "cucumber", "lemon", "lime", "apple", "banana",
            "ginger", "coriander", "parsley", "basil", "mint", "chilli", "chili", "spring onion", "avocado", "squash", "kale",
            "galangal", "lemongrass", "lime leaves", "cilantro", "green onion", "shallot")
            to listOf("Fruit & Veg", "Produce"),
    )

    fun guess(item: String, categoriesByLower: Map<String, String>): String? {
        val name = " ${item.lowercase()} "
        for ((keywords, candidates) in rules) {
            if (keywords.any { it in name }) {
                candidates.firstNotNullOfOrNull { categoriesByLower[it.lowercase()] }?.let { return it }
            }
        }
        return null
    }
}
