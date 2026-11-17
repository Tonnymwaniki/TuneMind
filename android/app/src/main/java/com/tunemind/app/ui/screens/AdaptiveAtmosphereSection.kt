package com.tunemind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
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

    val themes = listOf(
        Triple("morning", "Glacier", "#38BDF8"),
        Triple("afternoon", "Emerald", "#10B981"),
        Triple("evening", "Ember", "#F59E0B"),
        Triple("night", "Violet", "#A78BFA")
    )

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        StudioSectionHeader(
            title = "Appearance",
            actionLabel = if (themePreferences.behavior == ThemeBehavior.ADAPTIVE) {
                "Auto · ${activeThemeState.activePalette.name}"
            } else {
                "Use Auto"
            },
            onActionClick = {
                vm?.updateThemePreferences(
                    themePreferences.copy(behavior = ThemeBehavior.ADAPTIVE)
                )
            }
        )
        Spacer(modifier = Modifier.height(6.dp))
        StudioHairlineDivider()

        // Studio presets
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            themes.forEach { (paletteId, name, accentHex) ->
                val swatchColor = Color(android.graphics.Color.parseColor(accentHex))
                val isSelected = themePreferences.behavior == ThemeBehavior.FIXED && themePreferences.fixedPaletteId == paletteId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) swatchColor.copy(alpha = 0.18f) else colors.secondaryBackground.copy(alpha = 0.72f))
                        .border(
                            1.dp,
                            if (isSelected) swatchColor.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.08f),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            val nextBehavior = if (isSelected) ThemeBehavior.ADAPTIVE else ThemeBehavior.FIXED
                            vm?.updateThemePreferences(
                                themePreferences.copy(
                                    behavior = nextBehavior,
                                    fixedPaletteId = paletteId
                                )
                            )
                        }
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(swatchColor)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = name,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) Color(0xFFF8FAFC) else Color.White.copy(alpha = 0.72f),
                        maxLines = 1
                    )
                }
            }
        }

        // Accent Swatches
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Accent Color",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFF8FAFC)
                )
                Text(
                    text = if (themePreferences.customAccentHex != null) "Custom" else "Automatic",
                    fontSize = 12.sp,
                    color = colors.primaryAccent
                )
            }

            val customSwatches = listOf(
                "#00E5FF",
                "#10B981",
                "#FF2A85",
                "#A855F7",
                "#FFB000",
                "#3B82F6"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                customSwatches.forEach { hex ->
                    val selected = themePreferences.customAccentHex?.equals(hex, ignoreCase = true) == true
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(android.graphics.Color.parseColor(hex)))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) Color.White else Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                vm?.setCustomThemeAccentHex(if (selected) null else hex)
                            }
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (themePreferences.customAccentHex == null) colors.primaryAccent.copy(alpha = 0.18f)
                            else colors.secondaryBackground.copy(alpha = 0.72f)
                        )
                        .border(
                            1.dp,
                            if (themePreferences.customAccentHex == null) colors.primaryAccent else Color.White.copy(alpha = 0.12f),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { vm?.setCustomThemeAccentHex(null) }
                        .padding(horizontal = 10.dp)
                ) {
                    Text(
                        text = "Auto",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF8FAFC)
                    )
                }
            }
        }
        StudioHairlineDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    vm?.updateThemePreferences(
                        themePreferences.copy(enableMoodAdaptation = !themePreferences.enableMoodAdaptation)
                    )
                }
                .padding(vertical = 14.dp, horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = "Match Song Mood",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFF8FAFC)
                )
                Text(
                    text = if (themePreferences.enableMoodAdaptation) "Colors follow the playing song" else "Follows time of day only",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.56f)
                )
            }
            Switch(
                checked = themePreferences.enableMoodAdaptation,
                onCheckedChange = { checked ->
                    vm?.updateThemePreferences(themePreferences.copy(enableMoodAdaptation = checked))
                }
            )
        }
        StudioHairlineDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    vm?.updateThemePreferences(
                        themePreferences.copy(enableEnergyAdaptation = !themePreferences.enableEnergyAdaptation)
                    )
                }
                .padding(vertical = 14.dp, horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = "Dynamic Glow",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFF8FAFC)
                )
                Text(
                    text = if (themePreferences.enableEnergyAdaptation) "Responds gently to music energy" else "Steady ambient lighting",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.56f)
                )
            }
            Switch(
                checked = themePreferences.enableEnergyAdaptation,
                onCheckedChange = { checked ->
                    vm?.updateThemePreferences(themePreferences.copy(enableEnergyAdaptation = checked))
                }
            )
        }
        StudioHairlineDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Color Temperature",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFF8FAFC)
                )
                Text(
                    text = when {
                        themePreferences.observedWarmCoolBias < -0.1f -> "Cool"
                        themePreferences.observedWarmCoolBias > 0.1f -> "Warm"
                        else -> "Balanced"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
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
        StudioHairlineDivider()
    }
}
