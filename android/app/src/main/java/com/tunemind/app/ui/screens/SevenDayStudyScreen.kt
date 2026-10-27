package com.tunemind.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.experiment.ExperimentStatus
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*
import java.text.DateFormat
import java.util.Date

@Composable
fun SevenDayStudyScreen(vm: TuneMindViewModel, onBack: () -> Unit) {
    val studyState by vm.studyState.collectAsState()
    val context = LocalContext.current
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)

    // Refresh readiness whenever the screen appears
    LaunchedEffect(Unit) { vm.showReadinessScreen() }

    Column(modifier = Modifier.fillMaxSize().background(Surface).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(AccentCyan))
                    Spacer(Modifier.width(6.dp))
                    Text("7-DAY EXPERIMENT", fontSize = 10.sp, color = AccentCyan, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text("Seven-Day Listening Experiment", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
            IconButton(onClick = onBack) { Icon(Icons.Default.Close, "Close", tint = TextSecondary) }
        }

        Spacer(Modifier.height(6.dp))

        when (studyState.status) {
            ExperimentStatus.NOT_STARTED -> {
                if (studyState.showReadiness) {
                    // Readiness checklist
                    Text("Prepare TuneMind", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text("Complete the requirements below to start the seven-day experiment.", fontSize = 13.sp, color = TextSecondary)

                    Spacer(Modifier.height(12.dp))

                    studyState.readinessItems.forEach { item ->
                        Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (item.isGranted) AccentGreen.copy(alpha = 0.4f) else BorderSubtle),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (item.isGranted) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                    contentDescription = null,
                                    tint = if (item.isGranted) AccentGreen else TextMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                    Text(item.explanation, fontSize = 11.sp, color = TextMuted)
                                }
                                if (!item.isGranted && item.settingsAction != null) {
                                    TextButton(onClick = {
                                        if (item.settingsAction == "notification_listener") {
                                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                        }
                                    }) {
                                        Text("Grant", fontSize = 12.sp, color = AccentCyan)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    if (studyState.allRequiredGranted) {
                        Button(onClick = { vm.startStudy() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet)) {
                            Text("Start Seven-Day Experiment", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    } else {
                        Button(onClick = { vm.refreshReadiness() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceContainerHigh)) {
                            Text("Check Again", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Complete all required items above to start.", fontSize = 12.sp, color = TextMuted)
                    }
                } else {
                    // Initial description
                    Text("This experiment tracks your listening patterns over seven days. Observations are stored separately from the upload queue and will not be pruned.", fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { vm.showReadinessScreen() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet)) {
                        Text("Prepare Experiment", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
            ExperimentStatus.ACTIVE -> {
                Text("Experiment in progress.", fontSize = 13.sp, color = TextSecondary)
                studyState.startDateMs?.let { Text("Started: ${fmt.format(Date(it))}", fontSize = 12.sp, color = TextMuted) }
                studyState.endDateMs?.let { Text("Ends: ${fmt.format(Date(it))}", fontSize = 12.sp, color = TextMuted) }

                studyState.startDateMs?.let { start ->
                    val elapsed = System.currentTimeMillis() - start
                    val daysElapsed = (elapsed / 86_400_000f).coerceIn(0f, 7f)
                    Text("Day ${(daysElapsed + 1).toInt().coerceAtMost(7)} of 7", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                }

                Spacer(Modifier.height(14.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    studyState.dayEvidence.forEach { day ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (day.hasEvents) SurfaceContainerHigh else SurfaceContainerLowest)
                            .border(1.dp, if (day.hasEvents) PrimaryViolet.copy(alpha = 0.5f) else BorderSubtle, RoundedCornerShape(8.dp))
                            .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(day.dayLabel, fontSize = 10.sp, color = TextMuted)
                            Spacer(Modifier.height(2.dp))
                            Text("D${day.dayNumber}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextPrimary)
                            Spacer(Modifier.height(4.dp))
                            Icon(if (day.hasEvents) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null, tint = if (day.hasEvents) AccentGreen else TextMuted, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Days with observations", fontSize = 12.sp, color = TextSecondary)
                            Text("${studyState.daysWithEvents} of ${studyState.totalDays}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                        }
                        LinearProgressIndicator(progress = { studyState.daysWithEvents.toFloat() / studyState.totalDays.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = AccentCyan, trackColor = SurfaceContainerHighest)
                        Divider(color = BorderSubtle)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("Observations", fontSize = 10.sp, color = TextMuted); Text("${studyState.observationCount}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary) }
                            Column { Text("Sources", fontSize = 10.sp, color = TextMuted); Text(studyState.observedSources.take(3).joinToString(", ") { it.substringAfterLast(".") }.ifEmpty { "None" }, fontSize = 12.sp, color = AccentCyan) }
                        }
                        studyState.lastObservationMs?.let { Text("Last: ${fmt.format(Date(it))}", fontSize = 10.sp, color = TextMuted) }
                    }
                }

                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { vm.resetStudy() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCrimson)) {
                    Text("End Experiment Early", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                }
            }
            ExperimentStatus.COMPLETED -> {
                Text("Experiment completed.", fontSize = 13.sp, color = TextSecondary)
                Spacer(Modifier.height(8.dp))
                Text("${studyState.observationCount} observations recorded from ${studyState.observedSources.size} source(s).", fontSize = 13.sp, color = TextSecondary)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { vm.resetStudy() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text("Start New Experiment", fontWeight = FontWeight.Medium)
                }
            }
            else -> {
                Text("Experiment was interrupted.", fontSize = 13.sp, color = TextSecondary)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.resetStudy() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text("Restart Experiment", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
