package com.tunemind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
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

    val colors = TuneMindTheme.colors

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. HARDWARE BUS CONSOLE HEADER
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = colors.secondaryBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(DeepObsidianAmber.Success)
                    )
                    Text(
                        text = "HARDWARE BUS // CONSOLE CONFIG",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryText,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    text = "DSP LINK: SYNCED",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = DeepObsidianAmber.SecondaryAccent
                )
            }
        }

        // Section Title
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "STUDIO SWITCHBOARD // ADAPTIVE ATMOSPHERE",
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = colors.primaryText,
                letterSpacing = 0.5.sp
            )
            Text(
                text = "Physical channel strip routing and acoustic luminance calibration.",
                fontSize = 12.sp,
                color = colors.secondaryText,
                lineHeight = 17.sp
            )
        }

        // Active State Pill / Telemetry Bar
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = colors.secondaryBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(colors.primaryAccent)
                    )
                    Text(
                        text = "${activeThemeState.timePeriod.label.uppercase()} // ACTIVE CHROMA",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryText,
                        letterSpacing = 0.8.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.Bedtime, contentDescription = null, tint = colors.secondaryAccent, modifier = Modifier.size(13.dp))
                    Text(
                        text = "CIRCADIAN LOCK",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryAccent
                    )
                }
            }
        }

        // 2. CHROMA ENGINE 4-CHANNEL MATRIX (Tactile Channel Strip Matrix)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CHROMA ENGINE // HARDWARE PALETTE SELECTORS",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.secondaryText,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "4-CH MATRIX",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.primaryAccent
                )
            }

            val channels = listOf(
                Triple("CH 01 // DAWN", "Nixie Gold", "#FFA800"),
                Triple("CH 02 // NOON", "Acid Lime", "#D4F01A"),
                Triple("CH 03 // DUSK", "Sunset Amber", "#FF9400"),
                Triple("CH 04 // NIGHT", "Vacuum Tube", "#FFA800")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // CH 01 & CH 02
                for (i in 0..1) {
                    val (chLabel, name, accentHex) = channels[i]
                    val paletteId = when (i) {
                        0 -> "morning"
                        1 -> "afternoon"
                        else -> "night"
                    }
                    val isSelected = themePreferences.behavior == ThemeBehavior.FIXED && themePreferences.fixedPaletteId == paletteId

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) colors.cardSurface else colors.secondaryBackground,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) colors.primaryAccent else colors.border.copy(alpha = 0.4f)
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
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = chLabel,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.secondaryText
                                )
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) colors.primaryAccent else colors.border)
                                )
                            }

                            // Swatch Bar
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(18.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(android.graphics.Color.parseColor(accentHex)))
                            )

                            Text(
                                text = name,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.primaryText
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // CH 03 & CH 04
                for (i in 2..3) {
                    val (chLabel, name, accentHex) = channels[i]
                    val paletteId = when (i) {
                        2 -> "evening"
                        3 -> "night"
                        else -> "night"
                    }
                    val isSelected = (themePreferences.behavior == ThemeBehavior.FIXED && themePreferences.fixedPaletteId == paletteId) ||
                            (themePreferences.behavior == ThemeBehavior.ADAPTIVE && i == 3)

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) colors.cardSurface else colors.secondaryBackground,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) colors.primaryAccent else colors.border.copy(alpha = 0.4f)
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
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = chLabel,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.secondaryText
                                )
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) colors.primaryAccent else colors.border)
                                )
                            }

                            // Swatch Bar
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(18.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(android.graphics.Color.parseColor(accentHex)))
                            )

                            Text(
                                text = name,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.primaryText
                            )
                        }
                    }
                }
            }

            // Custom Studio Accent Swatches (also voice-controllable)
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CUSTOM ACCENT OVERRIDE",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.secondaryText,
                    letterSpacing = 1.sp
                )
                Text(
                    text = themePreferences.customAccentHex?.uppercase() ?: "AUTO",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.primaryAccent
                )
            }
            val customSwatches = listOf(
                "#00E5FF" to "Cyan",
                "#00FFA3" to "Mint",
                "#FF2A85" to "Pink",
                "#A855F7" to "Violet",
                "#FF9400" to "Amber",
                "#3B82F6" to "Blue"
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                customSwatches.forEach { (hex, label) ->
                    val selected = themePreferences.customAccentHex?.equals(hex, ignoreCase = true) == true
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(android.graphics.Color.parseColor(hex)))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) Color.White else colors.border.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                vm?.setCustomThemeAccentHex(if (selected) null else hex)
                            }
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (themePreferences.customAccentHex == null) colors.cardSurface else colors.secondaryBackground,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (themePreferences.customAccentHex == null) colors.primaryAccent else colors.border.copy(alpha = 0.4f)
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
                            text = "AUTO",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryText
                        )
                    }
                }
            }
        }

        // 3. ADAPTIVE INTENSITY & MOTION CONTROLS
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.secondaryBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "ACOUSTIC LUMINANCE & MODULATION",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.secondaryText,
                    letterSpacing = 1.sp
                )

                // Musical Mood Influence Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Musical Mood Adaptation",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryText
                        )
                        Text(
                            text = if (themePreferences.enableMoodAdaptation) "Harmonic palette shifts enabled" else "Palette locked to time of day",
                            fontSize = 10.sp,
                            color = colors.secondaryText
                        )
                    }
                    Switch(
                        checked = themePreferences.enableMoodAdaptation,
                        onCheckedChange = { checked ->
                            vm?.updateThemePreferences(themePreferences.copy(enableMoodAdaptation = checked))
                        }
                    )
                }

                // Energy Sensitivity & Modulation Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Energy Modulation Depth",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryText
                        )
                        Text(
                            text = if (themePreferences.enableEnergyAdaptation) "Dynamic luminescence follows playback intensity" else "Static luminescence",
                            fontSize = 10.sp,
                            color = colors.secondaryText
                        )
                    }
                    Switch(
                        checked = themePreferences.enableEnergyAdaptation,
                        onCheckedChange = { checked ->
                            vm?.updateThemePreferences(themePreferences.copy(enableEnergyAdaptation = checked))
                        }
                    )
                }

                // Spectral Warm / Cool Bias Slider
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Spectral Warm/Cool Tone Bias",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryText
                        )
                        Text(
                            text = when {
                                themePreferences.observedWarmCoolBias < -0.1f -> "COOL // CYAN"
                                themePreferences.observedWarmCoolBias > 0.1f -> "WARM // AMBER"
                                else -> "BALANCED"
                            },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
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
                            inactiveTrackColor = colors.border
                        )
                    )
                }
            }
        }
    }
}
