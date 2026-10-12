package app.recipebox.importer

import app.recipebox.data.model.InstructionSection
import app.recipebox.data.model.PrepTask
import app.recipebox.data.model.Recipe
import app.recipebox.data.model.SourceType
import app.recipebox.data.model.formatQuantity
import app.recipebox.data.model.editedDisplay
import app.recipebox.data.model.groceryParts
import app.recipebox.data.model.withEditedAmount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientParserTest {
    @Test fun `parses unicode fraction and unit`() {
        val i = IngredientParser.parse("1½ tbsp olive oil, plus extra")
        assertEquals(1.5, i.quantity!!, 0.001)
        assertEquals("tbsp", i.unit)
        assertEquals("olive oil", i.name)
        assertEquals("plus extra", i.note)
    }

    @Test fun `parses unit stuck to number`() {
        val i = IngredientParser.parse("400g chopped tomatoes")
        assertEquals(400.0, i.quantity!!, 0.001)
        assertEquals("g", i.unit)
    }

    @Test fun `does not treat a food word as a unit`() {
        val i = IngredientParser.parse("2 garlic bulbs")
        assertEquals(2.0, i.quantity!!, 0.001)
        assertNull(i.unit)
        assertEquals("garlic bulbs", i.name)
    }

    @Test fun `moves trailing form words into the unit`() {
        val i = IngredientParser.parse("2 garlic cloves, finely chopped")
        assertEquals("clove", i.unit)
        assertEquals("garlic", i.name)
        assertEquals("finely chopped", i.note)
        assertEquals("garlic" to "2 cloves", i.groceryParts())
    }

    @Test fun `parses pack multipliers`() {
        val i = IngredientParser.parse("2 x 400g tins plum tomatoes")
        assertEquals(2.0, i.quantity!!, 0.001)
        assertEquals("x 400g tin", i.unit)
        assertEquals("plum tomatoes", i.name)
        assertEquals("plum tomatoes" to "2 x 400g tins", i.groceryParts())
    }

    @Test fun `reads amounts written in words`() {
        val basil = IngredientParser.parse("small pack basil, leaves picked, ¾ finely chopped")
        assertEquals("basil", basil.name)
        assertEquals("small pack", basil.amountText)
        assertEquals("basil" to "small pack", basil.groceryParts())
        val butter = IngredientParser.parse("a knob of butter")
        assertEquals("butter", butter.name)
        assertEquals("knob", butter.amountText)
    }

    @Test fun `splits notes on lines without a quantity`() {
        val i = IngredientParser.parse("crusty bread, to serve (optional)")
        assertNull(i.quantity)
        assertEquals("crusty bread", i.name)
        assertEquals("to serve, optional", i.note)
    }

    @Test fun `parses ranges`() {
        val i = IngredientParser.parse("2-3 tsp chilli flakes")
        assertEquals(2.0, i.quantity!!, 0.001)
        assertEquals(3.0, i.quantityMax!!, 0.001)
    }

    @Test fun `leaves unparseable lines alone`() {
        val i = IngredientParser.parse("Salt and pepper to taste")
        assertNull(i.quantity)
        assertEquals("Salt and pepper to taste", i.raw)
    }

    @Test fun `formats nice fractions`() {
        assertEquals("1½", formatQuantity(1.5))
        assertEquals("⅓", formatQuantity(1.0 / 3))
        assertEquals("600", formatQuantity(600.0))
    }
}

class SchemaOrgMapperTest {
    private val yoastStyle = """
        {"@context":"https://schema.org","@graph":[
          {"@type":"WebPage","name":"Page"},
          {"@type":["Recipe"],"name":"Stovies","recipeYield":["4","4 servings"],
           "prepTime":"PT15M","cookTime":"PT1H",
           "image":{"@type":"ImageObject","url":"https://example.com/s.jpg"},
           "recipeIngredient":["1kg floury potatoes, thickly sliced","2 onions, sliced"],
           "recipeInstructions":[
             {"@type":"HowToSection","name":"Prep","itemListElement":[{"@type":"HowToStep","text":"Peel &amp; slice."}]},
             {"@type":"HowToStep","text":"Simmer for 45 minutes."}
           ]}
        ]}
    """.trimIndent()

    @Test fun `finds recipe inside graph and normalises it`() {
        val node = SchemaOrgMapper.findRecipe(Json.parseToJsonElement(yoastStyle))
        assertNotNull(node)
        val r = SchemaOrgMapper.toRecipe(node!!, "https://example.com", SourceType.WEB_JSONLD)
        assertEquals("Stovies", r.title)
        assertEquals(4, r.servings)
        assertEquals("4 servings", r.yieldText)
        assertEquals(75, r.totalMinutes)
        assertEquals("https://example.com/s.jpg", r.imageUrl)
        assertEquals(2, r.instructions.size)
        assertEquals("Peel & slice.", r.instructions[0].steps[0])
        assertEquals(r.instructions, r.originalInstructions)
    }

    @Test fun `round trips through export`() {
        val node = SchemaOrgMapper.findRecipe(Json.parseToJsonElement(yoastStyle))!!
        val r = SchemaOrgMapper.toRecipe(node, null, SourceType.WEB_JSONLD)
        val again = SchemaOrgMapper.toRecipe(
            Json.parseToJsonElement(SchemaOrgMapper.toSchemaOrg(r).toString()).jsonObject, null, SourceType.MANUAL,
        )
        assertEquals(r.title, again.title)
        assertEquals(r.ingredients.map { it.raw }, again.ingredients.map { it.raw })
        assertEquals(r.instructions.flatMap { it.steps }, again.instructions.flatMap { it.steps })
    }

    @Test fun `parses durations`() {
        assertEquals(90, SchemaOrgMapper.parseDurationMinutes("PT1H30M"))
        assertNull(SchemaOrgMapper.parseDurationMinutes("PT0M"))
        assertEquals("PT1H30M", SchemaOrgMapper.formatDuration(90))
    }
}

class HeuristicPrepTest {
    @Test fun `pulls prep forward and flags lead time`() {
        val recipe = Recipe(
            title = "Test",
            ingredients = listOf(
                IngredientParser.parse("2 onions, finely chopped"),
                IngredientParser.parse("100g butter, at room temperature"),
            ),
            instructions = listOf(
                InstructionSection(
                    steps = listOf(
                        "Marinate the chicken overnight.",
                        "Fry the onions until soft. Meanwhile, preheat the oven to 200C.",
                    ),
                ),
            ),
        )
        val planned = HeuristicPrep.plan(recipe)
        val texts = planned.prep.map { it.text }
        assertTrue(texts.contains("Finely chop the onions"))
        assertTrue(texts.contains("Bring the butter to room temperature"))
        assertTrue(texts.any { it.contains("preheat", ignoreCase = true) })
        assertEquals(480, planned.startAheadMinutes)
        assertEquals("Fry the onions until soft.", planned.instructions[0].steps[1])
        assertEquals(recipe.originalInstructions, planned.originalInstructions)
    }
}

class LessonLogTest {
    @Test fun `flags real disagreements only`() {
        val parser = IngredientParser.parse("2 medium onions, finely chopped")
        val claude = parser.copy(name = "onions", note = "medium, finely chopped")
        assertTrue(LessonLog.differs(parser, claude))
        assertTrue(!LessonLog.differs(parser, parser.copy(name = "Medium Onions", note = "something else")))
    }

    @Test fun `canonicalises Claude's units`() {
        assertEquals("tbsp", IngredientParser.canonicalUnit("Tablespoons"))
        assertEquals("tin", IngredientParser.canonicalUnit("tins."))
        assertEquals("x 400g tin", IngredientParser.canonicalUnit("x 400g tin"))
    }
}

class IngredientEditTest {
    private val base = IngredientParser.parse("small pack basil, leaves picked")

    @Test fun `numbers, fractions and ranges become quantities`() {
        assertEquals(1.5, base.withEditedAmount("1½").quantity!!, 0.001)
        assertEquals(1.5, base.withEditedAmount("1 1/2").quantity!!, 0.001)
        val range = base.withEditedAmount("2–3")
        assertEquals(2.0, range.quantity!!, 0.001)
        assertEquals(3.0, range.quantityMax!!, 0.001)
        assertNull(range.amountText)
    }

    @Test fun `words stay as words`() {
        val words = base.withEditedAmount("Large Bunch")
        assertNull(words.quantity)
        assertEquals("large bunch", words.amountText)
    }

    @Test fun `edited lines read back cleanly`() {
        val fixed = base.withEditedAmount("2").copy(name = "garlic", unit = "clove", note = "finely chopped", edited = true)
        assertEquals("2 cloves garlic, finely chopped", fixed.editedDisplay())
        assertEquals("4 cloves garlic, finely chopped", fixed.editedDisplay(2.0))
    }
}

class HeatUpPrepTest {
    private fun recipe(vararg steps: String) =
        Recipe(title = "Test", instructions = listOf(InstructionSection(steps = steps.toList())))

    @Test fun `early preheat moves to the front`() {
        val planned = HeuristicPrep.plan(recipe("Preheat the oven to 200C/180C fan/gas 6. Grease a tin.", "Mix everything.", "Bake for 30 minutes."))
        val oven = planned.prep.first()
        assertTrue(oven.heatUp)
        assertNull(oven.neededAtStep)
        assertEquals("Grease a tin.", planned.instructions[0].steps[0])
    }

    @Test fun `late preheat is flagged but stays in the method`() {
        val planned = HeuristicPrep.plan(
            recipe("Fry the onions.", "Add the mince.", "Simmer for an hour.", "Heat the grill to high.", "Grill until golden."),
        )
        val grill = planned.prep.first { it.heatUp }
        assertEquals(4, grill.neededAtStep)
        assertTrue(planned.instructions[0].steps.any { it.contains("grill to high") })
    }

    @Test fun `pan of water counts as a heat-up`() {
        val planned = HeuristicPrep.plan(recipe("Bring a large pan of salted water to the boil.", "Cook the pasta."))
        assertTrue(planned.prep.first().heatUp)
    }

    @Test fun `adds a heat-up Claude missed`() {
        val method = recipe("Preheat the oven to 220C.", "Roast for 40 minutes.").originalInstructions
        val fromClaude = listOf(PrepTask("Chop the potatoes", "ingredients"))
        val fixed = HeuristicPrep.withMissingHeatUps(fromClaude, method)
        assertTrue(fixed.any { it.heatUp && it.text.contains("220C") })
        val already = listOf(PrepTask("Preheat the oven to 220C", "step 1", heatUp = true))
        assertEquals(1, HeuristicPrep.withMissingHeatUps(already, method).size)
    }
}

class BuiltInReaderTest {
    private val caption = """
        Creamy Tuscan chicken orzo 🍋🔥 one pan, 30 mins
        Serves 4

        Ingredients:
        ✅ 2 chicken breasts, sliced
        ✅ 1 tbsp olive oil
        ✅ 250g orzo
        ✅ 750ml chicken stock

        Method:
        1. Fry the chicken in the oil until golden, then set aside.
        2. Stir in the orzo and stock, simmer 12-15 mins.

        Save this for later! 💾
        #orzo #onepan #dinnerideas
    """.trimIndent()

    @Test fun `reads a caption with headings`() {
        val r = SchemaOrgMapper.toRecipe(BuiltInReader.fromText(caption)!!, null, SourceType.INSTAGRAM)
        assertEquals("Creamy Tuscan chicken orzo one pan, 30 mins", r.title)
        assertEquals(4, r.servings)
        assertEquals(listOf("2 chicken breasts, sliced", "1 tbsp olive oil", "250g orzo", "750ml chicken stock"), r.ingredients.map { it.raw })
        assertEquals(2, r.instructions.flatMap { it.steps }.size)
        assertTrue(r.instructions.flatMap { it.steps }.none { it.contains("Save this") })
    }

    @Test fun `reads a caption without headings`() {
        val text = "Quick garlic noodles\n- 200g noodles\n- 4 garlic cloves\n- 2 tbsp butter\nCook the noodles. Toss it all together.\n#noodles"
        val r = SchemaOrgMapper.toRecipe(BuiltInReader.fromText(text)!!, null, SourceType.TEXT)
        assertEquals("Quick garlic noodles", r.title)
        assertEquals(3, r.ingredients.size)
        assertEquals(1, r.instructions.flatMap { it.steps }.size)
    }

    @Test fun `gives up on text that isn't a recipe`() {
        assertNull(BuiltInReader.fromText("What a gorgeous sunset tonight 🌅\n#nofilter"))
    }
}
