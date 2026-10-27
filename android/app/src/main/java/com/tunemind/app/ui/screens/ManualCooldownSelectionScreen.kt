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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.model.TrackItem
import com.tunemind.app.model.TuneMindDemoData
import com.tunemind.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualCooldownSelectionScreen(onBack: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedDays by remember { mutableStateOf(5f) }
    var selectedTrack by remember { mutableStateOf<TrackItem?>(null) }
    var isSubmitted by remember { mutableStateOf(false) }

    val allCandidates = remember {
        TuneMindDemoData.candidateCooldownTracks + TuneMindDemoData.palateCleansers
    }
    val filteredTracks = remember(searchQuery) {
        if (searchQuery.isBlank()) allCandidates
        else allCandidates.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617)) // Deep slate-950
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Header Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF38BDF8).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.AddCircleOutline,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "QUARANTINE BENCH",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyan,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Add Track to Cooldown",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                    }
                }

                Text(
                    text = "Manually place a high-rotation track into receptor rest. While quarantined, TuneMind will actively prevent repeat exposure.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 18.sp
                )
            }
        }

        if (isSubmitted) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B).copy(alpha = 0.3f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(36.dp))
                    Text(
                        text = "Quarantine Activated",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "${selectedTrack?.title} is now in cooldown for ${selectedDays.toInt()} days.",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Return to Cooldown Vault", color = Color(0xFF020617), fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search songs or artists...", color = Color(0xFF64748B)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF64748B)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF0F172A),
                    unfocusedContainerColor = Color(0xFF0F172A),
                    focusedBorderColor = AccentCyan,
                    unfocusedBorderColor = Color(0xFF1E293B),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            // Duration Slider Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Cooldown Duration", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            "${selectedDays.toInt()} Days",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyan
                        )
                    }

                    Slider(
                        value = selectedDays,
                        onValueChange = { selectedDays = it },
                        valueRange = 1f..14f,
                        steps = 12,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentCyan,
                            activeTrackColor = AccentCyan,
                            inactiveTrackColor = Color(0xFF1E293B)
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("1 day (Micro-reset)", fontSize = 10.sp, color = Color(0xFF64748B))
                        Text("14 days (Deep clean)", fontSize = 10.sp, color = Color(0xFF64748B))
                    }
                }
            }

            // Track Selection List
            Text(
                text = "Select Track to Quarantine",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            filteredTracks.forEach { track ->
                val isSelected = selectedTrack?.id == track.id
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) Color(0xFF0891B2).copy(alpha = 0.2f) else Color(0xFF0F172A)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) AccentCyan else Color(0xFF1E293B)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedTrack = track }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E293B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = track.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(text = "${track.artist} • Satiety: ${track.satietyScore}%", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }

                        RadioButton(
                            selected = isSelected,
                            onClick = { selectedTrack = track },
                            colors = RadioButtonDefaults.colors(selectedColor = AccentCyan, unselectedColor = Color(0xFF64748B))
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    if (selectedTrack != null) {
                        isSubmitted = true
                    }
                },
                enabled = selectedTrack != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentCyan,
                    disabledContainerColor = Color(0xFF1E293B)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = if (selectedTrack != null) "Quarantine ${selectedTrack?.title} for ${selectedDays.toInt()} Days" else "Select a Track Above",
                    color = if (selectedTrack != null) Color(0xFF020617) else Color(0xFF64748B),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
