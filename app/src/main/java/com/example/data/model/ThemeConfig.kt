package com.example.data.model

/**
 * Full theme engine state (reference: V2Fall theme editor, 10 presets + arbitrary custom).
 *
 * Colours are stored per mode as `RRGGBB` so one config carries both the dark and the light
 * look, exactly like the reference's "separate light-mode palette". A preset supplies both
 * palettes; editing a colour switches [presetId] to [CUSTOM_PRESET_ID] but keeps the palette.
 */
data class ThemeConfig(
    val presetId: String = PRESET_GRAPHITE_AMBER,
    val mode: ThemeMode = ThemeMode.DARK,
    val dark: ThemePalette = ThemePalette.GRAPHITE_AMBER_DARK,
    val light: ThemePalette = ThemePalette.GRAPHITE_AMBER_LIGHT,
    val font: FontChoice = FontChoice.VAZIRMATN,
    /** Multiplier on the 15/18.75 base type ramp; 0.85 … 1.3. */
    val fontScale: Float = 1f,
    val boldText: Boolean = false,
    /** Multiplier on the 20/14/10 radius ladder; 0.6 … 1.6. */
    val radiusScale: Float = 1f,
    val pattern: ThemePattern = ThemePattern.AURORA,
    val cardStyle: CardStyle = CardStyle.GLASS,
    val orbShape: OrbShape = OrbShape.CIRCLE,
    val orbDock: OrbDockPosition = OrbDockPosition.BOTTOM,
    val motionEnabled: Boolean = true,
    val simpleMode: Boolean = false
) {
    fun palette(isDark: Boolean): ThemePalette = if (isDark) dark else light

    /** A preset carries two palettes; any hand-edited colour makes the theme custom. */
    fun withPalette(isDark: Boolean, palette: ThemePalette): ThemeConfig =
        copy(
            presetId = CUSTOM_PRESET_ID,
            dark = if (isDark) palette else dark,
            light = if (isDark) light else palette
        )

    companion object {
        const val PRESET_GRAPHITE_AMBER = "graphite-amber"
        const val CUSTOM_PRESET_ID = "custom"
    }
}

/** Three user-visible colours per mode. Everything else is derived in [com.example.ui.theme]. */
data class ThemePalette(
    val background: String,
    val card: String,
    val accent: String
) {
    companion object {
        val GRAPHITE_AMBER_DARK = ThemePalette("050100", "181412", "F0994B")
        val GRAPHITE_AMBER_LIGHT = ThemePalette("F1EADB", "FFFDF7", "B45309")
    }
}

enum class FontChoice(val label: String) {
    VAZIRMATN("Vazirmatn"),
    SYSTEM("System sans");

    companion object {
        fun parse(value: String?): FontChoice =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: VAZIRMATN
    }
}

enum class ThemePattern(val label: String) {
    NONE("None"),
    AURORA("Aurora"),
    DOTS("Dots"),
    CHECKER("Checker");

    companion object {
        fun parse(value: String?): ThemePattern =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: AURORA
    }
}

enum class CardStyle(val label: String) {
    FILLED("Filled"),
    OUTLINE("Outline"),
    SHADOWED("Shadowed"),
    GLASS("Matted glass");

    companion object {
        fun parse(value: String?): CardStyle =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: GLASS
    }
}

/** The reference ships seven connect-button shapes. */
enum class OrbShape(val label: String) {
    CIRCLE("Circle"),
    SQUIRCLE("Squircle"),
    HEXAGON("Hexagon"),
    SHIELD("Shield"),
    RING("Ring"),
    DIAMOND("Diamond"),
    TEARDROP("Teardrop");

    companion object {
        fun parse(value: String?): OrbShape =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: CIRCLE
    }
}

/** Two dock positions: the orb inside the bottom dock, or floating above it. */
enum class OrbDockPosition(val label: String) {
    BOTTOM("In dock"),
    FLOATING("Floating");

    companion object {
        fun parse(value: String?): OrbDockPosition =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: BOTTOM
    }
}

enum class ThemePreset(
    val id: String,
    val label: String,
    val dark: ThemePalette,
    val light: ThemePalette
) {
    GRAPHITE_AMBER(
        ThemeConfig.PRESET_GRAPHITE_AMBER, "Graphite amber",
        ThemePalette.GRAPHITE_AMBER_DARK, ThemePalette.GRAPHITE_AMBER_LIGHT
    ),
    OBSIDIAN_GOLD(
        "obsidian-gold", "Obsidian gold",
        ThemePalette("080604", "141110", "E8B45A"), ThemePalette("F4EFE4", "FFFCF3", "9A6A12")
    ),
    MIDNIGHT_VIOLET(
        "midnight-violet", "Midnight violet",
        ThemePalette("07060D", "12101C", "8B7CFF"), ThemePalette("EFECFB", "FFFDFF", "5B4BD6")
    ),
    FOREST_EMERALD(
        "forest-emerald", "Forest emerald",
        ThemePalette("040805", "0D1510", "3DD68C"), ThemePalette("E9F2EA", "FDFFFB", "14784B")
    ),
    OCEAN_CYAN(
        "ocean-cyan", "Ocean cyan",
        ThemePalette("03070A", "0B1418", "2FC7E8"), ThemePalette("E7F1F4", "FFFFFF", "0E7C93")
    ),
    PLUM_ROSE(
        "plum-rose", "Plum rose",
        ThemePalette("0A0508", "150C11", "F2668B"), ThemePalette("F6E9EE", "FFFCFD", "B72B57")
    ),
    SAND_BRONZE(
        "sand-bronze", "Sand bronze",
        ThemePalette("0B0805", "17110C", "C9A227"), ThemePalette("F3ECDC", "FFFDF6", "8A6D0F")
    ),
    SLATE_BLUE(
        "slate-blue", "Slate blue",
        ThemePalette("05070A", "0F1319", "7DA2FF"), ThemePalette("ECF0F6", "FFFFFF", "2F52C7")
    ),
    CARBON_LIME(
        "carbon-lime", "Carbon lime",
        ThemePalette("050604", "101208", "B9E03B"), ThemePalette("F0F3E6", "FFFFFF", "5C7A0A")
    ),
    PARCHMENT_PERSIMMON(
        "parchment-persimmon", "Parchment persimmon",
        ThemePalette("0C0806", "1A1310", "E8774B"), ThemePalette("F1EADB", "FFFDF7", "B45309")
    );

    fun applyTo(config: ThemeConfig, keepMode: ThemeMode = config.mode): ThemeConfig =
        config.copy(presetId = id, mode = keepMode, dark = dark, light = light)

    companion object {
        fun byId(id: String?): ThemePreset? = entries.firstOrNull { it.id == id?.trim() }

        fun apply(id: String?, config: ThemeConfig): ThemeConfig = byId(id)?.applyTo(config) ?: config
    }
}
