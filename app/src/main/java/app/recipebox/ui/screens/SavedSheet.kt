package app.recipebox.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.recipebox.data.model.Recipe
import app.recipebox.ui.components.BowlArt
import app.recipebox.ui.components.InkRule
import app.recipebox.ui.components.StickerButton
import app.recipebox.ui.components.Tape
import app.recipebox.ui.components.hardShadow
import app.recipebox.ui.components.rememberHaptics
import app.recipebox.ui.components.rememberReducedMotion
import app.recipebox.ui.theme.Tin
import coil.compose.AsyncImage

/** Shown when an import lands: a preview of the card, then the stamp comes down. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedSheet(recipe: Recipe, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val reduced = rememberReducedMotion()
    val haptics = rememberHaptics()
    val stamp = remember { Animatable(if (reduced) 1f else 2.2f) }
    val stampAlpha = remember { Animatable(if (reduced) 1f else 0f) }

    LaunchedEffect(recipe.id) {
        if (!reduced) {
            kotlinx.coroutines.delay(250)
            stampAlpha.snapTo(1f)
            stamp.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 900f))
        }
        haptics.confirm()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Tin.Card,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val shape = RoundedCornerShape(16.dp)
            Box(Modifier.padding(top = 10.dp)) {
                Column(
                    Modifier.rotate(1f).fillMaxWidth().hardShadow(shape, Tin.Ink, 5.dp).clip(shape)
                        .background(Tin.Card).border(2.dp, Tin.Ink, shape),
                ) {
                    Box(Modifier.fillMaxWidth().height(120.dp).background(Tin.Radish), contentAlignment = Alignment.Center) {
                        if (recipe.imageUrl != null) {
                            AsyncImage(recipe.imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            BowlArt(Modifier.size(64.dp))
                        }
                    }
                    InkRule()
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(recipe.title, style = MaterialTheme.typography.headlineSmall, color = Tin.Ink)
                        Text(summary(recipe), style = MaterialTheme.typography.bodyMedium, color = Tin.Ink)
                    }
                }
                Tape(Modifier.align(Alignment.TopStart).offset(x = 28.dp, y = (-8).dp), tilt = -3f)
                Text(
                    "Saved to\nyour tin",
                    style = MaterialTheme.typography.titleLarge,
                    color = Tin.Stamp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-14).dp, y = 64.dp)
                        .graphicsLayer {
                            scaleX = stamp.value
                            scaleY = stamp.value
                            alpha = stampAlpha.value
                            rotationZ = -14f
                        }
                        .background(Tin.Card.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                        .border(3.dp, Tin.Stamp, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            StickerButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Open recipe") }
            StickerButton(onClick = onDismiss, fill = Tin.Card, shadowOffset = 0.dp, minHeight = 52.dp, modifier = Modifier.fillMaxWidth()) {
                Text("Keep browsing")
            }
        }
    }
}

private fun summary(r: Recipe): String {
    val steps = r.instructions.sumOf { it.steps.size }
    val host = r.sourceUrl?.let { runCatching { java.net.URI(it).host.removePrefix("www.") }.getOrNull() }
    val parts = buildString {
        append("${r.ingredients.size} ingredients and $steps steps")
        host?.let { append(", from $it") }
    }
    return if (r.prep.isNotEmpty()) "$parts. ${r.prep.size} prep jobs moved to the start." else parts
}
