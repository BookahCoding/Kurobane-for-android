package dev.kurobanekotlin.designsystem

import androidx.compose.ui.graphics.Color

/** Kurobane theme families (Hokusai-inspired, same families as the Windows client), light/dark pairs. */
data class ThemeColors(
    val bg: Color, val surface: Color, val surfaceRaised: Color, val text: Color, val textMuted: Color,
    val accent: Color, val onAccent: Color, val border: Color, val danger: Color,
)

data class ThemeDef(val id: String, val displayName: String, val light: ThemeColors, val dark: ThemeColors)

object ThemeCatalog {
    private fun c(hex: String) = Color(hex.removePrefix("#").toLong(16) or 0xFF000000L)

    val all: List<ThemeDef> = listOf(
        ThemeDef("washi-ink", "Washi & Ink",
            light = ThemeColors(c("#F4EDD9"), c("#FAF5E3"), c("#EFE6CF"), c("#2B2822"), c("#6A5F50"), c("#A63F2D"), c("#FFFFFF"), c("#1E140A0A"), c("#8A1C45")),
            dark = ThemeColors(c("#0E1424"), c("#151C30"), c("#1B2440"), c("#F4EDD9"), c("#A8A08C"), c("#E5927E"), c("#0A0A0C"), c("#33F4EDD9"), c("#F4739B"))),
        ThemeDef("great-wave", "Great Wave",
            light = ThemeColors(c("#E8F0F4"), c("#F5FAFC"), c("#D0E0EA"), c("#0F2438"), c("#4A657A"), c("#1B4F72"), c("#FFFFFF"), c("#1A0F2438"), c("#8B1E3F")),
            dark = ThemeColors(c("#07131F"), c("#0C1C2C"), c("#12263A"), c("#E8F0F4"), c("#7F9BB0"), c("#5BA4D9"), c("#07131F"), c("#33E8F0F4"), c("#F080A0"))),
        ThemeDef("red-fuji", "Red Fuji",
            light = ThemeColors(c("#F7EFE6"), c("#FFF8F0"), c("#E8D8C8"), c("#2A1810"), c("#7A5A48"), c("#A84828"), c("#FFFFFF"), c("#182A1810"), c("#8A2030")),
            dark = ThemeColors(c("#120E1C"), c("#1A1528"), c("#241C34"), c("#F7EFE6"), c("#A89080"), c("#E09070"), c("#120E1C"), c("#33F7EFE6"), c("#F07088"))),
        ThemeDef("kirifuri", "Kirifuri Falls",
            light = ThemeColors(c("#B9E0D8"), c("#E7F6F2"), c("#D7EFEA"), c("#06211E"), c("#2A4E48"), c("#084F48"), c("#FFFFFF"), c("#1A06211E"), c("#7A1C2C")),
            dark = ThemeColors(c("#0C3C38"), c("#124842"), c("#16564E"), c("#F3FFFC"), c("#A8DDD2"), c("#D8FFF6"), c("#0C3C38"), c("#33F3FFFC"), c("#FFC8D0"))),
    )

    fun get(id: String): ThemeDef = all.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: all.first()
}
