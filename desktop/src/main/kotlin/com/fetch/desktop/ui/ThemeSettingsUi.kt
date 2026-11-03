package com.fetch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.AppState
import com.fetch.desktop.data.AppearanceSettings
import com.fetch.tv.domain.model.AppFont
import com.fetch.tv.domain.model.AppTheme
import com.fetch.tv.domain.model.CustomThemeColors
import com.fetch.tv.domain.model.formatHexColor
import com.fetch.tv.domain.model.parseHexColor

private fun AppState.updateAppearance(change: (AppearanceSettings) -> AppearanceSettings) =
    updateSettings { it.copy(appearance = change(it.appearance)) }

/**
 * Settings › Appearance's theme part, as the TV's ThemeSettingsScreen: the colour themes with
 * Custom, Increase contrast, AMOLED Mode and Pure Black Surfaces, and the app font. Essential mode
 * keeps the theme and Increase contrast, as on TV.
 */
@Composable
fun ThemeSettingsContent(state: AppState) {
    val essential = state.settings.experience.isEssential
    val current = state.settings.appTheme
    val look = state.settings.appearance
    var editing by remember { mutableStateOf(false) }

    PageHeader("Appearance", subtitle = if (essential) "Choose your color theme" else "Choose your color theme and font")
    SettingsSection("Color Theme") {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(20.dp)
        ) {
            // Custom first, as the TV lists it.
            ThemeSwatch(AppTheme.CUSTOM, selected = current == AppTheme.CUSTOM, colors = look.customColors) {
                if (current == AppTheme.CUSTOM) editing = true else state.updateSettings { it.copy(theme = AppTheme.CUSTOM.name) }
            }
            AppTheme.entries.filter { it != AppTheme.CUSTOM }.forEach { theme ->
                ThemeSwatch(theme, selected = theme == current) { state.updateSettings { it.copy(theme = theme.name) } }
            }
        }
        if (current == AppTheme.CUSTOM) {
            SettingsRow(
                "Custom",
                "Your own color: ${look.customColors.colors.distinct().joinToString(", ", transform = ::formatHexColor)}",
                onClick = { editing = true }
            ) { SecondaryButton("Edit", onClick = { editing = true }) }
        }
        SettingsToggle("Increase contrast", "Brighter secondary text and borders", look.highContrast) { v ->
            state.updateAppearance { it.copy(highContrast = v) }
        }
        if (!essential) {
            SettingsToggle("AMOLED Mode", "Use pure black for app backgrounds", look.amoledMode) { v ->
                // Turning it off turns Pure Black Surfaces off too, as on TV.
                state.updateAppearance { it.copy(amoledMode = v, amoledSurfaces = it.amoledSurfaces && v) }
            }
            if (look.amoledMode) {
                SettingsToggle("Pure Black Surfaces", "Also make cards, panels, and containers pure black", look.amoledSurfaces) { v ->
                    state.updateAppearance { it.copy(amoledSurfaces = v) }
                }
            }
        }
    }
    if (!essential) {
        SettingsSection("Font") {
            SettingsChoice(
                "App Font",
                "Choose your preferred font",
                look.appFont,
                AppFont.entries.map { DropdownOption(it, it.displayName) }
            ) { font -> state.updateAppearance { it.copy(font = font.name) } }
        }
    }

    if (editing) {
        CustomThemeEditorDialog(
            initial = look.customColors,
            onDismiss = { editing = false },
            onSave = { colors ->
                editing = false
                // Saving picks the Custom theme, as on TV.
                state.updateSettings { it.copy(theme = AppTheme.CUSTOM.name, appearance = it.appearance.copy(customThemeColors = colors.encode())) }
            }
        )
    }
}

@Composable
private fun ThemeSwatch(theme: AppTheme, selected: Boolean, colors: CustomThemeColors? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)
    val accent = if (colors != null) customAccent(colors) else accentFor(theme)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .width(128.dp)
            .clip(shape)
            .background(FetchPalette.SurfaceRaised)
            .border(
                if (selected || hovered) 2.dp else 1.dp,
                when {
                    hovered -> Color.White
                    selected -> accent
                    else -> FetchPalette.SurfaceHigh
                },
                shape
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 18.dp)
    ) {
        val fill = if (colors != null && !colors.isSolid) {
            Modifier.background(Brush.sweepGradient(colors.colors.map { Color(it or 0xFF000000.toInt()) } + Color(colors.first or 0xFF000000.toInt())))
        } else {
            Modifier.background(accent)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).then(fill))
        Text(theme.displayName, color = if (selected) FetchPalette.TextPrimary else FetchPalette.TextSecondary, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** The TV's swatches in the colour picker. */
private val PresetColors = listOf(0xFF6B6B, 0xFFB37A, 0xFFD45C, 0x22D37C, 0x4DE3FF, 0x3185F5, 0xB75AFF, 0xEC70A9, 0xFFFFFF)

/**
 * The TV's CustomThemeEditor, ThemeColorPicker and HexColorDialog in one dialog: a single colour
 * (or, for a gradient the TV saved, each of its three), picked from swatches, hue, saturation and
 * brightness sliders or a hex code, with a preview. Desktop draws with the middle colour.
 */
@Composable
fun CustomThemeEditorDialog(initial: CustomThemeColors, onDismiss: () -> Unit, onSave: (CustomThemeColors) -> Unit) {
    var gradient by remember { mutableStateOf(!initial.isSolid) }
    var draft by remember { mutableStateOf(initial) }
    var slot by remember { mutableIntStateOf(if (initial.isSolid) 1 else 0) }
    val colors = if (gradient) draft else CustomThemeColors.solid(draft.second)
    val editing = if (gradient) slot else 1
    val set: (Int) -> Unit = { c -> draft = if (gradient) draft.withColor(editing, c) else CustomThemeColors.solid(c) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(if (gradient) "Your custom gradient" else "Your custom color", color = FetchPalette.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.width(500.dp)) {
                Text(
                    if (gradient) "Choose three colors for your theme. Select a color to edit it." else "Choose a color for your theme.",
                    color = FetchPalette.TextSecondary
                )
                // A gradient comes from the TV (a supporter theme there); it can be kept or made one colour.
                if (!initial.isSolid) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeChip("Gradient", gradient) { gradient = true }
                        ModeChip("Single color", !gradient) { gradient = false }
                    }
                }
                if (gradient) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        (0..2).forEach { i ->
                            ColorSlot("Color ${i + 1}", draft.colors[i], selected = slot == i) { slot = i }
                        }
                    }
                }
                // Each colour slot gets its own sliders.
                androidx.compose.runtime.key(editing, gradient) { ThemeColorPicker(colors.colors[editing], onChange = set) }
                CustomThemePreview(colors)
            }
        },
        confirmButton = { PrimaryButton("Save theme", icon = null, onClick = { onSave(colors) }) },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) }
    )
}

@Composable
private fun ModeChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 14.sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        color = if (on) FetchPalette.OnAccent else FetchPalette.TextPrimary,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (on) FetchPalette.Accent else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

@Composable
private fun ColorSlot(label: String, color: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else FetchPalette.Outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Color(color or 0xFF000000.toInt())))
        Text(label, color = FetchPalette.TextPrimary, fontSize = 14.sp)
    }
}

/** Swatches, hue / saturation / brightness sliders and a hex field for one colour. */
@Composable
fun ThemeColorPicker(color: Int, onChange: (Int) -> Unit) {
    val hsb = remember(color) { java.awt.Color.RGBtoHSB((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF, null) }
    // Hue and saturation are kept while brightness is 0 (or saturation 0), where RGB loses them.
    var hue by remember { mutableStateOf(hsb[0]) }
    var sat by remember { mutableStateOf(hsb[1]) }
    var bri by remember { mutableStateOf(hsb[2]) }
    fun emit(h: Float = hue, s: Float = sat, b: Float = bri) {
        hue = h; sat = s; bri = b
        onChange(java.awt.Color.HSBtoRGB(h, s, b) and 0xFFFFFF)
    }
    fun adopt(c: Int) {
        val v = java.awt.Color.RGBtoHSB((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, null)
        if (v[2] > 0f && v[1] > 0f) hue = v[0]
        if (v[2] > 0f) sat = v[1]
        bri = v[2]
        onChange(c)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PresetColors.forEach { preset ->
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Color(preset or 0xFF000000.toInt()))
                        .border(if (preset == color) 3.dp else 1.dp, if (preset == color) Color.White else FetchPalette.Outline, CircleShape)
                        .clickable { adopt(preset) }
                        .pointerHoverIcon(PointerIcon.Hand)
                )
            }
        }
        ChannelSlider("Hue", hue, Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) })) { emit(h = it) }
        ChannelSlider("Saturation", sat, Brush.horizontalGradient(listOf(Color.hsv(hue * 360f, 0f, bri), Color.hsv(hue * 360f, 1f, bri)))) { emit(s = it) }
        ChannelSlider("Brightness", bri, Brush.horizontalGradient(listOf(Color.Black, Color.hsv(hue * 360f, sat, 1f)))) { emit(b = it) }
        HexColorField(color, onChange = ::adopt)
    }
}

@Composable
private fun ChannelSlider(label: String, value: Float, track: Brush, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = FetchPalette.TextSecondary, fontSize = 14.sp, modifier = Modifier.width(84.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(track))
            Slider(
                value = value,
                onValueChange = onChange,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                )
            )
        }
    }
}

/** "Enter hex code": six hex digits, used once they read as a colour. */
@Composable
fun HexColorField(color: Int, onChange: (Int) -> Unit) {
    var text by remember(color) { mutableStateOf(formatHexColor(color).removePrefix("#")) }
    val valid = parseHexColor(text) != null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Hex color", color = FetchPalette.TextSecondary, fontSize = 14.sp, modifier = Modifier.width(84.dp))
        Text("#", color = FetchPalette.TextMuted, fontSize = 15.sp)
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val cleaned = raw.removePrefix("#").filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.take(6).uppercase()
                text = cleaned
                parseHexColor(cleaned)?.let(onChange)
            },
            singleLine = true,
            textStyle = TextStyle(color = FetchPalette.TextPrimary, fontSize = 15.sp),
            cursorBrush = SolidColor(FetchPalette.Accent),
            modifier = Modifier.width(110.dp).clip(RoundedCornerShape(8.dp)).background(FetchPalette.Background)
                .border(1.dp, if (valid) FetchPalette.Outline else FetchPalette.Error, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
        if (!valid) Text("Six characters, 0–9 and A–F", color = FetchPalette.TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun CustomThemePreview(colors: CustomThemeColors) {
    val accent = customAccent(colors)
    val surfaces = surfaceColors(AppTheme.CUSTOM, AppearanceSettings(customThemeColors = colors.encode()))
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(surfaces.background).padding(16.dp)
    ) {
        Text("PREVIEW", style = OverlineStyle, color = surfaces.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Play",
                color = if (accent.luminance() > 0.5f) Color.Black else Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(accent).padding(horizontal = 22.dp, vertical = 10.dp)
            )
            if (!colors.isSolid) {
                Box(
                    Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp))
                        .background(Brush.horizontalGradient(colors.colors.map { Color(it or 0xFF000000.toInt()) }))
                )
            }
        }
        if (customAccentAdjusted(colors)) {
            Text("Lightened a little so buttons stay visible on dark backgrounds", color = surfaces.textSecondary, fontSize = 13.sp)
        }
    }
}

