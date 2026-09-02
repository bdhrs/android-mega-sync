package org.bodhirasa.androidmegasync.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoreRuleTest {

    @Test
    fun exactPathIsIgnored() {
        val rule = PathListIgnoreRule(listOf("notes/a.md"))
        assertTrue(rule.isIgnored("notes/a.md"))
    }

    @Test
    fun pathUnderExcludedFolderIsIgnored() {
        val rule = PathListIgnoreRule(listOf("archive"))
        assertTrue(rule.isIgnored("archive/2020/a.md"))
    }

    @Test
    fun unrelatedPathIsNotIgnored() {
        val rule = PathListIgnoreRule(listOf("archive"))
        assertFalse(rule.isIgnored("archive2/a.md"))
        assertFalse(rule.isIgnored("notes/a.md"))
    }

    @Test
    fun similarlyNamedSiblingIsNotIgnored() {
        // "archive-old" shares the "archive" prefix but isn't inside it — pins the
        // subtree match down to a real path-segment boundary ("archive/…"), not a
        // naive startsWith(it).
        val rule = PathListIgnoreRule(listOf("archive"))
        assertFalse(rule.isIgnored("archive-old/a.md"))
    }
}
