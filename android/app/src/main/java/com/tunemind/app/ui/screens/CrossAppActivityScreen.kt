package com.tunemind.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.tunemind.app.BuildConfig
import com.tunemind.app.data.local.EventBufferRepository
import com.tunemind.app.data.local.EventQueueEntity
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.service.NotificationAccess
import com.tunemind.app.sync.SyncScheduler
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrossAppActivityScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.crossAppState.collectAsState()
    var isNotificationAccessGranted by remember { mutableStateOf<Boolean>(NotificationAccess.isEnabled(context)) }
    var testBroadcastingStatus by remember { mutableStateOf<String?>(null) }
    var localBufferedCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        vm.refreshCrossAppData()
        isNotificationAccessGranted = NotificationAccess.isEnabled(context)
        withContext(Dispatchers.IO) {
            val db = TuneMindDatabase.getInstance(context)
            localBufferedCount = db.eventQueueDao().unsyncedCount()
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
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f)),
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
                                .background(Color(0xFF10B981).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Sensors,
                                contentDescription = null,
                                tint = AccentGreen,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "INGESTION & DAEMONS",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentGreen,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Connected Observers",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isNotificationAccessGranted) AccentGreen else AccentAmber)
                    )
                }

                Text(
                    text = "TuneMind passively captures media playback from background services across Spotify, YouTube Music, Apple Music, and local audio players without needing an open foreground app.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 18.sp
                )
            }
        }

        // Notification Access Status Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isNotificationAccessGranted) Color(0xFF064E3B).copy(alpha = 0.3f) else Color(0xFF78350F).copy(alpha = 0.3f)
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isNotificationAccessGranted) Color(0xFF10B981).copy(alpha = 0.4f) else Color(0xFFF59E0B).copy(alpha = 0.4f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            if (isNotificationAccessGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isNotificationAccessGranted) AccentGreen else AccentAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (isNotificationAccessGranted) "Notification Listener: Active" else "Notification Access: Action Required",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isNotificationAccessGranted) Color(0xFF1E293B) else AccentAmber
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isNotificationAccessGranted) "Check Settings" else "Grant Permission",
                            fontSize = 11.sp,
                            color = if (isNotificationAccessGranted) Color(0xFF94A3B8) else Color(0xFF020617),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = if (isNotificationAccessGranted)
                        "Your phone is currently authorized to observe playback metadata from active media notifications."
                    else
                        "Android requires Notification Access so TuneMind can read track title, artist, and playback states from Spotify, YouTube Music, and other media players.",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 16.sp
                )
            }
        }

        // Live Cloud Ingestion Pipeline
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "TARGET CLOUD ENDPOINT",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = AccentCyan
                )
                Text(
                    text = BuildConfig.TUNEMIND_BASE_URL,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Phone Queue: $localBufferedCount events buffered in Room",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )

                    Button(
                        onClick = {
                            SyncScheduler.schedule(context)
                            testBroadcastingStatus = "Immediate sync scheduled"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Sync to Cloud", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                testBroadcastingStatus?.let { msg ->
                    Text(msg, fontSize = 11.sp, color = AccentGreen, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Test Song Broadcaster
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF090D16)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                    Text("Instant Test Broadcaster", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Text(
                    "Simulate an active track playback on this phone to test local Room storage and cloud sync instantly.",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )

                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val db = TuneMindDatabase.getInstance(context)
                            val now = System.currentTimeMillis()
                            val eventId = UUID.randomUUID().toString()
                            val payload = """
                                {"event_id":"$eventId","media_source":"com.spotify.music","event_type":"METADATA_CHANGED","occurred_at":"$now","track":{"title":"Espresso","artist":"Sabrina Carpenter","album":"Short n' Sweet","duration_seconds":175,"bpm":104}}
                            """.trimIndent()
                            db.eventQueueDao().insert(
                                EventQueueEntity(
                                    eventId = eventId,
                                    payloadJson = payload,
                                    synced = false,
                                    eventTimeMs = now,
                                    savedAtMs = now
                                )
                            )
                            SyncScheduler.schedule(context)
                            withContext(Dispatchers.Main) {
                                testBroadcastingStatus = "Broadcasted test track 'Espresso' & triggered sync!"
                                localBufferedCount = db.eventQueueDao().unsyncedCount()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, tint = Color(0xFF020617), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Broadcast 'Espresso' Playback Event", color = Color(0xFF020617), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        // Observed Daemons
        Text(
            text = "Supported Android Media Daemons",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        val daemons = listOf(
            Triple("Spotify", "com.spotify.music", Color(0xFF1DB954)),
            Triple("YouTube Music", "com.google.android.apps.youtube.music", Color(0xFFEF4444)),
            Triple("Apple Music", "com.apple.android.music", Color(0xFFFA243C)),
            Triple("Tidal", "com.aspiro.tidal", Color(0xFF06B6D4)),
            Triple("SoundCloud", "com.soundcloud.android", Color(0xFFF97316)),
            Triple("Generic Android Players", "com.android.music", Color(0xFF8B5CF6))
        )

        daemons.forEach { (name, pkg, color) ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(pkg, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF1E293B)
                    ) {
                        Text(
                            text = "Observer Armed",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
