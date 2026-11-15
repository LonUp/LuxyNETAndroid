package com.example.ui.screens

import com.example.i18n.tr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.CardStyle
import com.example.data.model.ConnectionState
import com.example.data.model.FontChoice
import com.example.data.model.OrbDockPosition
import com.example.data.model.OrbShape
import com.example.data.model.ThemeConfig
import com.example.data.model.ThemeMode
import com.example.data.model.ThemePalette
import com.example.data.model.ThemePattern
import com.example.data.model.ThemePreset
import com.example.data.model.ThemeCodec
import com.example.ui.components.LuxyCard
import com.example.ui.components.PowerOrb
import com.example.ui.components.SegmentedControl
import com.example.ui.theme.LocalLuxyColors
import com.example.ui.theme.LocalIsDarkTheme
import com.example.ui.theme.MonoFontFamily

/**
 * Theme editor (reference §4.2): presets, per-mode palette, type, radius, pattern, card style,
 * orb shape and position, motion and simple mode, plus the shareable `LX1.` theme code.
 *
 * Every control writes through [onChange] immediately — the reference previews live, and the
 * whole config is a single DataStore string, so there is no draft/save split to lose state in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(
    theme: ThemeConfig,
    onChange: (ThemeConfig) -> Unit,
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    val colors = LocalLuxyColors.current
    val isDark = LocalIsDarkTheme.current
    val palette = theme.palette(isDark)
    val clipboard = LocalClipboardManager.current
    val invalidCode = stringResource(R.string.appearance_code_invalid)
    val appliedCode = stringResource(R.string.appearance_code_applied)
    var importDraft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.appearance_back),
                    tint = colors.onBackground
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.appearance_title),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onBackground,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onChange(ThemeConfig()) }) {
                Text(stringResource(R.string.appearance_reset), color = colors.primary, fontSize = 12.sp)
            }
        }

        LuxyCard {
            Text(
                text = stringResource(R.string.appearance_mode),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
            val modeLabels = mapOf(
                ThemeMode.DARK to stringResource(R.string.appearance_mode_dark),
                ThemeMode.LIGHT to stringResource(R.string.appearance_mode_light),
                ThemeMode.SYSTEM to stringResource(R.string.appearance_mode_system)
            )
            SegmentedControl(
                options = ThemeMode.entries,
                selected = theme.mode,
                onSelect = { mode -> onChange(theme.copy(mode = mode)) },
                label = { modeLabels.getValue(it) }
            )
            Text(
                text = stringResource(R.string.appearance_presets),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemePreset.entries.forEach { preset ->
                    val selected = theme.presetId == preset.id
                    val shown = if (isDark) preset.dark else preset.light
                    Surface(
                        modifier = Modifier.clickable { onChange(preset.applyTo(theme)) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) colors.primaryContainer else colors.surfaceVariant,
                        border = BorderStroke(
                            if (selected) 1.5.dp else 1.dp,
                            if (selected) colors.primary else colors.outline
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Swatch(shown.background, 12.dp)
                            Swatch(shown.accent, 12.dp)
                            Swatch(shown.card, 12.dp)
                            Text(
                                text = tr(preset.label),
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = colors.onSurface
                            )
                        }
                    }
                }
            }
        }

        LuxyCard {
            Text(
                text = stringResource(
                    if (isDark) R.string.appearance_palette_dark else R.string.appearance_palette_light
                ),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface
            )
            val swatches = remember(isDark) { paletteSwatches(isDark) }
            PaletteRow(
                label = stringResource(R.string.appearance_color_background),
                value = palette.background,
                swatches = swatches,
                onPick = { onChange(theme.withPalette(isDark, palette.copy(background = it))) }
            )
            PaletteRow(
                label = stringResource(R.string.appearance_color_card),
                value = palette.card,
                swatches = swatches,
                onPick = { onChange(theme.withPalette(isDark, palette.copy(card = it))) }
            )
            PaletteRow(
                label = stringResource(R.string.appearance_color_accent),
                value = palette.accent,
                swatches = swatches,
                onPick = { onChange(theme.withPalette(isDark, palette.copy(accent = it))) }
            )
            Text(
                text = stringResource(R.string.appearance_palette_note),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }

        if (!theme.simpleMode) {
            LuxyCard {
                Text(
                    text = stringResource(R.string.appearance_type),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant
                )
                SegmentedControl(
                    options = FontChoice.entries,
                    selected = theme.font,
                    onSelect = { font -> onChange(theme.copy(font = font)) },
                    label = { it.label }
                )
                ScaledSlider(
                    label = stringResource(R.string.appearance_font_size),
                    value = theme.fontScale,
                    range = 0.85f..1.30f,
                    onCommit = { onChange(theme.copy(fontScale = it)) }
                )
                SwitchRow(
                    title = stringResource(R.string.appearance_bold),
                    checked = theme.boldText,
                    onCheckedChange = { onChange(theme.copy(boldText = it)) }
                )
            }

            LuxyCard {
                Text(
                    text = stringResource(R.string.appearance_shape),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant
                )
                ScaledSlider(
                    label = stringResource(R.string.appearance_radius),
                    value = theme.radiusScale,
                    range = 0.6f..1.6f,
                    onCommit = { onChange(theme.copy(radiusScale = it)) }
                )
                Text(
                    text = stringResource(R.string.appearance_pattern),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant
                )
                SegmentedControl(
                    options = ThemePattern.entries,
                    selected = theme.pattern,
                    onSelect = { onChange(theme.copy(pattern = it)) },
                    label = { it.label }
                )
                Text(
                    text = stringResource(R.string.appearance_card_style),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant
                )
                SegmentedControl(
                    options = CardStyle.entries,
                    selected = theme.cardStyle,
                    onSelect = { onChange(theme.copy(cardStyle = it)) },
                    label = { it.label }
                )
            }

            LuxyCard {
                Text(
                    text = stringResource(R.string.appearance_orb_shape),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OrbShape.entries.forEach { shape ->
                        val selected = theme.orbShape == shape
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .border(
                                    BorderStroke(
                                        if (selected) 2.dp else 0.dp,
                                        if (selected) colors.primary else Color.Transparent
                                    ),
                                    CircleShape
                                )
                                .padding(2.dp)
                        ) {
                            PowerOrb(
                                state = ConnectionState.DISCONNECTED,
                                shape = shape,
                                label = "",
                                onClick = { onChange(theme.copy(orbShape = shape)) },
                                size = 40.dp
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.appearance_orb_position),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant
                )
                SegmentedControl(
                    options = OrbDockPosition.entries,
                    selected = theme.orbDock,
                    onSelect = { onChange(theme.copy(orbDock = it)) },
                    label = { it.label }
                )
            }

            LuxyCard {
                SwitchRow(
                    title = stringResource(R.string.appearance_motion),
                    subtitle = stringResource(R.string.appearance_motion_note),
                    checked = theme.motionEnabled,
                    onCheckedChange = { onChange(theme.copy(motionEnabled = it)) }
                )
                SwitchRow(
                    title = stringResource(R.string.appearance_simple),
                    subtitle = stringResource(R.string.appearance_simple_note),
                    checked = theme.simpleMode,
                    onCheckedChange = { onChange(theme.copy(simpleMode = it)) }
                )
            }
        } else {
            LuxyCard {
                SwitchRow(
                    title = stringResource(R.string.appearance_simple),
                    subtitle = stringResource(R.string.appearance_simple_note),
                    checked = theme.simpleMode,
                    onCheckedChange = { onChange(theme.copy(simpleMode = it)) }
                )
            }
        }

        LuxyCard {
            Text(
                text = stringResource(R.string.appearance_share),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
            val code = remember(theme) { ThemeCodec.encode(theme) }
            Text(
                text = code,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                color = colors.onSurfaceVariant,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedChipButton(
                    icon = Icons.Rounded.ContentCopy,
                    label = stringResource(R.string.appearance_copy_code)
                ) {
                    clipboard.setText(AnnotatedString(code))
                    onMessage(code)
                }
                OutlinedChipButton(
                    icon = Icons.Rounded.ContentPaste,
                    label = stringResource(R.string.appearance_paste_code)
                ) {
                    val pasted = clipboard.getText()?.text?.trim().orEmpty()
                    importDraft = pasted
                    val decoded = ThemeCodec.decode(pasted)
                    if (decoded != null) {
                        onChange(decoded)
                    } else {
                        onMessage(invalidCode)
                    }
                }
            }
            OutlinedTextField(
                value = importDraft,
                onValueChange = { importDraft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.appearance_import_code), fontSize = 12.sp) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done
                )
            )
            TextButton(
                onClick = {
                    val decoded = ThemeCodec.decode(importDraft)
                    if (decoded != null) {
                        onChange(decoded)
                        onMessage(appliedCode)
                    } else {
                        onMessage(invalidCode)
                    }
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.appearance_apply_code), color = colors.primary, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.size(12.dp))
    }
}

@Composable
private fun PaletteRow(
    label: String,
    value: String,
    swatches: List<String>,
    onPick: (String) -> Unit
) {
    val colors = LocalLuxyColors.current
    var draft by remember(value) { mutableStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Swatch(value, 18.dp, border = colors.line2)
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { raw ->
                    val cleaned = raw.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6)
                    draft = cleaned
                    if (cleaned.length == 6) onPick(cleaned)
                },
                modifier = Modifier.width(108.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done
                )
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            swatches.forEach { hex ->
                val selected = hex.equals(value, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(parseHex(hex, colors.surfaceVariant))
                        .border(
                            BorderStroke(
                                if (selected) 2.dp else 1.dp,
                                if (selected) colors.primary else colors.line2
                            ),
                            CircleShape
                        )
                        .clickable { onPick(hex) }
                )
            }
        }
    }
}

@Composable
private fun Swatch(hex: String, size: androidx.compose.ui.unit.Dp, border: Color? = null) {
    val colors = LocalLuxyColors.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(parseHex(hex, colors.surfaceVariant))
            .border(BorderStroke(1.dp, border ?: Color.White.copy(alpha = 0.20f)), CircleShape)
    )
}

@Composable
private fun ScaledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit
) {
    val colors = LocalLuxyColors.current
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
            Text(
                text = tr("${(draft * 100).toInt()}%"),
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                color = colors.onSurfaceVariant
            )
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(draft.coerceIn(range.start, range.endInclusive)) },
            valueRange = range
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    val colors = LocalLuxyColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Start
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun OutlinedChipButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val colors = LocalLuxyColors.current
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = colors.surfaceVariant,
        border = BorderStroke(1.dp, colors.outline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(15.dp))
            Text(label, fontSize = 11.sp, color = colors.onSurface)
        }
    }
}

/** Candidate colours: every preset's three roles for the current mode, deduped, plus extremes. */
private fun paletteSwatches(isDark: Boolean): List<String> {
    val fromPresets = ThemePreset.entries.flatMap { preset ->
        val palette: ThemePalette = if (isDark) preset.dark else preset.light
        listOf(palette.background, palette.card, palette.accent)
    }
    return (fromPresets + listOf("FFFFFF", "000000")).map { it.uppercase() }.distinct()
}

internal fun parseHex(hex: String, fallback: Color): Color {
    val cleaned = hex.removePrefix("#")
    if (cleaned.length != 6) return fallback
    val value = cleaned.toLongOrNull(16) ?: return fallback
    return Color(0xFF000000L or value)
}
