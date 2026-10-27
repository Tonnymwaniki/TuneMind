package com.tunemind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrossAppActivityScreen(vm: TuneMindViewModel, onBack: () -> Unit) {
    val state by vm.crossAppState.collectAsState()
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)

    LaunchedEffect(Unit) { vm.refreshCrossAppData() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cross-App Activity") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = TextSecondary) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface, titleContentColor = TextPrimary)
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else if (state.sources.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Devices, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(32.dp))
                Text("Cross-App Activity", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("No observed sources yet. Observed media sources will appear here as they are detected.", fontSize = 13.sp, color = TextSecondary)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // Summary header
                Text("Cross-App Activity", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${state.sources.size}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                                Text("Sources", fontSize = 11.sp, color = TextMuted)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(formatDuration(state.totalObservedPlaybackMs), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("Playback", fontSize = 11.sp, color = TextMuted)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${state.totalEventCount}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("Events", fontSize = 11.sp, color = TextMuted)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Observed playback time is estimated from playback state transitions. It is not a verified measure of active listening duration.", fontSize = 10.sp, color = TextMuted)
                    }
                }

                // Per-source cards
                state.sources.forEach { source ->
                    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceContainerHigh),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(source.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
                                    Text(source.sourcePackage, fontSize = 11.sp, color = TextMuted)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(formatDuration(source.observedPlaybackMs), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                                    Text("Playback", fontSize = 10.sp, color = TextMuted)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${source.eventCount}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text("Events", fontSize = 10.sp, color = TextMuted)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${source.intervalCount}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text("Intervals", fontSize = 10.sp, color = TextMuted)
                                }
                            }
                            source.latestEventMs?.let {
                                Spacer(Modifier.height(4.dp))
                                Text("Latest: ${fmt.format(Date(it))}", fontSize = 10.sp, color = TextMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%dh %dm".format(hours, minutes)
    else if (minutes > 0) "%dm %ds".format(minutes, seconds)
    else "%ds".format(seconds)
}
