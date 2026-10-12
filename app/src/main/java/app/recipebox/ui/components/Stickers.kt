package app.recipebox.ui.components

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recipebox.ui.theme.Tin

// ---------- feel ----------

class Haptics(private val view: View) {
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun confirm() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.LONG_PRESS,
        )
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** True when the user has turned animations off in system settings. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Gentle, deterministic tilt for a card so the tin looks hand-filed. */
fun tiltFor(id: Long): Float = (((id * 7919) % 5) - 2) * 0.7f

// ---------- shapes and surfaces ----------

/** A hard, offset "sticker" shadow, no blur. */
fun Modifier.hardShadow(shape: Shape, color: Color, offset: Dp): Modifier = drawBehind {
    val px = offset.toPx()
    if (px <= 0f) return@drawBehind
    val outline = shape.createOutline(size, layoutDirection, this)
    translate(px, px) { drawOutline(outline, color) }
}

fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 2.dp): Modifier = drawBehind {
    val stroke = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
    )
}

/**
 * The signature button: chunky outline, hard shadow, and a springy squash
 * into the shadow when pressed, with a haptic tick.
 */
@Composable
fun StickerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Tin.Radish,
    contentColor: Color = Tin.Ink,
    outline: Color = Tin.Ink,
    shadow: Color = Tin.Ink,
    shape: Shape = RoundedCornerShape(percent = 50),
    minHeight: Dp = 56.dp,
    shadowOffset: Dp = 4.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp),
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = rememberReducedMotion()
    val haptics = rememberHaptics()
    val press by animateDpAsState(
        targetValue = if (pressed) shadowOffset - 1.dp else 0.dp,
        animationSpec = if (reduced) snap() else spring(dampingRatio = 0.45f, stiffness = 1100f),
        label = "press",
    )

    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .offset(press, press)
            .hardShadow(shape, shadow, shadowOffset - press)
            .background(fill, shape)
            .border(2.dp, outline, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) {
                haptics.tick()
                onClick()
            }
            .heightIn(min = minHeight)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides contentColor,
            LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(color = contentColor),
        ) { content() }
    }
}

/** A small label stuck on at a slight angle: times, cuisines, warnings. */
@Composable
fun StickerChip(
    text: String,
    modifier: Modifier = Modifier,
    fill: Color = Tin.Card,
    contentColor: Color = Tin.Ink,
    outline: Color? = Tin.Ink,
    tilt: Float = 0f,
) {
    val shape = RoundedCornerShape(percent = 50)
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = contentColor,
        modifier = modifier
            .rotate(tilt)
            .background(fill, shape)
            .then(if (outline != null) Modifier.border(2.dp, outline, shape) else Modifier)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** A strip of washi tape. Purely decorative. */
@Composable
fun Tape(modifier: Modifier = Modifier, color: Color = Tin.Saffron.copy(alpha = 0.85f), tilt: Float = -4f) {
    Box(modifier.rotate(tilt).size(width = 76.dp, height = 22.dp).background(color))
}

// ---------- the tick-off ----------

/**
 * A checklist row. Ticking pops a pea-green tick and scribbles a hand-drawn
 * line through the text; unticking rubs it back out.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ScribbleCheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val reduced = rememberReducedMotion()
    val haptics = rememberHaptics()
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(320),
        label = "scribble",
    )
    val tickScale by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium),
        label = "tick",
    )
    val ink = if (dark) Tin.Card else Tin.Ink
    val boxShape = RoundedCornerShape(10.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { toggleableState = ToggleableState(checked) }
            .combinedClickable(
                role = Role.Checkbox,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick?.let { action -> { haptics.confirm(); action() } },
            ) {
                val next = !checked
                if (next) haptics.confirm() else haptics.tick()
                onCheckedChange(next)
            }
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .offset(if (checked) 2.dp else 0.dp, if (checked) 2.dp else 0.dp)
                .hardShadow(boxShape, ink, if (checked || dark) 0.dp else 3.dp)
                .size(30.dp)
                .background(if (checked) Tin.Pea else Color.Transparent, boxShape)
                .border(2.dp, ink, boxShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(18.dp).scale(tickScale)) {
                val path = Path().apply {
                    moveTo(size.width * 0.18f, size.height * 0.52f)
                    lineTo(size.width * 0.40f, size.height * 0.72f)
                    lineTo(size.width * 0.82f, size.height * 0.28f)
                }
                drawPath(path, Tin.Ink, style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Box(Modifier.weight(1f).alpha(1f - 0.45f * progress)) {
            content()
            Canvas(Modifier.matchParentSize()) {
                if (progress <= 0f) return@Canvas
                val y = size.height / 2
                val w = size.width
                val squiggle = Path().apply {
                    moveTo(-4f, y + 2f)
                    cubicTo(w * 0.15f, y - 6f, w * 0.25f, y + 7f, w * 0.4f, y)
                    cubicTo(w * 0.55f, y - 6f, w * 0.7f, y + 6f, w + 4f, y - 2f)
                }
                val measure = PathMeasure().apply { setPath(squiggle, false) }
                val partial = Path()
                measure.getSegment(0f, measure.length * progress, partial, true)
                drawPath(partial, Tin.PeaInk, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

/** Placeholder art for cards without a photo: a steaming bowl. */
@Composable
fun BowlArt(modifier: Modifier = Modifier, color: Color = Tin.Ink) {
    Canvas(modifier) {
        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height
        val bowl = Path().apply {
            moveTo(w * 0.12f, h * 0.48f)
            lineTo(w * 0.88f, h * 0.48f)
            cubicTo(w * 0.88f, h * 0.75f, w * 0.7f, h * 0.88f, w * 0.5f, h * 0.88f)
            cubicTo(w * 0.3f, h * 0.88f, w * 0.12f, h * 0.75f, w * 0.12f, h * 0.48f)
            close()
        }
        drawPath(bowl, color, style = stroke)
        listOf(0.34f, 0.5f, 0.66f).forEach { x ->
            val steam = Path().apply {
                moveTo(w * x, h * 0.36f)
                cubicTo(w * (x - 0.05f), h * 0.28f, w * (x + 0.05f), h * 0.22f, w * x, h * 0.12f)
            }
            drawPath(steam, color, style = stroke)
        }
    }
}

/** Round icon-only sticker button (back, close, settings). */
@Composable
fun RoundStickerButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    fill: Color = Tin.Card,
    outline: Color = Tin.Ink,
    shadow: Color = Tin.Ink,
    shadowOffset: Dp = 3.dp,
    icon: @Composable () -> Unit,
) {
    StickerButton(
        onClick = onClick,
        modifier = modifier.size(48.dp).semantics { this.contentDescription = contentDescription },
        fill = fill,
        outline = outline,
        shadow = shadow,
        shape = RoundedCornerShape(16.dp),
        minHeight = 48.dp,
        shadowOffset = shadowOffset,
        contentPadding = PaddingValues(0.dp),
    ) { icon() }
}

/** Simple line icons drawn to match the outline style (no icon font needed). */
object LineIcons {
    @Composable
    fun Back(color: Color = Tin.Ink) = Icon(color) { w, h ->
        moveTo(w * 0.62f, h * 0.2f); lineTo(w * 0.32f, h * 0.5f); lineTo(w * 0.62f, h * 0.8f)
    }

    @Composable
    fun Close(color: Color = Tin.Ink) = Icon(color) { w, h ->
        moveTo(w * 0.25f, h * 0.25f); lineTo(w * 0.75f, h * 0.75f)
        moveTo(w * 0.75f, h * 0.25f); lineTo(w * 0.25f, h * 0.75f)
    }

    @Composable
    fun Plus(color: Color = Tin.Ink) = Icon(color) { w, h ->
        moveTo(w * 0.5f, h * 0.2f); lineTo(w * 0.5f, h * 0.8f)
        moveTo(w * 0.2f, h * 0.5f); lineTo(w * 0.8f, h * 0.5f)
    }

    @Composable
    fun Minus(color: Color = Tin.Ink) = Icon(color) { w, h ->
        moveTo(w * 0.2f, h * 0.5f); lineTo(w * 0.8f, h * 0.5f)
    }

    @Composable
    fun Gear(color: Color = Tin.Ink) = Icon(color) { w, h ->
        // A simple sliders glyph reads as "settings" and is easier to draw cleanly than a cog.
        moveTo(w * 0.15f, h * 0.3f); lineTo(w * 0.85f, h * 0.3f)
        moveTo(w * 0.15f, h * 0.7f); lineTo(w * 0.85f, h * 0.7f)
        addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.35f, h * 0.3f), w * 0.09f))
        addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.65f, h * 0.7f), w * 0.09f))
    }

    @Composable
    fun Basket(color: Color = Tin.Ink) = Icon(color) { w, h ->
        moveTo(w * 0.1f, h * 0.18f); lineTo(w * 0.22f, h * 0.18f); lineTo(w * 0.32f, h * 0.65f)
        lineTo(w * 0.78f, h * 0.65f); lineTo(w * 0.88f, h * 0.32f); lineTo(w * 0.27f, h * 0.32f)
        addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.38f, h * 0.82f), w * 0.06f))
        addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.72f, h * 0.82f), w * 0.06f))
    }

    @Composable
    fun Camera(color: Color = Tin.Ink) = Icon(color) { w, h ->
        moveTo(w * 0.12f, h * 0.32f); lineTo(w * 0.32f, h * 0.32f); lineTo(w * 0.4f, h * 0.2f)
        lineTo(w * 0.6f, h * 0.2f); lineTo(w * 0.68f, h * 0.32f); lineTo(w * 0.88f, h * 0.32f)
        lineTo(w * 0.88f, h * 0.8f); lineTo(w * 0.12f, h * 0.8f); close()
        addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.5f, h * 0.55f), w * 0.15f))
    }

    @Composable
    fun Play(color: Color = Tin.Card) {
        Canvas(Modifier.size(18.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.25f, size.height * 0.15f)
                lineTo(size.width * 0.85f, size.height * 0.5f)
                lineTo(size.width * 0.25f, size.height * 0.85f)
                close()
            }
            drawPath(path, color)
        }
    }

    @Composable
    private fun Icon(color: Color, build: Path.(Float, Float) -> Unit) {
        Canvas(Modifier.size(22.dp)) {
            val path = Path().apply { build(size.width, size.height) }
            drawPath(path, color, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Little helper so screens can draw a full-width divider in ink. */
@Composable
fun InkRule(modifier: Modifier = Modifier, color: Color = Tin.Ink) {
    Box(modifier.fillMaxWidth().height(2.dp).background(color))
}

/** Circle used for steppers. */
val Round = CircleShape
