package com.tunemind.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.theme.DeepObsidianAmber
import com.tunemind.app.ui.theme.TuneMindTheme

/**
 * Reusable TuneMind UI Components implementing the Deep Obsidian Amber Visual Identity.
 *
 * Color distribution:
 * - 65% Deep signature background (#652814)
 * - 23% Layered amber surfaces (#78351E, #854126, #713019, #4F2012)
 * - 8% Copper details (#C2763F)
 * - 4% Gold highlights (#C68A46, #D9A054)
 */

@Composable
fun TuneMindCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    backgroundColor: Color = DeepObsidianAmber.CardBackground,
    borderColor: Color = DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f),
    borderWidth: Dp = 1.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = backgroundColor,
        border = BorderStroke(borderWidth, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun TuneMindElevatedCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    backgroundColor: Color = DeepObsidianAmber.CardElevated,
    borderColor: Color = DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.7f),
    borderWidth: Dp = 1.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = backgroundColor,
        border = BorderStroke(borderWidth, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun TuneMindPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DeepObsidianAmber.PrimaryAccent,
            contentColor = DeepObsidianAmber.AppBackground,
            disabledContainerColor = DeepObsidianAmber.CardElevated,
            disabledContentColor = DeepObsidianAmber.TextDisabled
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) DeepObsidianAmber.AppBackground else DeepObsidianAmber.TextDisabled,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
fun TuneMindSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (enabled) DeepObsidianAmber.CopperAccent else DeepObsidianAmber.SurfaceBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = DeepObsidianAmber.CardBackground,
            contentColor = if (enabled) DeepObsidianAmber.TextPrimary else DeepObsidianAmber.TextDisabled
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.TextDisabled,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun TuneMindIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = DeepObsidianAmber.TextPrimary,
    backgroundColor: Color = DeepObsidianAmber.CardElevated,
    size: Dp = 40.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .border(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}

@Composable
fun TuneMindSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = DeepObsidianAmber.TextPrimary,
                letterSpacing = (-0.2).sp
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = DeepObsidianAmber.TextSecondary
                )
            }
        }
        if (actionText != null && onActionClick != null) {
            Text(
                text = actionText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = DeepObsidianAmber.PrimaryAccent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onActionClick)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun TuneMindProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = DeepObsidianAmber.PrimaryAccent,
    trackColor: Color = DeepObsidianAmber.RecessedBackground
) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
        color = color,
        trackColor = trackColor
    )
}

@Composable
fun TuneMindBadge(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = DeepObsidianAmber.CardElevated,
    textColor: Color = DeepObsidianAmber.PrimaryAccent,
    borderColor: Color = DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f)
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = textColor,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun TuneMindTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label?.let { { Text(it, color = DeepObsidianAmber.TextSecondary) } },
        placeholder = placeholder?.let { { Text(it, color = DeepObsidianAmber.TextMuted) } },
        singleLine = singleLine,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = DeepObsidianAmber.CardBackground,
            unfocusedContainerColor = DeepObsidianAmber.CardBackground,
            focusedBorderColor = DeepObsidianAmber.PrimaryAccent,
            unfocusedBorderColor = DeepObsidianAmber.SurfaceBorder,
            focusedTextColor = DeepObsidianAmber.TextPrimary,
            unfocusedTextColor = DeepObsidianAmber.TextPrimary,
            cursorColor = DeepObsidianAmber.PrimaryAccent
        )
    )
}
