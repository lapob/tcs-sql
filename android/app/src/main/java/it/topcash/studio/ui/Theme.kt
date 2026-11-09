package it.topcash.studio.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.topcash.studio.data.Tone

// Top Cash brand: blue #0A6BDA, navy #08254B, red #C80303 on warm-neutral surfaces.
val BrandBlue = Color(0xFF0A6BDA); val BrandNavy = Color(0xFF08254B); val BrandRed = Color(0xFFC80303)

private val Light = lightColorScheme(
    primary = BrandBlue, onPrimary = Color.White, primaryContainer = Color(0xFFDCE9FB), onPrimaryContainer = Color(0xFF052F63),
    secondary = BrandNavy, onSecondary = Color.White, secondaryContainer = Color(0xFFE3E8F2), onSecondaryContainer = BrandNavy,
    tertiary = BrandRed, onTertiary = Color.White, tertiaryContainer = Color(0xFFFFE2DF), onTertiaryContainer = Color(0xFF5E0000),
    background = Color(0xFFF6F6F8), onBackground = Color(0xFF16161D), surface = Color(0xFFF6F6F8), onSurface = Color(0xFF16161D),
    surfaceVariant = Color(0xFFEFEFF3), onSurfaceVariant = Color(0xFF55566A), outline = Color(0xFFD6D6DE), outlineVariant = Color(0xFFE7E7EC),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFBFC), surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF1F1F5), surfaceContainerHighest = Color(0xFFEAEAF0), error = Color(0xFFE5484D),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF4D9BFF), onPrimary = Color(0xFF002A5C), primaryContainer = Color(0xFF0D3A73), onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFF9EB7DE), onSecondary = Color(0xFF0B2342), secondaryContainer = Color(0xFF1E2A3D), onSecondaryContainer = Color(0xFFDCE6F7),
    tertiary = Color(0xFFFF6B66), onTertiary = Color(0xFF4A0000), tertiaryContainer = Color(0xFF5C1210), onTertiaryContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0C0C10), onBackground = Color(0xFFEDEDF2), surface = Color(0xFF0C0C10), onSurface = Color(0xFFEDEDF2),
    surfaceVariant = Color(0xFF1C1C25), onSurfaceVariant = Color(0xFFA3A3B5), outline = Color(0xFF34343F), outlineVariant = Color(0xFF24242D),
    surfaceContainerLowest = Color(0xFF101015), surfaceContainerLow = Color(0xFF131319), surfaceContainer = Color(0xFF15151C),
    surfaceContainerHigh = Color(0xFF1B1B23), surfaceContainerHighest = Color(0xFF22222B), error = Color(0xFFFB7185),
)

@Immutable data class StatusColors(val ok: Color, val due: Color, val bad: Color, val info: Color, val accent: Color, val neutral: Color) {
    fun of(t: Tone) = when (t) { Tone.Ok -> ok; Tone.Due -> due; Tone.Bad -> bad; Tone.Info -> info; Tone.Accent -> accent; Tone.Neutral -> neutral }
}
val LocalStatus = staticCompositionLocalOf { StatusColors(Color.Green, Color.Yellow, Color.Red, Color.Cyan, Color.Blue, Color.Gray) }

private val base = Typography()
private val Type = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 22.sp), bodyMedium = base.bodyMedium.copy(lineHeight = 20.sp),
)
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)
val Bouncy = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
val Snappy = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

@Composable fun TopCashTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val status = if (dark) StatusColors(Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFFFB7185), Color(0xFF38BDF8), Color(0xFF4D9BFF), Color(0xFFA3A3B5))
                 else StatusColors(Color(0xFF10A36B), Color(0xFFD98A06), Color(0xFFE5484D), Color(0xFF0B8DE0), BrandBlue, Color(0xFF6E6F82))
    androidx.compose.runtime.CompositionLocalProvider(LocalStatus provides status) {
        // Material 3 Expressive motion: every M3 component (sheets, chips, buttons, menus) animates with the official springs
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, shapes = AppShapes, motionScheme = androidx.compose.material3.MotionScheme.expressive(), content = content)
    }
}
val TextStyle.tabular get() = copy(fontFeatureSettings = "tnum")
