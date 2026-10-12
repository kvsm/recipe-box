package app.recipebox.data.model

import kotlinx.serialization.Serializable

/** Where a recipe came from, so we know how it was extracted. */
enum class SourceType { WEB_JSONLD, WEB_AI, INSTAGRAM, TEXT, IMAGE, MANUAL }

/**
 * One ingredient line. [raw] is always the original text; the structured
 * fields are best-effort and power scaling. If parsing failed, quantity is null.
 */
@Serializable
data class Ingredient(
    val raw: String,
    val quantity: Double? = null,
    /** A non-numeric amount, e.g. "small pack" or "knob". Only set when [quantity] is null. */
    val amountText: String? = null,
    val quantityMax: Double? = null,
    val unit: String? = null,
    val name: String = raw,
    val note: String? = null,
    /** True once the user has corrected this line by hand; the card then shows their version, not [raw]. */
    val edited: Boolean = false,
)

/** A group of steps. Most recipes have a single section with no name. */
@Serializable
data class InstructionSection(
    val name: String? = null,
    val steps: List<String>,
)

/**
 * A bit of prep pulled to the front of the recipe ("Finely chop the onions").
 * [source] says where it was found, e.g. "step 5" or "ingredients".
 * [startAheadMinutes] is set when it needs doing well before cooking
 * (marinate overnight, soak beans), so the card can warn you while planning.
 */
@Serializable
data class PrepTask(
    val text: String,
    val source: String? = null,
    val startAheadMinutes: Int? = null,
    /** Oven, grill, air fryer, barbecue or a pan of water: things that take a while to get hot. */
    val heatUp: Boolean = false,
    /** The method step that first needs this, when that's well into the recipe (so there's no rush). */
    val neededAtStep: Int? = null,
)

/** What read the recipe in: the page's own structured data, Claude, or the app's built-in reader. */
enum class ReadBy { STRUCTURED, CLAUDE, BUILT_IN }

/** How the "Get ready" section was produced. */
enum class PrepSource { NONE, HEURISTIC, CLAUDE }

/**
 * The normalised recipe: what the UI reads, the user edits, and export writes from.
 * [instructions] is the method as shown (prep pulled out into [prep]);
 * [originalInstructions] is the method exactly as imported, for "Show original".
 */
data class Recipe(
    val id: Long = 0,
    val title: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val sourceUrl: String? = null,
    val sourceType: SourceType = SourceType.MANUAL,
    val author: String? = null,
    val servings: Int? = null,
    val yieldText: String? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val totalMinutes: Int? = null,
    val cuisine: String? = null,
    val category: String? = null,
    val keywords: List<String> = emptyList(),
    val ingredients: List<Ingredient> = emptyList(),
    val instructions: List<InstructionSection> = emptyList(),
    val originalInstructions: List<InstructionSection> = instructions,
    val prep: List<PrepTask> = emptyList(),
    val prepSource: PrepSource = PrepSource.NONE,
    val readBy: ReadBy = ReadBy.STRUCTURED,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    /** Read without Claude, either the recipe itself or its prep pass. Claude could tidy it up. */
    val couldTidyUp: Boolean
        get() = readBy == ReadBy.BUILT_IN || prepSource == PrepSource.HEURISTIC

    /** The longest lead time across prep tasks, if any need starting well ahead. */
    val startAheadMinutes: Int?
        get() = prep.mapNotNull { it.startAheadMinutes }.maxOrNull()
}

/** The untouched source of an import, kept so we can re-run better parsers later. */
data class RawSnapshot(
    val kind: String,
    val payload: String,
    val sourceUrl: String?,
)
