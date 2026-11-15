package dev.mirutime.app.design

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewStateStoreTest {

    private val roots: List<File> = listOf(
        "src/main/java",
        "app/src/main/java",
    ).map(::File).filter { it.isDirectory }

    private fun source(relative: String): String =
        roots.map { File(it, relative) }.first { it.isFile }.readText()

    @Test
    fun `view state uses the warmed ViewStateStore instance`() {
        val persistedStateSource = source("dev/mirutime/app/design/PersistedState.kt")
        val appSource = source("dev/mirutime/app/MiruApp.kt")

        // ViewStateStore must own the SharedPreferences instance
        assertTrue(persistedStateSource.contains("object ViewStateStore"))
        assertTrue(persistedStateSource.contains("ViewStateStore.get(context)"))

        // rememberPersistedState must go through the warmed instance and never
        // fetch its own SharedPreferences during composition
        val rememberFun = persistedStateSource.substringAfter("fun <T> rememberPersistedState(")
            .substringBefore("\n@Composable")
        assertTrue("rememberPersistedState body must be found", rememberFun.isNotBlank())
        assertFalse(
            "rememberPersistedState must not call context.applicationContext.getSharedPreferences directly",
            rememberFun.contains("context.applicationContext.getSharedPreferences"),
        )

        // MiruApp must pre-warm ViewStateStore in onCreate on Dispatchers.IO
        assertTrue(
            "MiruApp must initialize ViewStateStore on startup",
            appSource.contains("ViewStateStore.init(this@MiruApp)"),
        )
    }
}
