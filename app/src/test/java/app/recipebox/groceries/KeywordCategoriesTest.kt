package app.recipebox.groceries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeywordCategoriesTest {
    private val kevs = listOf("Fruit & Veg", "Produce", "Meat", "Herbs & Spices", "Canned/Dry Goods", "Dairy")
        .associateBy { it.lowercase() }

    @Test fun `places common ingredients in existing categories`() {
        assertEquals("Meat", KeywordCategories.guess("beef mince", kevs))
        assertEquals("Fruit & Veg", KeywordCategories.guess("floury potatoes", kevs))
        assertEquals("Herbs & Spices", KeywordCategories.guess("ground white pepper", kevs))
        assertEquals("Fruit & Veg", KeywordCategories.guess("red pepper", kevs))
        assertEquals("Canned/Dry Goods", KeywordCategories.guess("chickpeas", kevs))
    }

    @Test fun `never invents a category`() {
        assertNull(KeywordCategories.guess("salmon fillets", kevs))
    }

    /** Lines and categories from the first lessons export (docs/lessons.md). */
    private val fromLessons = listOf(
        "Bakery", "Baking", "Baking & Food Prep", "Bread", "Canned/Dry Goods", "Chilled", "Condiments", "Dairy", "Drinks",
        "Fish", "Frozen Food", "Fruit & Veg", "Herbs & Spices", "International Foods", "Meat", "Pantry", "Produce", "Seafood",
    ).associateBy { it.lowercase() }

    @Test fun `things named after another ingredient go where they're sold`() {
        assertEquals("Canned/Dry Goods", KeywordCategories.guess("chicken stock", fromLessons))
        assertEquals("Canned/Dry Goods", KeywordCategories.guess("beef stock cubes", fromLessons))
        assertEquals("Canned/Dry Goods", KeywordCategories.guess("coconut milk", fromLessons))
        assertEquals("Fruit & Veg", KeywordCategories.guess("sundried tomatoes", fromLessons))
    }

    @Test fun `gaps from the lessons are filled`() {
        assertEquals("Fruit & Veg", KeywordCategories.guess("galangal", fromLessons))
        assertEquals("Fruit & Veg", KeywordCategories.guess("thai chilies", fromLessons))
        assertEquals("Fruit & Veg", KeywordCategories.guess("lemongrass", fromLessons))
        assertEquals("Baking", KeywordCategories.guess("puff pastry", fromLessons))
        // Spices are still spices.
        assertEquals("Herbs & Spices", KeywordCategories.guess("chilli flakes", fromLessons))
    }
}
