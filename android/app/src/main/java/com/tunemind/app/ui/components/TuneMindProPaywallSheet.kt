package com.tunemind.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.billing.BillingUiState
import com.tunemind.app.billing.PlayPlanUiModel
import com.tunemind.app.billing.TuneMindBillingManager
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

/**
 * Calm, transparent TuneMind Pro upgrade sheet.
 * Clearly highlights what stays Free Forever, presents Yearly & Monthly 7-day trial plans,
 * and triggers native Google Play Billing checkout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuneMindProPaywallSheet(
    billingState: BillingUiState,
    triggerReason: String? = null,
    onDismiss: () -> Unit,
    onSelectPlanAndPurchase: (productId: String) -> Unit,
    onRestorePurchases: () -> Unit,
    onManageSubscription: () -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedPlanId by remember { mutableStateOf(TuneMindBillingManager.PRODUCT_ANNUAL) }
    val scrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.cardSurface,
        contentColor = Color(0xFFF8FAFC),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.22f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 22.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(theme.primaryAccent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = theme.primaryAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "TuneMind Pro",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = "7-day free trial via Google Play",
                            fontSize = 12.sp,
                            color = theme.primaryAccent
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.65f)
                    )
                }
            }

            if (!triggerReason.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(theme.primaryAccent.copy(alpha = 0.12f))
                        .border(1.dp, theme.primaryAccent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = triggerReason,
                        color = Color(0xFFE2E8F0),
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            // Free Forever Reassurance Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF10B981).copy(alpha = 0.10f))
                    .border(1.dp, Color(0xFF10B981).copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Headphones,
                    contentDescription = null,
                    tint = Color(0xFF34D399),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Free forever: Background listening tracking, true play counts, playlists, Spotify Premium playback & 2 daily picks stay 100% free.",
                    color = Color(0xFFD1FAE5),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }

            // What Pro Unlocks
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Included in your 7-day trial",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.85f)
                )
                ProBenefitRow("Conversational Pulse AI Agent for mood-based music discovery")
                ProBenefitRow("Full 15-song For You Daily Drop (12 genre + 3 wildcard picks) & refreshes")
                ProBenefitRow("Smart Dopamine Vault & Hearing / Ear Care deep insights")
            }

            // Plan Cards
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                billingState.plans.forEach { plan ->
                    PlanSelectionCard(
                        plan = plan,
                        selected = selectedPlanId == plan.productId,
                        accent = theme.primaryAccent,
                        onClick = { selectedPlanId = plan.productId }
                    )
                }
            }

            if (!billingState.statusMessage.isNullOrBlank()) {
                Text(
                    text = billingState.statusMessage,
                    color = Color(0xFFFDE68A),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (billingState.hasProAccess) {
                Button(
                    onClick = onManageSubscription,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF10B981),
                        contentColor = Color(0xFF041410)
                    )
                ) {
                    Text(
                        text = if (billingState.isTrial) {
                            "7-Day Trial Active (${billingState.trialDaysRemaining}d left) · Manage in Play Store"
                        } else {
                            "TuneMind Pro Active · Manage in Play Store"
                        },
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Button(
                    onClick = { onSelectPlanAndPurchase(selectedPlanId) },
                    enabled = !billingState.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = theme.primaryAccent,
                        contentColor = Color(0xFF06101B)
                    )
                ) {
                    if (billingState.isLoading) {
                        TuneMindStudioLoader(
                            color = Color(0xFF06101B),
                            secondaryColor = theme.primaryAccent,
                            strokeWidth = 2.dp,
                            size = 18.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connecting to Google Play...", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start 7-Day Free Trial", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onRestorePurchases, enabled = !billingState.isLoading) {
                    Text(
                        text = "Restore Purchase",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Continue Free",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.58f)
                    )
                }
            }

            val context = LocalContext.current
            TextButton(
                onClick = {
                    val sku = Uri.encode(selectedPlanId)
                    val pkg = Uri.encode(context.packageName)
                    val url = "https://play.google.com/store/account/subscriptions?sku=$sku&package=$pkg"
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = "Manage or Cancel Subscription in Google Play",
                    color = Color(0xFF00F0FF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = "Google Play Billing: 7 days free, then renews automatically at your selected plan rate. Cancel anytime before Day 7 in Google Play Subscriptions with \$0 charge.",
                color = Color(0xFFB8B2CC),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ProBenefitRow(text: String) {
    val theme = LocalAdaptiveTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = theme.primaryAccent,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            color = Color(0xFFE2E8F0),
            fontSize = 12.5.sp,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun PlanSelectionCard(
    plan: PlayPlanUiModel,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val bgModifier = if (selected) {
        Modifier.background(
            Brush.horizontalGradient(
                colors = listOf(accent.copy(alpha = 0.20f), accent.copy(alpha = 0.08f))
            )
        )
    } else {
        Modifier.background(Color.White.copy(alpha = 0.04f))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(bgModifier)
            .border(
                BorderStroke(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) accent else Color.White.copy(alpha = 0.14f)
                ),
                shape = shape
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = plan.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = plan.badge,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) accent else Color.White.copy(alpha = 0.78f)
                    )
                }
            }
            Text(
                text = plan.subtext,
                fontSize = 11.5.sp,
                color = Color.White.copy(alpha = 0.64f)
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = plan.formattedPrice,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (selected) accent else Color(0xFFF8FAFC)
            )
            Text(
                text = plan.periodLabel,
                fontSize = 10.5.sp,
                color = Color.White.copy(alpha = 0.54f)
            )
        }
    }
}
