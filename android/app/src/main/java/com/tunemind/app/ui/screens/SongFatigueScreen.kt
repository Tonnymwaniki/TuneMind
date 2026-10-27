package com.tunemind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongFatigueScreen(
    onOpenCalibration: () -> Unit,
    onOpenCooldown: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Song Fatigue Research") },
                navigationIcon = { IconButton(onClick = { /* back handled by nav */ }) { Icon(Icons.Default.ArrowBack, "Back", tint = TextSecondary) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface, titleContentColor = TextPrimary)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Icon(Icons.Outlined.Science, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(32.dp))
            Text("Song Fatigue Research", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("Song fatigue is not currently detected by TuneMind. This screen shows what would be required to assess it.", fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp)

            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("What is needed", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text("• Validated fatigue model\n• Sufficient longitudinal data per track\n• Controlled listening conditions\n• User-reported fatigue labels for calibration\n• Ability to distinguish genuine fatigue from observation gaps", fontSize = 12.sp, color = TextSecondary, lineHeight = 18.sp)
                }
            }

            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle), modifier = Modifier.fillMaxWidth(),
                onClick = onOpenCooldown) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = AccentCyan)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Track Cooldown", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPrimary)
                        Text("View and manage track cooldown", fontSize = 12.sp, color = TextSecondary)
                    }
                }
            }
        }
    }
}
