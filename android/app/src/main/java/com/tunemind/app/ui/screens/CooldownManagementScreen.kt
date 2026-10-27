package com.tunemind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CooldownManagementScreen(onBack: () -> Unit, onAddTrack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Track Cooldown") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = TextSecondary) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface, titleContentColor = TextPrimary)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Icon(Icons.Outlined.AcUnit, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(32.dp))
            Text("Track Cooldown", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("Track cooldown management is not yet available.", fontSize = 13.sp, color = TextSecondary)

            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Not yet implemented", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text("Cooldown management requires a validated fatigue model and sufficient listening history per track. These are not available in the current version.", fontSize = 12.sp, color = TextSecondary, lineHeight = 18.sp)
                }
            }
        }
    }
}
