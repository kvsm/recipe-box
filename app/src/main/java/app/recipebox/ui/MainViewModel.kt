package app.recipebox.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.recipebox.RecipeBoxApp
import app.recipebox.data.model.Ingredient
import app.recipebox.data.model.RawSnapshot
import app.recipebox.data.model.Recipe
import app.recipebox.groceries.GroceryItem
import app.recipebox.groceries.GroceryRouter
import app.recipebox.importer.ImportScheduler
import app.recipebox.importer.ImportWorker
import app.recipebox.settings.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** A recipe that's just been taken out of the tin, kept until its Undo runs out. */
data class Removed(val recipe: Recipe, val raw: RawSnapshot?)

/** An import that's still running, or failed and hasn't been dismissed. */
data class ImportStatus(val id: UUID, val label: String, val failed: Boolean, val error: String?)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as RecipeBoxApp).container
    private val workManager = WorkManager.getInstance(app)
    private val seenPrefs = app.getSharedPreferences("seen_imports", Context.MODE_PRIVATE)

    val recipes: StateFlow<List<Recipe>> = container.repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val workInfos = workManager.getWorkInfosByTagFlow(ImportScheduler.TAG)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Import work the user has already seen land (or dismissed), persisted across launches. */
    private val seen = MutableStateFlow(seenPrefs.getStringSet(KEY_SEEN, emptySet()).orEmpty())

    val imports: StateFlow<List<ImportStatus>> = combine(workInfos, seen) { infos, seenIds ->
        infos.filter { it.state in PENDING || (it.state == WorkInfo.State.FAILED && it.id.toString() !in seenIds) }
            .map { ImportStatus(it.id, ImportScheduler.labelOf(it), it.state == WorkInfo.State.FAILED, it.outputData.getString(ImportWorker.KEY_ERROR)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The most recent import that landed and hasn't been stamped yet. */
    val justSaved: StateFlow<Recipe?> = combine(workInfos, seen) { infos, seenIds ->
        infos.filter { it.state == WorkInfo.State.SUCCEEDED && it.id.toString() !in seenIds }
            .map { it.outputData.getLong(ImportWorker.KEY_RECIPE_ID, -1L) }
            .filter { it > 0 }
            .maxOrNull()
    }.flatMapLatest { id -> if (id == null) flowOf(null) else container.repository.observe(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val _removed = MutableSharedFlow<Removed>(extraBufferCapacity = 4)
    /** Each removal, for the "Removed … Undo" snackbar. */
    val removed: SharedFlow<Removed> = _removed

    private val _hasClaudeKey = MutableStateFlow(false)
    val hasClaudeKey = _hasClaudeKey.asStateFlow()
    private val _ourGroceriesConnected = MutableStateFlow(false)
    val ourGroceriesConnected = _ourGroceriesConnected.asStateFlow()
    private val _ourGroceriesEmail = MutableStateFlow<String?>(null)
    val ourGroceriesEmail = _ourGroceriesEmail.asStateFlow()
    private val _listName = MutableStateFlow(GroceryRouter.DEFAULT_LIST)
    val listName = _listName.asStateFlow()
    private val _lessons = MutableStateFlow<Map<String, Int>>(emptyMap())
    val lessons = _lessons.asStateFlow()

    init {
        refreshStatus()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.photos.sweep(container.repository.all().mapNotNull { it.imageUrl }.toSet()) }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _hasClaudeKey.value = container.claude.hasKey()
            _ourGroceriesConnected.value = container.ourGroceries.isConnected()
            _ourGroceriesEmail.value = container.ourGroceries.connectedEmail()
            _listName.value = container.groceries.listName
            _lessons.value = container.lessons.countsByArea()
        }
    }

    fun exportLessons(uri: Uri) {
        viewModelScope.launch {
            val message = runCatching { container.lessons.exportTo(getApplication(), uri) }
                .fold({ "Exported $it lessons" }, { "Export failed: ${it.message}" })
            _messages.emit(message)
        }
    }

    fun clearLessons() {
        viewModelScope.launch(Dispatchers.IO) {
            container.lessons.clear()
            _lessons.value = emptyMap()
            _messages.emit("Lessons cleared")
        }
    }

    fun recipe(id: Long) = container.repository.observe(id)

    fun importUrl(url: String) {
        ImportScheduler.importUrl(getApplication(), url.trim())
    }

    /** Saves a hand-fixed ingredient and, if the built-in parser got it wrong, keeps it as a parser lesson. */
    fun updateIngredient(recipe: Recipe, index: Int, ingredient: Ingredient) {
        if (index !in recipe.ingredients.indices) return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = recipe.ingredients.toMutableList().also { it[index] = ingredient }
            container.repository.update(recipe.copy(ingredients = updated))
            if (ingredient.edited) {
                runCatching { container.lessons.recordCorrection(ingredient) }
                _lessons.value = container.lessons.countsByArea()
            }
            _messages.emit(if (ingredient.edited) "Saved" else "Back to how the recipe had it")
        }
    }

    /** Re-reads a recipe from its saved original with Claude. */
    fun tidyUp(recipe: Recipe) {
        viewModelScope.launch {
            _messages.emit("Claude’s having a look…")
            val message = runCatching { container.importer.tidyUp(recipe) }
                .fold({ "Tidied up" }, { "Couldn’t tidy that up: ${it.message}" })
            _messages.emit(message)
        }
    }

    /** Takes a recipe out of the tin. Its photo stays until the Undo runs out, in case it comes back. */
    fun delete(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val recipe = container.repository.all().firstOrNull { it.id == id } ?: return@launch
            val raw = container.repository.raw(id)
            container.repository.delete(id)
            _removed.emit(Removed(recipe, raw))
        }
    }

    fun undoDelete(removed: Removed) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.repository.restore(removed.recipe, removed.raw) }
                .onFailure { _messages.emit("Couldn't put ${removed.recipe.title} back") }
        }
    }

    /** The Undo has gone: the photo can go too. */
    fun forgetRemoved(removed: Removed) {
        viewModelScope.launch(Dispatchers.IO) { container.photos.delete(removed.recipe.imageUrl) }
    }

    /** Sets a recipe's photo from a picked or freshly taken image, or removes it when [uri] is null. */
    fun setPhoto(recipe: Recipe, uri: Uri?, fromCamera: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val local = uri?.let { runCatching { container.photos.copyFrom(it) }.getOrNull() }
            if (uri != null && local == null) {
                _messages.emit("Couldn't use that photo")
                return@launch
            }
            container.repository.update(recipe.copy(imageUrl = local))
            container.photos.delete(recipe.imageUrl)
            if (fromCamera) uri?.lastPathSegment?.let { name -> java.io.File(getApplication<Application>().cacheDir, "camera/$name").delete() }
            _messages.emit(if (local == null) "Photo removed" else "New photo on the card")
        }
    }

    /** Marks every finished import as seen: clears the stamp sheet and failure cards. */
    fun dismissFinishedImports() {
        val finished = workInfos.value.filter { it.state.isFinished }.map { it.id.toString() }
        seen.update { (it + finished).also { ids -> seenPrefs.edit().putStringSet(KEY_SEEN, ids).apply() } }
    }

    fun sendToList(items: List<GroceryItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val message = when (val outcome = container.groceries.send(items)) {
                is GroceryRouter.Outcome.Added ->
                    if (outcome.count == items.size) "Added ${outcome.count} to ${outcome.listName}"
                    else "Added ${outcome.count} of ${items.size} to ${outcome.listName}"
                is GroceryRouter.Outcome.Copied -> buildString {
                    outcome.reason?.let { append("Couldn't add directly: $it ") }
                    append("Copied ${outcome.count} items")
                    append(if (outcome.openedApp) ". Paste them into OurGroceries." else " to your clipboard.")
                }
            }
            _ourGroceriesConnected.value = container.ourGroceries.isConnected()
            _messages.emit(message)
        }
    }

    fun saveClaudeKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.secrets.put(SecretStore.CLAUDE_API_KEY, key.trim().ifEmpty { null })
            _hasClaudeKey.value = container.claude.hasKey()
            _messages.emit(if (_hasClaudeKey.value) "API key saved" else "API key removed")
        }
    }

    /** Signs in to OurGroceries. Returns true if it worked, so the form can clear itself. */
    suspend fun signInToOurGroceries(email: String, password: String): Boolean =
        withContext(Dispatchers.IO) { runCatching { container.ourGroceries.signIn(email, password) } }
            .onSuccess {
                _ourGroceriesConnected.value = true
                _ourGroceriesEmail.value = container.ourGroceries.connectedEmail()
                _messages.emit("OurGroceries connected")
            }
            .onFailure { _messages.emit(it.message ?: "Couldn't sign in to OurGroceries.") }
            .isSuccess

    fun disconnectOurGroceries() {
        viewModelScope.launch(Dispatchers.IO) {
            container.ourGroceries.signOut()
            _ourGroceriesConnected.value = false
            _ourGroceriesEmail.value = null
            _messages.emit("OurGroceries disconnected")
        }
    }

    fun setListName(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.groceries.listName = name
            _listName.value = container.groceries.listName
        }
    }

    fun export(uri: Uri) {
        viewModelScope.launch {
            val message = runCatching { container.exporter.exportTo(uri) }
                .fold({ "Exported $it recipes" }, { "Export failed: ${it.message}" })
            withContext(Dispatchers.Main) { _messages.emit(message) }
        }
    }

    private companion object {
        const val KEY_SEEN = "seen"
        val PENDING = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
    }
}
