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
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*

data class CooldownCandidate(
    val title: String,
    val artist: String,
    val playCount: Int,
    val satietyScore: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualCooldownSelectionScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit
) {
    val historyState by vm.historyState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedDays by remember { mutableStateOf(5f) }
    var selectedTrack by remember { mutableStateOf<CooldownCandidate?>(null) }
    var isSubmitted by remember { mutableStateOf(false) }

    // Derive candidates strictly from real captured songs
    val realCandidates = remember(historyState.tracks) {
        historyState.tracks
            .filter { !it.title.isNullOrBlank() }
            .map {
                CooldownCandidate(
                    title = it.title ?: "Unknown",
                    artist = it.artist ?: "Unknown",
                    playCount = it.distinctAppearanceCount,
                    satietyScore = minOf(95, it.distinctAppearanceCount * 22)
                )
            }
    }

    val filteredTracks = remember(searchQuery, realCandidates) {
        if (searchQuery.isBlank()) realCandidates
        else realCandidates.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    val isCustomSearchMatch = searchQuery.isNotBlank() && filteredTracks.none {
        it.title.equals(searchQuery.trim(), ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617)) // Deep slate-950
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Column {
                    Text(
                        text = "MANUAL QUARANTINE",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Add Track to Vault",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // Info Banner
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "RECEPTOR RESET PROTOCOL",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = AccentCyan
                )
                Text(
                    text = "Quarantining a song temporarily pauses its exposure, allowing dopamine receptor sensitivity to recover via exponential decay (e^-0.18t).",
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
                        Text("Return to Satiety Lab", color = Color(0xFF020617), fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // Working Search Field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search captured songs or type any track...", color = Color(0xFF64748B)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF64748B)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color(0xFF64748B))
                        }
                    }
                },
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

            // Custom search write-in card
            if (isCustomSearchMatch) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0891B2).copy(alpha = 0.15f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedTrack = CooldownCandidate(
                                title = searchQuery.trim(),
                                artist = "Custom Track",
                                playCount = 1,
                                satietyScore = 75
                            )
                        }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(24.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Quarantine \"${searchQuery.trim()}\"", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Custom song name • Will be added to Vault", fontSize = 11.sp, color = AccentCyanBright)
                        }
                        RadioButton(
                            selected = selectedTrack?.title == searchQuery.trim(),
                            onClick = {
                                selectedTrack = CooldownCandidate(
                                    title = searchQuery.trim(),
                                    artist = "Custom Track",
                                    playCount = 1,
                                    satietyScore = 75
                                )
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = AccentCyan, unselectedColor = Color(0xFF64748B))
                        )
                    }
                }
            }

            // Real Captured Track Selection List
            Text(
                text = "Captured Songs Available for Vault",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            if (filteredTracks.isEmpty() && !isCustomSearchMatch) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(32.dp))
                        Text(
                            text = if (searchQuery.isBlank()) "No songs captured yet" else "No matching captured songs",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = if (searchQuery.isBlank())
                                "Play tracks in Spotify to populate this list, or type a custom track name in the search bar above."
                            else
                                "You can type any track name above to quarantine it directly.",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                filteredTracks.forEach { track ->
                    val isSelected = selectedTrack?.title == track.title

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
                                Text(text = "${track.artist} • ${track.playCount} plays • ${track.satietyScore}% Satiety", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            }

                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedTrack = track },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentCyan, unselectedColor = Color(0xFF64748B))
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    selectedTrack?.let { track ->
                        vm.quarantineTrack(track.title, track.artist, selectedDays)
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
