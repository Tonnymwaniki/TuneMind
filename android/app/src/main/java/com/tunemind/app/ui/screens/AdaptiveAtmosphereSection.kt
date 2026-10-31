package com.tunemind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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

    var previewMood by remember { mutableStateOf<MusicalMood?>(null) }
    var previewPaletteId by remember { mutableStateOf<String?>(null) }

    val colors = TuneMindTheme.colors

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.primaryAccent.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Palette,
                            contentDescription = null,
                            tint = colors.primaryAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "ADAPTIVE PERSONAL ATMOSPHERE",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryAccent,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Atmosphere & Theme Engine",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryText
                        )
                    }
                }

                // Active Time Badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.elevatedSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.border)
                ) {
                    Text(
                        text = activeThemeState.timePeriod.label.uppercase(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.highlight
                    )
                }
            }

            Text(
                text = "Intelligence that feels personal. Adapts seamlessly to time of day, musical mood, and listening energy without losing TuneMind's core midnight-indigo identity.",
                fontSize = 11.sp,
                color = colors.secondaryText,
                lineHeight = 16.sp
            )

            // Current Active Atmosphere Swatches
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.elevatedSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Active Foundational Palette: ${activeThemeState.activePalette.name}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.primaryText
                            )
                            Text(
                                "Movement: ${activeThemeState.visualMovement} • Mood: ${activeThemeState.musicMood.label}",
                                fontSize = 10.sp,
                                color = colors.secondaryText
                            )
                        }
                    }

                    // 5-Color Atmosphere Swatch Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(colors.primaryBackground))
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(colors.secondaryBackground))
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(colors.cardSurface))
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(colors.primaryAccent))
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(colors.secondaryAccent))
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(colors.highlight))
                    }
                }
            }

            Divider(color = colors.border)

            // 1. THEME BEHAVIOR
            Text(
                "Theme Behavior",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = colors.primaryText
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeBehavior.values().forEach { behavior ->
                    val isSelected = themePreferences.behavior == behavior
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) colors.primaryAccent.copy(alpha = 0.15f) else colors.elevatedSurface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) colors.primaryAccent else colors.border
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm?.updateThemePreferences(themePreferences.copy(behavior = behavior))
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    vm?.updateThemePreferences(themePreferences.copy(behavior = behavior))
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = colors.primaryAccent,
                                    unselectedColor = colors.secondaryText
                                ),
                                modifier = Modifier.size(18.dp)
                            )
                            Column {
                                Text(
                                    text = behavior.label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) colors.primaryText else colors.secondaryText
                                )
                                Text(
                                    text = behavior.description,
                                    fontSize = 10.sp,
                                    color = colors.secondaryText
                                )
                            }
                        }
                    }
                }
            }

            // Fixed Palette Picker (visible when FIXED behavior is selected)
            if (themePreferences.behavior == ThemeBehavior.FIXED) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Selected Fixed Palette",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primaryText
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TuneMindPalettes.AllPalettes.forEach { palette ->
                        val isCurrent = themePreferences.fixedPaletteId == palette.id
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = palette.cardSurface,
                            border = androidx.compose.foundation.BorderStroke(
                                if (isCurrent) 2.dp else 1.dp,
                                if (isCurrent) palette.primaryAccent else palette.border
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    vm?.updateThemePreferences(themePreferences.copy(fixedPaletteId = palette.id))
                                }
                        ) {
                            Column(
                                modifier = Modifier.padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(palette.primaryAccent)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = palette.name,
                                    fontSize = 9.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = palette.primaryText
                                )
                            }
                        }
                    }
                }
            }

            Divider(color = colors.border)

            // 2. MUSICAL MOOD & ENERGY ADAPTATION
            Text(
                "Musical Atmosphere Layer",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = colors.primaryText
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Musical Mood Adaptation", fontSize = 12.sp, color = colors.primaryText)
                    Text("Gently shifts accent tones with track characteristics", fontSize = 10.sp, color = colors.secondaryText)
                }
                Switch(
                    checked = themePreferences.enableMoodAdaptation,
                    onCheckedChange = {
                        vm?.updateThemePreferences(themePreferences.copy(enableMoodAdaptation = it))
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.primaryAccent,
                        checkedTrackColor = colors.primaryAccent.copy(alpha = 0.5f)
                    )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Musical Energy Dynamics", fontSize = 12.sp, color = colors.primaryText)
                    Text("Modulates visual movement & subtle gradient depth", fontSize = 10.sp, color = colors.secondaryText)
                }
                Switch(
                    checked = themePreferences.enableEnergyAdaptation,
                    onCheckedChange = {
                        vm?.updateThemePreferences(themePreferences.copy(enableEnergyAdaptation = it))
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.primaryAccent,
                        checkedTrackColor = colors.primaryAccent.copy(alpha = 0.5f)
                    )
                )
            }

            // Visual Intensity Selector
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Visual Adaptation Intensity",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primaryText
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VisualIntensity.values().forEach { intensity ->
                        val isSelected = themePreferences.visualIntensity == intensity
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) colors.primaryAccent else colors.elevatedSurface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    vm?.updateThemePreferences(themePreferences.copy(visualIntensity = intensity))
                                }
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = intensity.label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) colors.primaryBackground else colors.primaryText
                                )
                            }
                        }
                    }
                }
            }

            Divider(color = colors.border)

            // 3. PALETTE & MOOD PREVIEW LAB
            Text(
                "Palette & Mood Preview",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = colors.primaryText
            )

            // Time Periods Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TuneMindPalettes.AllPalettes.forEach { p ->
                    val isPreviewing = previewPaletteId == p.id
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = p.cardSurface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isPreviewing) p.primaryAccent else p.border
                        ),
                        modifier = Modifier
                            .clickable {
                                previewPaletteId = if (previewPaletteId == p.id) null else p.id
                            }
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(p.primaryAccent))
                            Text(p.name, fontSize = 10.sp, color = p.primaryText)
                        }
                    }
                }
            }

            // Mood Accents Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TuneMindPalettes.MoodProfiles.values.forEach { mp ->
                    val isMoodPreviewing = previewMood == mp.mood
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.elevatedSurface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isMoodPreviewing) mp.moodAccent else colors.border
                        ),
                        modifier = Modifier
                            .clickable {
                                previewMood = if (previewMood == mp.mood) null else mp.mood
                            }
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(mp.moodAccent))
                            Text(mp.mood.label, fontSize = 10.sp, color = colors.primaryText)
                        }
                    }
                }
            }

            Divider(color = colors.border)

            // 4. ACCESSIBILITY & CONTRAST
            Text(
                "Accessibility & Contrast",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = colors.primaryText
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Reduced Motion", fontSize = 12.sp, color = colors.primaryText)
                    Text("Instant palette transitions (0ms) and static gradients", fontSize = 10.sp, color = colors.secondaryText)
                }
                Switch(
                    checked = themePreferences.reducedMotion,
                    onCheckedChange = {
                        vm?.updateThemePreferences(themePreferences.copy(reducedMotion = it))
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.primaryAccent,
                        checkedTrackColor = colors.primaryAccent.copy(alpha = 0.5f)
                    )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("High Contrast Mode", fontSize = 12.sp, color = colors.primaryText)
                    Text("Pure white text and high-contrast card surface outlines", fontSize = 10.sp, color = colors.secondaryText)
                }
                Switch(
                    checked = themePreferences.highContrast,
                    onCheckedChange = {
                        vm?.updateThemePreferences(themePreferences.copy(highContrast = it))
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.primaryAccent,
                        checkedTrackColor = colors.primaryAccent.copy(alpha = 0.5f)
                    )
                )
            }

            // 5. RESET BUTTON
            OutlinedButton(
                onClick = {
                    vm?.resetThemePreferencesToDefaults()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.border)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.secondaryText)
                Spacer(Modifier.width(6.dp))
                Text("Reset Theme to TuneMind Defaults", fontSize = 11.sp, color = colors.secondaryText)
            }
        }
    }
}
