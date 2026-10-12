package app.recipebox.export

import android.content.ContentResolver
import android.net.Uri
import app.recipebox.data.RecipeRepository
import app.recipebox.importer.SchemaOrgMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** Writes every recipe as a JSON array of schema.org Recipe objects. */
class RecipeExporter(
    private val repository: RecipeRepository,
    private val resolver: ContentResolver,
) {
    private val json = Json { prettyPrint = true }

    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val recipes = repository.all()
        val payload = json.encodeToString(JsonArray.serializer(), JsonArray(recipes.map(SchemaOrgMapper::toSchemaOrg)))
        val stream = resolver.openOutputStream(uri) ?: throw IllegalStateException("Couldn't open that file.")
        stream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        recipes.size
    }
}
