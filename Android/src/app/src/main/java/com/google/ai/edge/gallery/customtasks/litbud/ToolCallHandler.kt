/*
 * LitBud — Offline AI Reading Tutor for Children
 * Apache 2.0 License (same as Google AI Edge Gallery fork)
 */

package com.google.ai.edge.gallery.customtasks.litbud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ToolCallHandler"
private const val PREFS_NAME = "litbud_prefs"
const val PREF_KEY_DIFFICULTY = "current_difficulty_level"

/**
 * Extracts and executes tool calls from a raw coaching model response.
 *
 * The tutor_system.txt prompt instructs the model to append a JSON block at the
 * end of every response:
 *
 *   ```json
 *   { "tool_calls": [ { "name": "...", "arguments": { ... } } ] }
 *   ```
 *
 * This handler:
 *   - Extracts the JSON block (handles fenced and bare formats)
 *   - Routes each call to its handler
 *   - Returns hint text if `get_hint` was called (for the coaching display), null otherwise
 *   - Never crashes — a malformed tool call is logged and skipped
 */
class ToolCallHandler(private val context: Context) {

    private val db by lazy { LitBudDatabase.getInstance(context) }
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Parses all tool calls in [rawResponse] and executes them.
     * DB writes are dispatched on [coroutineScope] using [Dispatchers.IO].
     *
     * @return Hint text from `get_hint`, or null if not called.
     */
    fun handle(rawResponse: String, coroutineScope: CoroutineScope): String? {
        val json = extractToolCallsJson(rawResponse) ?: return null
        var hintText: String? = null

        try {
            val toolCalls: JSONArray = json.getJSONArray("tool_calls")
            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.getJSONObject(i)
                val name = call.optString("name")
                val args = call.optJSONObject("arguments") ?: JSONObject()
                when (name) {
                    "track_progress"    -> handleTrackProgress(args, coroutineScope)
                    "log_session"       -> handleLogSession(args, coroutineScope)
                    "get_hint"          -> hintText = args.optString("hint").takeIf { it.isNotEmpty() }
                    "adjust_difficulty" -> handleAdjustDifficulty(args)
                    else                -> Log.w(TAG, "Unknown tool: $name — ignored")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool call execution failed: ${e.message}")
            // Coaching text is still shown — never let a parse failure reach the child
        }

        return hintText
    }

    // ── Tool implementations ──────────────────────────────────────────────────

    private fun handleTrackProgress(args: JSONObject, scope: CoroutineScope) {
        val accuracy = args.optDouble("accuracy_percent", 0.0).toFloat()
        val wpm = args.optDouble("words_per_minute", 0.0).toFloat()
        val struggled = args.optJSONArray("struggled_words") ?: JSONArray()

        scope.launch(Dispatchers.IO) {
            try {
                db.progressDao().insert(
                    ProgressEntity(
                        timestamp = System.currentTimeMillis(),
                        accuracyPercent = accuracy,
                        wordsPerMinute = wpm,
                        struggledWords = struggled.toString(),
                    )
                )
                Log.d(TAG, "track_progress → accuracy=$accuracy wpm=$wpm")
            } catch (e: Exception) {
                Log.e(TAG, "track_progress DB write failed: ${e.message}")
            }
        }
    }

    private fun handleLogSession(args: JSONObject, scope: CoroutineScope) {
        val durationSec = args.optInt("duration_seconds", 0)
        val overallAccuracy = args.optDouble("overall_accuracy", 0.0).toFloat()
        val vocab = args.optJSONArray("new_vocabulary") ?: JSONArray()

        scope.launch(Dispatchers.IO) {
            try {
                db.sessionDao().insert(
                    SessionEntity(
                        date = LocalDate.now().toString(),
                        durationSeconds = durationSec,
                        overallAccuracy = overallAccuracy,
                        newVocabulary = vocab.toString(),
                    )
                )
                Log.d(TAG, "log_session → duration=${durationSec}s accuracy=$overallAccuracy")
            } catch (e: Exception) {
                Log.e(TAG, "log_session DB write failed: ${e.message}")
            }
        }
    }

    private fun handleAdjustDifficulty(args: JSONObject) {
        val newLevel = args.optInt("new_level", 1).coerceIn(1, 5)
        prefs.edit().putInt(PREF_KEY_DIFFICULTY, newLevel).apply()
        Log.d(TAG, "adjust_difficulty → level=$newLevel")
    }

    // ── JSON extraction ───────────────────────────────────────────────────────

    /**
     * Finds and parses the first valid `{ "tool_calls": [...] }` JSON object in [raw].
     * Tries fenced (```json ... ```) first, then falls back to bare-text markers.
     */
    private fun extractToolCallsJson(raw: String): JSONObject? {
        // 1. Fenced: ```json { "tool_calls": [...] } ```
        val fenceMatch = Regex("```json([\\s\\S]*?)```").find(raw)
        if (fenceMatch != null) {
            val candidate = fenceMatch.groupValues[1].trim()
            tryParse(candidate)?.let { return it }
        }

        // 2. Bare JSON starting at a known key marker
        val markers = listOf(
            "{\"tool_calls\"",
            "{ \"tool_calls\"",
            "{\n  \"tool_calls\"",
            "{\n\"tool_calls\"",
        )
        for (marker in markers) {
            val idx = raw.indexOf(marker)
            if (idx >= 0) {
                tryParse(raw.substring(idx))?.let { return it }
            }
        }

        return null
    }

    private fun tryParse(text: String): JSONObject? {
        return try {
            val obj = JSONObject(text)
            if (obj.has("tool_calls")) obj else null
        } catch (e: Exception) {
            null
        }
    }
}
