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
import com.tunemind.app.data.local.EventQueueEntity
import com.tunemind.app.data.local.TuneMindDatabase
import com.tunemind.app.sync.SyncScheduler
import com.tunemind.app.service.NotificationAccess
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun CrossAppActivityScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val crossAppState by vm.crossAppState.collectAsState()
    var isNotificationAccessGranted by remember { mutableStateOf(false) }
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
            .background(DeepObsidianAmber.AppBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Header Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
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
                                .background(DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Sensors,
                                contentDescription = null,
                                tint = DeepObsidianAmber.PrimaryAccent,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "INGESTION & DAEMONS",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.PrimaryAccent,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Connected Observers",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.TextPrimary
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isNotificationAccessGranted) DeepObsidianAmber.Success else DeepObsidianAmber.Warning)
                    )
                }

                Text(
                    text = "TuneMind passively captures media playback from background services across Spotify, YouTube Music, Apple Music, and local audio players without needing an open foreground app.",
                    fontSize = 12.sp,
                    color = DeepObsidianAmber.TextSecondary,
                    lineHeight = 18.sp
                )
            }
        }

        // Notification Access Status Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isNotificationAccessGranted) DeepObsidianAmber.CardElevated else DeepObsidianAmber.CardBackground
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isNotificationAccessGranted) DeepObsidianAmber.Success.copy(alpha = 0.5f) else DeepObsidianAmber.Warning.copy(alpha = 0.5f)
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
                            tint = if (isNotificationAccessGranted) DeepObsidianAmber.Success else DeepObsidianAmber.Warning,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (isNotificationAccessGranted) "Notification Listener: Active" else "Notification Access: Action Required",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.TextPrimary
                        )
                    }

                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isNotificationAccessGranted) DeepObsidianAmber.RecessedBackground else DeepObsidianAmber.PrimaryAccent,
                            contentColor = if (isNotificationAccessGranted) DeepObsidianAmber.TextPrimary else DeepObsidianAmber.AppBackground
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isNotificationAccessGranted) "Check Settings" else "Grant Permission",
                            fontSize = 11.sp,
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
                    color = DeepObsidianAmber.TextSecondary,
                    lineHeight = 16.sp
                )
            }
        }

        // Live Cloud Ingestion Pipeline
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "TARGET CLOUD ENDPOINT",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = DeepObsidianAmber.SecondaryAccent
                )
                Text(
                    text = BuildConfig.TUNEMIND_BASE_URL,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = DeepObsidianAmber.TextPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Phone Queue: $localBufferedCount events buffered in Room",
                        fontSize = 11.sp,
                        color = DeepObsidianAmber.TextSecondary
                    )

                    Button(
                        onClick = {
                            SyncScheduler.schedule(context)
                            testBroadcastingStatus = "Immediate sync scheduled"
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepObsidianAmber.PrimaryAccent,
                            contentColor = DeepObsidianAmber.AppBackground
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, tint = DeepObsidianAmber.AppBackground, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Sync to Cloud", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                testBroadcastingStatus?.let { msg ->
                    Text(msg, fontSize = 11.sp, color = DeepObsidianAmber.Success, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Test Song Broadcaster
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardElevated),
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null, tint = DeepObsidianAmber.PrimaryAccent, modifier = Modifier.size(18.dp))
                    Text("Instant Test Broadcaster", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.TextPrimary)
                }
                Text(
                    "Simulate an active track playback on this phone to test local Room storage and cloud sync instantly.",
                    fontSize = 11.sp,
                    color = DeepObsidianAmber.TextSecondary
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DeepObsidianAmber.PrimaryAccent,
                        contentColor = DeepObsidianAmber.AppBackground
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, tint = DeepObsidianAmber.AppBackground, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Broadcast 'Espresso' Playback Event", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        // Observed Daemons
        Text(
            text = "Supported Android Media Daemons",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = DeepObsidianAmber.TextPrimary
        )

        val daemons = listOf(
            Triple("Spotify", "com.spotify.music", Color(0xFF1DB954)),
            Triple("YouTube Music", "com.google.android.apps.youtube.music", Color(0xFFEF4444)),
            Triple("Apple Music", "com.apple.android.music", Color(0xFFFA243C)),
            Triple("Tidal", "com.aspiro.tidal", DeepObsidianAmber.SecondaryAccent),
            Triple("SoundCloud", "com.soundcloud.android", DeepObsidianAmber.CopperAccent),
            Triple("Generic Android Players", "com.android.music", DeepObsidianAmber.PrimaryAccent)
        )

        daemons.forEach { (name, pkg, color) ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
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
                        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.TextPrimary)
                        Text(pkg, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = DeepObsidianAmber.TextMuted)
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = DeepObsidianAmber.RecessedBackground
                    ) {
                        Text(
                            text = "Observer Armed",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.TextSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
