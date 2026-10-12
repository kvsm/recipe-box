package app.recipebox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RecipeDao {
    @Query("SELECT * FROM recipes ORDER BY createdAt DESC")
    abstract fun observeAll(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    abstract fun observe(id: Long): Flow<RecipeEntity?>

    @Query("SELECT * FROM recipes ORDER BY title COLLATE NOCASE")
    abstract suspend fun getAll(): List<RecipeEntity>

    @Insert
    abstract suspend fun insert(recipe: RecipeEntity): Long

    @Update
    abstract suspend fun update(recipe: RecipeEntity)

    @Query("DELETE FROM recipes WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertRaw(raw: RawSourceEntity)

    @Query("SELECT * FROM raw_sources WHERE recipeId = :recipeId")
    abstract suspend fun getRaw(recipeId: Long): RawSourceEntity?

    @Transaction
    open suspend fun insertWithRaw(recipe: RecipeEntity, raw: RawSourceEntity?): Long {
        val id = insert(recipe)
        if (raw != null) upsertRaw(raw.copy(recipeId = id))
        return id
    }
}
