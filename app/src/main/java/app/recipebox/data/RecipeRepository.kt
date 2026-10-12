package app.recipebox.data

import app.recipebox.data.db.RawSourceEntity
import app.recipebox.data.db.RecipeDao
import app.recipebox.data.db.RecipeEntity
import app.recipebox.data.model.RawSnapshot
import app.recipebox.data.model.PrepSource
import app.recipebox.data.model.ReadBy
import app.recipebox.data.model.Recipe
import app.recipebox.data.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RecipeRepository(private val dao: RecipeDao) {

    fun observeAll(): Flow<List<Recipe>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observe(id: Long): Flow<Recipe?> = dao.observe(id).map { it?.toDomain() }

    suspend fun all(): List<Recipe> = dao.getAll().map { it.toDomain() }

    suspend fun save(recipe: Recipe, raw: RawSnapshot?): Long {
        val now = System.currentTimeMillis()
        val rawEntity = raw?.let {
            RawSourceEntity(recipeId = 0, sourceUrl = it.sourceUrl, kind = it.kind, payload = it.payload, importedAt = now)
        }
        return dao.insertWithRaw(recipe.copy(id = 0, createdAt = now, updatedAt = now).toEntity(), rawEntity)
    }

    suspend fun update(recipe: Recipe) = dao.update(recipe.copy(updatedAt = System.currentTimeMillis()).toEntity())

    suspend fun delete(id: Long) = dao.delete(id)

    /** Puts a removed recipe back with its original id, dates and saved source (for Undo). */
    suspend fun restore(recipe: Recipe, raw: RawSnapshot?, rawImportedAt: Long = recipe.createdAt) {
        val rawEntity = raw?.let {
            RawSourceEntity(recipeId = recipe.id, sourceUrl = it.sourceUrl, kind = it.kind, payload = it.payload, importedAt = rawImportedAt)
        }
        dao.insertWithRaw(recipe.toEntity(), rawEntity)
    }

    suspend fun raw(id: Long): RawSnapshot? = dao.getRaw(id)?.let { RawSnapshot(it.kind, it.payload, it.sourceUrl) }
}

private fun RecipeEntity.toDomain() = Recipe(
    id = id,
    title = title,
    description = description,
    imageUrl = imageUrl,
    sourceUrl = sourceUrl,
    sourceType = runCatching { SourceType.valueOf(sourceType) }.getOrDefault(SourceType.MANUAL),
    author = author,
    servings = servings,
    yieldText = yieldText,
    prepMinutes = prepMinutes,
    cookMinutes = cookMinutes,
    totalMinutes = totalMinutes,
    cuisine = cuisine,
    category = category,
    keywords = keywords,
    ingredients = ingredients,
    instructions = instructions,
    originalInstructions = originalInstructions,
    prep = prep,
    prepSource = runCatching { PrepSource.valueOf(prepSource) }.getOrDefault(PrepSource.NONE),
    readBy = runCatching { ReadBy.valueOf(readBy) }.getOrDefault(ReadBy.STRUCTURED),
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun Recipe.toEntity() = RecipeEntity(
    id = id,
    title = title,
    description = description,
    imageUrl = imageUrl,
    sourceUrl = sourceUrl,
    sourceType = sourceType.name,
    author = author,
    servings = servings,
    yieldText = yieldText,
    prepMinutes = prepMinutes,
    cookMinutes = cookMinutes,
    totalMinutes = totalMinutes,
    cuisine = cuisine,
    category = category,
    keywords = keywords,
    ingredients = ingredients,
    instructions = instructions,
    originalInstructions = originalInstructions,
    prep = prep,
    prepSource = prepSource.name,
    readBy = readBy.name,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
