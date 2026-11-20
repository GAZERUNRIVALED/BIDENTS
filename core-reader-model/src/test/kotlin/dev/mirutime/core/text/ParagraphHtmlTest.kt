package dev.mirutime.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class ParagraphHtmlTest {

    @Test
    fun `newlines split paragraphs and text is escaped`() {
        assertEquals(
            "<p>A &amp; B</p><p>C &lt;tag&gt;</p>",
            textToParagraphHtml("A & B\n\n  C <tag>  \n\n"),
        )
    }

    @Test
    fun `blank text yields empty html`() {
        assertEquals("", textToParagraphHtml("   \n  \n"))
    }

    @Test
    fun `block html is preserved`() {
        val html = "<p>One</p><p><img src=\"images/1.jpg\"/></p>"
        assertEquals(html, chapterHtml(html))
    }

    @Test
    fun `plain text with a literal less-than is escaped`() {
        assertEquals("<p>Tom &amp; Jerry &lt;tag&gt;</p>", chapterHtml("Tom & Jerry <tag>"))
    }

    @Test
    fun `local body splits on blank lines and keeps a lone newline as a break`() {
        assertEquals(
            "<p>Verse one<br/>verse two</p><p>Next paragraph</p>",
            localTextToParagraphHtml("Verse one\nverse two\n\nNext paragraph\n"),
        )
    }

    @Test
    fun `local body escapes text around an explicit line break`() {
        assertEquals("<p>a &lt; b<br/>c &amp; d</p>", localTextToParagraphHtml("a < b\nc & d "))
    }

    @Test
    fun `local body with only whitespace yields empty html`() {
        assertEquals("", localTextToParagraphHtml(" \n\n \n "))
    }
}
