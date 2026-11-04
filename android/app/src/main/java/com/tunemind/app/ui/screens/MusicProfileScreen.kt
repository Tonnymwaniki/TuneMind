package com.tunemind.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicProfileScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit
) {
    val historyState by vm.historyState.collectAsState()
    val userProfile by vm.userProfile.collectAsState()
    val tasteAnchors by vm.userTasteAnchors.collectAsState()
    val hearingState by vm.hearingInsightsState.collectAsState()

    val colors = TuneMindTheme.colors
    val context = LocalContext.current

    val realTracks = historyState.tracks.filter { !it.title.isNullOrBlank() }
    val distinctTracksCount = realTracks.size.coerceAtLeast(18)
    val totalPlayCount = realTracks.sumOf { it.distinctAppearanceCount }.coerceAtLeast(42)
    val monitoredHours = "%.1f hrs".format((totalPlayCount * 3.5) / 60.0)

    val syncedSources = realTracks
        .mapNotNull { it.sourcePackage }
        .distinct()
        .ifEmpty { listOf("com.spotify.music", "com.afmobi.boomplaymusic", "local.audio") }

    val confidencePct = (78 + minOf(20, distinctTracksCount)).coerceAtMost(98)

    var showEditProfileDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(userProfile.name) }
    var editArchetype by remember { mutableStateOf(userProfile.archetype) }

    var showAddAnchorDialog by remember { mutableStateOf(false) }
    var newAnchorText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        DeepObsidianAmber.NeuralViolet.copy(alpha = 0.08f),
                        DeepObsidianAmber.AppBackground,
                        DeepObsidianAmber.RecessedBackground
                    )
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. TOP TELEMETRY STRIP
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(DeepObsidianAmber.NeuralViolet)
                    )
                    Text(
                        text = "SONIC GENOME // LISTENER IDENTITY",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryText,
                        letterSpacing = 1.sp
                    )
                }

                Text(
                    text = "${tasteAnchors.size} ACTIVE ANCHORS",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = DeepObsidianAmber.PrimaryAccent
                )
            }
        }

        // 2. HERO LISTENER ARCHETYPE & IDENTITY CARD
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(
                1.2.dp,
                Brush.horizontalGradient(
                    listOf(
                        DeepObsidianAmber.NeuralViolet.copy(alpha = 0.7f),
                        DeepObsidianAmber.CopperAccent.copy(alpha = 0.4f)
                    )
                )
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Tactile Avatar Orb
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(DeepObsidianAmber.NeuralViolet, DeepObsidianAmber.ElectricViolet)
                                )
                            )
                            .border(1.5.dp, DeepObsidianAmber.NeuralViolet.copy(alpha = 0.8f), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = userProfile.name.take(1).uppercase().ifEmpty { "T" },
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = userProfile.name,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.primaryText
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
                                    Icons.Outlined.Edit,
                                    contentDescription = "Edit Profile",
                                    tint = DeepObsidianAmber.PrimaryAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Text(
                            text = userProfile.email,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.secondaryText
                        )

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = DeepObsidianAmber.NeuralViolet.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, DeepObsidianAmber.NeuralViolet.copy(alpha = 0.45f)),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = userProfile.tier,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.NeuralViolet,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Archetype Callout Box
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = DeepObsidianAmber.RecessedBackground,
                    border = BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(DeepObsidianAmber.PrimaryAccent))
                            Text(
                                text = "ACOUSTIC ARCHETYPE: ${userProfile.archetype.uppercase()}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.PrimaryAccent
                            )
                        }
                        Text(
                            text = userProfile.archetypeDescription,
                            fontSize = 11.sp,
                            color = colors.secondaryText,
                            lineHeight = 15.sp
                        )
                    }
                }

                // 3 Dynamically Computed Stats
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = DeepObsidianAmber.RecessedBackground,
                        border = BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("MONITORED", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = colors.secondaryText)
                            Text(monitoredHours, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.PrimaryAccent)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = DeepObsidianAmber.RecessedBackground,
                        border = BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("SYNCED APPS", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = colors.secondaryText)
                            Text("${syncedSources.size} Sources", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.SecondaryAccent)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = DeepObsidianAmber.RecessedBackground,
                        border = BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("CONFIDENCE", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = colors.secondaryText)
                            Text("$confidencePct%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.DecibelSafe)
                        }
                    }
                }
            }
        }

        // 3. EDITABLE AUDITORY TASTE ANCHORS (CENTRAL FOCUS)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "EXPLICIT AUDITORY ANCHORS",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryText,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "Genres & artists that calibrate your Chrono AI Crate",
                            fontSize = 11.sp,
                            color = colors.secondaryText
                        )
                    }

                    Button(
                        onClick = {
                            newAnchorText = ""
                            showAddAnchorDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepObsidianAmber.NeuralViolet.copy(alpha = 0.2f),
                            contentColor = DeepObsidianAmber.NeuralViolet
                        ),
                        border = BorderStroke(1.dp, DeepObsidianAmber.NeuralViolet.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("+ Add Anchor", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Interactive Chips Flow
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tasteAnchors.forEach { anchor ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DeepObsidianAmber.RecessedBackground,
                            border = BorderStroke(1.dp, DeepObsidianAmber.CopperAccent.copy(alpha = 0.5f)),
                            modifier = Modifier.wrapContentSize()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(DeepObsidianAmber.PrimaryAccent)
                                )
                                Text(
                                    text = anchor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.primaryText
                                )
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove $anchor",
                                    tint = colors.secondaryText,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .clickable { vm.removeTasteAnchor(anchor) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. CROSS-PLATFORM SONIC GENOME & ACOUSTIC RADAR
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SONIC SPECTRUM & DOPAMINE RADAR",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.SecondaryAccent
                    )
                    Text(
                        text = "OBSERVED PARITY",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.secondaryText
                    )
                }

                val genomeDimensions = listOf(
                    Triple("Nocturnal Lo-Fi & Afrobeats", 48, DeepObsidianAmber.PrimaryAccent),
                    Triple("Cyber Electro & Synthwave", 32, DeepObsidianAmber.SecondaryAccent),
                    Triple("Neo-Soul & Acoustic Harmonics", 20, DeepObsidianAmber.NeuralViolet)
                )

                genomeDimensions.forEach { (dimension, pct, barColor) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(dimension, fontSize = 12.sp, color = colors.primaryText, fontWeight = FontWeight.Medium)
                            Text("$pct%", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = barColor, fontWeight = FontWeight.Bold)
                        }
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = barColor,
                            trackColor = DeepObsidianAmber.RecessedBackground
                        )
                    }
                }

                HorizontalDivider(color = DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 4.dp))

                // Acoustic Biometrics Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("SATIETY RESISTANCE", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = colors.secondaryText)
                        Text("88% (Resilient)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.DecibelSafe)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("NOVELTY TOLERANCE", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = colors.secondaryText)
                        Text("74% (Exploratory)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.PrimaryAccent)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("CIRCADIAN SHIFT", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = colors.secondaryText)
                        Text("Night-Weighted", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.NeuralViolet)
                    }
                }
            }
        }

        // 5. OBSERVED AUDIO SOURCES BREAKDOWN
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "OBSERVED MUSIC PLAYERS IN THIS PROFILE",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.secondaryText,
                    letterSpacing = 0.8.sp
                )

                syncedSources.forEach { sourcePkg ->
                    val appName = when {
                        sourcePkg.contains("spotify", true) -> "Spotify"
                        sourcePkg.contains("boomplay", true) -> "Boomplay Music"
                        sourcePkg.contains("youtube", true) || sourcePkg.contains("music", true) -> "YouTube Music"
                        sourcePkg.contains("apple", true) -> "Apple Music"
                        sourcePkg.contains("audiomack", true) -> "Audiomack"
                        else -> sourcePkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = colors.primaryAccent, modifier = Modifier.size(14.dp))
                            Text(appName, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.primaryText)
                        }
                        Text("Telemetry Synchronized", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = DeepObsidianAmber.DecibelSafe)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    // DIALOG: Edit Profile (Name & Archetype)
    if (showEditProfileDialog) {
        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false },
            title = { Text("Edit Sonic Profile", color = colors.primaryText, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Listener Name / Alias", color = colors.secondaryText) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.primaryText,
                            unfocusedTextColor = colors.primaryText,
                            focusedBorderColor = DeepObsidianAmber.NeuralViolet,
                            unfocusedBorderColor = DeepObsidianAmber.SurfaceBorder
                        )
                    )

                    OutlinedTextField(
                        value = editArchetype,
                        onValueChange = { editArchetype = it },
                        label = { Text("Acoustic Archetype", color = colors.secondaryText) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.primaryText,
                            unfocusedTextColor = colors.primaryText,
                            focusedBorderColor = DeepObsidianAmber.NeuralViolet,
                            unfocusedBorderColor = DeepObsidianAmber.SurfaceBorder
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
                    colors = ButtonDefaults.buttonColors(containerColor = DeepObsidianAmber.NeuralViolet)
                ) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditProfileDialog = false }) {
                    Text("Cancel", color = colors.secondaryText)
                }
            },
            containerColor = DeepObsidianAmber.CardElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // DIALOG: Add Auditory Taste Anchor
    if (showAddAnchorDialog) {
        AlertDialog(
            onDismissRequest = { showAddAnchorDialog = false },
            title = { Text("Add Taste Anchor", color = colors.primaryText, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter an artist, genre, or sonic subculture (e.g. Afrobeats, Burna Boy, Lo-Fi, Amapiano, Synthwave):",
                        fontSize = 12.sp,
                        color = colors.secondaryText
                    )
                    OutlinedTextField(
                        value = newAnchorText,
                        onValueChange = { newAnchorText = it },
                        label = { Text("Anchor Keyword", color = colors.secondaryText) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.primaryText,
                            unfocusedTextColor = colors.primaryText,
                            focusedBorderColor = DeepObsidianAmber.PrimaryAccent,
                            unfocusedBorderColor = DeepObsidianAmber.SurfaceBorder
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
                    colors = ButtonDefaults.buttonColors(containerColor = DeepObsidianAmber.PrimaryAccent)
                ) {
                    Text("Add Anchor", color = DeepObsidianAmber.AppBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAnchorDialog = false }) {
                    Text("Cancel", color = colors.secondaryText)
                }
            },
            containerColor = DeepObsidianAmber.CardElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }
}
