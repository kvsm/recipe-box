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
}
