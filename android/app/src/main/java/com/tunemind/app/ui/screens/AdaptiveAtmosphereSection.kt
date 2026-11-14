package com.tunemind.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*

@Composable
fun AdaptiveAtmosphereSection(
    vm: TuneMindViewModel?
) {
    val themePreferences by vm?.themePreferences?.collectAsState()
        ?: remember { mutableStateOf(ThemePreferences()) }
    val activeThemeState by vm?.adaptiveThemeState?.collectAsState()
        ?: remember { mutableStateOf(AdaptiveThemeState.defaultForNight()) }

    val colors = TuneMindTheme.colors
    val cardBg = Color(0xFF0C1018).copy(alpha = 0.92f)
    val subCardBg = Color(0xFF121722)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Appearance",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )

        // Active Theme Summary Pill
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colors.primaryAccent)
                    )
                    Text(
                        text = "${activeThemeState.activePalette.name} • ${activeThemeState.timePeriod.label}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.primaryAccent, modifier = Modifier.size(14.dp))
                    Text(
                        text = if (themePreferences.behavior == ThemeBehavior.ADAPTIVE) "Auto" else "Pinned",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.primaryAccent
                    )
                }
            }
        }

        // 4 Studio Theme Presets
        val themes = listOf(
            Triple("morning", "Glacier Dawn", "#00E5FF"),
            Triple("afternoon", "Emerald", "#10B981"),
            Triple("evening", "Solar Ember", "#FFB000"),
            Triple("night", "Synaptic Violet", "#A855F7")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (i in 0..1) {
                val (paletteId, name, accentHex) = themes[i]
                val swatchColor = Color(android.graphics.Color.parseColor(accentHex))
                val isSelected = themePreferences.behavior == ThemeBehavior.FIXED && themePreferences.fixedPaletteId == paletteId

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) swatchColor.copy(alpha = 0.14f) else cardBg,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) swatchColor else Color.White.copy(alpha = 0.08f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            val nextBehavior = if (isSelected) ThemeBehavior.ADAPTIVE else ThemeBehavior.FIXED
                            vm?.updateThemePreferences(
                                themePreferences.copy(
                                    behavior = nextBehavior,
                                    fixedPaletteId = paletteId
                                )
                            )
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(swatchColor)
                        )
                        Text(
                            text = name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (i in 2..3) {
                val (paletteId, name, accentHex) = themes[i]
                val swatchColor = Color(android.graphics.Color.parseColor(accentHex))
                val isSelected = (themePreferences.behavior == ThemeBehavior.FIXED && themePreferences.fixedPaletteId == paletteId) ||
                    (themePreferences.behavior == ThemeBehavior.ADAPTIVE && i == 3)

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) swatchColor.copy(alpha = 0.14f) else cardBg,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) swatchColor else Color.White.copy(alpha = 0.08f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            val nextBehavior = if (isSelected && i != 3) ThemeBehavior.ADAPTIVE else ThemeBehavior.FIXED
                            vm?.updateThemePreferences(
                                themePreferences.copy(
                                    behavior = nextBehavior,
                                    fixedPaletteId = paletteId
                                )
                            )
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(swatchColor)
                        )
                        Text(
                            text = name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Custom Accent Color Picker
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = cardBg,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Accent Color",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = if (themePreferences.customAccentHex != null) "Custom" else "Automatic",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.primaryAccent
                    )
                }

                val customSwatches = listOf(
                    "#00E5FF" to "Cyan",
                    "#10B981" to "Emerald",
                    "#FF2A85" to "Rose",
                    "#A855F7" to "Violet",
                    "#FFB000" to "Amber",
                    "#3B82F6" to "Cobalt"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    customSwatches.forEach { (hex, _) ->
                        val selected = themePreferences.customAccentHex?.equals(hex, ignoreCase = true) == true
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(android.graphics.Color.parseColor(hex)))
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) Color.White else Color.White.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    vm?.setCustomThemeAccentHex(if (selected) null else hex)
                                }
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (themePreferences.customAccentHex == null) colors.primaryAccent.copy(alpha = 0.18f) else subCardBg,
                        border = BorderStroke(
                            1.dp,
                            if (themePreferences.customAccentHex == null) colors.primaryAccent else Color.White.copy(alpha = 0.12f)
                        ),
                        modifier = Modifier
                            .height(28.dp)
                            .clickable { vm?.setCustomThemeAccentHex(null) }
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Auto",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Match Song Mood",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = if (themePreferences.enableMoodAdaptation) "Ambient colors follow the song playing" else "Follows time of day only",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.62f)
                        )
                    }
                    Switch(
                        checked = themePreferences.enableMoodAdaptation,
                        onCheckedChange = { checked ->
                            vm?.updateThemePreferences(themePreferences.copy(enableMoodAdaptation = checked))
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Dynamic Studio Glow",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = if (themePreferences.enableEnergyAdaptation) "Glow responds gently to music energy" else "Steady ambient glow",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.62f)
                        )
                    }
                    Switch(
                        checked = themePreferences.enableEnergyAdaptation,
                        onCheckedChange = { checked ->
                            vm?.updateThemePreferences(themePreferences.copy(enableEnergyAdaptation = checked))
                        }
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Color Temperature",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = when {
                                themePreferences.observedWarmCoolBias < -0.1f -> "Cool"
                                themePreferences.observedWarmCoolBias > 0.1f -> "Warm"
                                else -> "Balanced"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.primaryAccent
                        )
                    }

                    Slider(
                        value = themePreferences.observedWarmCoolBias,
                        onValueChange = { newBias ->
                            vm?.updateThemePreferences(themePreferences.copy(observedWarmCoolBias = newBias))
                        },
                        valueRange = -1f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.primaryAccent,
                            activeTrackColor = colors.primaryAccent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.14f)
                        )
                    )
                }
            }
        }
    }
}
