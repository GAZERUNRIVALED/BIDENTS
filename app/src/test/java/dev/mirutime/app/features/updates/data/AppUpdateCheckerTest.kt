package dev.mirutime.app.features.updates.data

import dev.mirutime.app.core.RemoteBytes
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    private val releaseJson = """
        {"tag_name":"v0.2.0","name":"0.2.0","html_url":"https://github.com/HnDK0/MiruTime/releases/tag/v0.2.0"}
    """.trimIndent()

    private fun response(status: Int, body: String = "") = RemoteBytes(
        status = status,
        bytes = body.encodeToByteArray(),
        headers = emptyMap(),
        finalUrl = AppRelease.LatestReleaseApi,
        location = null,
    )

    private fun checker(
        fetch: suspend (String, Map<String, String>, Long) -> RemoteBytes,
    ) = AppUpdateChecker(AppReleaseFetch { url, headers, timeoutMs -> fetch(url, headers, timeoutMs) })

    @Test
    fun `newer release is available`() = runBlocking {
        val result = checker { _, _, _ -> response(200, releaseJson) }.check("0.1.0")

        assertEquals(
            AppUpdateResult.Available("v0.2.0", "https://github.com/HnDK0/MiruTime/releases/tag/v0.2.0", "0.2.0"),
            result,
        )
    }

    @Test
    fun `same release is up to date`() = runBlocking {
        val result = checker { _, _, _ -> response(200, releaseJson) }.check("0.2.0")

        assertEquals(AppUpdateResult.UpToDate("v0.2.0"), result)
    }

    @Test
    fun `404 means no releases`() = runBlocking {
        assertEquals(AppUpdateResult.NoReleases, checker { _, _, _ -> response(404) }.check("0.1.0"))
    }

    @Test
    fun `5xx is an error with the status`() = runBlocking {
        val result = checker { _, _, _ -> response(503) }.check("0.1.0")

        assertEquals(AppUpdateResult.Error("GitHub returned HTTP 503"), result)
    }

    @Test
    fun `unreadable body is an error`() = runBlocking {
        val result = checker { _, _, _ -> response(200, "not json") }.check("0.1.0")

        assertEquals(AppUpdateResult.Error("Unreadable release response"), result)
    }

    @Test
    fun `thrown fetch failure is an error`() = runBlocking {
        val result = checker { _, _, _ -> throw IOException("offline") }.check("0.1.0")

        assertEquals(AppUpdateResult.Error("offline"), result)
    }

    @Test
    fun `request keeps the release headers and the 15 second budget`() = runBlocking {
        var seenUrl: String? = null
        var seenHeaders: Map<String, String>? = null
        var seenTimeout: Long? = null
        checker { url, headers, timeoutMs ->
            seenUrl = url
            seenHeaders = headers
            seenTimeout = timeoutMs
            response(200, releaseJson)
        }.check("0.1.0")

        assertEquals(AppRelease.LatestReleaseApi, seenUrl)
        assertEquals("application/vnd.github+json", seenHeaders?.get("Accept"))
        assertEquals("MiruTime/0.1.0", seenHeaders?.get("User-Agent"))
        assertEquals(15_000L, seenTimeout)
    }
    @Test
    fun `release json keeps tag and browser url`() {
        val release = AppUpdateParser.parse(
            """
            {
              "tag_name": "v0.2.0",
              "name": "0.2.0",
              "html_url": "https://github.com/HnDK0/MiruTime/releases/tag/v0.2.0"
            }
            """.trimIndent(),
        )

        assertEquals("v0.2.0", release?.tagName)
        assertEquals("https://github.com/HnDK0/MiruTime/releases/tag/v0.2.0", release?.htmlUrl)
    }

    @Test
    fun `release body is parsed for the notification`() = runBlocking {
        val body = """{"tag_name":"v0.2.0","html_url":"https://example.test/r","body":"## Added\n- Fixed playback"}"""
        val result = checker { _, _, _ -> response(200, body) }.check("0.1.0")

        assertEquals(
            "## Added\n- Fixed playback",
            (result as AppUpdateResult.Available).body,
        )
    }

    @Test
    fun `release notes are plain text and capped`() {
        assertEquals(
            "Added playback\nFixed export",
            appUpdateReleaseNotes("## Added playback\n\n- Fixed export"),
        )
        assertEquals("abc", appUpdateReleaseNotes("abc", maxChars = 3))
        assertEquals("", appUpdateReleaseNotes(null))
    }

    @Test
    fun `template body keeps only plain text`() {
        val body = """
            <div align="center">
            <img src="https://example.test/banner.png" width="200">
            <!-- Draft -->
            ## What's new
            - Fixed &amp; improved playback
            ![shot](https://example.test/shot.png)
            <details><summary><b>Changelog</b></summary>text</details>
            </div>
        """.trimIndent()

        assertEquals(
            "What's new\nFixed & improved playback\ntext",
            appUpdateReleaseNotes(body),
        )
        // Entities decode before the cap, so the 800th character is not inside one.
        assertEquals(800, appUpdateReleaseNotes("&amp;".repeat(1000)).length)
    }

    @Test
    fun `template body notifies only the english changelog without artifacts`() {
        val body = """
            <div align="center">
            <img src="https://example.test/icon.png" width="88" height="88" alt="MiruTime"/>

            # MiruTime v0.2.0

            Reader for novels, manga and video
            </div>

            <details>
            <summary><b>🇷🇺 Русская версия</b></summary>

            > 🤖 Список изменений собран автоматически.

            Изменения с `v1.2.3`:

            ### ✨ Новое

            - feat(reader): русская строка

            ## Артефакты

            - `MiruTime-v0.2.0-arm64-v8a.apk` — для современных устройств.

            [Полный список изменений](https://example.test/compare/v1.2.3...v0.2.0).
            </details>

            <details open>
            <summary><b>🇬🇧 English version</b></summary>

            > 🤖 The changelog is generated automatically from commit messages.

            > ⚠️ Back up the library before upgrading; Room migrations are one-way.

            Changes since `v1.2.3`:

            ### ✨ New

            - feat(reader): english change line

            ## Artifacts

            - `MiruTime-v0.2.0-arm64-v8a.apk` — for modern 64-bit devices.
            - `MiruTime-v0.2.0-universal.apk` — for emulators and 32-bit devices.

            [Full changelog](https://example.test/compare/v1.2.3...v0.2.0).
            </details>

            <!-- Draft: add release highlights before publishing. -->
        """.trimIndent()

        val notes = appUpdateReleaseNotes(body)

        assertTrue(notes.contains("feat(reader): english change line"))
        assertFalse(notes.contains("English version"))
        assertFalse(notes.contains("Русская версия"))
        assertFalse(notes.contains("русская строка"))
        assertFalse(notes.contains("Артефакты"))
        assertFalse(notes.contains("Artifacts"))
        assertFalse(notes.contains("arm64-v8a"))
        assertFalse(notes.contains("compare/"))
        assertTrue(notes.length <= 800)
    }

    @Test
    fun `body without an english spoiler falls back to the whole text`() {
        assertEquals(
            "What's new\nFixed playback",
            appUpdateReleaseNotes("## What's new\n\n- Fixed playback"),
        )
    }

    @Test
    fun `body without an english spoiler still drops the artifacts section`() {
        assertEquals(
            "What's new\nFixed playback",
            appUpdateReleaseNotes(
                "## What's new\n\n- Fixed playback\n\n## Artifacts\n\n- app.apk",
            ),
        )
    }

    @Test
    fun `missing or broken payload is not a release`() {
        assertNull(AppUpdateParser.parse("{}"))
        assertNull(AppUpdateParser.parse("not json"))
        assertNull(AppUpdateParser.parse("""{"tag_name":"","html_url":""}"""))
    }

    @Test
    fun `version compare ignores the v prefix and trailing zeroes`() {
        assertTrue(AppVersion.isNewer("v0.2", "0.1"))
        assertTrue(AppVersion.isNewer("0.1.1", "0.1"))
        assertFalse(AppVersion.isNewer("v0.1", "0.1.0"))
        assertFalse(AppVersion.isNewer("0.1", "0.1"))
        assertTrue(AppVersion.isNewer("1.10", "1.9"))
    }

    @Test
    fun `prerelease sorts before the final release`() {
        assertTrue(AppVersion.isNewer("1.0.0", "1.0.0-beta.1"))
        assertFalse(AppVersion.isNewer("1.0.0-beta.1", "1.0.0"))
        assertTrue(AppVersion.isNewer("1.0.0-beta.2", "1.0.0-beta.1"))
    }

    @Test
    fun `invalid versions never claim an update`() {
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("v"))
        assertNull(AppVersion.parse("abc"))
        assertFalse(AppVersion.isNewer("abc", "0.1"))
        assertFalse(AppVersion.isNewer("0.2", "abc"))
    }
}
