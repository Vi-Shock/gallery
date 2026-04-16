/*
 * LitBud — Offline AI Reading Tutor for Children
 * Apache 2.0 License (same as Google AI Edge Gallery fork)
 */

package com.google.ai.edge.gallery.customtasks.litbud

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import dagger.hilt.android.lifecycle.HiltViewModel
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

enum class LitBudPhase { CAPTURE, PROCESSING, RESULT, ERROR }

data class LitBudUiState(
    val phase: LitBudPhase = LitBudPhase.CAPTURE,
    val capturedBitmap: Bitmap? = null,
    val ocrText: String = "",
    val friendlyError: String = "",
)

@HiltViewModel
class LitBudViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(LitBudUiState())
    val uiState = _uiState.asStateFlow()

    /**
     * Called when the child taps "Capture Page".
     * Snapshots [bitmap] from the live camera feed and runs OCR via Gemma 4 vision.
     */
    fun captureAndOcr(bitmap: Bitmap, model: Model) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update {
                it.copy(phase = LitBudPhase.PROCESSING, capturedBitmap = bitmap)
            }

            // Wait for model instance to finish initialising.
            var waited = 0
            while (model.instance == null && waited < 30_000) {
                delay(100)
                waited += 100
            }
            if (model.instance == null) {
                showError("The model isn't ready yet. Try again in a moment!")
                return@launch
            }

            model.runtimeHelper.resetConversation(
                model = model,
                supportImage = true,
                supportAudio = false,
            )

            var fullResponse = ""
            var responseStarted = false

            model.runtimeHelper.runInference(
                model = model,
                input = OCR_PROMPT,
                images = listOf(bitmap),
                resultListener = { partialResult, done, _ ->
                    fullResponse += partialResult
                    responseStarted = true

                    if (done) {
                        val text = fullResponse.trim()
                        if (text.isNotEmpty()) {
                            _uiState.update {
                                it.copy(phase = LitBudPhase.RESULT, ocrText = text)
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

    /** Reset back to the camera capture screen. */
    fun reset() {
        _uiState.update {
            LitBudUiState()
        }
    }

    private fun showError(message: String) {
        _uiState.update {
            it.copy(phase = LitBudPhase.ERROR, friendlyError = message)
        }
    }
}
