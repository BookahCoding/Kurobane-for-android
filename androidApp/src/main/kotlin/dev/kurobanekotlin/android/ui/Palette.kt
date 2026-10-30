package dev.kurobanekotlin.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.kurobanekotlin.designsystem.ThemeCatalog
import dev.kurobanekotlin.designsystem.ThemeColors

private val LocalKurobaneColors = staticCompositionLocalOf { ThemeCatalog.get("washi-ink").light }

/** Active Hokusai palette. Typography stays on the platform font so Greek and large text remain readable. */
val KurobanePalette: ThemeColors
    @Composable get() = LocalKurobaneColors.current

@Composable
fun KurobaneTheme(
    themeId: String,
    appearance: String,
    systemDark: Boolean,
    content: @Composable () -> Unit,
) {
    val light = ThemeCatalog.isLight(appearance, systemDark)
    val colors = ThemeCatalog.get(themeId).let { if (light) it.light else it.dark }
    val scheme = if (light) {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.text2,
            error = colors.danger,
            onError = colors.onAccent,
            outline = colors.border,
        )
    } else {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.text2,
            error = colors.danger,
            onError = colors.onAccent,
            outline = colors.border,
        )
    }
    CompositionLocalProvider(LocalKurobaneColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
