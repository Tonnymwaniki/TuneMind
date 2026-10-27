package com.tunemind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicProfileScreen(vm: TuneMindViewModel, onBack: () -> Unit) {
    val state by vm.profileState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Music Profile") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = TextSecondary) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface, titleContentColor = TextPrimary)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Text("Listening Profile", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("${state.observationCount} observations", color = TextMuted, fontSize = 12.sp)

            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Recurrence Signal", color = TextMuted, fontSize = 11.sp, letterSpacing = 0.06.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(state.signalText, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }

            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Temporal Dynamics", color = TextMuted, fontSize = 11.sp, letterSpacing = 0.06.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(state.dynamicsText, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }

            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Pattern Research", color = TextMuted, fontSize = 11.sp, letterSpacing = 0.06.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(state.researchText, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}
