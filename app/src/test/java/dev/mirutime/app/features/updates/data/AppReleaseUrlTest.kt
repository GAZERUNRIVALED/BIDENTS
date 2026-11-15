package dev.mirutime.app.features.updates.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Regression: "$AppRelease.RepoUrl/issues/new" interpolates the object, not the
// property, and produced a scheme-less string no browser could handle.
class AppReleaseUrlTest {

    @Test
    fun `new issue url is absolute and derived from the repo url`() {
        assertEquals("${AppRelease.RepoUrl}/issues/new", AppRelease.NewIssueUrl)
        assertTrue(AppRelease.NewIssueUrl.startsWith("https://github.com/"))
        assertFalse(
            "template must not interpolate the object reference: ${AppRelease.NewIssueUrl}",
            AppRelease.NewIssueUrl.contains("@"),
        )
    }
}
