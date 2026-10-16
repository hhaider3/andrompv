package dev.andrompv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A translucent panel with a soft top highlight, standing in for macmpv's Liquid Glass. */
fun Modifier.glass(
    shape: Shape,
    fill: Color = Palette.glass,
    opacity: Float = 0.86f,
    elevation: Dp = 18.dp
): Modifier = this
    .shadow(elevation, shape, ambientColor = Color.Black, spotColor = Color.Black)
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(
                fill.copy(alpha = (opacity + 0.06f).coerceAtMost(1f)),
                fill.copy(alpha = opacity)
            )
        )
    )
    .border(
        BorderStroke(
            1.dp,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.17f), Color.White.copy(alpha = 0.05f)))
        ),
        shape
    )

/** Circular icon control with macmpv's press feedback: a faint fill and a slight shrink. */
@Composable
fun PlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    tint: Color = Color.White.copy(alpha = 0.92f),
    background: Color = Color.Transparent,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(120), label = "press")
    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = Color.White),
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick
            )
            .alpha(if (enabled) 1f else 0.32f),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Capsule button used for "Open", the speed picker and skip chips. */
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentDescription: String = text,
    background: Color = Color.Black.copy(alpha = 0.32f),
    contentColor: Color = Color.White,
    border: Boolean = true,
    height: Dp = 36.dp,
    horizontalPadding: Dp = 14.dp,
    fontSize: Int = 13,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .heightIn(min = height)
            .clip(shape)
            .background(background)
            .then(if (border) Modifier.border(1.dp, Palette.stroke, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
        Text(text, color = contentColor, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun GlassMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        shape = RoundedCornerShape(14.dp),
        containerColor = Palette.menu,
        border = BorderStroke(1.dp, Palette.stroke),
        shadowElevation = 16.dp,
        modifier = Modifier.widthIn(min = 220.dp, max = 340.dp),
        content = content
    )
}

/** A menu row; `checked != null` reserves the checkmark column like a macOS picker menu. */
@Composable
fun MenuEntry(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    checked: Boolean? = null,
    detail: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true
) {
    val color = if (destructive) Palette.danger else Palette.textPrimary
    DropdownMenuItem(
        text = {
            Column {
                Text(text, color = color, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, color = Palette.textTertiary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = when {
            checked != null -> {
                { Box(Modifier.width(20.dp)) { if (checked) Icon(Icons.Rounded.Check, null, tint = Palette.accent, modifier = Modifier.size(18.dp)) } }
            }
            icon != null -> {
                { Icon(icon, null, tint = color.copy(alpha = 0.85f), modifier = Modifier.size(20.dp)) }
            }
            else -> null
        },
        colors = MenuDefaults.itemColors(textColor = color, disabledTextColor = Palette.textTertiary),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)
    )
}

@Composable
fun MenuLabel(text: String) {
    Text(
        text.uppercase(),
        color = Palette.textTertiary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun MenuDivider() = HorizontalDivider(color = Palette.stroke, modifier = Modifier.padding(vertical = 4.dp))

@Composable
fun MetadataPill(text: String) {
    Text(
        text,
        color = Palette.textSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.fill)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
fun SectionCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = Palette.textTertiary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        modifier = modifier
    )
}

/** Centered glass card dialog used for every sheet; content scrolls when it outgrows the screen. */
@Composable
fun GlassDialog(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    maxWidth: Dp = 440.dp,
    scrollable: Boolean = true,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .imePadding()
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .glass(RoundedCornerShape(24.dp), fill = Palette.glassStrong, opacity = 0.97f)
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Palette.accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(icon, null, tint = Palette.accent, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (subtitle != null) Text(subtitle, color = Palette.textSecondary, fontSize = 12.sp)
                }
                PlayerIconButton(Icons.Rounded.Close, "Close", size = 34.dp, iconSize = 18.dp, background = Palette.fill, onClick = onDismiss)
            }
            Spacer(Modifier.size(16.dp))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                content = content
            )
            if (actions != null) {
                Spacer(Modifier.size(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    actions()
                }
            }
        }
    }
}
