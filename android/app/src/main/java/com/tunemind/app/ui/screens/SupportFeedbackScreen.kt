package com.tunemind.app.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.BuildConfig
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.domain.network.BackendEndpointManager
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import com.tunemind.app.util.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@Composable
fun SupportFeedbackScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLegal: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme = LocalAdaptiveTheme.current
    val authManager = remember { TuneMindAuthSessionManager.getInstance(context) }
    val authSession by authManager.sessionFlow.collectAsState()

    val neonCyan = theme.primaryAccent
    val safeGreen = Color(0xFF10B981)
    val warnAmber = Color(0xFFF59E0B)

    val categories = listOf("Bug Report", "Spotify Sync", "Voice & Agent", "Feature Idea", "Other")
    var selectedCategory by remember { mutableStateOf(categories.first()) }
    var messageText by remember { mutableStateOf("") }
    var includeDiagnostics by remember { mutableStateOf(true) }
    var isSubmitting by remember { mutableStateOf(false) }
    var submitStatus by remember { mutableStateOf<String?>(null) }
    var showEmailFallback by remember { mutableStateOf(false) }

    var expandedGuideIdx by remember { mutableStateOf(0) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color(0xFFFAFAFA),
        unfocusedTextColor = Color(0xFFFAFAFA),
        focusedContainerColor = theme.cardSurface.copy(alpha = 0.92f),
        unfocusedContainerColor = theme.cardSurface.copy(alpha = 0.68f),
        focusedBorderColor = neonCyan,
        unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
        focusedLabelColor = neonCyan,
        unfocusedLabelColor = Color.White.copy(alpha = 0.56f),
        cursorColor = neonCyan
    )

    fun launchEmailShareFallback() {
        val body = buildString {
            appendLine("Category: $selectedCategory")
            appendLine()
            appendLine(messageText.trim())
            if (includeDiagnostics) {
                appendLine()
                appendLine("--- Diagnostics ---")
                appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
                appendLine("Notification Access: ${PermissionUtils.hasNotificationListenerPermission(context)}")
                appendLine("Battery Unrestricted: ${PermissionUtils.isIgnoringBatteryOptimizations(context)}")
                appendLine("Mic Permission: ${PermissionUtils.hasMicrophonePermission(context)}")
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "TuneMind Support: $selectedCategory")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, "Send Feedback").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    fun submitFeedback() {
        val cleanMsg = messageText.trim()
        if (cleanMsg.length < 6) {
            submitStatus = "Please describe what happened (at least a few words)."
            return
        }
        isSubmitting = true
        submitStatus = null
        showEmailFallback = false

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val baseUrl = BackendEndpointManager.getActiveHttpBaseUrl(context).trimEnd('/')
                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(12, TimeUnit.SECONDS)
                        .build()

                    val payload = JSONObject().apply {
                        put("category", selectedCategory)
                        put("message", cleanMsg)
                        put("user_email", authSession.email)
                        put("user_id", authSession.userId)
                        if (includeDiagnostics) {
                            put("diagnostics", JSONObject().apply {
                                put("app_version", BuildConfig.VERSION_NAME)
                                put("version_code", BuildConfig.VERSION_CODE)
                                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                                put("android_release", Build.VERSION.RELEASE)
                                put("has_notification_access", PermissionUtils.hasNotificationListenerPermission(context))
                                put("has_mic_permission", PermissionUtils.hasMicrophonePermission(context))
                                put("battery_unrestricted", PermissionUtils.isIgnoringBatteryOptimizations(context))
                            })
                        }
                    }

                    val accessToken = authManager.getAccessToken()
                    val reqBuilder = Request.Builder()
                        .url("$baseUrl/api/v1/support/feedback")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    if (accessToken.isNotBlank()) {
                        reqBuilder.header("Authorization", "Bearer $accessToken")
                    }
                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        resp.isSuccessful
                    }
                }.getOrDefault(false)
            }

            isSubmitting = false
            if (result) {
                messageText = ""
                submitStatus = "Thanks! Your feedback was sent to the TuneMind team."
            } else {
                submitStatus = "Couldn't reach the support server right now — you can send this report via your email or share app below."
                showEmailFallback = true
            }
        }
    }

    StudioAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color(0xFFF8FAFC)
                    )
                }
                Text(
                    text = "Help & Feedback",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 1. Troubleshooting Guides
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    StudioSectionHeader(title = "Quick Troubleshooting")
                    Spacer(modifier = Modifier.height(6.dp))
                    StudioHairlineDivider()

                    TroubleshootingGuideItem(
                        title = "Spotify Remote won't connect",
                        steps = "1. Open the Spotify app and play any song for 2 seconds so Android wakes its playback service.\n2. Return to TuneMind and tap Connect Remote in Settings.\n3. Confirm Spotify isn't restricted in Android Battery settings.",
                        expanded = expandedGuideIdx == 0,
                        accent = Color(0xFF1DB954),
                        actionLabel = "Open Settings",
                        onToggle = { expandedGuideIdx = if (expandedGuideIdx == 0) -1 else 0 },
                        onAction = onOpenSettings
                    )
                    StudioHairlineDivider()

                    TroubleshootingGuideItem(
                        title = "Music detection stopped tracking songs",
                        steps = "1. Verify Notification Access is enabled for TuneMind.\n2. If already enabled after an app update, toggle it off and back on once to refresh Android's listener binding.",
                        expanded = expandedGuideIdx == 1,
                        accent = neonCyan,
                        actionLabel = "Notification Access",
                        onToggle = { expandedGuideIdx = if (expandedGuideIdx == 1) -1 else 1 },
                        onAction = {
                            runCatching {
                                context.startActivity(
                                    PermissionUtils.getNotificationListenerIntent().apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                        }
                    )
                    StudioHairlineDivider()

                    TroubleshootingGuideItem(
                        title = "Background sync pauses on Samsung / Xiaomi / Tecno",
                        steps = "1. Set Battery usage for TuneMind to Unrestricted.\n2. On Xiaomi/Tecno/Oppo devices, also allow Auto-start in your phone's Security/Battery manager.",
                        expanded = expandedGuideIdx == 2,
                        accent = warnAmber,
                        actionLabel = "Battery Settings",
                        onToggle = { expandedGuideIdx = if (expandedGuideIdx == 2) -1 else 2 },
                        onAction = { vm.requestIgnoreBatteryOptimization(context) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Bug Report & Feedback Form
            SoftGlassCard(modifier = Modifier.fillMaxWidth(), accentTint = theme.primaryAccent) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StudioSectionHeader(title = "Send Feedback or Report a Bug")

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categories.forEach { cat ->
                            val selected = selectedCategory == cat
                            Surface(
                                shape = RoundedCornerShape(28.dp),
                                color = if (selected) neonCyan.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) neonCyan.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.14f)
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(28.dp))
                                    .clickable { selectedCategory = cat }
                            ) {
                                Text(
                                    text = cat,
                                    color = if (selected) neonCyan else Color.White.copy(alpha = 0.72f),
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = messageText,
                        onValueChange = {
                            messageText = it
                            submitStatus = null
                        },
                        label = { Text("What happened or what would you like improved?") },
                        minLines = 4,
                        maxLines = 7,
                        shape = RoundedCornerShape(16.dp),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeDiagnostics = !includeDiagnostics },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                text = "Include device diagnostics",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFF8FAFC)
                            )
                            Text(
                                text = "Adds app version, phone model & permission health (${Build.MODEL})",
                                fontSize = 11.5.sp,
                                color = Color.White.copy(alpha = 0.56f)
                            )
                        }
                        Switch(
                            checked = includeDiagnostics,
                            onCheckedChange = { includeDiagnostics = it }
                        )
                    }

                    if (!submitStatus.isNullOrBlank()) {
                        Text(
                            text = submitStatus!!,
                            color = if (showEmailFallback) warnAmber else safeGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = { submitFeedback() },
                        enabled = !isSubmitting,
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = neonCyan,
                            contentColor = Color(0xFF0D0D0D)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isSubmitting) "Sending…" else "Send Feedback",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (showEmailFallback) {
                        OutlinedButton(
                            onClick = { launchEmailShareFallback() },
                            shape = RoundedCornerShape(28.dp),
                            border = BorderStroke(1.dp, warnAmber.copy(alpha = 0.52f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Icon(Icons.Rounded.Email, contentDescription = null, tint = warnAmber, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Send via Email / Share Sheet", color = warnAmber, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            TextButton(
                onClick = onOpenLegal,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = "View Privacy Policy & Terms",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TroubleshootingGuideItem(
    title: String,
    steps: String,
    expanded: Boolean,
    accent: Color,
    actionLabel: String,
    onToggle: () -> Unit,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFF8FAFC),
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = steps,
                    fontSize = 12.5.sp,
                    color = Color.White.copy(alpha = 0.68f),
                    lineHeight = 18.sp
                )
                OutlinedButton(
                    onClick = onAction,
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(actionLabel, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
