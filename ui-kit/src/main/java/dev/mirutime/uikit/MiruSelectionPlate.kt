package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

/**
 * THE floating selection plate: a solid tinted card over scrolling content,
 * one header row (close / count / select-all / clear) and one action strip
 * that never wraps — on narrow screens it scrolls sideways instead of growing.
 *
 * Both bulk-selection surfaces (chapter list, library) fill the two slots, so
 * labels, callbacks and enabled predicates stay with their owners while the
 * container stays one component.
 */
@Composable
fun MiruSelectionPlate(
    modifier: Modifier = Modifier,
    header: @Composable RowScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(MiruRadius.Control)
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Solid tint: rows scroll behind the plate and must not read
            // through it.
            .miruCardShadow(shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, content = header)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = MiruSpace.S, end = MiruSpace.S, bottom = MiruSpace.S),
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
            content = actions,
        )
    }
}
