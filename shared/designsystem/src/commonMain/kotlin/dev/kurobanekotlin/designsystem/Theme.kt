package dev.kurobanekotlin.designsystem

import androidx.compose.ui.graphics.Color

/**
 * One Hokusai family, using the same ids, names, and hex tokens as the Windows client.
 * Eight-digit values are AARRGGBB, matching Windows `ThemeService.ParseColor`.
 */
data class ThemeColors(
    val bg: Color,
    val surface: Color,
    val surfaceSidebar: Color,
    val surfaceRaised: Color,
    val text: Color,
    val text2: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val border: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
)

data class ThemeDef(val id: String, val displayName: String, val light: ThemeColors, val dark: ThemeColors)

object ThemeCatalog {
    const val LIGHT = "light"
    const val DARK = "dark"
    const val SYSTEM = "system"

    private fun c(hex: String): Color {
        val h = hex.removePrefix("#").trim()
        val bits = h.toLong(16)
        val argb = if (h.length >= 8) bits.toInt() else (bits or 0xFF000000L).toInt()
        return Color(argb)
    }

    private fun pair(
        bg: String, surface: String, sidebar: String, raised: String,
        text: String, text2: String, muted: String, accent: String, onAccent: String, border: String,
        danger: String, success: String, warning: String,
    ) = ThemeColors(
        c(bg), c(surface), c(sidebar), c(raised), c(text), c(text2), c(muted),
        c(accent), c(onAccent), c(border), c(danger), c(success), c(warning),
    )

    val all: List<ThemeDef> = listOf(
        ThemeDef(
            "washi-ink", "Washi & Ink",
            light = pair("#F4EDD9", "#FAF5E3", "#F0E8D3", "#EFE6CF", "#2B2822", "#403B31", "#6A5F50", "#A63F2D", "#FFFFFF", "#140A0A1E", "#8A1C45", "#3F6A4B", "#855608"),
            dark = pair("#0E1424", "#151C30", "#111829", "#1B2440", "#F4EDD9", "#DCD3BC", "#A8A08C", "#E5927E", "#0A0A0C", "#33F4EDD9", "#F4739B", "#7DBE8A", "#E3B458"),
        ),
        ThemeDef(
            "great-wave", "Great Wave",
            light = pair("#E8F0F4", "#F5FAFC", "#DDE8EF", "#D0E0EA", "#0F2438", "#243B52", "#4A657A", "#1B4F72", "#FFFFFF", "#1A0F2438", "#8B1E3F", "#2F6B4F", "#8A6A12"),
            dark = pair("#07131F", "#0C1C2C", "#091624", "#12263A", "#E8F0F4", "#C5D6E4", "#7F9BB0", "#5BA4D9", "#07131F", "#33E8F0F4", "#F080A0", "#6FBF95", "#E0C05A"),
        ),
        ThemeDef(
            "red-fuji", "Red Fuji",
            light = pair("#F7EFE6", "#FFF8F0", "#F0E4D6", "#E8D8C8", "#2A1810", "#4A3024", "#7A5A48", "#A84828", "#FFFFFF", "#182A1810", "#8A2030", "#3E6848", "#8C6410"),
            dark = pair("#120E1C", "#1A1528", "#14101F", "#241C34", "#F7EFE6", "#E0D0C0", "#A89080", "#E09070", "#120E1C", "#33F7EFE6", "#F07088", "#78B888", "#E0B858"),
        ),
        ThemeDef(
            "pine-mist", "Pine & Mist",
            light = pair("#EEF2EC", "#F7FAF5", "#E4EBE0", "#D8E2D4", "#1A2420", "#2E3C36", "#5A6A62", "#3F6B4E", "#FFFFFF", "#181A2420", "#8A2A3A", "#2F6848", "#7A6810"),
            dark = pair("#0C1410", "#121A16", "#0E1612", "#1A2420", "#EEF2EC", "#D0D8D0", "#889488", "#7AAA88", "#0C1410", "#33EEF2EC", "#E08090", "#80C098", "#D0C060"),
        ),
        ThemeDef(
            "chrysanthemum", "Chrysanthemums",
            light = pair("#F6F1E4", "#FCF8EE", "#F0E9D6", "#ECE3CB", "#26201A", "#3E3528", "#6B5C42", "#8C5A0B", "#FFFFFF", "#1426201A", "#8E2A1E", "#3D6A3A", "#7E5A06"),
            dark = pair("#14130E", "#1C1A13", "#17150F", "#26231A", "#F4ECD8", "#DCD0B4", "#A9997A", "#E0B04A", "#14130E", "#33F4ECD8", "#F08A6E", "#8CC08A", "#E8C060"),
        ),
        ThemeDef(
            "peony", "Peonies",
            light = pair("#F7F0EF", "#FDF8F7", "#F1E6E5", "#EBDCDB", "#2A1D22", "#43303A", "#765A64", "#9C2E58", "#FFFFFF", "#142A1D22", "#8E1F2E", "#2F6A4A", "#84600E"),
            dark = pair("#160F14", "#1F161C", "#191117", "#2A1E26", "#F7EEF1", "#E2D0D7", "#B097A2", "#E58AAE", "#160F14", "#33F7EEF1", "#F4848F", "#7FC09A", "#E2BC62"),
        ),
        ThemeDef(
            "kirifuri", "Kirifuri Falls",
            light = pair("#B9E0D8", "#E7F6F2", "#C9E6E0", "#D7EFEA", "#06211E", "#143632", "#2A4E48", "#084F48", "#FFFFFF", "#1A06211E", "#7A1C2C", "#0E5A34", "#6A4A06"),
            dark = pair("#0C3C38", "#124842", "#0E403C", "#16564E", "#F3FFFC", "#D4F3EC", "#A8DDD2", "#D8FFF6", "#0C3C38", "#33F3FFFC", "#FFC8D0", "#9EE8C0", "#FFE08A"),
        ),
        ThemeDef(
            "thunderstorm", "Thunderstorm",
            light = pair("#A8C6F2", "#E6EEFC", "#C5D7F6", "#D6E4FA", "#10182C", "#1E3050", "#2C4068", "#1C2748", "#FFFFFF", "#1A10182C", "#7A1428", "#0C5834", "#5E4406"),
            dark = pair("#070A14", "#12182A", "#0E1422", "#1A2240", "#F7F9FF", "#D5DBF0", "#B4BCDC", "#FFF3C4", "#070A14", "#33F7F9FF", "#FFB4BE", "#A4E8C4", "#FFE08A"),
        ),
    )

    fun get(id: String): ThemeDef = all.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: all.first()

    /** Light palette when appearance is light, dark when dark, otherwise the opposite of [systemDark]. */
    fun isLight(appearance: String, systemDark: Boolean): Boolean = when (appearance.lowercase()) {
        LIGHT -> true
        DARK -> false
        else -> !systemDark
    }
}
