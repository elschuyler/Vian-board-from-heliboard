// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

// Material 3 Light Sky Blue ColorScheme (crisp, modern, high-contrast)
val LightSkyBlueColorScheme = lightColorScheme(
    primary = Color(0xFF0284C7),          // Sky 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE), // Sky 100
    onPrimaryContainer = Color(0xFF0369A1), // Sky 700
    inversePrimary = Color(0xFF38BDF8),
    secondary = Color(0xFF0EA5E9),        // Sky 500
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F9FF), // Sky 50
    onSecondaryContainer = Color(0xFF0C4A6E), // Sky 900
    tertiary = Color(0xFF0284C7),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBAE6FD),
    onTertiaryContainer = Color(0xFF0369A1),
    background = Color(0xFFF8FAFC),       // Slate 50 clean surface
    onBackground = Color(0xFF0F172A),     // Slate 900
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),   // Slate 200
    onSurfaceVariant = Color(0xFF334155), // Slate 700
    surfaceTint = Color(0xFF0284C7),
    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF8FAFC),
    outline = Color(0xFF94A3B8),          // Slate 400
    outlineVariant = Color(0xFFCBD5E1),   // Slate 300
    scrim = Color(0xFF000000),
)

// Material 3 Dark Sky Blue ColorScheme (deep midnight sky)
val DarkSkyBlueColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),          // Sky 400
    onPrimary = Color(0xFF082F49),        // Sky 950
    primaryContainer = Color(0xFF0369A1), // Sky 700
    onPrimaryContainer = Color(0xFFE0F2FE),
    inversePrimary = Color(0xFF0284C7),
    secondary = Color(0xFF7DD3FC),        // Sky 300
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF0C4A6E),
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = Color(0xFF38BDF8),
    onTertiary = Color(0xFF082F49),
    tertiaryContainer = Color(0xFF0284C7),
    onTertiaryContainer = Color(0xFFE0F2FE),
    background = Color(0xFF0B132B),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceTint = Color(0xFF38BDF8),
    inverseSurface = Color(0xFFF1F5F9),
    inverseOnSurface = Color(0xFF0F172A),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFF334155),
    scrim = Color(0xFF000000),
)

@Composable
fun Theme(dark: Boolean = false, content: @Composable () -> Unit) {
    val material3 = Typography()
    val colorScheme = if (dark) DarkSkyBlueColorScheme else LightSkyBlueColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(
            titleLarge = material3.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = material3.titleMedium.copy(fontWeight = FontWeight.Bold),
            titleSmall = material3.titleSmall.copy(fontWeight = FontWeight.Bold)
        ),
        //shapes = Shapes(),
        content = content
    )
}

const val previewDark = true
