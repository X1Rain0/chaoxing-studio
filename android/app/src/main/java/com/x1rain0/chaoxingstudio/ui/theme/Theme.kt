package com.x1rain0.chaoxingstudio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Vermilion,
    onPrimary = Color.White,
    primaryContainer = VermilionContainer,
    onPrimaryContainer = Color(0xFF481A14),
    secondary = InkSecondary,
    onSecondary = Color.White,
    secondaryContainer = PaperRaised,
    onSecondaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperRaised,
    onSurfaceVariant = InkSecondary,
    outline = InkMuted,
    outlineVariant = Rule,
    error = Error,
    onError = Color.White,
    errorContainer = Color(0xFFF8DEDA),
    onErrorContainer = Color(0xFF410E0A),
)

private val DarkColors = darkColorScheme(
    primary = VermilionNight,
    onPrimary = Color(0xFF3D0B06),
    primaryContainer = Color(0xFF6F2119),
    onPrimaryContainer = Color(0xFFFFDAD4),
    secondary = TextNightSecondary,
    onSecondary = Color(0xFF302A22),
    secondaryContainer = InkSurface,
    onSecondaryContainer = TextNight,
    background = InkNight,
    onBackground = TextNight,
    surface = InkNight,
    onSurface = TextNight,
    surfaceVariant = InkSurface,
    onSurfaceVariant = TextNightSecondary,
    outline = TextNightSecondary,
    outlineVariant = RuleNight,
    error = Color(0xFFFFB4A9),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF930006),
    onErrorContainer = Color(0xFFFFDAD4),
)

private val StudioShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
internal fun ChaoxingStudioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        shapes = StudioShapes,
        content = content,
    )
}
