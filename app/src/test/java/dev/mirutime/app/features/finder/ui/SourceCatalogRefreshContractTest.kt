package dev.mirutime.app.features.finder.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Guards the catalogue refresh wiring (issue #16): the screen has no Scaffold,
// so the action is hoisted into the shell header for every catalogue type
// (plugin, local, OPDS); the plugin reload still drops the source's other
// lists, its filter schema and the old list position. These are source checks,
// not pixel tests.
class SourceCatalogRefreshContractTest {

    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
        File("../$relative"),
    ).firstOrNull { it.isFile }?.readText()
        ?: error("source not found: $relative")

    private fun screen(): String =
        source("src/main/java/dev/mirutime/app/features/finder/ui/SourceCatalogScreen.kt")

    private fun shell(): String =
        source("src/main/java/dev/mirutime/app/shell/MiruShell.kt")

    private fun header(): String =
        source("src/main/java/dev/mirutime/app/shell/AppHeader.kt")

    @Test
    fun `refresh forgets the source's other lists and its filter schema`() {
        val catalog = screen()
        assertTrue(catalog.contains("sessions.invalidateSource(sourceId, keep = filters)"))
        assertTrue(catalog.contains("provider.refreshSource(sourceId)"))
        assertTrue(catalog.contains("schemaTick++"))
        assertTrue(catalog.contains("session.refresh(force = true)"))
    }

    @Test
    fun `refresh lands on the first page and skips a load already in flight`() {
        val catalog = screen()
        assertTrue(catalog.contains("gridState.scrollToItem(0)"))
        assertTrue(catalog.contains("if (!session.state.value.loading)"))
    }

    @Test
    fun `every catalogue type hoists a reload and drops it on dispose`() {
        val catalog = screen()
        // No catalogue type is left without a header action.
        assertFalse(catalog.contains("if (isLocal || isOpds) null"))
        assertTrue(catalog.contains("isOpds -> reloadOpds"))
        assertTrue(catalog.contains("isLocal -> reloadLocal"))
        assertTrue(catalog.contains("else -> reloadSource"))
        assertTrue(catalog.contains("val headerReload = rememberUpdatedState("))
        assertTrue(catalog.contains("onRefreshAction { headerReload.value() }"))
        assertTrue(catalog.contains("onDispose { onRefreshAction(null) }"))
    }

    @Test
    fun `each reload keeps the gate and the work of its old in-row button`() {
        val catalog = screen()
        // Three reload paths (plugin, OPDS, local), all behind the same gate.
        assertEquals(3, catalog.split("if (!session.state.value.loading)").size - 1)
        assertTrue(catalog.contains("opdsSession?.refresh(force = true)"))
        // The local reload still bumps the tick the cards reload on.
        val local = catalog.substringAfter("val reloadLocal: () -> Unit = {")
            .substringBefore("val headerReload")
        assertTrue(local.contains("reloadTick++"))
        assertTrue(local.contains("session.refresh(force = true)"))
    }

    @Test
    fun `search row keeps filters, site and folders but no refresh`() {
        val catalog = screen()
        // Refresh lives in the shell header for every catalogue type now.
        assertFalse(catalog.contains("Icons.Filled.Refresh"))
        assertFalse(catalog.contains("onClick = { opdsSession?.refresh"))
        assertFalse(catalog.contains("onClick = { reloadTick++; session.refresh"))
        assertTrue(
            catalog.contains(
                "contentDescription = stringResource(R.string.finder_cd_filters)",
            ),
        )
        assertTrue(catalog.contains("{ onSiteOpen(siteUrl) }"))
        assertTrue(
            catalog.contains(
                "contentDescription = stringResource(R.string.finder_local_manage)",
            ),
        )
    }

    @Test
    fun `shell header renders the hoisted catalogue refresh`() {
        val shell = shell()
        assertTrue(shell.contains("var catalogRefreshAction by remember"))
        assertTrue(shell.contains("onRefreshAction = { catalogRefreshAction = it }"))
        assertTrue(shell.contains("catalogRefreshAction?.takeIf { onCatalog }"))
        assertTrue(shell.contains("icon = Icons.Filled.Refresh"))
        assertTrue(shell.contains("R.string.finder_local_refresh_cd"))
        assertTrue(shell.contains("onClick = reload"))
        assertTrue(header().contains("action?.invoke()"))
    }
}
