package dev.mirutime.app.features.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruCompactButton
import dev.mirutime.uikit.MiruIconButton
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.SheetSection
import dev.mirutime.uikit.MiruSelectionPlate
import dev.mirutime.app.features.library.domain.Shelf

// Bottom action bar shown while the library is in selection mode. Same
// MiruSelectionPlate container as the chapter-list SelectionBar; busy shows a
// spinner next to the count and disables every submit action, while close
// stays live so the user can always leave selection mode.
@Composable
fun LibrarySelectionBar(
    selectedCount: Int,
    visibleCount: Int,
    busy: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onMove: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    val allSelected = visibleCount > 0 && selectedCount >= visibleCount
    val actionsEnabled = !busy && selectedCount > 0
    MiruSelectionPlate(
        modifier = modifier,
        header = {
            MiruIconButton(
                icon = Icons.Filled.Close,
                contentDescription = stringResource(R.string.lib_selection_close),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                iconSize = MiruSize.IconCompact,
                onClick = onClose,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.plural_selected_books,
                    selectedCount,
                    selectedCount,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            TextButton(
                onClick = onSelectAll,
                enabled = !busy && !allSelected && visibleCount > 0,
            ) {
                Text(text = stringResource(R.string.lib_select_all))
            }
            TextButton(
                onClick = onClear,
                enabled = !busy && selectedCount > 0,
            ) {
                Text(text = stringResource(R.string.lib_clear_selection))
            }
        },
        actions = {
            MiruCompactButton(
                icon = Icons.AutoMirrored.Filled.DriveFileMove,
                label = stringResource(R.string.lib_bulk_move),
                ink = extra.accent,
                enabled = actionsEnabled,
                onClick = onMove,
            )
            MiruCompactButton(
                icon = Icons.Filled.Delete,
                label = stringResource(R.string.lib_bulk_delete),
                ink = MaterialTheme.colorScheme.error,
                enabled = actionsEnabled,
                onClick = onDelete,
            )
            MiruCompactButton(
                icon = Icons.Filled.CheckCircle,
                label = stringResource(R.string.lib_bulk_mark_read),
                ink = extra.accent,
                enabled = actionsEnabled,
                onClick = onMarkRead,
            )
            MiruCompactButton(
                icon = Icons.Filled.RemoveCircleOutline,
                label = stringResource(R.string.lib_bulk_mark_unread),
                ink = extra.accent,
                enabled = actionsEnabled,
                onClick = onMarkUnread,
            )
        },
    )
}

// Delete confirmation: the destructive semantics are explicit (app data and
// downloaded files go, the source document stays).
@Composable
fun BulkDeleteSheet(
    selectedCount: Int,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppBottomSheet(
        title = stringResource(R.string.lib_bulk_delete_title),
        description = pluralStringResource(
            R.plurals.plural_bulk_delete_desc,
            selectedCount,
            selectedCount,
        ),
        onClose = onClose,
        closeLabel = stringResource(R.string.lib_cancel),
        doneLabel = stringResource(R.string.lib_bulk_delete),
        doneDanger = true,
        onDone = onConfirm,
    ) {
        // The description carries the whole warning; keep the sheet glanceable.
    }
}

// Shelf picker for a bulk move. Every shelf stays available, including the
// system Reading/Completed rows; nothing here can delete a shelf.
@Composable
fun BulkMoveShelfSheet(
    shelves: List<Shelf>,
    selectedCount: Int,
    onClose: () -> Unit,
    onPick: (String) -> Unit,
) {
    AppBottomSheet(
        title = stringResource(R.string.lib_move_title),
        description = pluralStringResource(
            R.plurals.plural_bulk_move_desc,
            selectedCount,
            selectedCount,
        ),
        onClose = onClose,
        closeLabel = stringResource(R.string.lib_cancel),
    ) {
        SheetSection(title = stringResource(R.string.lib_move_title)) {
            shelves.forEach { shelf ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(shelf.id) }
                        .padding(vertical = MiruSpace.M),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = shelfLabel(shelf),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (shelf.isSystemShelf()) {
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = stringResource(R.string.lib_move_system),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalMiruExtraColors.current.muted,
                        )
                    }
                }
            }
        }
    }
}
