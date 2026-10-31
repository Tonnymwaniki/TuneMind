package com.tunemind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*

@Composable
fun SevenDayStudyScreen(vm: TuneMindViewModel, onBack: () -> Unit) {
    val studyState by vm.studyState.collectAsState()
    val historyState by vm.historyState.collectAsState()
    val colors = TuneMindTheme.colors

    var selectedDayIndex by remember { mutableStateOf(2) } // Wednesday default
    val days = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    val listeningHours = listOf("2.4h", "4.1h", "3.2h", "5.0h", "4.8h", "6.2h", "3.5h")
    val fillPercentages = listOf(0.48f, 0.82f, 0.64f, 0.95f, 0.90f, 1.0f, 0.70f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.primaryBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. TOP HARDWARE TELEMETRY HEADER
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
                            .background(Color(0xFF10B981))
                    )
                    Text(
                        text = "CIRCADIAN // NEURO-ACOUSTIC DRIFT",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryText,
                        letterSpacing = 1.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = colors.cardSurface
                ) {
                    Text(
                        text = "BUFFER // 7D SYNC",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryAccent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // Section Title
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "CIRCADIAN INTELLIGENCE // 7-DAY BASELINE",
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = colors.primaryText,
                letterSpacing = 0.5.sp
            )
            Text(
                text = "Calibrating auditory attention drift and dopamine receptivity across circadian cycles.",
                fontSize = 12.sp,
                color = colors.secondaryText,
                lineHeight = 17.sp
            )
        }

        // 2. INTERACTIVE 7-DAY MATRIX MODULE (7 Slim Vacuum Pill Tubes Grid)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.secondaryBackground)
                .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = Color(0xFF83B9BD), modifier = Modifier.size(16.dp))
                    Text(
                        text = "WEEKLY NEURO-ACOUSTIC CYCLE",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryText,
                        letterSpacing = 0.8.sp
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = colors.primaryBackground
                ) {
                    Text(
                        text = "CURRENT CYCLE: W38",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryAccent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // The 7 Vacuum Pill Tubes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                days.forEachIndexed { index, day ->
                    val isSelected = index == selectedDayIndex
                    val fill = fillPercentages[index]
                    val hours = listeningHours[index]

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedDayIndex = index }
                    ) {
                        Text(
                            text = hours,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) colors.primaryText else colors.secondaryText
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        // Vacuum Tube Body
                        Box(
                            modifier = Modifier
                                .width(22.dp)
                                .height(110.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(colors.primaryBackground)
                                .border(
                                    1.dp,
                                    if (isSelected) colors.primaryAccent else colors.border.copy(alpha = 0.5f),
                                    RoundedCornerShape(11.dp)
                                )
                                .padding(2.dp),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            // Illuminated Liquid Column
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(fill)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        if (isSelected) colors.primaryAccent else Color(0xFF83B9BD).copy(alpha = 0.8f)
                                    )
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = day.take(1),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) colors.primaryAccent else colors.secondaryText
                        )
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 4.dp else 2.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) colors.primaryAccent else colors.secondaryText.copy(alpha = 0.4f))
                        )
                    }
                }
            }
        }

        // 3. DAY DETAIL TELEMETRY CARD
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.secondaryBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${days[selectedDayIndex]} PROFILE // DEEP FOCUS ZONE",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryAccent,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "${listeningHours[selectedDayIndex]} ACTIVE",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryText
                    )
                }

                Text(
                    text = "Acoustic equilibrium maintained. No significant fatigue drift observed during evening sessions.",
                    fontSize = 12.sp,
                    color = colors.secondaryText,
                    lineHeight = 17.sp
                )
            }
        }
    }
}
