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
import app.recipebox.data.model.UnitSystem
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

/** A direct add to OurGroceries that didn't work, shown in full so it can be reported. */
data class GroceryFailure(val reason: String, val items: List<GroceryItem>)

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

    private val _sendingToList = MutableStateFlow(false)
    /** True while items are on their way to OurGroceries, for the button's spinner. */
    val sendingToList = _sendingToList.asStateFlow()

    private val settingsPrefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _units = MutableStateFlow(
        when (val saved = settingsPrefs.getString(KEY_UNITS, null)) {
            null -> UnitSystem.METRIC
            "IMPERIAL" -> UnitSystem.IMPERIAL_US // the single imperial option before UK and US were split
            else -> runCatching { UnitSystem.valueOf(saved) }.getOrDefault(UnitSystem.METRIC)
        },
    )
    /** Metric or imperial, for showing amounts and oven temperatures. Metric unless changed. */
    val units = _units.asStateFlow()

    fun setUnits(system: UnitSystem) {
        settingsPrefs.edit().putString(KEY_UNITS, system.name).apply()
        _units.value = system
    }

    private val _groceryFailure = MutableStateFlow<GroceryFailure?>(null)
    val groceryFailure = _groceryFailure.asStateFlow()

    private val _hasClaudeKey = MutableStateFlow(false)
    val hasClaudeKey = _hasClaudeKey.asStateFlow()
    private val _claudeEnabled = MutableStateFlow(true)
    /** The Settings switch. Claude is only used when this is on and there's a key. */
    val claudeEnabled = _claudeEnabled.asStateFlow()

    fun setClaudeEnabled(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            container.claude.enabled = on
            _claudeEnabled.value = container.claude.enabled
            _messages.emit(if (on) "Claude's on" else "Claude's off: everything's read on your phone")
        }
    }
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
            _claudeEnabled.value = container.claude.enabled
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

    /** Screenshots picked in the app: pages of one recipe, in the order they were picked. */
    fun importScreenshots(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val message = runCatching {
                val files = ImportScheduler.copyImages(getApplication(), uris.take(ImportScheduler.MAX_PAGES))
                ImportScheduler.importImages(getApplication(), files)
                if (files.size > 1) "Reading your ${files.size} screenshots as one recipe…" else "Reading your screenshot…"
            }.getOrElse { "Couldn't open those screenshots: ${it.message}" }
            _messages.emit(message)
        }
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
        if (items.isEmpty() || _sendingToList.value) return
        _sendingToList.value = true
        viewModelScope.launch {
            val outcome = try {
                container.groceries.send(items)
            } finally {
                _sendingToList.value = false
            }
            when (outcome) {
                is GroceryRouter.Outcome.Added -> _messages.emit(
                    (if (outcome.count == items.size) "Added ${outcome.count} to ${outcome.listName}"
                    else "Added ${outcome.count} of ${items.size} to ${outcome.listName}") +
                        if (outcome.createdList) " (a new list in OurGroceries)" else "",
                )
                is GroceryRouter.Outcome.Copied -> _messages.emit(copiedMessage(outcome))
                is GroceryRouter.Outcome.Failed -> _groceryFailure.value = GroceryFailure(outcome.reason, items)
            }
            _ourGroceriesConnected.value = container.ourGroceries.isConnected()
        }
    }

    /** From the failure dialog: fall back to copying after all. */
    fun copyInstead() {
        val failure = _groceryFailure.value ?: return
        _groceryFailure.value = null
        viewModelScope.launch { _messages.emit(copiedMessage(container.groceries.copyAndOpen(failure.items))) }
    }

    fun dismissGroceryFailure() {
        _groceryFailure.value = null
    }

    private fun copiedMessage(outcome: GroceryRouter.Outcome.Copied) =
        "Copied ${outcome.count} items" + if (outcome.openedApp) ". Paste them into OurGroceries." else " to your clipboard."

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
        const val KEY_UNITS = "units"
        val PENDING = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
    }
}
