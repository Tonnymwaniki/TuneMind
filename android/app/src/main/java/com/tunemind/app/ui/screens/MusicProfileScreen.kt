package com.tunemind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.SpotifyAvatarCircle
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MusicProfileScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val stats by vm.listeningStats.collectAsState()
    val userProfile by vm.userProfile.collectAsState()
    val tasteAnchors by vm.userTasteAnchors.collectAsState()
    val spotifyProfile by vm.spotifyUserProfile.collectAsState()
    val tasteShifts by vm.tasteShiftSummary.collectAsState()
    val theme = LocalAdaptiveTheme.current

    val monitoredHours = formatListeningDuration(stats.allTimeListeningMs)
    val confidencePct = if (stats.uniqueTrackCount == 0) 0 else (74 + minOf(24, stats.uniqueTrackCount)).coerceAtMost(98)

    var showEditProfileDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(userProfile.name) }
    var editArchetype by remember { mutableStateOf(userProfile.archetype) }

    var showAddAnchorDialog by remember { mutableStateOf(false) }
    var newAnchorText by remember { mutableStateOf("") }

    val sheetFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color(0xFFF8FAFC),
        unfocusedTextColor = Color(0xFFF8FAFC),
        focusedContainerColor = Color(0xFF07090E),
        unfocusedContainerColor = Color(0xFF07090E),
        focusedBorderColor = theme.primaryAccent,
        unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
        focusedLabelColor = theme.primaryAccent,
        unfocusedLabelColor = Color.White.copy(alpha = 0.56f),
        cursorColor = theme.primaryAccent
    )

    StudioAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
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
                        text = "Taste Profile",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                }

                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        tint = Color.White.copy(alpha = 0.80f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 1. Primary Hero Card (only SoftGlassCard on this screen)
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SpotifyAvatarCircle(
                            imageUrl = spotifyProfile?.avatarUrl.orEmpty(),
                            fallbackInitial = userProfile.name.ifEmpty { "T" },
                            size = 56.dp,
                            accentColor = theme.primaryAccent
                        )

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
                                    color = Color(0xFFF8FAFC)
                                )
                                IconButton(
                                    onClick = {
                                        editName = userProfile.name
                                        editArchetype = userProfile.archetype
                                        showEditProfileDialog = true
                                    },
                                    modifier = Modifier.size(32.dp)
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
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = theme.primaryAccent
                            )
                            Text(
                                text = userProfile.archetypeDescription,
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.60f),
                                lineHeight = 17.sp
                            )
                        }
                    }

                    StudioHairlineDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ProfileStatColumn("Listened", monitoredHours, theme.primaryAccent)
                        ProfileStatColumn("Apps", "${stats.appBreakdown.size}", theme.secondaryAccent)
                        ProfileStatColumn("Match", if (confidencePct > 0) "$confidencePct%" else "--", Color(0xFF10B981))
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Settings link row
            StudioHairlineDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSettings() }
                    .padding(vertical = 15.dp, horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = null,
                        tint = theme.primaryAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Playback & Voice Settings",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = "Spotify Remote, players & permissions",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.56f)
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "Open Settings",
                    tint = Color.White.copy(alpha = 0.40f),
                    modifier = Modifier.size(18.dp)
                )
            }
            StudioHairlineDivider()

            Spacer(modifier = Modifier.height(24.dp))

            // Favorite Genres & Artists
            StudioSectionHeader(
                title = "Favorite Genres & Artists",
                actionLabel = "+ Add",
                onActionClick = {
                    newAnchorText = ""
                    showAddAnchorDialog = true
                }
            )
            Text(
                text = "Guides your For You picks and voice suggestions.",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.56f),
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tasteAnchors.forEach { anchor ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(theme.cardSurface)
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                            .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = anchor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFF8FAFC)
                        )
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Remove $anchor",
                            tint = Color.White.copy(alpha = 0.52f),
                            modifier = Modifier
                                .size(15.dp)
                                .clickable { vm.removeTasteAnchor(anchor) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            StudioHairlineDivider()
            Spacer(modifier = Modifier.height(20.dp))

            // Listening Habits
            StudioSectionHeader(title = "Listening Habits")
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ProfileStatColumn("Fresh Balance", "${stats.discoveryRatePercent}% Fresh", Color(0xFF10B981))
                ProfileStatColumn("Completion", "${stats.completionRatePercent}%", theme.primaryAccent)
                ProfileStatColumn("Peak Time", stats.peakWindowLabel.substringBefore(" "), theme.secondaryAccent)
            }

            if (tasteShifts.topGenres.isNotEmpty() || tasteShifts.risingArtists.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                StudioHairlineDivider()
                Spacer(modifier = Modifier.height(20.dp))

                if (tasteShifts.topGenres.isNotEmpty()) {
                    StudioSectionHeader(title = "Spotify Genre DNA")
                    Spacer(modifier = Modifier.height(10.dp))
                    tasteShifts.topGenres.take(5).forEach { weight ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = weight.genre.replaceFirstChar { it.uppercase() },
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFF8FAFC)
                                )
                                Text(
                                    text = "${weight.sharePercent}%",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = theme.primaryAccent
                                )
                            }
                            LinearProgressIndicator(
                                progress = { (weight.sharePercent / 100f).coerceIn(0.06f, 1f) },
                                color = theme.primaryAccent,
                                trackColor = Color.White.copy(alpha = 0.08f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                }

                if (tasteShifts.risingArtists.isNotEmpty()) {
                    StudioSectionHeader(title = "Rising in Your Rotation")
                    Spacer(modifier = Modifier.height(8.dp))
                    StudioHairlineDivider()
                    tasteShifts.risingArtists.take(4).forEach { artistInsight ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 11.dp, horizontal = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = artistInsight.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFF8FAFC),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val genreLine = artistInsight.genres.take(2)
                                    .joinToString(" · ") { g -> g.replaceFirstChar { it.uppercase() } }
                                    .ifBlank { "Recent Spotify favorite" }
                                Text(
                                    text = genreLine,
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.58f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            SpotifyAvatarCircle(
                                imageUrl = artistInsight.imageUrl,
                                fallbackInitial = artistInsight.name,
                                size = 38.dp,
                                accentColor = theme.primaryAccent
                            )
                        }
                        StudioHairlineDivider()
                    }
                }
            }

            if (stats.appBreakdown.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                StudioHairlineDivider()
                Spacer(modifier = Modifier.height(20.dp))

                StudioSectionHeader(title = "Observed Music Apps")
                Spacer(modifier = Modifier.height(8.dp))
                StudioHairlineDivider()

                stats.appBreakdown.forEach { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 13.dp, horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = resolveAppAccentColor(app.packageName, theme.primaryAccent),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = app.displayName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFF8FAFC)
                            )
                        }
                        Text(
                            text = "${app.playCount} plays · ${formatListeningDuration(app.listeningMs)}",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.62f)
                        )
                    }
                    StudioHairlineDivider()
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    // Native Full-Width Edit Profile Bottom Sheet
    if (showEditProfileDialog) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showEditProfileDialog = false },
            sheetState = sheetState,
            containerColor = theme.cardSurface,
            contentColor = Color(0xFFF8FAFC),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.22f)) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Edit Profile",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )

                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(14.dp),
                    colors = sheetFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = editArchetype,
                    onValueChange = { editArchetype = it },
                    label = { Text("Listening Style") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (editName.isNotBlank() && editArchetype.isNotBlank()) {
                                vm.updateUserProfile(editName.trim(), editArchetype.trim())
                                showEditProfileDialog = false
                            }
                        }
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = sheetFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = {
                        if (editName.isNotBlank() && editArchetype.isNotBlank()) {
                            vm.updateUserProfile(editName.trim(), editArchetype.trim())
                            showEditProfileDialog = false
                        }
                    },
                    enabled = editName.isNotBlank() && editArchetype.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF8FAFC),
                        contentColor = Color(0xFF07090E)
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text("Save Changes", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    // Native Full-Width Add Genre or Artist Bottom Sheet
    if (showAddAnchorDialog) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val quickSuggestions = listOf(
            "Afrobeats", "Lo-Fi", "R&B", "Synthwave", "Indie Pop", "Jazz", "Amapiano", "Hip-Hop"
        ).filter { suggestion -> tasteAnchors.none { it.equals(suggestion, ignoreCase = true) } }

        ModalBottomSheet(
            onDismissRequest = { showAddAnchorDialog = false },
            sheetState = sheetState,
            containerColor = theme.cardSurface,
            contentColor = Color(0xFFF8FAFC),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.22f)) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Add Genre or Artist",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = "Tap a suggestion or type an artist, genre, or mood you want more of.",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.62f),
                        lineHeight = 18.sp
                    )
                }

                OutlinedTextField(
                    value = newAnchorText,
                    onValueChange = { newAnchorText = it },
                    label = { Text("Genre, mood, or artist") },
                    placeholder = { Text("e.g., Burna Boy, Soul, Acoustic", color = Color.White.copy(alpha = 0.34f)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (newAnchorText.isNotBlank()) {
                                vm.addTasteAnchor(newAnchorText.trim())
                                showAddAnchorDialog = false
                            }
                        }
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = sheetFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                if (quickSuggestions.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        quickSuggestions.forEach { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF07090E))
                                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                    .clickable {
                                        vm.addTasteAnchor(item)
                                        showAddAnchorDialog = false
                                    }
                                    .padding(horizontal = 11.dp, vertical = 7.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = theme.primaryAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = item,
                                    fontSize = 13.sp,
                                    color = Color(0xFFF8FAFC),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        if (newAnchorText.isNotBlank()) {
                            vm.addTasteAnchor(newAnchorText.trim())
                            showAddAnchorDialog = false
                        }
                    },
                    enabled = newAnchorText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF8FAFC),
                        contentColor = Color(0xFF07090E)
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text("Add to Taste", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ProfileStatColumn(
    label: String,
    value: String,
    accent: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = accent
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.56f)
        )
    }
}
