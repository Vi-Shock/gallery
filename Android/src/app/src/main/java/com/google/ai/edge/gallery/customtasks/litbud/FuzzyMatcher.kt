/*
 * LitBud — Offline AI Reading Tutor for Children
 * Apache 2.0 License (same as Google AI Edge Gallery fork)
 */

package com.google.ai.edge.gallery.customtasks.litbud

import org.apache.commons.text.similarity.LevenshteinDistance

/** Locked thresholds — do not change without product sign-off. */
const val THRESHOLD_CORRECT = 85
const val THRESHOLD_STRUGGLING = 60

enum class WordStatus {
    CORRECT,      // Read correctly (score ≥ 85)
    STRUGGLING,   // Mispronounced (score 60–84)
    MISSED,       // Skipped or badly wrong within the words the child attempted
    NOT_REACHED,  // Child stopped reading before this word — do not penalise
}

data class WordResult(
    val expected: String,
    val spoken: String,
    val score: Int,       // 0–100 similarity
    val status: WordStatus,
)

object FuzzyMatcher {

    private val levenshtein = LevenshteinDistance()

    /**
     * Compare two word lists using alignment-aware matching.
     *
     * [pageWords] — words extracted from the book page (from OCR text).
     * [spokenWords] — words the child spoke (from model transcription).
     *
     * Strategy:
     *   1. Find the best-match spoken word for each page word within a search
     *      window (handles insertions / skips without cascading misalignment).
     *   2. Page words beyond the last matched spoken position are NOT_REACHED —
     *      the child ran out of time or stopped; they are not penalised.
     *   3. Page words within the spoken range that have no good match are MISSED.
     *
     * @return list of [WordResult] the same length as [pageWords].
     */
    fun compare(pageWords: List<String>, spokenWords: List<String>): List<WordResult> {
        if (spokenWords.isEmpty()) {
            // Nothing was spoken at all — everything is NOT_REACHED, not MISSED
            return pageWords.map { expected ->
                WordResult(expected = expected, spoken = "", score = 0, status = WordStatus.NOT_REACHED)
            }
        }

        // Align: for each page word, find the best-scoring spoken word within a
        // sliding window of ±WINDOW positions around the current spoken cursor.
        // This tolerates a few inserted/skipped words without breaking all downstream alignment.
        val WINDOW = 3
        val results = mutableListOf<WordResult>()
        var spokenCursor = 0   // how far into spokenWords we have consumed

        for ((pageIdx, expected) in pageWords.withIndex()) {
            val normalizedExpected = normalize(expected)

            // Search window: look a few positions ahead of the cursor
            val searchEnd = minOf(spokenCursor + WINDOW + 1, spokenWords.size)
            var bestScore = -1
            var bestOffset = 0  // offset from spokenCursor

            for (offset in 0 until (searchEnd - spokenCursor)) {
                val candidate = normalize(spokenWords[spokenCursor + offset])
                val s = similarity(normalizedExpected, candidate)
                if (s > bestScore) {
                    bestScore = s
                    bestOffset = offset
                }
            }

            // If we've consumed all spoken words, remaining page words are NOT_REACHED
            if (spokenCursor >= spokenWords.size) {
                results.add(
                    WordResult(expected = expected, spoken = "", score = 0, status = WordStatus.NOT_REACHED)
                )
                continue
            }

            // Accept the best match if it's at least STRUGGLING-level; otherwise treat as MISSED
            val accepted = bestScore >= THRESHOLD_STRUGGLING
            if (accepted) {
                // Advance cursor past any skipped spoken words and this match
                spokenCursor += bestOffset + 1
                results.add(
                    WordResult(
                        expected = expected,
                        spoken = spokenWords[spokenCursor - 1],
                        score = bestScore,
                        status = statusFromScore(bestScore),
                    )
                )
            } else {
                // No good match found in the window — mark as MISSED but don't advance cursor
                // (the page word may have been skipped; the spoken cursor stays for the next page word)
                results.add(
                    WordResult(expected = expected, spoken = "", score = 0, status = WordStatus.MISSED)
                )
            }
        }

        // Post-pass: if the child clearly ran out of time (last N consecutive MISSED all have
        // no spoken counterpart AND spokenCursor didn't advance past them), reclassify the
        // trailing run of MISSED → NOT_REACHED.
        // Simple heuristic: find the last result where spoken is non-empty, and reclassify
        // everything after that as NOT_REACHED.
        val lastSpokenIdx = results.indexOfLast { it.spoken.isNotEmpty() }
        if (lastSpokenIdx >= 0 && lastSpokenIdx < results.lastIndex) {
            for (i in (lastSpokenIdx + 1)..results.lastIndex) {
                val r = results[i]
                if (r.status == WordStatus.MISSED && r.spoken.isEmpty()) {
                    results[i] = r.copy(status = WordStatus.NOT_REACHED)
                }
            }
        }

        return results
    }

    /** Extract page words from raw OCR text. */
    fun tokenize(text: String): List<String> =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /**
     * Filter results to only struggling or missed words — used when building the coaching prompt.
     * NOT_REACHED words are excluded: the child didn't attempt them, so don't coach on them.
     */
    fun needsHelp(results: List<WordResult>): List<WordResult> =
        results.filter { it.status == WordStatus.STRUGGLING || it.status == WordStatus.MISSED }

    // ─── internals ────────────────────────────────────────────────────────────

    internal fun normalize(word: String): String =
        word.lowercase().replace(Regex("[^a-z0-9]"), "").trim()

    /**
     * Returns 0–100 similarity score. 100 = identical after normalization.
     * Uses Levenshtein edit distance divided by the length of the longer string.
     */
    internal fun similarity(a: String, b: String): Int {
        if (a.isEmpty() && b.isEmpty()) return 100
        if (a.isEmpty() || b.isEmpty()) return 0
        val maxLen = maxOf(a.length, b.length)
        val distance = levenshtein.apply(a, b)
        return ((1.0 - distance.toDouble() / maxLen) * 100).toInt().coerceIn(0, 100)
    }

    private fun statusFromScore(score: Int): WordStatus = when {
        score >= THRESHOLD_CORRECT -> WordStatus.CORRECT
        score >= THRESHOLD_STRUGGLING -> WordStatus.STRUGGLING
        else -> WordStatus.MISSED
    }
}
