package app.recipebox.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import app.recipebox.data.model.Recipe
import app.recipebox.ui.components.BowlArt
import app.recipebox.ui.theme.Tin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.recipebox.ui.screens.CookScreen
import app.recipebox.ui.screens.RecipeScreen
import app.recipebox.ui.screens.SavedSheet
import app.recipebox.ui.screens.SettingsScreen
import app.recipebox.ui.screens.TinScreen
import app.recipebox.ui.theme.RecipeBoxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RecipeBoxTheme { RecipeBoxRoot() } }
    }
}

/** Three-and-a-bit screens don't need a navigation library; plain state does. */
@Composable
private fun RecipeBoxRoot(vm: MainViewModel = viewModel()) {
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var cooking by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var cookServings by rememberSaveable { mutableStateOf<Int?>(null) }
    val snackbar = remember { SnackbarHostState() }
    // 840dp is Android's "expanded" width: tablets in landscape, big foldables open, desktop windows.
    val expanded = LocalConfiguration.current.screenWidthDp >= 840

    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val imports by vm.imports.collectAsStateWithLifecycle()
    val justSaved by vm.justSaved.collectAsStateWithLifecycle()
    val hasClaudeKey by vm.hasClaudeKey.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        vm.removed.collect { removed ->
            val result = snackbar.showSnackbar(
                message = "Took ${removed.recipe.title} out of the tin",
                actionLabel = "Undo",
                withDismissAction = false,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) vm.undoDelete(removed) else vm.forgetRemoved(removed)
        }
    }

    val id = openId
    when {
        settings -> {
            val ogConnected by vm.ourGroceriesConnected.collectAsStateWithLifecycle()
            val ogEmail by vm.ourGroceriesEmail.collectAsStateWithLifecycle()
            val listName by vm.listName.collectAsStateWithLifecycle()
            val lessons by vm.lessons.collectAsStateWithLifecycle()
            SettingsScreen(
                hasClaudeKey = hasClaudeKey,
                ourGroceriesConnected = ogConnected,
                ourGroceriesEmail = ogEmail,
                listName = listName,
                snackbar = snackbar,
                onBack = { settings = false },
                onResume = vm::refreshStatus,
                onSaveClaudeKey = vm::saveClaudeKey,
                onSignInOurGroceries = vm::signInToOurGroceries,
                onDisconnectOurGroceries = vm::disconnectOurGroceries,
                onListNameChange = vm::setListName,
                onExport = vm::export,
                lessons = lessons,
                onExportLessons = vm::exportLessons,
                onClearLessons = vm::clearLessons,
            )
        }

        // Tablet in landscape (or any window this wide): the tin and the open recipe side by side.
        expanded && !cooking -> {
            BackHandler(enabled = id != null) { openId = null }
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(0.42f).fillMaxHeight()) {
                    TinScreen(
                        recipes = recipes,
                        imports = imports,
                        snackbar = remember { SnackbarHostState() }, // messages show in the recipe pane
                        onOpen = { openId = it },
                        onSettings = { settings = true },
                        onImportUrl = vm::importUrl,
                        onDismissImports = vm::dismissFinishedImports,
                        onRemove = { removeId -> vm.delete(removeId); if (removeId == id) openId = null },
                        selectedId = id,
                    )
                }
                Box(Modifier.width(2.dp).fillMaxHeight().background(Tin.Ink))
                Box(Modifier.weight(0.58f).fillMaxHeight()) {
                    val current = id?.let { openRecipe(vm, it) }
                    if (current != null) {
                        RecipeScreen(
                            recipe = current,
                            snackbar = snackbar,
                            onBack = { openId = null },
                            onCook = { cookServings = it; cooking = true },
                            onSendToList = vm::sendToList,
                            onDelete = { vm.delete(current.id); openId = null },
                            onEditIngredient = { index, fixed -> vm.updateIngredient(current, index, fixed) },
                            onSetPhoto = { uri, camera -> vm.setPhoto(current, uri, camera) },
                            canTidyUp = hasClaudeKey && current.couldTidyUp,
                            onTidyUp = { vm.tidyUp(current) },
                            showBack = false,
                        )
                    } else {
                        NothingOpen(snackbar)
                    }
                }
            }
        }

        id != null -> {
            val current = openRecipe(vm, id)
            BackHandler { if (cooking) cooking = false else openId = null }
            if (current != null) {
                if (cooking) {
                    CookScreen(current, onExit = { cooking = false }, factor = scaleFor(current, cookServings))
                } else {
                    RecipeScreen(
                        recipe = current,
                        snackbar = snackbar,
                        onBack = { openId = null },
                        onCook = { cookServings = it; cooking = true },
                        onSendToList = vm::sendToList,
                        onDelete = { vm.delete(id); openId = null },
                        onEditIngredient = { index, fixed -> vm.updateIngredient(current, index, fixed) },
                        onSetPhoto = { uri, camera -> vm.setPhoto(current, uri, camera) },
                        canTidyUp = hasClaudeKey && current.couldTidyUp,
                        onTidyUp = { vm.tidyUp(current) },
                    )
                }
            }
        }

        else -> TinScreen(
            recipes = recipes,
            imports = imports,
            snackbar = snackbar,
            onOpen = { openId = it },
            onSettings = { settings = true },
            onImportUrl = vm::importUrl,
            onDismissImports = vm::dismissFinishedImports,
            onRemove = vm::delete,
        )
    }

    justSaved?.let { saved ->
        if (!cooking) {
            SavedSheet(
                recipe = saved,
                onOpen = { vm.dismissFinishedImports(); openId = saved.id },
                onDismiss = vm::dismissFinishedImports,
            )
        }
    }
}

@Composable
private fun openRecipe(vm: MainViewModel, id: Long): Recipe? {
    val recipe by remember(id) { vm.recipe(id) }.collectAsStateWithLifecycle(initialValue = null)
    return recipe
}

private fun scaleFor(recipe: Recipe, servings: Int?): Double {
    val base = recipe.servings ?: return 1.0
    return if (base > 0 && servings != null && servings > 0) servings.toDouble() / base else 1.0
}

/** The right-hand pane before anything's been opened. */
@Composable
private fun NothingOpen(snackbar: SnackbarHostState) {
    Box(Modifier.fillMaxSize().background(Tin.Paper), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BowlArt(Modifier.size(96.dp))
            Text("Pick a card from the tin", style = MaterialTheme.typography.headlineSmall, color = Tin.Ink)
            Text(
                "It opens here, with the ingredients and method side by side.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tin.Ink,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }
}
