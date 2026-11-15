package dev.mirutime.app.features.library.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.SheetSection
import dev.mirutime.app.features.library.data.LibraryShelfSummary
import dev.mirutime.app.features.library.domain.Shelf

// System shelves are pinned: no rename, no delete, no reorder.
fun Shelf.isSystemShelf(): Boolean = id == "reading" || id == "completed"

// Display-only names of the seeded system shelves. The data layer stores them
// with English labels, so match by id — never by label.
@StringRes
private fun systemShelfLabelRes(id: String): Int? = when (id) {
    "reading" -> R.string.shelf_system_reading
    "completed" -> R.string.shelf_system_completed
    else -> null
}

@Composable
fun shelfLabel(shelf: Shelf): String =
    systemShelfLabelRes(shelf.id)?.let { stringResource(it) } ?: shelf.label

fun shelfLabel(shelf: Shelf, resources: Resources): String =
    systemShelfLabelRes(shelf.id)?.let { resources.getString(it) } ?: shelf.label

// Shelf manager overlay (08-library.md): long-press a tab or the manage icon
// opens this. Create/Rename/Delete/Reorder live inside; picking a shelf is the
// tab row's job. Dedup names, anti-blank, confirm on delete.
@Composable
fun ShelfManagerSheet(
    shelves: List<Shelf>,
    summaries: List<LibraryShelfSummary>,
    onClose: () -> Unit,
    onCreateShelf: (name: String, skipUpdates: Boolean) -> Unit,
    onRenameShelf: (shelf: Shelf, name: String, skipUpdates: Boolean) -> Unit,
    onDeleteShelf: (Shelf) -> Unit,
    onMoveShelf: (Shelf, Int) -> Unit,
) {
    var createOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Shelf?>(null) }
    var deleteTarget by remember { mutableStateOf<Shelf?>(null) }

    val counts = remember(summaries) { summaries.associate { it.shelfId to it.bookCount } }

    if (createOpen) {
        ShelfEditSheet(
            title = stringResource(R.string.shelf_new_title),
            description = stringResource(R.string.shelf_new_desc),
            confirmLabel = stringResource(R.string.shelf_create),
            initial = "",
            initialSkipUpdates = false,
            shelves = shelves,
            exceptId = null,
            onClose = { createOpen = false },
            onConfirm = { name, skip ->
                onCreateShelf(name, skip)
                createOpen = false
            },
        )
        return
    }
    renameTarget?.let { target ->
        ShelfEditSheet(
            title = stringResource(R.string.shelf_edit_title),
            description = stringResource(R.string.shelf_edit_desc),
            confirmLabel = stringResource(R.string.shelf_save),
            initial = target.label,
            initialSkipUpdates = target.excludeFromUpdates,
            shelves = shelves,
            exceptId = target.id,
            onClose = { renameTarget = null },
            onConfirm = { name, skip ->
                onRenameShelf(target, name, skip)
                renameTarget = null
            },
        )
        return
    }
    deleteTarget?.let { target ->
        DeleteShelfSheet(
            shelf = target,
            bookCount = counts[target.id] ?: 0,
            onClose = { deleteTarget = null },
            onConfirm = {
                onDeleteShelf(target)
                deleteTarget = null
            },
        )
        return
    }

    AppBottomSheet(
        title = stringResource(R.string.shelf_title),
        description = stringResource(R.string.shelf_desc),
        onClose = onClose,
        doneLabel = stringResource(R.string.lib_done),
        onDone = onClose,
    ) {
        SheetSection(title = stringResource(R.string.shelf_section_manage), collapsible = false) {
            TextButton(onClick = { createOpen = true }) {
                Text(text = stringResource(R.string.shelf_new_btn))
            }
            shelves.forEach { shelf ->
                ManageShelfRow(
                    shelf = shelf,
                    bookCount = counts[shelf.id] ?: 0,
                    canMoveUp = shelves.canMove(shelf, -1),
                    canMoveDown = shelves.canMove(shelf, 1),
                    onMove = { dir -> onMoveShelf(shelf, dir) },
                    onRename = { renameTarget = shelf },
                    onDelete = { deleteTarget = shelf },
                )
            }
        }
    }
}

@Composable
private fun ManageShelfRow(
    shelf: Shelf,
    bookCount: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shelfLabel(shelf),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = buildString {
                    if (shelf.isSystemShelf()) append(stringResource(R.string.shelf_system))
                    append(
                        pluralStringResource(
                            R.plurals.plural_books,
                            bookCount,
                            bookCount,
                        ),
                    )
                    if (shelf.excludeFromUpdates) append(stringResource(R.string.shelf_updates_off))
                },
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
            )
        }
        if (!shelf.isSystemShelf()) {
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.shelf_cd_up),
                )
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.shelf_cd_down),
                )
            }
            IconButton(onClick = onRename) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.shelf_cd_edit),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.shelf_cd_delete),
                )
            }
        }
    }
}

// Reorder runs inside the non-system tail; system shelves stay pinned on top.
private fun List<Shelf>.canMove(shelf: Shelf, dir: Int): Boolean {
    if (shelf.isSystemShelf()) return false
    val tail = filterNot { it.isSystemShelf() }
    val index = tail.indexOf(shelf)
    if (index < 0) return false
    val target = index + dir
    return target in tail.indices
}
