package app.recipebox.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * Pulls a recipe out of messy input (a page with no structured data, an
 * Instagram caption, pasted text, a screenshot) and returns it as
 * schema.org Recipe JSON, so it goes through the same mapper as everything else.
 */
class ClaudeExtractor(private val claude: ClaudeClient) {

    sealed interface Input {
        data class Text(val text: String, val sourceUrl: String?) : Input
        /** One or more screenshots of the same recipe, in page order. */
        class Images(val pages: List<ByteArray>) : Input
    }

    /** True when there's a Claude key and Claude is switched on in Settings. */
    fun available(): Boolean = claude.isOn()

    suspend fun extract(input: Input): JsonObject {
        val content = buildJsonArray {
            when (input) {
                is Input.Text -> addJsonObject {
                    put("type", "text")
                    put("text", (input.sourceUrl?.let { "Source: $it\n\n" } ?: "") + input.text)
                }
                is Input.Images -> {
                    input.pages.forEachIndexed { i, page ->
                        if (input.pages.size > 1) addJsonObject {
                            put("type", "text")
                            put("text", "Image ${i + 1}:")
                        }
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", Base64.getEncoder().encodeToString(page))
                            }
                        }
                    }
                    addJsonObject {
                        put("type", "text")
                        put(
                            "text",
                            if (input.pages.size == 1) {
                                "Extract the recipe from this image."
                            } else {
                                "These ${input.pages.size} images are consecutive screenshots or pages of one recipe, in order. " +
                                    "Read them as one recipe. Neighbouring screenshots often overlap: where the same lines appear " +
                                    "at the bottom of one and the top of the next, include them once. Set photo_image to the image " +
                                    "that best shows the finished food, if any does."
                            },
                        )
                    }
                }
            }
        }

        val result = claude.callTool(SYSTEM, content, TOOL)
        if (result["is_recipe"]?.jsonPrimitive?.booleanOrNull == false) {
            throw ImportException("Couldn't find a recipe in that. Try sharing a screenshot or pasting the caption.")
        }
        return toSchemaOrg(result)
    }

    /** Claude returns simple sections; convert them to schema.org HowToSection/HowToStep. */
    private fun toSchemaOrg(input: JsonObject): JsonObject = buildJsonObject {
        put("@type", "Recipe")
        input.forEach { (k, v) -> if (k != "sections" && k != "is_recipe") put(k, v) }
        // shows_dish passes through so the importer can decide whether a screenshot makes a good card photo.
        putJsonArray("recipeInstructions") {
            input["sections"]?.jsonArray?.forEach { section ->
                val s = section.jsonObject
                addJsonObject {
                    put("@type", "HowToSection")
                    s["name"]?.let { put("name", it) }
                    putJsonArray("itemListElement") {
                        s["steps"]?.jsonArray?.forEach { step ->
                            addJsonObject {
                                put("@type", "HowToStep")
                                put("text", step)
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val SYSTEM = """
            You extract recipes from web pages, social media captions and photos. Call save_recipe exactly once.
            Copy ingredient lines and steps faithfully. Never invent quantities, ingredients or steps that are not in the source.
            Keep the source's units and wording. Use ISO 8601 durations such as PT45M.
            Leave out anything that isn't the recipe (life stories, ads, comments, hashtags).
            If there is no recipe, set is_recipe to false and leave the other fields empty.
        """.trimIndent()

        val TOOL: JsonObject = Json.parseToJsonElement(
            """
            {
              "name": "save_recipe",
              "description": "Save the recipe found in the content.",
              "input_schema": {
                "type": "object",
                "properties": {
                  "is_recipe": {"type": "boolean"},
                  "shows_dish": {"type": "boolean", "description": "for images: true if a picture prominently shows the finished food, not just text"},
                  "photo_image": {"type": "integer", "description": "for several images: which one (1 = first) best shows the finished food"},
                  "name": {"type": "string"},
                  "description": {"type": "string"},
                  "author": {"type": "string"},
                  "recipeYield": {"type": "string", "description": "e.g. 'Serves 4' or '12 cookies'"},
                  "prepTime": {"type": "string", "description": "ISO 8601 duration"},
                  "cookTime": {"type": "string", "description": "ISO 8601 duration"},
                  "totalTime": {"type": "string", "description": "ISO 8601 duration"},
                  "recipeCuisine": {"type": "string"},
                  "recipeCategory": {"type": "string"},
                  "keywords": {"type": "string", "description": "comma separated"},
                  "recipeIngredient": {"type": "array", "items": {"type": "string"}, "description": "one ingredient line each, as written"},
                  "sections": {
                    "type": "array",
                    "description": "method steps; use one unnamed section unless the source groups steps",
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
                "required": ["is_recipe", "name", "recipeIngredient", "sections"]
              }
            }
            """.trimIndent()
        ).jsonObject
    }
}
