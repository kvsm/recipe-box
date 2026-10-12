package app.recipebox.data

import app.recipebox.data.model.Measures
import app.recipebox.data.model.UnitSystem
import app.recipebox.data.model.convertTemperatures
import app.recipebox.data.model.groceryParts
import app.recipebox.data.model.lineFor
import app.recipebox.importer.IngredientParser
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {
    private val metric = Measures.forRecipe(UnitSystem.METRIC, "https://www.example.com/recipe")
    private val imperial = Measures.forRecipe(UnitSystem.IMPERIAL, "https://www.example.com/recipe")

    private fun metric(line: String, factor: Double = 1.0) = IngredientParser.parse(line).lineFor(factor, metric)
    private fun imperial(line: String, factor: Double = 1.0) = IngredientParser.parse(line).lineFor(factor, imperial)

    @Test fun `weights become grams and kilos`() {
        assertEquals("450 g beef mince", metric("1 lb beef mince"))
        assertEquals("230 g cheddar", metric("8 oz cheddar"))
        assertEquals("1.4 kg potatoes", metric("3 lb potatoes"))
        assertEquals("450–910 g chicken thighs", metric("1-2 lb chicken thighs"))
    }

    @Test fun `cups of dry ingredients are weighed, liquids poured`() {
        assertEquals("250 g plain flour", metric("2 cups plain flour"))
        assertEquals("240 ml milk", metric("1 cup milk"))
        assertEquals("230 g butter", metric("2 sticks butter"))
        assertEquals("120 g cornflour", metric("1 cup cornflour"))
    }

    @Test fun `pints depend on where the recipe came from`() {
        val british = Measures.forRecipe(UnitSystem.METRIC, "https://www.bbcgoodfood.co.uk/recipes/x")
        assertEquals("470 ml milk", metric("1 pint milk"))
        assertEquals("570 ml milk", IngredientParser.parse("1 pint milk").lineFor(1.0, british))
    }

    @Test fun `metric to imperial`() {
        assertEquals("1 lb beef mince", imperial("500g beef mince"))
        assertEquals("7 oz plain flour", imperial("200g plain flour"))
        assertEquals("1 cup milk", imperial("250ml milk"))
        assertEquals("2 tbsp soy sauce", imperial("30ml soy sauce"))
        assertEquals("1 tsp vanilla extract", imperial("5ml vanilla extract"))
        assertEquals("3¼ lb potatoes", imperial("1.5kg potatoes"))
    }

    @Test fun `spoons and counts are left alone`() {
        assertEquals("2 tbsp olive oil", metric("2 tbsp olive oil"))
        assertEquals("1 tsp cumin", imperial("1 tsp cumin"))
        assertEquals("2 garlic cloves, crushed", metric("2 garlic cloves, crushed"))
        assertEquals("400g chopped tomatoes", metric("400g chopped tomatoes"))
    }

    @Test fun `scaling happens before rounding`() {
        assertEquals("680 g beef mince", metric("1 lb beef mince", factor = 1.5))
    }

    @Test fun `shopping list gets converted amounts`() {
        assertEquals("beef mince" to "450 g", IngredientParser.parse("1 lb beef mince").groceryParts(1.0, metric))
    }

    @Test fun `oven temperatures convert both ways and keep the original`() {
        assertEquals("Bake at 180°C (350°F) for 20 minutes", convertTemperatures("Bake at 350°F for 20 minutes", UnitSystem.METRIC))
        assertEquals("Heat the oven to 400°F (200C).", convertTemperatures("Heat the oven to 200C.", UnitSystem.IMPERIAL))
        assertEquals("Bake at 180°C (350°F).", convertTemperatures("Bake at 180°C (350°F).", UnitSystem.METRIC))
        assertEquals("Bake at 180°C (350°F).", convertTemperatures("Bake at 180°C (350°F).", UnitSystem.IMPERIAL))
        assertEquals("Simmer for 25 minutes, step 2.", convertTemperatures("Simmer for 25 minutes, step 2.", UnitSystem.METRIC))
    }
}
