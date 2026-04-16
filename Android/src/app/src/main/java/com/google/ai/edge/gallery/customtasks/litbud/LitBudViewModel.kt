/*
 * LitBud — Offline AI Reading Tutor for Children
 * Apache 2.0 License (same as Google AI Edge Gallery fork)
 */

package com.google.ai.edge.gallery.customtasks.litbud

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "LitBudViewModel"

private const val OCR_PROMPT =
    "Extract all text from this book page. Return only the text, preserving paragraph breaks. Do not add any commentary or extra text."

/**
 * Coaching prompt sent after fuzzy matching.
 * PAGE_TEXT and WORD_ANALYSIS are substituted at runtime.
 */
private const val COACHING_PROMPT_TEMPLATE =
    "PAGE TEXT:\n%s\n\nWORD ANALYSIS:\n%s\n\nThe child just finished reading this page aloud. " +
    "Coach them warmly. If they did well, celebrate it. If they struggled with words, give " +
    "one specific phonics hint for the hardest word. Keep it to 2-3 sentences."

enum class LitBudPhase { CAPTURE, PROCESSING, READING, COACHING, RESULT, DASHBOARD, ERROR }

data class LitBudUiState(
    val phase: LitBudPhase = LitBudPhase.CAPTURE,
    val capturedBitmap: Bitmap? = null,
    val ocrText: String = "",
    val wordResults: List<WordResult> = emptyList(),
    val coachingText: String = "",
    val friendlyError: String = "",
)

@HiltViewModel
class LitBudViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LitBudUiState())
    val uiState = _uiState.asStateFlow()

    /** Reactive stream of all reading attempts — drives the Dashboard chart. */
    val progressFlow by lazy {
        LitBudDatabase.getInstance(context).progressDao().allEntries()
    }

    /**
     * Executes tool calls found in the raw coaching response.
     * Writes to Room DB and SharedPreferences; never crashes on malformed JSON.
     */
    private val toolCallHandler by lazy { ToolCallHandler(context) }

    /**
     * System prompt loaded from assets once and reused for every coaching call.
     * OCR and transcription calls do NOT use this — they have their own simple prompts.
     */
    private val coachingSystemInstruction: Contents? by lazy {
        try {
            val text = context.assets.open("prompts/tutor_system.txt")
                .bufferedReader().use { it.readText() }
            if (text.isNotEmpty()) Contents.of(Content.Text(text)) else null
        } catch (e: Exception) {
            Log.w(TAG, "Could not load tutor_system.txt: ${e.message}")
            null
        }
    }

    // ─── Feature 1: Page Capture & OCR ───────────────────────────────────────

    /**
     * Called when the child taps "Capture Page".
     * Snapshots [bitmap] from the live camera feed and runs OCR via Gemma 4 vision.
     */
    fun captureAndOcr(bitmap: Bitmap, model: Model) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update {
                it.copy(phase = LitBudPhase.PROCESSING, capturedBitmap = bitmap)
            }

            if (!waitForModel(model)) {
                showError("The model isn't ready yet. Try again in a moment!")
                return@launch
            }

            model.runtimeHelper.resetConversation(
                model = model,
                supportImage = true,
                supportAudio = false,
            )

            var fullResponse = ""

            model.runtimeHelper.runInference(
                model = model,
                input = OCR_PROMPT,
                images = listOf(bitmap),
                resultListener = { partialResult, done, _ ->
                    fullResponse += partialResult

                    if (done) {
                        val text = fullResponse.trim()
                        if (text.isNotEmpty()) {
                            _uiState.update {
                                it.copy(phase = LitBudPhase.READING, ocrText = text)
                            }
                        } else {
                            showError(
                                "I couldn't read the page clearly. " +
                                    "Try holding the phone steady and make sure the text is well-lit!"
                            )
                        }
                    }
                },
                cleanUpListener = {
                    if (_uiState.value.phase == LitBudPhase.PROCESSING) {
                        showError("Oops, something went wrong. Let's try again!")
                    }
                },
                onError = { message ->
                    Log.e(TAG, "OCR inference error: $message")
                    showError("Oops, something went wrong. Let's try again!")
                },
                coroutineScope = viewModelScope,
            )
        }
    }

    // ─── Feature 2: Listen & Compare ─────────────────────────────────────────

    /**
     * Called when the child finishes reading aloud (audio auto-stopped or they tapped Done).
     * [audioBytes] is raw PCM-16 at 16kHz from Android's AudioRecord.
     * [pageText] is the OCR text from the current reading session.
     *
     * Flow: fuzzy match (local) → coaching prompt → Gemma 4 → display
     */
    fun processReading(audioBytes: ByteArray, pageText: String, model: Model) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(phase = LitBudPhase.COACHING) }

            if (!waitForModel(model)) {
                showError("The model isn't ready yet. Try again in a moment!")
                return@launch
            }

            model.runtimeHelper.resetConversation(
                model = model,
                supportImage = false,
                supportAudio = true,
            )

            // Step 1: Transcribe the audio — ask model to extract what was spoken.
            val transcribePrompt =
                "The child read this page aloud. Listen to the audio and write out exactly " +
                "what they said, word by word. Return only the spoken words, no commentary.\n\n" +
                "PAGE TEXT:\n$pageText"

            var transcription = ""

            model.runtimeHelper.runInference(
                model = model,
                input = transcribePrompt,
                audioClips = listOf(audioBytes),
                resultListener = { partial, done, _ ->
                    transcription += partial
                    if (done) {
                        val spokenText = transcription.trim()
                        runFuzzyAndCoach(spokenText = spokenText, pageText = pageText, model = model)
                    }
                },
                cleanUpListener = {
                    if (_uiState.value.phase == LitBudPhase.COACHING) {
                        showError("Oops, something went wrong while listening. Let's try again!")
                    }
                },
                onError = { message ->
                    Log.e(TAG, "Transcription error: $message")
                    showError("I had trouble hearing that. Make sure the microphone is unblocked!")
                },
                coroutineScope = viewModelScope,
            )
        }
    }

    private fun runFuzzyAndCoach(spokenText: String, pageText: String, model: Model) {
        viewModelScope.launch(Dispatchers.Default) {
            // Step 2: fuzzy matching (fully local, no model call)
            val pageWords = FuzzyMatcher.tokenize(pageText)
            val spokenWords = FuzzyMatcher.tokenize(spokenText)
            val wordResults = FuzzyMatcher.compare(pageWords, spokenWords)

            _uiState.update { it.copy(wordResults = wordResults) }

            // Step 3: coaching prompt uses only the struggling/missed words
            val needsHelp = FuzzyMatcher.needsHelp(wordResults)
            val wordAnalysis = if (needsHelp.isEmpty()) {
                "All words read correctly!"
            } else {
                needsHelp.joinToString("\n") { r ->
                    "\"${r.expected}\" — said \"${r.spoken.ifEmpty { "(nothing)" }}\" [${r.status.name}]"
                }
            }
            val coachingPrompt = COACHING_PROMPT_TEMPLATE.format(pageText, wordAnalysis)

            model.runtimeHelper.resetConversation(
                model = model,
                supportImage = false,
                supportAudio = false,
                systemInstruction = coachingSystemInstruction,
            )

            var coachingResponse = ""

            model.runtimeHelper.runInference(
                model = model,
                input = coachingPrompt,
                resultListener = { partial, done, _ ->
                    coachingResponse += partial
                    if (done) {
                        // Execute tool calls (DB writes, SharedPrefs) before stripping JSON
                        toolCallHandler.handle(coachingResponse, viewModelScope)
                        val coachingText = parseCoachingResponse(coachingResponse)
                        _uiState.update {
                            it.copy(
                                phase = LitBudPhase.RESULT,
                                coachingText = coachingText,
                            )
                        }
                    }
                },
                cleanUpListener = {
                    if (_uiState.value.phase == LitBudPhase.COACHING) {
                        showError("Oops, something went wrong. Let's try again!")
                    }
                },
                onError = { message ->
                    Log.e(TAG, "Coaching inference error: $message")
                    showError("Oops, something went wrong. Let's try again!")
                },
                coroutineScope = viewModelScope,
            )
        }
    }

    // ─── Navigation ───────────────────────────────────────────────────────────

    /** Open the progress dashboard (full-screen overlay). */
    fun showDashboard() {
        _uiState.update { it.copy(phase = LitBudPhase.DASHBOARD) }
    }

    /** Return from dashboard — go back to RESULT if we came from there, else CAPTURE. */
    fun hideDashboard() {
        val hasResult = _uiState.value.coachingText.isNotEmpty() || _uiState.value.wordResults.isNotEmpty()
        _uiState.update { it.copy(phase = if (hasResult) LitBudPhase.RESULT else LitBudPhase.CAPTURE) }
    }

    /** Go back to reading screen (re-use same OCR result). */
    fun tryReadingAgain() {
        _uiState.update {
            it.copy(
                phase = LitBudPhase.READING,
                wordResults = emptyList(),
                coachingText = "",
            )
        }
    }

    /** Full reset back to camera screen. */
    fun reset() {
        _uiState.update { LitBudUiState() }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Strips non-display content from the raw model response before showing it to the child:
     *   1. Thinking blocks: <think>...</think>
     *   2. Tool call blocks: ```json { "tool_calls": [...] } ```
     *   3. Bare JSON objects that contain a "tool_calls" key (fallback if no fences)
     */
    private fun parseCoachingResponse(raw: String): String {
        var text = raw
        // Remove <think>...</think> blocks (Gemma thinking mode traces)
        text = text.replace(
            Regex("<think>[\\s\\S]*?</think>", setOf(RegexOption.IGNORE_CASE)),
            "",
        )
        // Remove ```json ... ``` fenced tool call blocks
        text = text.replace(Regex("```json[\\s\\S]*?```"), "")
        // Remove any remaining bare { "tool_calls": ... } JSON (no fences)
        val toolCallMarkers = listOf(
            "{\"tool_calls\"",
            "{ \"tool_calls\"",
            "{\n  \"tool_calls\"",
            "{\n\"tool_calls\"",
        )
        for (marker in toolCallMarkers) {
            val idx = text.indexOf(marker)
            if (idx >= 0) {
                text = text.substring(0, idx)
                break
            }
        }
        return text.trim()
    }

    private suspend fun waitForModel(model: Model): Boolean {
        var waited = 0
        while (model.instance == null && waited < 30_000) {
            delay(100)
            waited += 100
        }
        return model.instance != null
    }

    private fun showError(message: String) {
        _uiState.update {
            it.copy(phase = LitBudPhase.ERROR, friendlyError = message)
        }
    }
}
