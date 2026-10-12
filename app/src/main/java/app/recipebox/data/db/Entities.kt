package app.recipebox.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import app.recipebox.data.model.Ingredient
import app.recipebox.data.model.InstructionSection
import app.recipebox.data.model.PrepTask

@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String?,
    val imageUrl: String?,
    val sourceUrl: String?,
    val sourceType: String,
    val author: String?,
    val servings: Int?,
    val yieldText: String?,
    val prepMinutes: Int?,
    val cookMinutes: Int?,
    val totalMinutes: Int?,
    val cuisine: String?,
    val category: String?,
    val keywords: List<String>,
    val ingredients: List<Ingredient>,
    val instructions: List<InstructionSection>,
    val originalInstructions: List<InstructionSection>,
    val prep: List<PrepTask>,
    val prepSource: String,
    val readBy: String,
    @ColumnInfo(defaultValue = "[]") val countWords: List<String> = emptyList(),
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Archive copy of what we imported. Never shown or edited; only read when
 * re-processing recipes with an improved parser. Deleted with its recipe.
 */
@Entity(
    tableName = "raw_sources",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RawSourceEntity(
    @PrimaryKey val recipeId: Long,
    val sourceUrl: String?,
    /** schema_org | page_text | text | image_file */
    val kind: String,
    val payload: String,
    val importedAt: Long,
)
