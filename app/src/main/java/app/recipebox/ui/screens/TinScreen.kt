package app.recipebox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.recipebox.data.model.Recipe
import app.recipebox.data.model.formatMinutes
import app.recipebox.ui.ImportStatus
import app.recipebox.ui.components.BowlArt
import app.recipebox.ui.components.InkRule
import app.recipebox.ui.components.LineIcons
import app.recipebox.ui.components.RoundStickerButton
import app.recipebox.ui.components.StickerButton
import app.recipebox.ui.components.StickerChip
import app.recipebox.ui.components.Tape
import app.recipebox.ui.components.dashedBorder
import app.recipebox.ui.components.hardShadow
import app.recipebox.ui.components.tiltFor
import app.recipebox.ui.theme.Tin
import coil.compose.AsyncImage

/** Home: the tin of index cards. */
@Composable
fun TinScreen(
    recipes: List<Recipe>,
    imports: List<ImportStatus>,
    snackbar: SnackbarHostState,
    onOpen: (Long) -> Unit,
    onSettings: () -> Unit,
    onImportUrl: (String) -> Unit,
    onDismissImports: () -> Unit,
    selectedId: Long? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var divider by rememberSaveable { mutableStateOf<String?>(null) }
    var showAdd by rememberSaveable { mutableStateOf(false) }

    // Divider tabs come from the user's own recipes: their most common categories.
    val dividers = remember(recipes) {
        recipes.flatMap { r -> listOfNotNull(r.category, r.cuisine).flatMap { it.split(',') } }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(4).map { it.key }
    }
    val shown = recipes.filter { r ->
        (query.isBlank() || r.title.contains(query, ignoreCase = true) ||
            r.ingredients.any { it.name.contains(query, ignoreCase = true) }) &&
            (divider == null || listOfNotNull(r.category, r.cuisine).any { it.contains(divider!!, ignoreCase = true) })
    }

    Box(Modifier.fillMaxSize().background(Tin.Paper)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Your tin", style = MaterialTheme.typography.displayLarge, color = Tin.Ink)
                    Text(
                        when (recipes.size) {
                            0 -> "Empty for now"
                            1 -> "1 recipe"
                            else -> "${recipes.size} recipes"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tin.Ink,
                    )
                }
                RoundStickerButton(onClick = onSettings, contentDescription = "Settings") { LineIcons.Gear() }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Fancy something with leeks?") },
                singleLine = true,
                shape = RoundedCornerShape(26.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Tin.Card,
                    unfocusedContainerColor = Tin.Card,
                    focusedBorderColor = Tin.Ink,
                    unfocusedBorderColor = Tin.Ink,
                ),
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp),
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                item { DividerTab("Everything", Tin.Radish, divider == null) { divider = null } }
                items(dividers) { name ->
                    val colour = Tin.headers[(dividers.indexOf(name) + 1) % Tin.headers.size]
                    DividerTab(name, colour, divider == name) { divider = if (divider == name) null else name }
                }
            }
            InkRule()

            LazyVerticalGrid(
                // Two cards across on a phone, more as the space allows.
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize().background(Tin.PaperDeep),
            ) {
                if (imports.any { it.failed }) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        FailedImports(imports.filter { it.failed }, onDismissImports)
                    }
                }
                items(imports.filter { !it.failed }, key = { it.id.toString() }) { ImportingCard(it) }
                items(shown, key = { it.id }) { recipe ->
                    RecipeCard(
                        recipe,
                        Modifier.offset(y = if (shown.indexOf(recipe) % 2 == 1) 10.dp else 0.dp),
                        selected = recipe.id == selectedId,
                    ) {
                        onOpen(recipe.id)
                    }
                }
                if (recipes.isEmpty() && imports.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyTin { showAdd = true } }
                }
            }
        }

        StickerButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
            minHeight = 60.dp,
        ) {
            LineIcons.Plus()
            Text("Add a recipe")
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 90.dp))
    }

    if (showAdd) {
        AddLinkDialog(onDismiss = { showAdd = false }, onAdd = { onImportUrl(it); showAdd = false })
    }
}

@Composable
private fun DividerTab(label: String, colour: androidx.compose.ui.graphics.Color, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    Box(
        Modifier
            .height(if (selected) 46.dp else 40.dp)
            .clip(shape)
            .background(colour)
            .border(2.dp, Tin.Ink, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Tin.Ink)
    }
}

@Composable
private fun RecipeCard(recipe: Recipe, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            // The open card (tablet only) straightens up and wears a radish shadow.
            .rotate(if (selected) 0f else tiltFor(recipe.id))
            .hardShadow(shape, if (selected) Tin.Radish else Tin.Ink, if (selected) 6.dp else 4.dp)
            .clip(shape)
            .background(Tin.Card)
            .border(if (selected) 3.dp else 2.dp, Tin.Ink, shape)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().height(104.dp).background(Tin.headers[(recipe.id % Tin.headers.size).toInt()]),
            contentAlignment = Alignment.Center,
        ) {
            if (recipe.imageUrl != null) {
                AsyncImage(
                    model = recipe.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                BowlArt(Modifier.size(56.dp))
            }
        }
        InkRule()
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(recipe.title, style = MaterialTheme.typography.titleMedium, color = Tin.Ink, maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recipe.totalMinutes?.let { StickerChip(formatMinutes(it), fill = Tin.Paper) }
                recipe.startAheadMinutes?.let { StickerChip(if (it >= 480) "Night before" else "Start ahead", fill = Tin.Radish) }
            }
        }
    }
}

@Composable
private fun ImportingCard(status: ImportStatus) {
    Box(
        Modifier
            .rotate(-1f)
            .fillMaxWidth()
            .heightIn(min = 170.dp)
            .dashedBorder(Tin.Ink, 14.dp)
            .background(Tin.Card.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Tape(Modifier.align(Alignment.TopCenter).offset(y = (-22).dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(color = Tin.Ink, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            Text("Reading ${status.label}", style = MaterialTheme.typography.titleMedium, color = Tin.Ink, textAlign = TextAlign.Center)
            Text("Lands here when it’s ready", style = MaterialTheme.typography.bodySmall, color = Tin.Ink, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun FailedImports(failed: List<ImportStatus>, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().background(Tin.Card, shape).border(2.dp, Tin.Stamp, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        failed.forEach {
            Text("Couldn’t save ${it.label}", style = MaterialTheme.typography.titleMedium, color = Tin.Stamp)
            Text(it.error ?: "Something went wrong.", style = MaterialTheme.typography.bodyMedium, color = Tin.Ink)
        }
        TextButton(onClick = onDismiss) { Text("Dismiss", color = Tin.Ink, style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun EmptyTin(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp, start = 12.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Nothing in the tin yet", style = MaterialTheme.typography.headlineSmall, color = Tin.Ink)
        Text(
            "Find a recipe in Chrome or Instagram, tap Share, and pick Save to Recipe Box. Screenshots of cookbooks work too.",
            style = MaterialTheme.typography.bodyLarge,
            color = Tin.Ink,
        )
        Spacer(Modifier.height(4.dp))
        StickerButton(onClick = onAdd, fill = Tin.Saffron) { Text("Paste a link") }
    }
}

@Composable
private fun AddLinkDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    val valid = url.trim().startsWith("http")
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tin.Card,
        title = { Text("Add a recipe", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Paste a link to a recipe page or an Instagram post. Quicker still: use Share in Chrome or Instagram.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Recipe link") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            StickerButton(onClick = { onAdd(url.trim()) }, enabled = valid, minHeight = 48.dp) { Text("Save it") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Tin.Ink, style = MaterialTheme.typography.titleMedium) }
        },
    )
}
