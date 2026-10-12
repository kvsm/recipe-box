package app.recipebox.data

import app.recipebox.data.model.Measures
import app.recipebox.data.model.UnitSystem
import app.recipebox.data.model.convertAmountsInText
import app.recipebox.data.model.convertTemperatures
import app.recipebox.data.model.convertText
import app.recipebox.data.model.countedIngredientWords
import app.recipebox.data.model.groceryParts
import app.recipebox.data.model.lineFor
import app.recipebox.importer.IngredientParser
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {
    private val us = "https://www.example.com/recipe"
    private val uk = "https://www.bbcgoodfood.co.uk/recipes/x"
    private val metric = Measures.forRecipe(UnitSystem.METRIC, us)
    private val imperialUs = Measures.forRecipe(UnitSystem.IMPERIAL_US, us)
    private val imperialUk = Measures.forRecipe(UnitSystem.IMPERIAL_UK, us)

    private fun line(text: String, m: Measures, factor: Double = 1.0) = IngredientParser.parse(text).lineFor(factor, m)

    @Test fun `weights become grams and kilos`() {
        assertEquals("450 g beef mince", line("1 lb beef mince", metric))
        assertEquals("230 g cheddar", line("8 oz cheddar", metric))
        assertEquals("1.4 kg potatoes", line("3 lb potatoes", metric))
        assertEquals("450–910 g chicken thighs", line("1-2 lb chicken thighs", metric))
    }

    @Test fun `cups of dry ingredients are weighed, liquids poured`() {
        assertEquals("250 g plain flour", line("2 cups plain flour", metric))
        assertEquals("240 ml milk", line("1 cup milk", metric))
        assertEquals("230 g butter", line("2 sticks butter", metric))
        assertEquals("120 g cornflour", line("1 cup cornflour", metric))
    }

    @Test fun `pints depend on where the recipe came from`() {
        assertEquals("470 ml milk", line("1 pint milk", metric))
        assertEquals("570 ml milk", line("1 pint milk", Measures.forRecipe(UnitSystem.METRIC, uk)))
    }

    @Test fun `metric to US imperial`() {
        assertEquals("1 lb beef mince", line("500g beef mince", imperialUs))
        assertEquals("7 oz plain flour", line("200g plain flour", imperialUs))
        assertEquals("1 cup milk", line("250ml milk", imperialUs))
        assertEquals("2 cups stock", line("500ml stock", imperialUs))
        assertEquals("2 tbsp soy sauce", line("30ml soy sauce", imperialUs))
        assertEquals("1 tsp vanilla extract", line("5ml vanilla extract", imperialUs))
        assertEquals("3¼ lb potatoes", line("1.5kg potatoes", imperialUs))
    }

    @Test fun `UK imperial uses fluid ounces and pints, and weighs cups of flour`() {
        assertEquals("1 pint stock", line("500ml stock", imperialUk))
        assertEquals("8 fl oz milk", line("1 cup milk", imperialUk))
        assertEquals("9 oz plain flour", line("2 cups plain flour", imperialUk))
        assertEquals("7 oz plain flour", line("200g plain flour", imperialUk))
        assertEquals("4 oz butter", line("1 stick butter", imperialUk))
        // A British recipe's pint is already a British pint.
        assertEquals("1 pint milk", line("1 pint milk", Measures.forRecipe(UnitSystem.IMPERIAL_UK, uk)))
    }

    @Test fun `spoons and counts are left alone`() {
        assertEquals("2 tbsp olive oil", line("2 tbsp olive oil", metric))
        assertEquals("1 tsp cumin", line("1 tsp cumin", imperialUs))
        assertEquals("2 garlic cloves, crushed", line("2 garlic cloves, crushed", metric))
        assertEquals("400g chopped tomatoes", line("400g chopped tomatoes", metric))
    }

    @Test fun `scaling happens before rounding`() {
        assertEquals("680 g beef mince", line("1 lb beef mince", metric, factor = 1.5))
    }

    @Test fun `shopping list gets converted amounts`() {
        assertEquals("beef mince" to "450 g", IngredientParser.parse("1 lb beef mince").groceryParts(1.0, metric))
    }

    @Test fun `amounts in method text`() {
        assertEquals(
            "Whisk in 240 ml (1 cup) of milk, then fold in 250 g (2 cups) flour.",
            convertAmountsInText("Whisk in 1 cup of milk, then fold in 2 cups flour.", metric),
        )
        assertEquals("Pour in ½ pint (300ml) stock.", convertAmountsInText("Pour in 300ml stock.", imperialUk))
        assertEquals("Add the 14 oz (400g) tin of tomatoes.", convertAmountsInText("Add the 400g tin of tomatoes.", imperialUs))
        assertEquals("Brown 450–910 g (1-2 lb) of beef.", convertAmountsInText("Brown 1-2 lb of beef.", metric))
    }

    @Test fun `text leaves spoons, words and existing conversions alone`() {
        val untouched = listOf(
            "Add 2 tbsp oil and 3 garlic cloves.",
            "Stir in 200g/7oz flour.",
            "Stir in 200g (7oz) flour.",
            "Cook for 2 minutes, then add 1 large egg.",
        )
        untouched.forEach { assertEquals(it, convertAmountsInText(it, metric)) }
        assertEquals("Stir in 200g/7oz flour.", convertAmountsInText("Stir in 200g/7oz flour.", imperialUk))
    }

    @Test fun `method amounts follow the servings`() {
        // Doubled: converted, with the original doubled in brackets.
        assertEquals("Fold in 500 g (4 cups) flour.", convertAmountsInText("Fold in 2 cups flour.", metric, factor = 2.0))
        // Halved, nothing to convert: just the smaller amount.
        assertEquals("Pour in 250 ml stock.", convertAmountsInText("Pour in 500ml stock.", metric, factor = 0.5))
        // Spoons scale too, and a recipe's own pair is scaled on both sides.
        assertEquals(
            "Add 400 g/14 oz flour and 4 tbsp oil.",
            convertAmountsInText("Add 200g/7oz flour and 2 tbsp oil.", metric, factor = 2.0),
        )
        // Temperatures and times don't scale.
        assertEquals(
            "Bake at 180°C for 20 minutes.",
            convertAmountsInText("Bake at 180°C for 20 minutes.", metric, factor = 2.0),
        )
    }

    @Test fun `counts of the recipe's own ingredients scale, other counts don't`() {
        val counted = countedIngredientWords(
            listOf("3 large eggs", "1 onion, finely chopped", "2 garlic cloves", "500g beef mince", "1 lemon")
                .map(IngredientParser::parse),
        )
        fun scaled(text: String, factor: Double) = convertText(text, metric, factor, counted)
        assertEquals(
            "Crack in 6 eggs, add 2 onions and 4 garlic cloves, then cook in 2 batches.",
            scaled("Crack in 3 eggs, add 1 onion and 2 garlic cloves, then cook in 2 batches.", 2.0),
        )
        assertEquals("Beat 6 large eggs.", scaled("Beat three large eggs.", 2.0))
        assertEquals("Squeeze in the juice of 1 lemon.", scaled("Squeeze in the juice of 2 lemons.", 0.5))
        // Doubled 500g reads as 1 kg, and "4 pieces" isn't an ingredient so stays put.
        assertEquals("Cut into 4 pieces and fry 1 kg mince.", scaled("Cut into 4 pieces and fry 500g mince.", 2.0))
    }

    @Test fun `counts under other names scale too`() {
        // Built in: eggs bring yolks and whites; weighed chicken brings thighs.
        val builtIn = countedIngredientWords(listOf("3 eggs", "500g chicken thighs").map(IngredientParser::parse))
        assertEquals(
            "Whisk 6 yolks, and brown the 8 thighs.",
            convertText("Whisk 3 yolks, and brown the 4 thighs.", metric, 2.0, builtIn),
        )
        // Found by Claude on import: lamb counted as cutlets.
        val withClaude = countedIngredientWords(listOf("800g lamb").map(IngredientParser::parse), extra = listOf("cutlets"))
        assertEquals("Grill 8 cutlets.", convertText("Grill 4 cutlets.", metric, 2.0, withClaude))
        // Weighed ingredients don't bring their own names: "2 lamb" isn't a count.
        assertEquals("Season 2 lamb racks.", convertText("Season 2 lamb racks.", metric, 2.0, countedIngredientWords(listOf("800g lamb").map(IngredientParser::parse))))
    }

    @Test fun `oven temperatures convert both ways and keep the original`() {
        assertEquals("Bake at 180°C (350°F) for 20 minutes", convertTemperatures("Bake at 350°F for 20 minutes", UnitSystem.METRIC))
        assertEquals("Bake at 180°C (350°F) for 20 minutes", convertTemperatures("Bake at 350°F for 20 minutes", UnitSystem.IMPERIAL_UK))
        assertEquals("Heat the oven to 400°F (200C).", convertTemperatures("Heat the oven to 200C.", UnitSystem.IMPERIAL_US))
        assertEquals("Bake at 180°C (350°F).", convertTemperatures("Bake at 180°C (350°F).", UnitSystem.METRIC))
        assertEquals("Bake at 180°C (350°F).", convertTemperatures("Bake at 180°C (350°F).", UnitSystem.IMPERIAL_US))
        assertEquals("Simmer for 25 minutes, step 2.", convertTemperatures("Simmer for 25 minutes, step 2.", UnitSystem.METRIC))
    }
}
