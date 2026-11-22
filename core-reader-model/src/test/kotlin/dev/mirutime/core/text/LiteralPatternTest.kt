package dev.mirutime.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteralPatternTest {

    @Test
    fun `plain terms stay readable`() {
        assertEquals("Иван", literalPattern("Иван"))
        assertEquals("John Smith", literalPattern("John Smith"))
    }

    @Test
    fun `metacharacters are escaped`() {
        assertEquals("""a\.b""", literalPattern("a.b"))
        assertEquals("""x\*y""", literalPattern("x*y"))
        assertEquals("""\(v2\)""", literalPattern("(v2)"))
        assertEquals("""a\\b""", literalPattern("""a\b"""))
    }

    @Test
    fun `escaped term matches only itself`() {
        val pattern = literalPattern("a.b")
        assertTrue(Regex(pattern).matches("a.b"))
        assertEquals("aXb", Regex(pattern).replace("aXb", "hit"))
    }

    // An enabled empty replacement would delete the term from every chapter,
    // so a seeded rule must stay off until the user types a replacement.
    @Test
    fun `seeded term rule is literal, whole-word and off`() {
        val rule = termSeedRule("Иван", id = 7)

        assertEquals(7L, rule.id)
        assertEquals("Иван", rule.pattern)
        assertEquals("", rule.replacement)
        assertTrue(rule.wholeWords)
        assertTrue(!rule.enabled)
        assertEquals(RegexRuleScope.Both, rule.scope)
    }

    @Test
    fun `seeded rule uses the list scope`() {
        assertEquals(RegexRuleScope.Translation, termSeedRule("a", 1, RegexRuleScope.Translation).scope)
    }
}
