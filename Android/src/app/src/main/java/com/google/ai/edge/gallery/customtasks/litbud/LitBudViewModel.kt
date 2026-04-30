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

import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray

private const val TAG = "LitBudViewModel"

private const val OCR_PROMPT =
    "Extract all text from this book page. Return only the text, preserving paragraph breaks. Do not add any commentary or extra text."

/**
 * Coaching prompt sent after fuzzy matching.
 * PAGE_TEXT and STRUGGLED_WORDS are substituted at runtime.
 *
 * Key constraint: the model must only coach on words from STRUGGLED WORDS.
 * Without this explicit rule the model reads PAGE TEXT, picks the most complex-
 * looking word on the page (e.g. "transport"), and coaches on it — even when
 * the child actually read that word correctly.
 */
private const val COACHING_PROMPT_TEMPLATE =
    "PAGE TEXT:\n%s\n\n" +
    "STRUGGLED WORDS — the ONLY words that need coaching (empty means child read everything correctly):\n%s\n\n" +
    "The child just finished reading this page aloud. Coach them warmly in 2–3 sentences.\n" +
    "RULE: If STRUGGLED WORDS lists any words, your phonics hint MUST be for one of those words. " +
    "Never give a hint for a word from PAGE TEXT that is not in STRUGGLED WORDS — those were read correctly."

enum class LitBudPhase { CAPTURE, PROCESSING, READING, COACHING, RESULT, DASHBOARD, WORD_DRILL, ERROR }

/** Sub-state used while the child is in the word-by-word practice drill. */
enum class DrillState {
    FETCHING_TIP,  // model generating phonics tip + example sentence
    SHOWING_WORD,  // word + tip on screen, waiting for mic tap
    EVALUATING,    // model checking recorded audio against target word
    WORD_CORRECT,  // child got it — celebrate
    WORD_FAILED,   // used all tries — show encouragement
    COMPLETE,      // all words drilled
}

data class LitBudUiState(
    val phase: LitBudPhase = LitBudPhase.CAPTURE,
    val capturedBitmap: Bitmap? = null,
    val ocrText: String = "",
    val wordResults: List<WordResult> = emptyList(),
    val coachingText: String = "",
    val friendlyError: String = "",
    // ── Word Drill ───────────────────────────────────────────────────────────
    val drillWords: List<String> = emptyList(),    // struggled words to practice
    val drillIndex: Int = 0,                        // current word index
    val drillTip: String = "",                      // phonics tip from model
    val drillSentence: String = "",                 // example sentence from model
    val drillTriesLeft: Int = 3,                    // tries remaining for current word
    val drillState: DrillState = DrillState.FETCHING_TIP,
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

            // Guard: very short audio means the mic didn't capture anything useful.
            // 1000 bytes at 16kHz PCM-16 ≈ 31ms — clearly too short to be speech.
            if (audioBytes.size < 1000) {
                showError("I couldn't hear anything. Try reading a little louder!")
                return@launch
            }

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
            // IMPORTANT: do NOT include the page text here. Including it causes Gemma to echo
            // the "correct" answer from context instead of transcribing the actual audio,
            // making every word appear correct even when the child misread.
            // IMPORTANT: the prompt must strongly discourage sentence completion/hallucination.
            // Gemma as an LLM naturally "fills in" plausible continuations even when the child
            // stopped talking — this makes skipped and unread words appear green. Being
            // explicit that stopping early is correct suppresses most of this behaviour.
            val transcribePrompt =
                "Transcribe only what is clearly spoken in this audio recording. A child is reading aloud.\n" +
                "Rules you MUST follow:\n" +
                "- Write only words you can actually hear, in the exact order they were spoken.\n" +
                "- If the child stops talking, stop writing immediately. Do not continue.\n" +
                "- Do NOT complete sentences. Do NOT add any words that were not spoken.\n" +
                "- Do NOT guess or predict what comes next. Only transcribe what was said.\n" +
                "- A short, accurate transcript is correct. A long, guessed transcript is wrong.\n" +
                "Return only the spoken words with no punctuation changes or commentary."

            var transcription = ""

            // LiteRT-LM miniaudio decoder requires a valid WAV file, not raw PCM bytes.
            val wavBytes = pcmToWav(audioBytes, sampleRate = 16000)
            Log.d(TAG, "Sending WAV to transcription: ${wavBytes.size} bytes")

            try {
                model.runtimeHelper.runInference(
                    model = model,
                    input = transcribePrompt,
                    audioClips = listOf(wavBytes),
                    resultListener = { partial, done, _ ->
                        transcription += partial
                        if (done) {
                            val spokenText = transcription.trim()
                            Log.d(TAG, "Transcription result: '$spokenText'")
                            Log.d(TAG, "Page text for comparison: '$pageText'")
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
            } catch (e: Exception) {
                // LiteRtLmJniException from native miniaudio decoder bypasses the onError
                // callback and propagates as a coroutine exception — catch it here so it
                // never reaches the default crash handler.
                Log.e(TAG, "Audio inference JNI exception: ${e.message}")
                showError("I had trouble hearing that. Make sure the microphone is unblocked!")
            }
        }
    }

    private fun runFuzzyAndCoach(spokenText: String, pageText: String, model: Model) {
        viewModelScope.launch(Dispatchers.Default) {
            // Step 2: fuzzy matching (fully local, no model call)
            val pageWords = FuzzyMatcher.tokenize(pageText)
            val spokenWords = FuzzyMatcher.tokenize(spokenText)
            // Cap transcription length to page length. A child can only speak the words on
            // the page — if the transcription is longer, the model hallucinated extra words.
            // Without this cap, a hallucinated long transcription exhausts the matcher's
            // NOT_REACHED path, making every unread word appear as MISSED (red) instead of
            // NOT_REACHED (gray).
            val spokenWordsCapped = spokenWords.take(pageWords.size)
            val wordResults = FuzzyMatcher.compare(pageWords, spokenWordsCapped)

            Log.d(TAG, "FuzzyMatch — pageWords(${pageWords.size}): $pageWords")
            Log.d(TAG, "FuzzyMatch — spokenWords raw(${spokenWords.size}) capped(${spokenWordsCapped.size}): $spokenWordsCapped")
            wordResults.forEach { r ->
                Log.d(TAG, "  '${r.expected}' vs '${r.spoken}' → score=${r.score} status=${r.status}")
            }

            _uiState.update { it.copy(wordResults = wordResults) }

            // Write accuracy to DB immediately from fuzzy matching — ground truth.
            // Only count words the child actually attempted (not NOT_REACHED).
            // This replaces the model's track_progress tool call, which only guesses.
            val attempted = wordResults.filter { it.status != WordStatus.NOT_REACHED }
            val correctCount = attempted.count { it.status == WordStatus.CORRECT }
            val accuracyPct = if (attempted.isEmpty()) 100f
                              else correctCount.toFloat() / attempted.size * 100f
            val struggledJson = JSONArray(
                FuzzyMatcher.needsHelp(wordResults).map { it.expected }
            ).toString()
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    LitBudDatabase.getInstance(context).progressDao().insert(
                        ProgressEntity(
                            timestamp = System.currentTimeMillis(),
                            accuracyPercent = accuracyPct,
                            wordsPerMinute = 0f,
                            struggledWords = struggledJson,
                        )
                    )
                    Log.d(TAG, "Progress saved: accuracy=%.0f%% attempted=${attempted.size}".format(accuracyPct))
                } catch (e: Exception) {
                    Log.e(TAG, "Progress DB write failed: ${e.message}")
                }
            }

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

            // Do NOT pass systemInstruction here. The engine was already initialised with
            // tutor_system.txt in LitBudTask.initializeModelFn. Passing it again via
            // ConversationConfig causes engine.createConversation() to fail silently inside
            // the Gallery's try-catch, leaving instance.conversation as the just-closed old
            // conversation — then runInference immediately hits checkIsAlive and crashes.
            model.runtimeHelper.resetConversation(
                model = model,
                supportImage = false,
                supportAudio = false,
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

    // ─── Feature 6: Word Drill ────────────────────────────────────────────────

    /**
     * Starts a word-by-word practice drill for all struggled/missed words from the
     * current session. Called when the child taps "Practice Missed Words" on the
     * Result screen.
     */
    fun startDrill(model: Model) {
        val words = FuzzyMatcher.needsHelp(_uiState.value.wordResults).map { it.expected }
        if (words.isEmpty()) return
        _uiState.update {
            it.copy(
                phase = LitBudPhase.WORD_DRILL,
                drillWords = words,
                drillIndex = 0,
                drillTriesLeft = 3,
                drillState = DrillState.FETCHING_TIP,
            )
        }
        fetchDrillTip(words[0], model)
    }

    /**
     * Fetches a phonics tip and example sentence for [word] using a lightweight
     * text-only model call. Falls back to a letter-by-letter tip if parsing fails.
     */
    private fun fetchDrillTip(word: String, model: Model) {
        viewModelScope.launch(Dispatchers.Default) {
            if (!waitForModel(model)) {
                _uiState.update {
                    it.copy(
                        drillTip = "Say each letter: ${word.uppercase()}",
                        drillSentence = "",
                        drillState = DrillState.SHOWING_WORD,
                    )
                }
                return@launch
            }

            val langInstruction = detectLanguageInstruction(_uiState.value.ocrText)
            val tipPrompt = buildString {
                append("Give a one-sentence phonics tip for the word \"$word\" for a young child aged 5-8.")
                append("\nThen give one very short example sentence (5-8 words) using that word.")
                if (langInstruction.isNotEmpty()) append("\n$langInstruction")
                append("\nRespond ONLY in this exact format (no extra text):\nTIP: <tip here>\nSENTENCE: <sentence here>")
            }

            model.runtimeHelper.resetConversation(
                model = model,
                supportImage = false,
                supportAudio = false,
            )

            var tipResponse = ""
            model.runtimeHelper.runInference(
                model = model,
                input = tipPrompt,
                resultListener = { partial, done, _ ->
                    tipResponse += partial
                    if (done) {
                        val tip = extractLine(tipResponse, "TIP:")
                        val sentence = extractLine(tipResponse, "SENTENCE:")
                        Log.d(TAG, "Drill tip for '$word': tip='$tip' sentence='$sentence'")
                        _uiState.update {
                            it.copy(
                                drillTip = tip.ifEmpty { "Say each letter: ${word.uppercase()}" },
                                drillSentence = sentence,
                                drillState = DrillState.SHOWING_WORD,
                            )
                        }
                    }
                },
                cleanUpListener = {
                    if (_uiState.value.drillState == DrillState.FETCHING_TIP) {
                        _uiState.update {
                            it.copy(
                                drillTip = "Say each letter: ${word.uppercase()}",
                                drillSentence = "",
                                drillState = DrillState.SHOWING_WORD,
                            )
                        }
                    }
                },
                onError = { message ->
                    Log.e(TAG, "Drill tip error: $message")
                    _uiState.update {
                        it.copy(
                            drillTip = "Say each letter: ${word.uppercase()}",
                            drillSentence = "",
                            drillState = DrillState.SHOWING_WORD,
                        )
                    }
                },
                coroutineScope = viewModelScope,
            )
        }
    }

    /**
     * Evaluates whether the child said the current drill word correctly.
     * [audioBytes] is raw PCM-16 at 16kHz — converted to WAV before sending to the model.
     *
     * Uses transcription + FuzzyMatcher (same as main reading flow) instead of asking the
     * model CORRECT/WRONG. Model-based judgment always defaults to "CORRECT" when uncertain
     * because Gemma as an LLM is encouraging by nature — it would pass "stupid" for "difference".
     * Transcribing first and scoring with FuzzyMatcher gives an objective similarity score.
     */
    fun recordAndEvaluateDrillWord(audioBytes: ByteArray, model: Model) {
        val currentWord = _uiState.value.drillWords.getOrNull(_uiState.value.drillIndex) ?: return
        _uiState.update { it.copy(drillState = DrillState.EVALUATING) }

        viewModelScope.launch(Dispatchers.Default) {
            if (audioBytes.size < 500) {
                val tries = _uiState.value.drillTriesLeft
                _uiState.update {
                    it.copy(
                        drillTriesLeft = if (tries > 1) tries - 1 else 1,
                        drillState = DrillState.SHOWING_WORD,
                    )
                }
                return@launch
            }

            if (!waitForModel(model)) {
                _uiState.update { it.copy(drillState = DrillState.SHOWING_WORD) }
                return@launch
            }

            val wavBytes = pcmToWav(audioBytes, sampleRate = 16000)
            val transcribePrompt =
                "Transcribe only what is clearly spoken in this short audio clip. " +
                "Write only the word(s) you can actually hear — do not guess or complete. " +
                "Return only the spoken word(s) with no commentary."

            model.runtimeHelper.resetConversation(
                model = model,
                supportImage = false,
                supportAudio = true,
            )

            var transcription = ""
            try {
                model.runtimeHelper.runInference(
                    model = model,
                    input = transcribePrompt,
                    audioClips = listOf(wavBytes),
                    resultListener = { partial, done, _ ->
                        transcription += partial
                        if (done) {
                            val spokenText = transcription.trim()
                            val spokenWords = FuzzyMatcher.tokenize(spokenText)
                            // Accept if ANY spoken word scores ≥ THRESHOLD_CORRECT against target
                            val bestScore = spokenWords.maxOfOrNull { spoken ->
                                FuzzyMatcher.similarity(
                                    FuzzyMatcher.normalize(currentWord),
                                    FuzzyMatcher.normalize(spoken),
                                )
                            } ?: 0
                            Log.d(TAG, "Drill eval '$currentWord': spoken=$spokenWords bestScore=$bestScore")
                            if (bestScore >= THRESHOLD_CORRECT) {
                                _uiState.update { it.copy(drillState = DrillState.WORD_CORRECT) }
                            } else {
                                val tries = _uiState.value.drillTriesLeft
                                if (tries > 1) {
                                    _uiState.update {
                                        it.copy(
                                            drillTriesLeft = tries - 1,
                                            drillState = DrillState.SHOWING_WORD,
                                        )
                                    }
                                } else {
                                    _uiState.update { it.copy(drillState = DrillState.WORD_FAILED) }
                                }
                            }
                        }
                    },
                    cleanUpListener = {
                        if (_uiState.value.drillState == DrillState.EVALUATING) {
                            _uiState.update { it.copy(drillState = DrillState.SHOWING_WORD) }
                        }
                    },
                    onError = { message ->
                        Log.e(TAG, "Drill transcription error: $message")
                        _uiState.update { it.copy(drillState = DrillState.SHOWING_WORD) }
                    },
                    coroutineScope = viewModelScope,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Drill transcription JNI exception: ${e.message}")
                _uiState.update { it.copy(drillState = DrillState.SHOWING_WORD) }
            }
        }
    }

    /**
     * Advances to the next drill word, or transitions to COMPLETE if all words are done.
     */
    fun advanceDrill(model: Model) {
        val nextIndex = _uiState.value.drillIndex + 1
        if (nextIndex < _uiState.value.drillWords.size) {
            _uiState.update {
                it.copy(
                    drillIndex = nextIndex,
                    drillTriesLeft = 3,
                    drillTip = "",
                    drillSentence = "",
                    drillState = DrillState.FETCHING_TIP,
                )
            }
            fetchDrillTip(_uiState.value.drillWords[nextIndex], model)
        } else {
            _uiState.update { it.copy(drillState = DrillState.COMPLETE) }
        }
    }

    /** Finishes the drill and returns to the capture screen for a new page. */
    fun finishDrill() {
        _uiState.update { LitBudUiState() }
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

    /** Parses a "PREFIX: value" line out of a multi-line model response. */
    private fun extractLine(text: String, prefix: String): String {
        val line = text.lines().firstOrNull { it.trim().startsWith(prefix) } ?: return ""
        return line.substringAfter(prefix).trim()
    }

    /**
     * Detects the language of the session from the OCR text script and returns
     * a language instruction line to include in drill prompts.
     */
    private fun detectLanguageInstruction(ocrText: String): String {
        val hasDevanagari = ocrText.any { it.code in 0x0900..0x097F }
        val hasTamil = ocrText.any { it.code in 0x0B80..0x0BFF }
        return when {
            hasDevanagari -> "IMPORTANT: Respond entirely in Hindi."
            hasTamil -> "IMPORTANT: Respond entirely in Tamil."
            else -> ""
        }
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

    /**
     * Wraps raw PCM-16 mono bytes in a 44-byte WAV/RIFF header.
     * LiteRT-LM's miniaudio decoder requires a valid WAV file — passing raw PCM
     * causes "Failed to initialize miniaudio decoder, error code: -10".
     */
    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val dataSize = pcm.size
        val fileSize = dataSize + 44

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (fileSize and 0xff).toByte()
        header[5] = (fileSize shr 8 and 0xff).toByte()
        header[6] = (fileSize shr 16 and 0xff).toByte()
        header[7] = (fileSize shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // fmt chunk size
        header[20] = 1; header[21] = 0                                   // PCM format
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte(); header[33] = 0 // block align
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (dataSize and 0xff).toByte()
        header[41] = (dataSize shr 8 and 0xff).toByte()
        header[42] = (dataSize shr 16 and 0xff).toByte()
        header[43] = (dataSize shr 24 and 0xff).toByte()
        return header + pcm
    }
}
