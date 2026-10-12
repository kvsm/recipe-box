package app.recipebox.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.recipebox.ui.components.LineIcons
import app.recipebox.ui.components.RoundStickerButton
import app.recipebox.ui.components.StickerButton
import app.recipebox.ui.components.hardShadow
import app.recipebox.ui.theme.Tin
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    hasClaudeKey: Boolean,
    ourGroceriesConnected: Boolean,
    ourGroceriesEmail: String?,
    listName: String,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onResume: () -> Unit,
    onSaveClaudeKey: (String) -> Unit,
    onSignInOurGroceries: suspend (email: String, password: String) -> Boolean,
    onDisconnectOurGroceries: () -> Unit,
    onListNameChange: (String) -> Unit,
    onExport: (Uri) -> Unit,
    lessons: Map<String, Int>,
    onExportLessons: (Uri) -> Unit,
    onClearLessons: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // Coming back from the OurGroceries sign-in tab: re-check what's connected.
    LifecycleResumeEffect(Unit) {
        onResume()
        onPauseOrDispose { }
    }
    val scope = rememberCoroutineScope()
    var key by rememberSaveable { mutableStateOf("") }
    var list by rememberSaveable(listName) { mutableStateOf(listName) }
    var ogEmail by rememberSaveable { mutableStateOf("") }
    var ogPassword by remember { mutableStateOf("") }
    var signingIn by remember { mutableStateOf(false) }
    val autofill = LocalAutofillManager.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(onExport)
    }
    val gapsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        uri?.let(onExportLessons)
    }

    Box(Modifier.fillMaxSize().background(Tin.Paper)) {
        Column(
            // On a tablet the panels sit in a readable column instead of stretching edge to edge.
            Modifier.align(Alignment.TopCenter).widthIn(max = 640.dp).fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RoundStickerButton(onClick = onBack, contentDescription = "Back") { LineIcons.Back() }
                Text("Settings", style = MaterialTheme.typography.headlineLarge, color = Tin.Ink)
            }

            Panel("Claude") {
                Text(
                    if (hasClaudeKey) {
                        "Your API key is saved. Claude reads captions, screenshots and pages without recipe data, and pulls the prep to the front of every recipe."
                    } else {
                        "No key needed to get going: recipe sites, captions and screenshots are all read on your phone. A key makes the trickier ones tidier and does the full prep-first restructure, and anything read without it can be tidied up later."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tin.Ink,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = fieldColours(),
                    // Not a login: keep password managers from filling the OurGroceries password in here.
                    modifier = Modifier.fillMaxWidth().semantics { contentDataType = ContentDataType.None },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StickerButton(onClick = { onSaveClaudeKey(key); key = "" }, enabled = key.isNotBlank(), minHeight = 48.dp) {
                        Text("Save key")
                    }
                    if (hasClaudeKey) {
                        StickerButton(onClick = { onSaveClaudeKey("") }, fill = Tin.Card, minHeight = 48.dp) { Text("Remove") }
                    }
                }
                Text(
                    "Get a key from console.anthropic.com. It's stored encrypted on this phone and only sent to Anthropic.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tin.Ink,
                )
            }

            Panel("OurGroceries") {
                Text(
                    if (ourGroceriesConnected) {
                        "Signed in as ${ourGroceriesEmail.orEmpty()}. \"Add to list\" puts ingredients straight onto your list, filed in the aisles you use."
                    } else {
                        "Sign in so \"Add to list\" goes straight into OurGroceries, filed in the aisles you use. Until then, items are copied to your clipboard."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tin.Ink,
                )
                if (!ourGroceriesConnected) {
                    OutlinedTextField(
                        value = ogEmail,
                        onValueChange = { ogEmail = it },
                        label = { Text("OurGroceries email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = fieldColours(),
                        // Lets password managers (Google, Bitwarden…) fill in a saved OurGroceries login.
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
                    )
                    OutlinedTextField(
                        value = ogPassword,
                        onValueChange = { ogPassword = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = fieldColours(),
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                    )
                }
                OutlinedTextField(
                    value = list,
                    onValueChange = { list = it },
                    label = { Text("OurGroceries list to add to") },
                    singleLine = true,
                    colors = fieldColours(),
                    modifier = Modifier.fillMaxWidth(),
                )
                LaunchedEffect(list) { if (list != listName && list.isNotBlank()) onListNameChange(list) }
                if (ourGroceriesConnected) {
                    StickerButton(onClick = onDisconnectOurGroceries, fill = Tin.Card, minHeight = 48.dp) { Text("Sign out") }
                } else {
                    StickerButton(
                        onClick = {
                            signingIn = true
                            scope.launch {
                                if (onSignInOurGroceries(ogEmail, ogPassword)) {
                                    // Tells the password manager the login worked, so it can offer to save it.
                                    autofill?.commit()
                                    ogPassword = ""
                                }
                                signingIn = false
                            }
                        },
                        enabled = !signingIn && ogEmail.isNotBlank() && ogPassword.isNotEmpty(),
                        fill = Tin.Pea,
                        minHeight = 48.dp,
                    ) { Text(if (signingIn) "Signing in…" else "Sign in") }
                }
                Text(
                    "This uses the same connection as the OurGroceries website, which isn't officially supported, so it could stop working if they change their site. Your password is stored encrypted on this phone and only ever sent to ourgroceries.com.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tin.Ink,
                )
            }

            Panel("Lessons for no-key mode") {
                val total = lessons.values.sum()
                Text(
                    when {
                        total == 0 && !hasClaudeKey ->
                            "With a Claude key, the app notes every time Claude reads something differently from the built-in reader. Export those lessons to make the no-key mode better in a future update."
                        total == 0 -> "Nothing yet. Each time Claude reads something differently from the built-in reader, it lands here."
                        else -> "Times Claude, or you, did better than the built-in reader:"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tin.Ink,
                )
                if (total > 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            "ingredient" to "Ingredient lines",
                            "reader" to "Captions and pages",
                            "screenshot" to "Screenshots",
                            "prep" to "Get ready",
                            "category" to "Shopping categories",
                        ).forEach { (area, label) ->
                            lessons[area]?.takeIf { it > 0 }?.let { n ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium, color = Tin.Ink)
                                    Text("$n", style = MaterialTheme.typography.titleMedium, color = Tin.Ink)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StickerButton(onClick = { gapsLauncher.launch("recipe-box-lessons.jsonl") }, fill = Tin.Saffron, minHeight = 48.dp) {
                            Text("Export lessons")
                        }
                        StickerButton(onClick = onClearLessons, fill = Tin.Card, minHeight = 48.dp) { Text("Clear") }
                    }
                    Text(
                        "The export includes the recipe text Claude was given (captions, page text, text read from screenshots), your category names, and both readings. It stays on your phone unless you export it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tin.Ink,
                    )
                }
            }

            Panel("Your recipes") {
                Text(
                    "Export saves every recipe as schema.org Recipe JSON, the standard recipe sites use, so other apps can read it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tin.Ink,
                )
                StickerButton(onClick = { exportLauncher.launch("recipe-box.json") }, fill = Tin.Saffron, minHeight = 48.dp) {
                    Text("Export recipes")
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().hardShadow(shape, Tin.Ink, 4.dp).background(Tin.Card, shape).border(2.dp, Tin.Ink, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Tin.Ink)
        content()
    }
}

@Composable
private fun fieldColours() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Tin.Paper,
    unfocusedContainerColor = Tin.Paper,
    focusedBorderColor = Tin.Ink,
    unfocusedBorderColor = Tin.Ink,
    focusedLabelColor = Tin.Ink,
)
