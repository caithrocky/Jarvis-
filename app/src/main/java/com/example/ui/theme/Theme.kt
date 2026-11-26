package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class JarvisExtendedColors(
    val obsidianBg: Color,
    val midnightSurface: Color,
    val glassSurface: Color,
    val glassElevated: Color,
    val electricBlue: Color,
    val arcCyan: Color,
    val softTurquoise: Color,
    val softViolet: Color,
    val profitEmerald: Color,
    val warningAmber: Color,
    val alertCrimson: Color,
    val mutedSlate: Color,
    val hairlineGlow: Color
)

val LocalJarvisColors = staticCompositionLocalOf {
    JarvisExtendedColors(
        obsidianBg = ObsidianBlack,
        midnightSurface = MidnightNavy,
        glassSurface = GlassPanelSurface,
        glassElevated = GlassPanelElevated,
        electricBlue = ElectricBlue,
        arcCyan = ArcCyan,
        softTurquoise = SoftTurquoise,
        softViolet = SoftViolet,
        profitEmerald = ProfitEmerald,
        warningAmber = WarningAmber,
        alertCrimson = AlertCrimson,
        mutedSlate = MutedCyanSlate,
        hairlineGlow = SubtleBorderBlue
    )
}

private val JarvisDarkColorScheme = darkColorScheme(
    primary = ElectricBlue,
    onPrimary = ObsidianBlack,
    primaryContainer = Color(0xFF003B5C),
    onPrimaryContainer = ArcCyan,
    secondary = SoftViolet,
    onSecondary = FrostWhite,
    secondaryContainer = DeepSlateNavy,
    onSecondaryContainer = FrostWhite,
    tertiary = SoftTurquoise,
    onTertiary = ObsidianBlack,
    background = ObsidianBlack,
    onBackground = FrostWhite,
    surface = MidnightNavy,
    onSurface = FrostWhite,
    surfaceVariant = DeepSlateNavy,
    onSurfaceVariant = MutedCyanSlate,
    outline = SubtleBorderBlue,
    outlineVariant = Color(0x2067E8F9),
    error = AlertCrimson,
    onError = FrostWhite
)

private val JarvisHighContrastScheme = JarvisDarkColorScheme.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF040812),
    surfaceVariant = Color(0xFF091326),
    outline = Color(0x6600D4FF)
)

val JarvisShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun JarvisTheme(
    highContrastHud: Boolean = false,
    content: @Composable () -> Unit
) {
    val extendedColors = if (highContrastHud) {
        JarvisExtendedColors(
            obsidianBg = Color(0xFF000000),
            midnightSurface = Color(0xFF040812),
            glassSurface = Color(0xDE070E1E),
            glassElevated = Color(0xF00C1830),
            electricBlue = ElectricBlue,
            arcCyan = ArcCyan,
            softTurquoise = SoftTurquoise,
            softViolet = SoftViolet,
            profitEmerald = ProfitEmerald,
            warningAmber = WarningAmber,
            alertCrimson = AlertCrimson,
            mutedSlate = Color(0xFFB5CBE3),
            hairlineGlow = Color(0x5200D4FF)
        )
    } else {
        JarvisExtendedColors(
            obsidianBg = ObsidianBlack,
            midnightSurface = MidnightNavy,
            glassSurface = GlassPanelSurface,
            glassElevated = GlassPanelElevated,
            electricBlue = ElectricBlue,
            arcCyan = ArcCyan,
            softTurquoise = SoftTurquoise,
            softViolet = SoftViolet,
            profitEmerald = ProfitEmerald,
            warningAmber = WarningAmber,
            alertCrimson = AlertCrimson,
            mutedSlate = MutedCyanSlate,
            hairlineGlow = SubtleBorderBlue
        )
    }

    CompositionLocalProvider(LocalJarvisColors provides extendedColors) {
        MaterialTheme(
            colorScheme = if (highContrastHud) JarvisHighContrastScheme else JarvisDarkColorScheme,
            typography = JarvisTypography,
            shapes = JarvisShapes,
            content = content
        )
    }
}
