package app.recipebox

import android.app.Application
import android.content.Context
import app.recipebox.data.RecipePhotos
import app.recipebox.data.RecipeRepository
import app.recipebox.data.db.AppDatabase
import app.recipebox.export.RecipeExporter
import app.recipebox.groceries.CategoryMatcher
import app.recipebox.groceries.GroceryRouter
import app.recipebox.groceries.OurGroceriesClient
import app.recipebox.importer.ClaudeClient
import app.recipebox.importer.ClaudeExtractor
import app.recipebox.importer.LessonLog
import app.recipebox.importer.PrepPlanner
import app.recipebox.importer.RecipeImporter
import app.recipebox.settings.SecretStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class RecipeBoxApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Hand-rolled dependency wiring. Swap for Hilt if the app grows. */
class AppContainer(context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    val secrets = SecretStore(context)
    val repository = RecipeRepository(AppDatabase.create(context).recipeDao())

    val claude = ClaudeClient(http, secrets)
    val lessons = LessonLog(context)
    val photos = RecipePhotos(context, http)
    val importer = RecipeImporter(http, ClaudeExtractor(claude), PrepPlanner(claude, lessons), repository, photos, lessons)
    val exporter = RecipeExporter(repository, context.contentResolver)

    val ourGroceries = OurGroceriesClient(http, secrets, CategoryMatcher(context, claude, lessons))
    val groceries = GroceryRouter(context, ourGroceries, secrets)
}
