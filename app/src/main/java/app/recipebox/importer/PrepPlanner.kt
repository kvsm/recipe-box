package app.recipebox.importer

import app.recipebox.data.model.Ingredient
import app.recipebox.data.model.InstructionSection
import app.recipebox.data.model.PrepSource
import app.recipebox.data.model.PrepTask
import app.recipebox.data.model.Recipe
import app.recipebox.data.model.countedIngredientWords
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The "mise en place" pass. Finds prep buried in ingredient notes and later
 * steps, pulls it into a "Get ready" list at the start, and flags anything
 * that needs starting well ahead (marinades, soaking, proving).
 *
 * With a Claude key it restructures the whole method and, in the same call,
 * reads every ingredient line into name / amount / unit / note (more reliable
 * than the built-in parser on odd lines). Where Claude and the built-in parser
 * disagree, the line goes into [LessonLog] so the parser can learn from it
 * in a future release. Without a key, a built-in pass handles the obvious
 * cases. The imported method is always kept in Recipe.originalInstructions.
 */
class PrepPlanner(private val claude: ClaudeClient, private val gaps: LessonLog) {

    suspend fun plan(recipe: Recipe): Recipe {
        if (recipe.originalInstructions.isEmpty() && recipe.ingredients.isEmpty()) return recipe
        if (claude.isOn()) {
            try {
                return planWithClaude(recipe)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fall through to the built-in pass; a failed prep pass never fails the import.
            }
        }
        return HeuristicPrep.plan(recipe)
    }

    private suspend fun planWithClaude(recipe: Recipe): Recipe {
        val content = buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", describe(recipe))
            }
        }
        val result = claude.callTool(SYSTEM, content, TOOL)

        val prep = result["prep_tasks"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            val text = o["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            PrepTask(
                text = text,
                source = o["source"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null },
                startAheadMinutes = o["start_ahead_minutes"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 60 },
                heatUp = o["heat_up"]?.jsonPrimitive?.booleanOrNull == true,
                neededAtStep = o["needed_at_step"]?.jsonPrimitive?.intOrNull?.takeIf { it > 2 },
            )
        }.take(30)
            // Belt and braces: never lose an oven, grill or pan-of-water instruction, even if Claude skipped it.
            .let { HeuristicPrep.withMissingHeatUps(it, recipe.originalInstructions) }
            .let(HeuristicPrep::ordered)

        val method = result["method"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            val steps = o["steps"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.ifEmpty { null } }
            if (steps.isEmpty()) null
            else InstructionSection(name = o["name"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }, steps = steps)
        }

        // Sanity check: a restructure that loses most of the method isn't trustworthy.
        val originalCount = recipe.originalInstructions.sumOf { it.steps.size }
        val newCount = method.sumOf { it.steps.size } + prep.size
        if (method.isEmpty() || newCount < originalCount / 2) throw IllegalStateException("Restructure lost steps")

        // Claude's reading of each ingredient line, in the same order as the recipe.
        val structured = result["ingredients"]?.jsonArray.orEmpty().map { el ->
            val o = el.jsonObject
            fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            fun num(key: String) = o[key]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }
            val quantity = num("quantity")
            Ingredient(
                raw = str("raw").orEmpty(),
                quantity = quantity,
                quantityMax = num("quantity_max"),
                unit = IngredientParser.canonicalUnit(str("unit")),
                amountText = if (quantity == null) str("amount_text")?.lowercase() else null,
                name = str("name") ?: str("raw").orEmpty(),
                note = str("note"),
            )
        }
        val ingredients = if (structured.size == recipe.ingredients.size && structured.all { it.name.isNotBlank() }) {
            // Keep the original line exactly as imported, whatever Claude echoed back.
            structured.mapIndexed { i, s -> s.copy(raw = recipe.ingredients[i].raw) }.also {
                runCatching { gaps.recordIngredients(recipe.ingredients, it) }
            }
        } else {
            recipe.ingredients
        }

        // Words the method counts ingredients by ("yolks" for eggs), so those counts scale with the servings.
        val countWords = result["count_words"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val word = o["word"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.length > 2 && ' ' !in it }
                ?: return@mapNotNull null
            word to o["ingredient"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        }.distinctBy { it.first }.take(20)

        runCatching { recordPrepLessons(recipe, prep) }
        runCatching { recordCountLessons(ingredients, countWords) }
        return recipe.copy(
            prep = prep,
            instructions = method,
            ingredients = ingredients,
            countWords = countWords.map { it.first },
            prepSource = PrepSource.CLAUDE,
        )
    }

    /** A counting word Claude found that the built-in words (names plus [countAliases]) wouldn't have caught. */
    private fun recordCountLessons(ingredients: List<Ingredient>, found: List<Pair<String, String>>) {
        val builtIn = countedIngredientWords(ingredients)
        found.filter { (word, _) -> word !in builtIn }.forEach { (word, ingredient) ->
            gaps.record(
                "counts",
                "$ingredient|$word",
                buildJsonObject {
                    put("ingredient", ingredient)
                    put("ingredients", JsonArray(ingredients.map { JsonPrimitive(it.raw) }))
                },
                JsonArray(emptyList()),
                JsonArray(listOf(JsonPrimitive(word))),
            )
        }
    }

    /**
     * Step by step, where Claude and the built-in prep pass disagree about what to pull out of a
     * method step (prep hidden in it, a heat-up, something to start ahead), log it as a lesson.
     */
    private fun recordPrepLessons(recipe: Recipe, claudePrep: List<PrepTask>) {
        val steps = recipe.originalInstructions.flatMap { it.steps }
        val builtIn = HeuristicPrep.plan(recipe).prep
        fun fromSteps(tasks: List<PrepTask>) = tasks.filter { it.source?.startsWith("step", ignoreCase = true) == true }
            .groupBy { it.source!!.filter(Char::isDigit).toIntOrNull() }
        val claudeBy = fromSteps(claudePrep)
        val builtInBy = fromSteps(builtIn)
        fun kinds(tasks: List<PrepTask>?) = tasks.orEmpty().map { it.heatUp to (it.startAheadMinutes != null) }.toSet()
        fun asJson(tasks: List<PrepTask>?) = JsonArray(tasks.orEmpty().map { t ->
            buildJsonObject {
                put("text", t.text)
                if (t.heatUp) put("heat_up", true)
                t.startAheadMinutes?.let { put("start_ahead_minutes", it) }
                t.neededAtStep?.let { put("needed_at_step", it) }
            }
        })
        (claudeBy.keys + builtInBy.keys).filterNotNull().distinct().forEach { n ->
            val stepText = steps.getOrNull(n - 1) ?: return@forEach
            if (kinds(claudeBy[n]) != kinds(builtInBy[n])) {
                gaps.record("prep", stepText, LessonLog.text(stepText), asJson(builtInBy[n]), asJson(claudeBy[n]))
            }
        }
    }

    private fun describe(r: Recipe): String = buildString {
        appendLine("Recipe: ${r.title}")
        appendLine()
        appendLine("Ingredients:")
        r.ingredients.forEach { appendLine("- ${it.raw}") }
        appendLine()
        appendLine("Method:")
        var n = 1
        r.originalInstructions.forEach { section ->
            section.name?.let { appendLine("[$it]") }
            section.steps.forEach { appendLine("${n++}. $it") }
        }
    }

    private companion object {
        val SYSTEM = """
            You reorganise recipe methods so all preparation is done before the cooking starts, the way a chef sets out their mise en place.
            Call plan_prep exactly once.

            prep_tasks: everything that can and should be done before the first time-critical step. That includes chopping, slicing, grating,
            peeling, measuring out liquids and spices that get added quickly, preheating the oven, greasing or lining tins, bringing things to
            room temperature, soaking, marinating, and making any component that a later step needs ready (a sauce, a paste, a glaze).
            Include prep implied by ingredient lines, such as "2 onions, finely chopped". Order the tasks sensibly, longest lead time first.
            Set source to "ingredients" or "step N" (N from the numbered method) to say where each task came from.
            Set start_ahead_minutes only when a task must begin at least an hour before cooking (marinating, soaking, chilling, proving;
            "overnight" is 480).

            Heating things up always goes in prep_tasks with heat_up true: preheating the oven, grill, air fryer or barbecue, and
            putting a pan of water on to boil. Keep the exact temperatures (fan, conventional and gas mark) in the text. Set
            needed_at_step to the method step that first needs it. If that's later than step 2, also leave the instruction in the
            method at that point, so it isn't switched on an hour early.

            method: the remaining cooking steps, in order, rewritten so they don't repeat the prep (for example "Add the chopped onions").
            Keep the source's sections if it has them.

            ingredients: one entry per ingredient line, in the same order, with raw copied exactly. name is what you'd write on a
            shopping list, without sizes, prep or packaging words ("2 medium onions, finely chopped" is name "onions", quantity 2,
            note "medium, finely chopped"). quantity is a number (use the lower end of a range, with quantity_max for the upper end).
            unit is short and singular: g, kg, ml, l, tsp, tbsp, cup, oz, lb, clove, tin, stick, rasher, sprig, slice, bunch, pinch;
            for packs of a size write it like "x 400g tin". amount_text is for amounts in words with no number ("small pack",
            "knob", "to taste"). note holds everything else.

            count_words: words the method uses to count one of the listed ingredients by a different name, where the count
            should go up and down with the servings. For example "separate 3 eggs, then whisk the 3 yolks" (word "yolks",
            ingredient "eggs"), or "lay the 4 fillets skin-side down" when the list says "600g salmon" (word "fillets",
            ingredient "salmon"). Only list words that appear after a number in the method. Don't list the ingredient's own
            name, units (g, cup, tbsp) or things that aren't ingredients (batches, pieces, portions, layers). Leave it empty
            if there are none.

            Rules: never add ingredients, quantities, temperatures, times or techniques that aren't in the source. Never drop a step's
            content: every instruction in the source must end up in prep_tasks or method. Keep the source's wording where you can.
        """.trimIndent()

        val TOOL: JsonObject = Json.parseToJsonElement(
            """
            {
              "name": "plan_prep",
              "description": "Save the reorganised recipe: prep first, then the cooking method.",
              "input_schema": {
                "type": "object",
                "properties": {
                  "prep_tasks": {
                    "type": "array",
                    "items": {
                      "type": "object",
                      "properties": {
                        "text": {"type": "string"},
                        "source": {"type": "string"},
                        "start_ahead_minutes": {"type": "integer"},
                        "heat_up": {"type": "boolean"},
                        "needed_at_step": {"type": "integer"}
                      },
                      "required": ["text"]
                    }
                  },
                  "ingredients": {
                    "type": "array",
                    "items": {
                      "type": "object",
                      "properties": {
                        "raw": {"type": "string"},
                        "name": {"type": "string"},
                        "quantity": {"type": "number"},
                        "quantity_max": {"type": "number"},
                        "unit": {"type": "string"},
                        "amount_text": {"type": "string"},
                        "note": {"type": "string"}
                      },
                      "required": ["raw", "name"]
                    }
                  },
                  "count_words": {
                    "type": "array",
                    "items": {
                      "type": "object",
                      "properties": {
                        "word": {"type": "string"},
                        "ingredient": {"type": "string"}
                      },
                      "required": ["word", "ingredient"]
                    }
                  },
                  "method": {
                    "type": "array",
                    "items": {
                      "type": "object",
                      "properties": {
                        "name": {"type": "string"},
                        "steps": {"type": "array", "items": {"type": "string"}}
                      },
                      "required": ["steps"]
                    }
                  }
                },
                "required": ["prep_tasks", "method", "ingredients"]
              }
            }
            """.trimIndent()
        ).jsonObject
    }
}

/** Free, offline prep pass for when there's no Claude key (or Claude fails). */
object HeuristicPrep {

    private val participles = mapOf(
        "chopped" to "chop", "diced" to "dice", "sliced" to "slice", "grated" to "grate",
        "minced" to "mince", "crushed" to "crush", "peeled" to "peel", "cubed" to "cube",
        "shredded" to "shred", "trimmed" to "trim", "halved" to "halve", "quartered" to "quarter",
        "zested" to "zest", "juiced" to "juice", "beaten" to "beat", "melted" to "melt",
        "softened" to "soften", "toasted" to "toast", "drained" to "drain", "rinsed" to "rinse",
        "deseeded" to "deseed", "torn" to "tear", "sifted" to "sift", "picked" to "pick",
    )
    private val prepWord = Regex("""\b(?:(\w+ly)\s+)?(${participles.keys.joinToString("|")})\b""", RegexOption.IGNORE_CASE)
    private val roomTemp = Regex("""\bat room temperature\b""", RegexOption.IGNORE_CASE)
    private val preheat = Regex(
        """\b(pre-?heat|heat (?:the|your) (?:oven|grill|air ?fryer)|(?:turn|switch|put) (?:the|your) (?:oven|grill|air ?fryer) on|""" +
            """(?:turn|switch) on (?:the|your) (?:oven|grill|air ?fryer)|(?:light|fire up|get) (?:the|your) (?:barbecue|bbq)|""" +
            """(?:bring|put) (?:a|the) (?:large |big |medium |deep )?(?:pan|pot|saucepan) of (?:salted |lightly salted )?water (?:on )?to (?:the )?boil)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val leadTime = Regex(
        """\b(overnight|marinate|marinade for|soak|chill for|refrigerate for|leave to (?:rise|prove|set)|prove for|rise for)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val hours = Regex("""(\d+)\s*(?:-\s*\d+\s*)?(?:hours?|hrs?)\b""", RegexOption.IGNORE_CASE)
    private val minutes = Regex("""(\d+)\s*(?:-\s*\d+\s*)?(?:minutes?|mins?)\b""", RegexOption.IGNORE_CASE)
    private val sentenceSplit = Regex("""(?<=[.!?])\s+""")

    fun plan(recipe: Recipe): Recipe {
        val prep = mutableListOf<PrepTask>()
        val ahead = mutableListOf<PrepTask>()
        val heat = mutableListOf<PrepTask>()
        var stepNumber = 0

        val method = recipe.originalInstructions.mapNotNull { section ->
            val steps = section.steps.mapNotNull { step ->
                stepNumber++
                val kept = mutableListOf<String>()
                step.split(sentenceSplit).forEach { sentence ->
                    when {
                        preheat.containsMatchIn(sentence) -> {
                            // Early on: move it into Get ready. Later: flag it there, but leave it in the method too,
                            // so the oven isn't on for an hour before it's needed.
                            val late = stepNumber > 2
                            heat += PrepTask(sentence.trim(), "step $stepNumber", heatUp = true, neededAtStep = stepNumber.takeIf { late })
                            if (late) kept += sentence
                        }
                        leadTime.containsMatchIn(sentence) -> {
                            val lead = leadMinutes(sentence)
                            if (lead != null && lead >= 60) ahead += PrepTask(sentence.trim(), "step $stepNumber", lead)
                            kept += sentence
                        }
                        else -> {
                            kept += sentence
                        }
                    }
                }
                kept.joinToString(" ").trim().ifEmpty { null }
            }
            if (steps.isEmpty()) null else section.copy(steps = steps)
        }

        recipe.ingredients.mapNotNullTo(prep) { fromIngredient(it) }

        val all = ordered(ahead + heat + prep)
        if (all.isEmpty()) return recipe.copy(prepSource = PrepSource.HEURISTIC)
        return recipe.copy(prep = all, instructions = method, prepSource = PrepSource.HEURISTIC)
    }

    /**
     * Get ready order: things to start well ahead (longest first), then anything that needs to heat up
     * (needed soonest first), then the chopping and measuring.
     */
    fun ordered(tasks: List<PrepTask>): List<PrepTask> {
        val ahead = tasks.filter { it.startAheadMinutes != null }.sortedByDescending { it.startAheadMinutes }
        val heat = tasks.filter { it.startAheadMinutes == null && it.heatUp }.sortedBy { it.neededAtStep ?: 0 }
        val rest = tasks.filter { it.startAheadMinutes == null && !it.heatUp }
        return (ahead + heat + rest).distinctBy { it.text.lowercase() }
    }

    /** Adds any heat-up instruction from [method] that isn't already covered by [prep]. */
    fun withMissingHeatUps(prep: List<PrepTask>, method: List<InstructionSection>): List<PrepTask> {
        val found = plan(Recipe(title = "", instructions = method)).prep.filter { it.heatUp }
        // Covered if Claude already has a heat-up for the same thing (oven, grill, water…).
        fun applianceOf(text: String) = appliances.find(text)?.value?.lowercase()?.replace(" ", "")
        val covered = { task: PrepTask ->
            val appliance = applianceOf(task.text)
            prep.any { p ->
                (p.heatUp || preheat.containsMatchIn(p.text)) &&
                    (appliance == null || p.text.lowercase().replace(" ", "").contains(appliance))
            }
        }
        return prep + found.filterNot(covered)
    }

    private val appliances = Regex("""\b(oven|grill|air ?fryer|barbecue|bbq|water)\b""", RegexOption.IGNORE_CASE)

    internal fun fromIngredient(ingredient: Ingredient): PrepTask? {
        val raw = ingredient.raw
        val noun = cleanNoun(ingredient.name)
        if (roomTemp.containsMatchIn(raw)) {
            return PrepTask("Bring the $noun to room temperature", "ingredients")
        }
        val match = prepWord.find(raw) ?: return null
        val adverb = match.groupValues[1].lowercase()
        val verb = participles[match.groupValues[2].lowercase()] ?: return null
        val phrase = (if (adverb.isNotEmpty()) "$adverb $verb" else verb).replaceFirstChar { it.uppercase() }
        return PrepTask("$phrase the $noun", "ingredients")
    }

    private fun cleanNoun(name: String): String =
        name.replace(prepWord, "")
            .replace(roomTemp, "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', ',', '-')
            .ifEmpty { name }

    private fun leadMinutes(sentence: String): Int? {
        if (sentence.contains("overnight", ignoreCase = true)) return 480
        hours.find(sentence)?.let { return it.groupValues[1].toInt() * 60 }
        minutes.find(sentence)?.let { return it.groupValues[1].toInt() }
        return null
    }
}
