package app.recipebox.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.recipebox.data.model.PrepTask
import app.recipebox.data.model.Recipe
import app.recipebox.data.model.Measures
import app.recipebox.data.model.UnitSystem
import app.recipebox.data.model.lineFor
import app.recipebox.ui.components.LineIcons
import app.recipebox.ui.components.RoundStickerButton
import app.recipebox.ui.components.ScribbleCheckRow
import app.recipebox.ui.components.StickerButton
import app.recipebox.ui.components.StickerChip
import app.recipebox.ui.components.Tape
import app.recipebox.ui.components.rememberHaptics
import app.recipebox.ui.theme.Tin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cook mode: plum night mode, one thing per page in big type. If the recipe
 * has prep pulled forward, the first page is "Get ready".
 */
@Composable
fun CookScreen(recipe: Recipe, onExit: () -> Unit, factor: Double = 1.0, units: UnitSystem = UnitSystem.METRIC) {
    val steps = remember(recipe) { recipe.instructions.flatMap { it.steps } }
    val hasPrep = recipe.prep.isNotEmpty()
    val pageCount = steps.size + if (hasPrep) 1 else 0
    val pager = rememberPagerState { pageCount.coerceAtLeast(1) }
    val scope = rememberCoroutineScope()
    val highlights = remember(recipe) { ingredientKeywords(recipe) }

    // Keep the screen on while cooking.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(onBack = onExit)

    val page = pager.currentPage
    val onPrepPage = hasPrep && page == 0
    val stepNumber = page - if (hasPrep) 0 else -1

    BoxWithConstraints(Modifier.fillMaxSize().background(Tin.Ink)) {
        val wide = maxWidth >= TWO_PANE_WIDTH
        val stepsPane: @Composable (Modifier) -> Unit = { modifier ->
            Column(
                modifier.fillMaxHeight().background(Tin.Ink).statusBarsPadding().navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RoundStickerButton(
                        onClick = onExit,
                        contentDescription = "Leave cook mode",
                        fill = Tin.Ink,
                        outline = Tin.Card,
                        shadowOffset = 0.dp,
                    ) { LineIcons.Close(Tin.Card) }
                    Text(
                        recipe.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Tin.Card,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    Text(
                        if (onPrepPage) "Get ready" else "Step $stepNumber of ${steps.size}",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 16.sp),
                        color = Tin.Card,
                    )
                }

                Row(
                    Modifier.fillMaxWidth().semantics { contentDescription = "Page ${page + 1} of $pageCount" },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repeat(pageCount) { i ->
                        Box(
                            Modifier.weight(1f).height(8.dp).background(
                                when {
                                    i < page -> Tin.Pea
                                    i == page -> Tin.Radish
                                    else -> Tin.InkLine
                                },
                                RoundedCornerShape(4.dp),
                            ),
                        )
                    }
                }

                HorizontalPager(state = pager, modifier = Modifier.weight(1f), verticalAlignment = Alignment.Top) { p ->
                    if (hasPrep && p == 0) {
                        GetReadyPage(recipe.prep)
                    } else {
                        val index = p - if (hasPrep) 1 else 0
                        StepPage(steps.getOrElse(index) { "" }, highlights)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StickerButton(
                        onClick = { scope.launch { pager.animateScrollToPage((page - 1).coerceAtLeast(0)) } },
                        enabled = page > 0,
                        fill = Tin.Ink,
                        contentColor = Tin.Card,
                        outline = Tin.Card,
                        shadowOffset = 0.dp,
                        shape = RoundedCornerShape(24.dp),
                        minHeight = 72.dp,
                        modifier = Modifier.weight(1f),
                    ) { Text("Back") }
                    val last = page >= pageCount - 1
                    StickerButton(
                        onClick = { if (last) onExit() else scope.launch { pager.animateScrollToPage(page + 1) } },
                        fill = if (last) Tin.Pea else Tin.Radish,
                        outline = Tin.Card,
                        shadow = Tin.Card,
                        shape = RoundedCornerShape(24.dp),
                        minHeight = 72.dp,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (onPrepPage) "All set, cook" else if (last) "Done" else "Next step") }
                }
            }
        }
        if (wide) {
            // Tablet propped up on the worktop: ingredients stay in view beside the steps.
            Row(Modifier.fillMaxSize()) {
                CookIngredients(recipe, factor, Measures.forRecipe(units, recipe.sourceUrl), Modifier.width(340.dp).fillMaxHeight())
                Box(Modifier.width(2.dp).fillMaxHeight().background(Tin.InkLine))
                stepsPane(Modifier.weight(1f))
            }
        } else {
            stepsPane(Modifier.fillMaxWidth())
        }
    }
}

/** The tablet side panel: tick ingredients off as they go in. */
@Composable
private fun CookIngredients(recipe: Recipe, factor: Double, measures: Measures, modifier: Modifier) {
    var used by rememberSaveable(recipe.id) { mutableStateOf(emptySet<Int>()) }
    Column(
        modifier
            .background(Tin.InkRaised)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("Ingredients", style = MaterialTheme.typography.titleLarge, color = Tin.Card)
        Text(
            if (used.isEmpty()) "Tick them off as they go in" else "${recipe.ingredients.size - used.size} still to go in",
            style = MaterialTheme.typography.bodySmall,
            color = Tin.InkMuted,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        recipe.ingredients.forEachIndexed { i, ingredient ->
            ScribbleCheckRow(
                checked = i in used,
                onCheckedChange = { used = if (it) used + i else used - i },
                dark = true,
            ) {
                Text(ingredient.lineFor(factor, measures), style = MaterialTheme.typography.bodyLarge, color = Tin.Card)
            }
        }
    }
}

@Composable
private fun GetReadyPage(prep: List<PrepTask>) {
    var done by rememberSaveable { mutableStateOf(emptySet<Int>()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Before the heat goes on", style = MaterialTheme.typography.headlineLarge, color = Tin.Card)
        Text("Pulled forward from later in the recipe", style = MaterialTheme.typography.bodyMedium, color = Tin.InkMuted)
        Spacer(Modifier.height(4.dp))
        prep.forEachIndexed { i, task ->
            Box(
                Modifier.fillMaxWidth()
                    .background(Tin.InkRaised, RoundedCornerShape(16.dp))
                    .border(2.dp, Tin.InkLine, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            ) {
                ScribbleCheckRow(
                    checked = i in done,
                    onCheckedChange = { done = if (it) done + i else done - i },
                    dark = true,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(task.text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 26.sp), color = Tin.Card)
                        val hiding = task.source?.takeIf { it.startsWith("step", ignoreCase = true) }
                        when {
                            task.startAheadMinutes != null ->
                                StickerChip("Needs starting ahead", fill = Tin.Radish, outline = null, tilt = -1.5f)
                            task.heatUp && task.neededAtStep != null ->
                                StickerChip("No rush: needed at step ${task.neededAtStep}", fill = Tin.Card, outline = null, tilt = -1.5f)
                            task.heatUp ->
                                StickerChip("Do this first: it takes a while to heat", fill = Tin.Saffron, outline = null, tilt = -1.5f)
                            hiding != null ->
                                StickerChip("Was hiding in $hiding", fill = Tin.Saffron, outline = null, tilt = -1.5f)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepPage(step: String, keywords: List<String>) {
    val timer = remember(step) { findTimer(step) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Text(
            highlight(step, keywords),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 30.sp, lineHeight = 40.sp),
            color = Tin.Card,
            modifier = Modifier.padding(top = 8.dp),
        )
        timer?.let { StepTimer(it) }
    }
}

/** A taped-on kitchen timer, pulled out of the step text ("simmer for 45 minutes"). */
@Composable
private fun StepTimer(timer: FoundTimer) {
    val haptics = rememberHaptics()
    var endsAt by rememberSaveable { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = endsAt > 0L
    val remaining = if (running) (endsAt - now).coerceAtLeast(0L) else timer.seconds * 1000L
    val finished = running && remaining == 0L

    LaunchedEffect(endsAt) {
        while (endsAt > 0L && System.currentTimeMillis() < endsAt) {
            now = System.currentTimeMillis()
            delay(250)
        }
        if (endsAt > 0L) {
            now = System.currentTimeMillis()
            haptics.confirm()
        }
    }

    Box(Modifier.padding(top = 10.dp)) {
        Row(
            Modifier
                .rotate(-2f)
                .background(if (finished) Tin.Pea else Tin.Saffron, RoundedCornerShape(18.dp))
                .border(2.dp, Tin.Card, RoundedCornerShape(18.dp))
                .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column {
                Text(timer.label, style = MaterialTheme.typography.labelMedium, color = Tin.Ink)
                Text(
                    if (finished) "Done!" else formatClock(remaining),
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 46.sp),
                    color = Tin.Ink,
                )
            }
            StickerButton(
                onClick = { endsAt = if (running) 0L else System.currentTimeMillis() + timer.seconds * 1000L },
                fill = Tin.Ink,
                contentColor = Tin.Card,
                shadowOffset = 0.dp,
                minHeight = 52.dp,
                contentPadding = PaddingValues(horizontal = 18.dp),
            ) {
                if (!running) LineIcons.Play()
                Text(if (running) "Reset" else "Start timer", style = MaterialTheme.typography.titleMedium)
            }
        }
        Tape(Modifier.align(Alignment.TopStart).padding(start = 22.dp), color = Tin.Radish.copy(alpha = 0.85f), tilt = -6f)
    }
}

// ---------- helpers ----------

private data class FoundTimer(val seconds: Int, val label: String)

private val durationRegex = Regex(
    """(\d+)(?:\s*(?:-|–|to)\s*\d+)?\s*(hours?|hrs?|minutes?|mins?)\b""",
    RegexOption.IGNORE_CASE,
)
private val verbRegex = Regex("""\b(simmer|boil|bake|roast|cook|fry|rest|chill|steam|grill|toast|marinate|soak|leave|stir)\w*""", RegexOption.IGNORE_CASE)

/** Uses the lower end of a range ("20–25 minutes" → 20), so you check early rather than late. */
private fun findTimer(step: String): FoundTimer? {
    val match = durationRegex.find(step) ?: return null
    val amount = match.groupValues[1].toIntOrNull() ?: return null
    val seconds = if (match.groupValues[2].startsWith("h", ignoreCase = true)) amount * 3600 else amount * 60
    if (seconds <= 0 || seconds > 12 * 3600) return null
    val verb = verbRegex.find(step)?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() } ?: "Timer"
    return FoundTimer(seconds, verb)
}

private fun formatClock(millis: Long): String {
    val total = (millis + 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Key words from ingredient names ("floury potatoes" → "potato") to highlight in steps. */
private fun ingredientKeywords(recipe: Recipe): List<String> =
    recipe.ingredients
        .mapNotNull { it.name.substringBefore(',').trim().split(' ').lastOrNull()?.lowercase() }
        .map { it.trimEnd('s').removeSuffix("e").ifEmpty { it } }
        .filter { it.length >= 4 }
        .distinct()

private fun highlight(text: String, keywords: List<String>): AnnotatedString {
    if (keywords.isEmpty()) return AnnotatedString(text)
    val pattern = Regex("""\b(${keywords.joinToString("|") { Regex.escape(it) }})\w*""", RegexOption.IGNORE_CASE)
    return buildAnnotatedString {
        var last = 0
        pattern.findAll(text).forEach { m ->
            append(text.substring(last, m.range.first))
            withStyle(SpanStyle(background = Tin.Saffron, color = Tin.Ink, fontWeight = FontWeight.Bold)) { append(m.value) }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}
