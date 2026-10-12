package app.recipebox.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.recipebox.data.model.InstructionSection
import app.recipebox.data.model.PrepSource
import app.recipebox.data.model.ReadBy
import app.recipebox.data.model.Recipe
import app.recipebox.data.model.formatMinutes
import app.recipebox.data.model.formatQuantity
import app.recipebox.data.model.Ingredient
import app.recipebox.data.model.editedDisplay
import app.recipebox.data.model.groceryParts
import app.recipebox.data.model.Measures
import app.recipebox.data.model.UnitSystem
import app.recipebox.data.model.amountLabel
import app.recipebox.data.model.forCard
import app.recipebox.data.model.pluralUnit
import app.recipebox.groceries.GroceryItem
import app.recipebox.ui.components.BowlArt
import app.recipebox.ui.components.InkRule
import app.recipebox.ui.components.LineIcons
import app.recipebox.ui.components.RoundStickerButton
import app.recipebox.ui.components.ScribbleCheckRow
import app.recipebox.ui.components.StickerButton
import app.recipebox.ui.components.StickerChip
import app.recipebox.ui.components.Tape
import app.recipebox.ui.theme.Tin
import coil.compose.AsyncImage
import java.net.URI

/** One recipe card: gather ingredients, send the rest to the list, then cook. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipeScreen(
    recipe: Recipe,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onCook: (servings: Int?) -> Unit,
    onSendToList: (List<GroceryItem>) -> Unit,
    sendingToList: Boolean = false,
    units: UnitSystem = UnitSystem.METRIC,
    onDelete: () -> Unit,
    onEditIngredient: (index: Int, Ingredient) -> Unit,
    onSetPhoto: (uri: Uri?, fromCamera: Boolean) -> Unit,
    canTidyUp: Boolean = false,
    onTidyUp: () -> Unit = {},
    showBack: Boolean = true,
) {
    val measures = remember(units, recipe.sourceUrl) { Measures.forRecipe(units, recipe.sourceUrl) }
    val base = recipe.servings
    var servings by rememberSaveable(recipe.id) { mutableIntStateOf(base ?: 0) }
    var gathered by rememberSaveable(recipe.id) { mutableStateOf(emptySet<Int>()) }
    var showOriginal by rememberSaveable(recipe.id) { mutableStateOf(false) }
    var tab by rememberSaveable(recipe.id) { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by rememberSaveable(recipe.id) { mutableStateOf<Int?>(null) }
    var photoMenu by remember { mutableStateOf(false) }
    var confirmTidy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        cameraUri?.let { if (saved) onSetPhoto(it, true) }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onSetPhoto(it, false) }
    }
    val factor = if (base != null && base > 0 && servings > 0) servings.toDouble() / base else 1.0
    val uriHandler = LocalUriHandler.current

    val stillNeeded = recipe.ingredients.withIndex().filter { it.index !in gathered }
    val restructured = recipe.prepSource != PrepSource.NONE && recipe.prep.isNotEmpty()

    BoxWithConstraints(Modifier.fillMaxSize().background(Tin.Paper)) {
        val wide = maxWidth >= TWO_PANE_WIDTH
        // The card's pieces. Only the ingredient list (and the method) scroll; everything above stays put.
        // Kept short on phones so the list still gets most of the screen.
        val photoHeight = if (wide) 200.dp else 140.dp
        val photo: @Composable () -> Unit = {
            Column {
                    Box(
                        Modifier.fillMaxWidth().height(photoHeight).background(Tin.headers[(recipe.id % Tin.headers.size).toInt()]),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (recipe.imageUrl != null) {
                            AsyncImage(recipe.imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            BowlArt(Modifier.size(if (wide) 110.dp else 80.dp))
                        }
                        if (showBack) {
                            RoundStickerButton(
                                onClick = onBack,
                                contentDescription = "Back to your tin",
                                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp),
                            ) { LineIcons.Back() }
                        }
                        RoundStickerButton(
                        onClick = { photoMenu = true },
                        contentDescription = if (recipe.imageUrl == null) "Add a photo" else "Change the photo",
                        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp),
                    ) { LineIcons.Camera() }
                    Tape(Modifier.align(Alignment.BottomEnd).offset(x = (-40).dp, y = 11.dp), color = Tin.Radish.copy(alpha = 0.8f), tilt = 5f)
                    }
                    InkRule()
                }
        }
        val titleBlock: @Composable () -> Unit = {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                recipe.title,
                                style = MaterialTheme.typography.headlineLarge,
                                color = Tin.Ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            recipe.sourceUrl?.let { url ->
                                val host = runCatching { URI(url).host.removePrefix("www.") }.getOrNull() ?: url
                                Text("From $host", style = MaterialTheme.typography.bodyMedium, color = Tin.Ink)
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            recipe.totalMinutes?.let { StickerChip(formatMinutes(it), tilt = -2f) }
                            recipe.startAheadMinutes?.let {
                                StickerChip(
                                    if (it >= 480) "Start the night before" else "Start ${formatMinutes(it)} ahead",
                                    fill = Tin.Radish,
                                    tilt = 1.5f,
                                )
                            }
                            recipe.cuisine?.let { StickerChip(it, fill = Tin.Pea, tilt = 1.5f) }
                            recipe.category?.let { StickerChip(it, tilt = -1f) }
                            if (recipe.readBy == ReadBy.BUILT_IN) StickerChip("Read without Claude", fill = Tin.Paper, outline = Tin.Ink.copy(alpha = 0.4f))
                        }
                    }
                }
        val servingsStepper: @Composable () -> Unit = {
            if (base != null && base > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RoundStickerButton(
                                    onClick = { servings = (servings - 1).coerceAtLeast(1) },
                                    contentDescription = "Fewer servings",
                                ) { LineIcons.Minus() }
                                Text(
                                    "Serves $servings",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Tin.Ink,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.widthIn(min = 80.dp),
                                )
                                RoundStickerButton(
                                    onClick = { servings = (servings + 1).coerceAtMost(48) },
                                    contentDescription = "More servings",
                                    fill = Tin.Radish,
                                ) { LineIcons.Plus() }
                            }
                        }
        }
        val statusText: @Composable () -> Unit = {
            Column {
                Text(
                    when {
                        stillNeeded.isEmpty() -> "Everything’s out. Off you go."
                        gathered.isEmpty() -> "Tick what you’ve already got"
                        else -> "${stillNeeded.size} still to gather"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tin.Ink,
                )
                Text("Hold one to fix it", style = MaterialTheme.typography.bodySmall, color = Tin.Ink.copy(alpha = 0.7f))
            }
        }
        // Up top, so it's seen before the list: sends whatever isn't ticked (all of it, to start with).
        val addButton: @Composable () -> Unit = {
            if (stillNeeded.isNotEmpty()) {
                StickerButton(
                    onClick = {
                        if (!sendingToList) onSendToList(stillNeeded.map { it.value.groceryParts(factor, measures).let { (n, q) -> GroceryItem(n, q) } })
                    },
                    fill = Tin.Saffron,
                    minHeight = 44.dp,
                    shadowOffset = 3.dp,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    // The trolley turns into a spinner while OurGroceries is being talked to.
                    if (sendingToList) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Tin.Ink, strokeWidth = 2.4.dp)
                    } else {
                        LineIcons.Basket()
                    }
                    Text("Add ${stillNeeded.size} to list", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        val ingredientItems: LazyListScope.() -> Unit = {
                itemsIndexed(recipe.ingredients) { index, ingredient ->
                    ScribbleCheckRow(
                        checked = index in gathered,
                        onCheckedChange = { gathered = if (it) gathered + index else gathered - index },
                        onLongClick = { editing = index },
                        onLongClickLabel = "Fix this ingredient",
                        modifier = Modifier.padding(horizontal = 20.dp),
                    ) {
                        Text(
                            buildAnnotatedString {
                                // Scaled, converted or corrected lines are rebuilt with the amount in bold;
                                // anything else shows exactly as the recipe had it.
                                val shown = ingredient.forCard(factor, measures)
                                if (shown == null) {
                                    append(ingredient.raw)
                                } else {
                                    val amount = shown.amountLabel()
                                    if (!amount.isNullOrBlank()) {
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(amount) }
                                        append(" ")
                                    }
                                    append(shown.name)
                                    shown.note?.let { append(", $it") }
                                }
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = Tin.Ink,
                        )
                    }
                }
        }
        val methodBlock: LazyListScope.() -> Unit = {
                if (recipe.prep.isNotEmpty()) {
                    item { SectionTitle("Get ready", top = 30.dp) }
                    itemsIndexed(recipe.prep) { _, task ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("•", style = MaterialTheme.typography.bodyLarge, color = Tin.Ink)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(task.text, style = MaterialTheme.typography.bodyLarge, color = Tin.Ink)
                                when {
                                    task.startAheadMinutes != null -> StickerChip(
                                        if (task.startAheadMinutes >= 480) "Night before" else "${formatMinutes(task.startAheadMinutes)} ahead",
                                        fill = Tin.Radish,
                                        outline = null,
                                    )
                                    task.heatUp && task.neededAtStep != null ->
                                        StickerChip("Not till step ${task.neededAtStep}", fill = Tin.Paper, outline = Tin.Ink)
                                    task.heatUp -> StickerChip("Do this first", fill = Tin.Saffron, outline = null)
                                }
                            }
                        }
                    }
                }

                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 30.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (showOriginal) "Original method" else "Method", style = MaterialTheme.typography.titleLarge, color = Tin.Ink)
                        if (restructured) {
                            TextButton(onClick = { showOriginal = !showOriginal }) {
                                Text(
                                    if (showOriginal) "Show prepped version" else "Show original",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Tin.Ink,
                                )
                            }
                        }
                    }
                }
                method(if (showOriginal) recipe.originalInstructions else recipe.instructions)

                item {
                    Column(Modifier.padding(start = 12.dp, top = 24.dp)) {
                        recipe.sourceUrl?.let { url ->
                            TextButton(onClick = { uriHandler.openUri(url) }) {
                                Text("Open the original page", color = Tin.Ink, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        if (canTidyUp) {
                        TextButton(onClick = { if (recipe.ingredients.any { it.edited }) confirmTidy = true else onTidyUp() }) {
                            Text("Tidy up with Claude", color = Tin.Ink, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    TextButton(onClick = { confirmDelete = true }) {
                            Text("Remove from the tin", color = Tin.Stamp, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
        }

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                // Left: everything you gather. Only the list scrolls.
                Column(Modifier.weight(0.45f).fillMaxHeight()) {
                    photo()
                    titleBlock()
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Ingredients", style = MaterialTheme.typography.titleLarge, color = Tin.Ink)
                        servingsStepper()
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        statusText()
                        addButton()
                    }
                    InkRule(color = Tin.Ink.copy(alpha = 0.15f))
                    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp), modifier = Modifier.weight(1f)) {
                        ingredientItems()
                    }
                }
                Box(Modifier.width(2.dp).fillMaxHeight().background(Tin.Ink))
                // Right: everything you do.
                LazyColumn(
                    contentPadding = PaddingValues(top = 8.dp, bottom = 130.dp),
                    modifier = Modifier.weight(0.55f).fillMaxHeight().background(Tin.Card).statusBarsPadding(),
                ) {
                    methodBlock()
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                photo()
                titleBlock()
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    CardTab("Ingredients", selected = tab == 0) { tab = 0 }
                    CardTab("Method", selected = tab == 1) { tab = 1 }
                }
                InkRule()
                if (tab == 0) {
                    // Wraps onto two lines on a narrow phone rather than squashing.
                    FlowRow(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        servingsStepper()
                        addButton()
                    }
                    Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp)) { statusText() }
                    LazyColumn(contentPadding = PaddingValues(bottom = 120.dp), modifier = Modifier.weight(1f)) {
                        ingredientItems()
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 120.dp), modifier = Modifier.weight(1f).background(Tin.Card)) {
                        methodBlock()
                    }
                }
            }
        }
        // Start cooking sits under the method: full width on a phone, the method pane on a tablet.
        val barColour = if (wide || tab == 1) Tin.Card else Tin.Paper
        Box(
            Modifier
                .align(if (wide) Alignment.BottomEnd else Alignment.BottomCenter)
                .fillMaxWidth(if (wide) 0.55f else 1f)
                .background(Brush.verticalGradient(listOf(barColour.copy(alpha = 0f), barColour, barColour)))
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
        ) {
            StickerButton(
                onClick = { onCook(if (base != null) servings else null) },
                fill = Tin.Ink,
                contentColor = Tin.Card,
                shadow = Tin.Radish,
                minHeight = 60.dp,
                modifier = Modifier.align(Alignment.Center).widthIn(max = 520.dp).fillMaxWidth(),
            ) { Text("Start cooking") }
        }

        SnackbarHost(
            snackbar,
            Modifier.align(if (wide) Alignment.BottomStart else Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (wide) 16.dp else 96.dp),
        )
    }

    if (photoMenu) {
        PhotoMenu(
            hasPhoto = recipe.imageUrl != null,
            onTake = {
                photoMenu = false
                val file = java.io.File(java.io.File(context.cacheDir, "camera").apply { mkdirs() }, "${java.util.UUID.randomUUID()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                cameraUri = uri
                takePhoto.launch(uri)
            },
            onPick = {
                photoMenu = false
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onRemove = { photoMenu = false; onSetPhoto(null, false) },
            onDismiss = { photoMenu = false },
        )
    }

    editing?.let { index ->
        recipe.ingredients.getOrNull(index)?.let { ingredient ->
            IngredientEditor(
                ingredient = ingredient,
                onSave = { fixed -> onEditIngredient(index, fixed); editing = null },
                onDismiss = { editing = null },
            )
        }
    }

    if (confirmTidy) {
        AlertDialog(
            onDismissRequest = { confirmTidy = false },
            containerColor = Tin.Card,
            title = { Text("Tidy up with Claude?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Claude re-reads this from the original. Ingredients you've fixed by hand will be replaced with Claude's reading.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                StickerButton(onClick = { confirmTidy = false; onTidyUp() }, minHeight = 48.dp) { Text("Tidy it up") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTidy = false }) { Text("Leave it", color = Tin.Ink, style = MaterialTheme.typography.titleMedium) }
            },
        )
    }

    if (confirmDelete) {
        RemoveRecipeDialog(recipe.title, onRemove = { confirmDelete = false; onDelete() }, onDismiss = { confirmDelete = false })
    }
}

/** An index-card divider tab, like the ones on the tin. */
@Composable
private fun CardTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    Box(
        Modifier
            .height(if (selected) 46.dp else 40.dp)
            .clip(shape)
            .background(if (selected) Tin.Radish else Tin.Card)
            .border(2.dp, Tin.Ink, shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Tin.Ink)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PhotoMenu(hasPhoto: Boolean, onTake: () -> Unit, onPick: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Tin.Card,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (hasPhoto) "Change the photo" else "Add a photo", style = MaterialTheme.typography.headlineSmall, color = Tin.Ink)
            StickerButton(onClick = onTake, modifier = Modifier.fillMaxWidth()) {
                LineIcons.Camera()
                Text("Take a photo")
            }
            StickerButton(onClick = onPick, fill = Tin.Saffron, modifier = Modifier.fillMaxWidth()) { Text("Choose from your photos") }
            if (hasPhoto) {
                TextButton(onClick = onRemove) {
                    Text("Remove the photo", style = MaterialTheme.typography.titleMedium, color = Tin.Stamp)
                }
            }
        }
    }
}

/** At this width the recipe card splits into an ingredients pane and a method pane. */
val TWO_PANE_WIDTH = 700.dp

private fun androidx.compose.foundation.lazy.LazyListScope.method(sections: List<InstructionSection>) {
    var number = 1
    sections.forEach { section ->
        section.name?.let { name -> item { SectionTitle(name, top = 14.dp, small = true) } }
        val start = number
        itemsIndexed(section.steps) { i, step ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "${start + i}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Tin.Ink,
                    modifier = Modifier.width(28.dp),
                )
                Text(step, style = MaterialTheme.typography.bodyLarge, color = Tin.Ink, modifier = Modifier.weight(1f))
            }
        }
        number += section.steps.size
    }
}

@Composable
private fun SectionTitle(text: String, top: androidx.compose.ui.unit.Dp, small: Boolean = false) {
    Text(
        text,
        style = if (small) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
        color = Tin.Ink,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = top, bottom = 6.dp),
    )
}
