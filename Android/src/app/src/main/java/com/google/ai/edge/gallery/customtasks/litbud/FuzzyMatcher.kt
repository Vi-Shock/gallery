/*
 * LitBud — Offline AI Reading Tutor for Children
 * Apache 2.0 License (same as Google AI Edge Gallery fork)
 */

package com.google.ai.edge.gallery.customtasks.litbud

import org.apache.commons.text.similarity.LevenshteinDistance

/** Locked thresholds — do not change without product sign-off. */
const val THRESHOLD_CORRECT = 85
const val THRESHOLD_STRUGGLING = 60

enum class WordStatus { CORRECT, STRUGGLING, MISSED }

data class WordResult(
    val expected: String,
    val spoken: String,
    val score: Int,       // 0–100 similarity
    val status: WordStatus,
)

object FuzzyMatcher {

    private val levenshtein = LevenshteinDistance()

    /**
     * Compare two word lists positionally.
     *
     * [pageWords] — words extracted from the book page (from OCR text).
     * [spokenWords] — words the child spoke (from model transcription or STT).
     *
     * Words are matched by position. If [spokenWords] is shorter than [pageWords],
     * the remaining page words are marked MISSED with spoken = "".
     *
     * @return list of [WordResult] the same length as [pageWords].
     */
    fun compare(pageWords: List<String>, spokenWords: List<String>): List<WordResult> {
        return pageWords.mapIndexed { index, expected ->
            val normalizedExpected = normalize(expected)
            if (index < spokenWords.size) {
                val normalizedSpoken = normalize(spokenWords[index])
                val score = similarity(normalizedExpected, normalizedSpoken)
                WordResult(
                    expected = expected,
                    spoken = spokenWords[index],
                    score = score,
                    status = statusFromScore(score),
                )
            } else {
                WordResult(
                    expected = expected,
                    spoken = "",
                    score = 0,
                    status = WordStatus.MISSED,
                )
            }
        }
    }

    /** Extract page words from raw OCR text. */
    fun tokenize(text: String): List<String> =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Filter results to only struggling or missed words (used when building the coaching prompt). */
    fun needsHelp(results: List<WordResult>): List<WordResult> =
        results.filter { it.status != WordStatus.CORRECT }

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
