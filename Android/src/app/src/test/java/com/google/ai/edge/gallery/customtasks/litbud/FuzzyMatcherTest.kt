/*
 * LitBud — Offline AI Reading Tutor for Children
 * Apache 2.0 License (same as Google AI Edge Gallery fork)
 */

package com.google.ai.edge.gallery.customtasks.litbud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyMatcherTest {

    // ─── normalize ────────────────────────────────────────────────────────────

    @Test fun `normalize strips punctuation`() {
        assertEquals("cat", FuzzyMatcher.normalize("cat,"))
        assertEquals("dog", FuzzyMatcher.normalize("dog."))
        assertEquals("hello", FuzzyMatcher.normalize("Hello!"))
    }

    @Test fun `normalize lowercases`() {
        assertEquals("the", FuzzyMatcher.normalize("The"))
        assertEquals("quick", FuzzyMatcher.normalize("QUICK"))
    }

    @Test fun `normalize handles empty`() {
        assertEquals("", FuzzyMatcher.normalize(""))
        assertEquals("", FuzzyMatcher.normalize("---"))
    }

    // ─── similarity ───────────────────────────────────────────────────────────

    @Test fun `identical words score 100`() {
        assertEquals(100, FuzzyMatcher.similarity("cat", "cat"))
    }

    @Test fun `completely different words score 0`() {
        // "ab" vs "cd" — distance=2, maxLen=2 → 0%
        assertEquals(0, FuzzyMatcher.similarity("ab", "cd"))
    }

    @Test fun `both empty score 100`() {
        assertEquals(100, FuzzyMatcher.similarity("", ""))
    }

    @Test fun `one empty scores 0`() {
        assertEquals(0, FuzzyMatcher.similarity("cat", ""))
        assertEquals(0, FuzzyMatcher.similarity("", "dog"))
    }

    // ─── threshold boundary tests (the locked ones) ───────────────────────────

    @Test fun `score at or above 85 is CORRECT`() {
        // Use compare() so we go through the full pipeline
        val results = FuzzyMatcher.compare(listOf("cat"), listOf("cat"))
        assertEquals(WordStatus.CORRECT, results[0].status)
        assertEquals(100, results[0].score)
    }

    @Test fun `score 60 to 84 is STRUGGLING`() {
        // "kite" vs "mite": distance=1, maxLen=4 → 75% → STRUGGLING
        val score = FuzzyMatcher.similarity("kite", "mite")
        assertTrue("Expected 60-84, got $score", score in 60..84)
        val results = FuzzyMatcher.compare(listOf("kite"), listOf("mite"))
        assertEquals(WordStatus.STRUGGLING, results[0].status)
    }

    @Test fun `score below 60 is MISSED`() {
        // "elephant" vs "zzz": very different
        val results = FuzzyMatcher.compare(listOf("elephant"), listOf("zzz"))
        assertEquals(WordStatus.MISSED, results[0].status)
        assertTrue(results[0].score < 60)
    }

    // ─── positional matching ──────────────────────────────────────────────────

    @Test fun `shorter spoken list fills tail with MISSED`() {
        val page = listOf("the", "cat", "sat")
        val spoken = listOf("the", "cat")
        val results = FuzzyMatcher.compare(page, spoken)
        assertEquals(3, results.size)
        assertEquals(WordStatus.CORRECT, results[0].status)
        assertEquals(WordStatus.CORRECT, results[1].status)
        assertEquals(WordStatus.MISSED, results[2].status)
        assertEquals("", results[2].spoken)
        assertEquals(0, results[2].score)
    }

    @Test fun `extra spoken words are ignored`() {
        val page = listOf("cat")
        val spoken = listOf("cat", "dog", "fish")
        val results = FuzzyMatcher.compare(page, spoken)
        assertEquals(1, results.size)
        assertEquals(WordStatus.CORRECT, results[0].status)
    }

    // ─── tokenize ─────────────────────────────────────────────────────────────

    @Test fun `tokenize splits on whitespace`() {
        assertEquals(listOf("The", "cat", "sat"), FuzzyMatcher.tokenize("The cat sat"))
    }

    @Test fun `tokenize trims extra spaces`() {
        assertEquals(listOf("a", "b"), FuzzyMatcher.tokenize("  a   b  "))
    }

    @Test fun `tokenize empty string returns empty list`() {
        assertEquals(emptyList<String>(), FuzzyMatcher.tokenize(""))
        assertEquals(emptyList<String>(), FuzzyMatcher.tokenize("   "))
    }

    // ─── needsHelp filter ─────────────────────────────────────────────────────

    @Test fun `needsHelp excludes CORRECT words`() {
        val page = listOf("the", "cat", "sat")
        val spoken = listOf("the", "bat", "zzz")
        val results = FuzzyMatcher.compare(page, spoken)
        val help = FuzzyMatcher.needsHelp(results)
        assertTrue(help.none { it.status == WordStatus.CORRECT })
        assertTrue(help.size <= 2)
    }
}
