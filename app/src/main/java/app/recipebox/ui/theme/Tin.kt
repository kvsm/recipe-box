package app.recipebox.ui.theme

import android.content.res.AssetManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The palette: pistachio paper, card stock, plum ink, and three sticker colours. */
object Tin {
    val Paper = Color(0xFFE9F0DC)
    val PaperDeep = Color(0xFFDCE6C9)
    val Card = Color(0xFFFFFCF2)
    val Ink = Color(0xFF2E1A3B)
    val InkRaised = Color(0xFF3D2850)
    val InkLine = Color(0xFF5A4466)
    val InkMuted = Color(0xFFD9CCE0)
    val Radish = Color(0xFFFF5C8A)
    val Saffron = Color(0xFFFFC93C)
    val Pea = Color(0xFF8BCB4A)
    val PeaInk = Color(0xFF5E9A22)
    val Stamp = Color(0xFFD6245A)

    /** Card header colours, picked per recipe so the tin looks varied but stable. */
    val headers = listOf(Saffron, Pea, Radish, Card)
}

@Immutable
data class TinFonts(val display: FontFamily, val body: FontFamily) {
    companion object {
        private const val DISPLAY = "BricolageGrotesque.ttf"
        private const val BODY_REGULAR = "AtkinsonHyperlegible-Regular.ttf"
        private const val BODY_BOLD = "AtkinsonHyperlegible-Bold.ttf"

        /**
         * Loads the bundled fonts from assets/fonts (fetched by the downloadFonts
         * Gradle task). Falls back to system fonts if they aren't there.
         */
        fun load(assets: AssetManager): TinFonts {
            val files = runCatching { assets.list("fonts")?.toSet() }.getOrNull().orEmpty()
            val display = if (DISPLAY in files) {
                FontFamily(
                    displayFont(assets, FontWeight.SemiBold, 600),
                    displayFont(assets, FontWeight.Bold, 700),
                    displayFont(assets, FontWeight.ExtraBold, 800),
                )
            } else {
                FontFamily.SansSerif
            }
            val body = if (BODY_REGULAR in files && BODY_BOLD in files) {
                FontFamily(
                    Font("fonts/$BODY_REGULAR", assets, FontWeight.Normal),
                    Font("fonts/$BODY_BOLD", assets, FontWeight.Bold),
                )
            } else {
                FontFamily.SansSerif
            }
            return TinFonts(display, body)
        }

        private fun displayFont(assets: AssetManager, weight: FontWeight, axis: Int) = Font(
            "fonts/$DISPLAY",
            assets,
            weight,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(axis),
                FontVariation.Setting("opsz", 72f),
            ),
        )
    }
}

val LocalTinFonts = staticCompositionLocalOf { TinFonts(FontFamily.SansSerif, FontFamily.SansSerif) }

@Composable
fun RecipeBoxTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val fonts = remember { TinFonts.load(context.assets) }

    val colors = lightColorScheme(
        primary = Tin.Radish,
        onPrimary = Tin.Ink,
        secondary = Tin.Saffron,
        onSecondary = Tin.Ink,
        tertiary = Tin.Pea,
        onTertiary = Tin.Ink,
        background = Tin.Paper,
        onBackground = Tin.Ink,
        surface = Tin.Card,
        onSurface = Tin.Ink,
        surfaceVariant = Tin.PaperDeep,
        onSurfaceVariant = Tin.Ink,
        outline = Tin.Ink,
        error = Tin.Stamp,
        onError = Tin.Card,
    )

    val d = fonts.display
    val b = fonts.body
    val typography = Typography(
        displayLarge = TextStyle(fontFamily = d, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, lineHeight = 42.sp, letterSpacing = (-1.5).sp),
        headlineLarge = TextStyle(fontFamily = d, fontWeight = FontWeight.ExtraBold, fontSize = 38.sp, lineHeight = 38.sp, letterSpacing = (-1).sp),
        headlineSmall = TextStyle(fontFamily = d, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 28.sp, letterSpacing = (-0.5).sp),
        titleLarge = TextStyle(fontFamily = d, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp),
        titleMedium = TextStyle(fontFamily = d, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 22.sp),
        labelLarge = TextStyle(fontFamily = d, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 22.sp),
        labelMedium = TextStyle(fontFamily = b, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp),
        bodyLarge = TextStyle(fontFamily = b, fontSize = 17.sp, lineHeight = 25.sp),
        bodyMedium = TextStyle(fontFamily = b, fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontFamily = b, fontSize = 13.sp, lineHeight = 18.sp),
    )

    CompositionLocalProvider(LocalTinFonts provides fonts) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}
