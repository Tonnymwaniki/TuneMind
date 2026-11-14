package com.tunemind.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicProfileScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val stats by vm.listeningStats.collectAsState()
    val userProfile by vm.userProfile.collectAsState()
    val tasteAnchors by vm.userTasteAnchors.collectAsState()
    val theme = LocalAdaptiveTheme.current

    val monitoredHours = formatListeningDuration(stats.allTimeListeningMs)
    val confidencePct = (74 + minOf(24, stats.uniqueTrackCount)).coerceAtMost(98)

    var showEditProfileDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(userProfile.name) }
    var editArchetype by remember { mutableStateOf(userProfile.archetype) }

    var showAddAnchorDialog by remember { mutableStateOf(false) }
    var newAnchorText by remember { mutableStateOf("") }

    StudioAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Bar with Back + Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF0C1018).copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        modifier = Modifier
                            .size(38.dp)
                            .clickable { onBack() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = "TASTE PROFILE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.primaryAccent,
                            letterSpacing = 1.4.sp
                        )
                        Text(
                            text = "Your Sound Identity",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = Color(0xFF0C1018).copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { onOpenSettings() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = Color.White.copy(alpha = 0.88f),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            // Profile Identity Card
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(theme.primaryAccent, theme.secondaryAccent)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = userProfile.name.take(1).uppercase().ifEmpty { "T" },
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF05060A)
                            )
                        }

                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = userProfile.name,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                IconButton(
                                    onClick = {
                                        editName = userProfile.name
                                        editArchetype = userProfile.archetype
                                        showEditProfileDialog = true
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = "Edit Profile",
                                        tint = theme.primaryAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Text(
                                text = userProfile.archetype,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = theme.primaryAccent
                            )
                            Text(
                                text = userProfile.archetypeDescription,
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.62f),
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProfileStatTile("Listened", monitoredHours, theme.primaryAccent, Modifier.weight(1f))
                        ProfileStatTile("Music Apps", "${stats.appBreakdown.size.coerceAtLeast(1)}", theme.secondaryAccent, Modifier.weight(1f))
                        ProfileStatTile("Match Score", "$confidencePct%", Color(0xFF10B981), Modifier.weight(1f))
                    }
                }
            }

            // Switchboard Settings Link
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF0C1018).copy(alpha = 0.92f),
                border = BorderStroke(1.dp, theme.primaryAccent.copy(alpha = 0.28f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSettings() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(theme.primaryAccent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = null,
                                tint = theme.primaryAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Playback & Voice Settings",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = "Stay-in-TuneMind, Spotify Remote & permissions",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.60f)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Rounded.ArrowForward,
                        contentDescription = "Open Settings",
                        tint = theme.primaryAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Editable Favorite Genres & Artists
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Favorite Genres & Artists",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Guides what TuneMind suggests in For You & voice picks",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.60f)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                newAnchorText = ""
                                showAddAnchorDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = theme.primaryAccent.copy(alpha = 0.18f),
                                contentColor = theme.primaryAccent
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tasteAnchors.forEach { anchor ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.05f),
                                border = BorderStroke(1.dp, theme.primaryAccent.copy(alpha = 0.35f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = anchor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Remove $anchor",
                                        tint = Color.White.copy(alpha = 0.56f),
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { vm.removeTasteAnchor(anchor) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Listening Habits Summary
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Listening Habits",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Fresh vs Repeat", fontSize = 10.sp, color = Color.White.copy(alpha = 0.56f))
                            Text("${stats.discoveryRatePercent}% Fresh", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Completion", fontSize = 10.sp, color = Color.White.copy(alpha = 0.56f))
                            Text("${stats.completionRatePercent}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.primaryAccent)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Peak Time", fontSize = 10.sp, color = Color.White.copy(alpha = 0.56f))
                            Text(stats.peakWindowLabel.substringBefore(" "), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.secondaryAccent)
                        }
                    }
                }
            }

            // Connected Music Apps
            if (stats.appBreakdown.isNotEmpty()) {
                SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Observed Music Apps",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        stats.appBreakdown.forEach { app ->
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
                                        imageVector = Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = resolveAppAccentColor(app.packageName, theme.primaryAccent),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = app.displayName,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White
                                    )
                                }
                                Text(
                                    text = "${app.playCount} plays • ${formatListeningDuration(app.listeningMs)}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF10B981)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showEditProfileDialog) {
        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false },
            title = { Text("Edit Profile", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = theme.primaryAccent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                        )
                    )
                    OutlinedTextField(
                        value = editArchetype,
                        onValueChange = { editArchetype = it },
                        label = { Text("Listening Style") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = theme.primaryAccent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editName.isNotBlank() && editArchetype.isNotBlank()) {
                            vm.updateUserProfile(editName.trim(), editArchetype.trim())
                            showEditProfileDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primaryAccent)
                ) {
                    Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditProfileDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.65f))
                }
            },
            containerColor = Color(0xFF10131C),
            shape = RoundedCornerShape(18.dp)
        )
    }

    if (showAddAnchorDialog) {
        AlertDialog(
            onDismissRequest = { showAddAnchorDialog = false },
            title = { Text("Add Genre or Artist", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter a genre, mood, or favorite artist (for example: Afrobeats, Burna Boy, Lo-Fi, Synthwave):",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.68f)
                    )
                    OutlinedTextField(
                        value = newAnchorText,
                        onValueChange = { newAnchorText = it },
                        label = { Text("Genre or Artist") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = theme.primaryAccent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newAnchorText.isNotBlank()) {
                            vm.addTasteAnchor(newAnchorText.trim())
                            showAddAnchorDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primaryAccent)
                ) {
                    Text("Add", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAnchorDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.65f))
                }
            },
            containerColor = Color(0xFF10131C),
            shape = RoundedCornerShape(18.dp)
        )
    }
}

@Composable
private fun ProfileStatTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.56f))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent)
    }
}
