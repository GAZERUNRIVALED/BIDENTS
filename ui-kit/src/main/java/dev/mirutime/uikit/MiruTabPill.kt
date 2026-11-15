package dev.mirutime.uikit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

// One shelf tab: a compact 32dp pill inside the standard 48dp touch box. Over
// a photo theme each pill stands on its own plate; the unselected pill matches
// the plate fill (surfaceContainerLow) of the adjacent manage button, while
// the active pill takes the accent fill.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiruTabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
) {
    val onImage = LocalMiruOnImage.current
    val accent = LocalMiruExtraColors.current
    val zone = accent.photoTop
    val scheme = MaterialTheme.colorScheme
    val ink = if (selected) {
        if (onImage) accent.onAccent else scheme.onPrimary
    } else if (onImage) {
        zone.pillInk
    } else {
        scheme.onSurface
    }
    val background = if (selected) {
        if (onImage) accent.accent else scheme.primary
    } else {
        scheme.surfaceContainerLow
    }
    Box(
        modifier = modifier
            .heightIn(min = MiruSize.Touch)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = MiruSize.ControlCompactLarge)
                .clip(RoundedCornerShape(MiruRadius.Control))
                .background(background)
                .padding(horizontal = MiruSpace.M),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xxs),
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = iconContentDescription,
                        tint = ink,
                        modifier = Modifier.size(MiruSize.IconSmall),
                    )
                }
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = ink,
                )
            }
        }
    }
}
