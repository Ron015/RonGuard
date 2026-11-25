package dev.ronguard.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class WardenColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val text: Color,
    val muted: Color,
    /** Neon purple accent. Fills only (buttons, pills, switch track, power button). */
    val primary: Color,
    val onPrimary: Color,
    /** Accent as text, strokes and icons: deep violet on light, neon purple on dark. */
    val accentInk: Color,
    val blocked: Color,
    val onBlocked: Color,
    val warning: Color,
    /**
     * Categorical hues for identity (countries, stat squares, illustrations), in fixed order. Kept clear of
     * the status colours (protected green, blocked vermilion, warning amber). Validated with the
     * dataviz six-checks script on both surfaces. Graphics only: text stays in text tokens.
     */
    val palette: List<Color>,
    val isDark: Boolean,
)

// Light muted/blocked/warning pass as text on surfaces; primary purple is for fills;
// text-weight accents use accentInk.
// ContrastTest guards this; docs/design-system.md has the table.
val LightColors = WardenColors(
    background = Color(0xFFF0F0F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE4E4F0),
    outline = Color(0xFFC8C8DC),
    text = Color(0xFF0E1020),
    muted = Color(0xFF5A5C72),
    primary = Color(0xFF7C3AED),
    onPrimary = Color(0xFFFFFFFF),
    accentInk = Color(0xFF5B21B6),
    blocked = Color(0xFFC02020),
    onBlocked = Color(0xFFFFFFFF),
    warning = Color(0xFFA84A08),
    palette = listOf(Color(0xFF2563EB), Color(0xFFC026D3), Color(0xFF7C3AED), Color(0xFF0891B2), Color(0xFF4F46E5)),
    isDark = false,
)

val DarkColors = WardenColors(
    background = Color(0xFF0A0B1F),
    surface = Color(0xFF12132E),
    surfaceVariant = Color(0xFF1C1E3D),
    outline = Color(0xFF2A2C4A),
    text = Color(0xFFE8E8F5),
    muted = Color(0xFF9B9DB8),
    primary = Color(0xFFA855F7),
    onPrimary = Color(0xFF0A0B1F),
    accentInk = Color(0xFFC084FC),
    blocked = Color(0xFFFF5C5C),
    onBlocked = Color(0xFF0A0B1F),
    warning = Color(0xFFFBBF24),
    palette = listOf(Color(0xFF60A5FA), Color(0xFFE879F9), Color(0xFFA78BFA), Color(0xFF22D3EE), Color(0xFF818CF8)),
    isDark = true,
)

/** All fonts are bundled TTFs in res/font (OFL): no downloadable fonts, no network. */
val Rubik = FontFamily(
    Font(R.font.rubik, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.rubik, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.rubik, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

@Immutable
data class WardenType(
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    /** Small uppercase Rubik for section labels and metadata. */
    val caption: TextStyle,
)

val WardenTypography = WardenType(
    display = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Medium, fontSize = 44.sp, lineHeight = 48.sp),
    headline = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    title = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
    body = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    label = TextStyle(
        fontFamily = Rubik,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    caption = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.8.sp),
)

/** Tabular figures, so counters don't jitter as digits change. Use on every number. */
fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Radii {
    val small = 10.dp // chips, inputs
    val medium = 16.dp // buttons, cards
    val large = 28.dp // sheets
}

object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val SHORT = 150
    const val MEDIUM = 200
    const val LONG = 320
    const val COUNT_UP = 600
    const val STAGGER = 30
    const val STAGGER_ITEMS = 6

    fun <T> emphasized(durationMillis: Int = MEDIUM, delayMillis: Int = 0): FiniteAnimationSpec<T> =
        tween(durationMillis, delayMillis, Emphasized)

    /** Press feel for buttons and the switch thumb. */
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 500f)
}
