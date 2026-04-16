/*
 * LitBud — Offline AI Reading Tutor for Children
 * Apache 2.0 License (same as Google AI Edge Gallery fork)
 */

package com.google.ai.edge.gallery.customtasks.litbud

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.data.SAMPLE_RATE
import com.google.ai.edge.gallery.ui.common.LiveCameraView
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// LitBud brand green — readable, child-friendly
private val LitBudGreen = Color(0xFF1B8A6B)
private val LitBudGreenLight = Color(0xFFE8F5F0)
private val LitBudOrange = Color(0xFFE07B2A)
private val LitBudRed = Color(0xFFD32F2F)

/** Auto-stop recording a couple of seconds before the hard 30s limit. */
private const val AUTO_STOP_SECONDS = 28

private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

@Composable
fun LitBudScreen(
    modelManagerViewModel: ModelManagerViewModel,
    bottomPadding: Dp = 0.dp,
    setAppBarControlsDisabled: (Boolean) -> Unit = {},
    setTopBarVisible: (Boolean) -> Unit = {},
    viewModel: LitBudViewModel = hiltViewModel(),
) {
    val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val model = modelManagerUiState.selectedModel
    val modelReady = modelManagerUiState.isModelInitialized(model = model)

    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !modelReady -> ModelLoadingPanel()

            uiState.phase == LitBudPhase.CAPTURE -> CapturePanel(
                onBitmapAvailable = { bitmap -> latestBitmap = bitmap },
                onCapture = {
                    val bmp = latestBitmap
                    if (bmp != null) viewModel.captureAndOcr(bmp, model)
                },
                bottomPadding = bottomPadding,
            )

            uiState.phase == LitBudPhase.PROCESSING -> ProcessingPanel(
                message = "Reading your book page..."
            )

            uiState.phase == LitBudPhase.READING -> ReadingPanel(
                ocrText = uiState.ocrText,
                capturedBitmap = uiState.capturedBitmap,
                onAudioReady = { audioBytes ->
                    viewModel.processReading(audioBytes, uiState.ocrText, model)
                },
                onTryAnotherPage = { viewModel.reset() },
                bottomPadding = bottomPadding,
            )

            uiState.phase == LitBudPhase.COACHING -> ProcessingPanel(
                message = "Listening and thinking of some tips for you..."
            )

            uiState.phase == LitBudPhase.RESULT -> ResultPanel(
                ocrText = uiState.ocrText,
                wordResults = uiState.wordResults,
                coachingText = uiState.coachingText,
                capturedBitmap = uiState.capturedBitmap,
                onReadAgain = { viewModel.tryReadingAgain() },
                onTryAnotherPage = { viewModel.reset() },
                bottomPadding = bottomPadding,
            )

            uiState.phase == LitBudPhase.ERROR -> ErrorPanel(
                message = uiState.friendlyError,
                onTryAgain = { viewModel.reset() },
            )
        }
    }
}

// ─── Model loading ────────────────────────────────────────────────────────────

@Composable
private fun ModelLoadingPanel() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = LitBudGreen,
                strokeWidth = 4.dp,
            )
            Text(
                text = "Getting LitBud ready...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Camera capture ───────────────────────────────────────────────────────────

@Composable
private fun CapturePanel(
    onBitmapAvailable: (Bitmap) -> Unit,
    onCapture: () -> Unit,
    bottomPadding: Dp,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LiveCameraView(
                onBitmap = { bitmap, imageProxy ->
                    onBitmapAvailable(bitmap)
                    imageProxy.close()
                },
                modifier = Modifier.fillMaxSize(),
                cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
                preferredSize = 1024,
            )

            Surface(
                color = Color.Black.copy(alpha = 0.45f),
                shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            ) {
                Text(
                    text = "Point the camera at a book page",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = 16.dp,
                    bottom = (16.dp + bottomPadding),
                ),
        ) {
            Button(
                onClick = onCapture,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LitBudGreen),
                contentPadding = PaddingValues(horizontal = 24.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = "Capture Page",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                    ),
                )
            }
        }
    }
}

// ─── Processing spinner ───────────────────────────────────────────────────────

@Composable
private fun ProcessingPanel(message: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(52.dp),
                color = LitBudGreen,
                strokeWidth = 5.dp,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

// ─── Reading screen with mic button ──────────────────────────────────────────

@SuppressLint("MissingPermission") // RECORD_AUDIO permission declared in manifest
@Composable
private fun ReadingPanel(
    ocrText: String,
    capturedBitmap: Bitmap?,
    onAudioReady: (ByteArray) -> Unit,
    onTryAnotherPage: () -> Unit,
    bottomPadding: Dp,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isRecording by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(AUTO_STOP_SECONDS) }
    val audioRecordRef = remember { mutableStateOf<AudioRecord?>(null) }
    val audioStream = remember { ByteArrayOutputStream() }

    // Cleanup if composable leaves the tree while recording
    DisposableEffect(Unit) {
        onDispose {
            audioRecordRef.value?.release()
            audioRecordRef.value = null
        }
    }

    // Countdown timer — ticks every second while recording
    LaunchedEffect(isRecording) {
        if (isRecording) {
            secondsLeft = AUTO_STOP_SECONDS
            while (isRecording && secondsLeft > 0) {
                delay(1_000)
                secondsLeft--
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding),
    ) {
        // Scrollable book text
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (capturedBitmap != null) {
                Image(
                    bitmap = capturedBitmap.asImageBitmap(),
                    contentDescription = "Captured book page",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }

            Text(
                text = "Read this aloud:",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                ),
                color = LitBudGreen,
            )

            Surface(
                color = LitBudGreenLight,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = ocrText,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 20.sp,
                        lineHeight = 30.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        // Mic button area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isRecording) {
                Text(
                    text = "Recording... $secondsLeft s left",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = LitBudRed,
                )
            } else {
                Text(
                    text = "Tap the button and read aloud!",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Mic / Stop button — 72dp to be very tappable for children
            Button(
                onClick = {
                    if (!isRecording) {
                        coroutineScope.launch {
                            isRecording = true
                            val recordedBytes = recordAudio(
                                context = context,
                                audioRecordRef = audioRecordRef,
                                audioStream = audioStream,
                                autoStopSeconds = AUTO_STOP_SECONDS,
                                isRecordingCheck = { isRecording },
                                onAutoStop = { isRecording = false },
                            )
                            if (recordedBytes.isNotEmpty()) {
                                onAudioReady(recordedBytes)
                            }
                        }
                    } else {
                        isRecording = false
                        val bytes = stopAudioRecord(
                            audioRecordRef = audioRecordRef,
                            audioStream = audioStream,
                        )
                        if (bytes.isNotEmpty()) {
                            onAudioReady(bytes)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) LitBudRed else LitBudGreen,
                ),
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = if (isRecording) "Done Reading" else "Read It Aloud!",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                    ),
                )
            }

            OutlinedButton(
                onClick = onTryAnotherPage,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = "Try Another Page",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                )
            }
        }
    }
}

// ─── Result: coaching + word highlighting ────────────────────────────────────

@Composable
private fun ResultPanel(
    ocrText: String,
    wordResults: List<WordResult>,
    coachingText: String,
    capturedBitmap: Bitmap?,
    onReadAgain: () -> Unit,
    onTryAnotherPage: () -> Unit,
    bottomPadding: Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Captured image thumbnail
            if (capturedBitmap != null) {
                Image(
                    bitmap = capturedBitmap.asImageBitmap(),
                    contentDescription = "Captured book page",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }

            // Coaching speech bubble
            if (coachingText.isNotEmpty()) {
                Text(
                    text = "LitBud says:",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    ),
                    color = LitBudGreen,
                )

                Surface(
                    color = LitBudGreenLight,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text(
                        text = coachingText,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 20.sp,
                            lineHeight = 30.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }

            // Word-by-word highlighted text
            if (wordResults.isNotEmpty()) {
                Text(
                    text = "How you did:",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    ),
                    color = LitBudGreen,
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = buildAnnotatedString {
                            wordResults.forEachIndexed { i, result ->
                                val color = when (result.status) {
                                    WordStatus.CORRECT -> Color.Unspecified
                                    WordStatus.STRUGGLING -> LitBudOrange
                                    WordStatus.MISSED -> LitBudRed
                                }
                                val bold = result.status != WordStatus.CORRECT
                                withStyle(
                                    SpanStyle(
                                        color = color,
                                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                                    )
                                ) {
                                    append(result.expected)
                                }
                                if (i < wordResults.lastIndex) append(" ")
                            }
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 20.sp,
                            lineHeight = 30.sp,
                        ),
                        modifier = Modifier.padding(16.dp),
                    )
                }

                // Legend
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LegendItem(color = Color.Unspecified, label = "Read correctly")
                    LegendItem(color = LitBudOrange, label = "A little tricky")
                    LegendItem(color = LitBudRed, label = "Needs more practice")
                }
            } else if (ocrText.isNotEmpty()) {
                // Fallback if no audio was processed yet
                Text(
                    text = "Book text:",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    ),
                    color = LitBudGreen,
                )
                Surface(
                    color = LitBudGreenLight,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = ocrText,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 20.sp,
                            lineHeight = 30.sp,
                        ),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        // Action buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onReadAgain,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LitBudGreen),
            ) {
                Text(
                    text = "Read It Again!",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    ),
                )
            }

            OutlinedButton(
                onClick = onTryAnotherPage,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = "Try Another Page",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                )
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    val textColor = if (color == Color.Unspecified) LitBudGreen else color
    Text(
        text = "● $label",
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
        color = textColor,
    )
}

// ─── Error ────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorPanel(
    message: String,
    onTryAgain: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().padding(32.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(52.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Button(
                onClick = onTryAgain,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LitBudGreen),
            ) {
                Text(
                    text = "Try Again",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    ),
                )
            }
        }
    }
}

// ─── Audio recording helpers ──────────────────────────────────────────────────

/**
 * Records from the microphone until [autoStopSeconds] elapse or [isRecordingCheck] returns false.
 * Returns raw PCM-16 bytes at [SAMPLE_RATE].
 */
@SuppressLint("MissingPermission")
private suspend fun recordAudio(
    context: Context,
    audioRecordRef: androidx.compose.runtime.MutableState<AudioRecord?>,
    audioStream: ByteArrayOutputStream,
    autoStopSeconds: Int,
    isRecordingCheck: () -> Boolean,
    onAutoStop: () -> Unit,
): ByteArray {
    val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    audioRecordRef.value?.release()
    audioStream.reset()

    val recorder = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT,
        minBufferSize,
    )
    audioRecordRef.value = recorder

    val buffer = ByteArray(minBufferSize)
    val maxMs = autoStopSeconds * 1000L
    val startMs = System.currentTimeMillis()

    kotlinx.coroutines.withContext(Dispatchers.IO) {
        recorder.startRecording()
        while (isRecordingCheck() && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val bytesRead = recorder.read(buffer, 0, buffer.size)
            if (bytesRead > 0) audioStream.write(buffer, 0, bytesRead)
            if (System.currentTimeMillis() - startMs >= maxMs) {
                onAutoStop()
                break
            }
        }
        recorder.stop()
        recorder.release()
        audioRecordRef.value = null
    }

    val bytes = audioStream.toByteArray()
    audioStream.reset()
    return bytes
}

private fun stopAudioRecord(
    audioRecordRef: androidx.compose.runtime.MutableState<AudioRecord?>,
    audioStream: ByteArrayOutputStream,
): ByteArray {
    val recorder = audioRecordRef.value
    if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        recorder.stop()
    }
    recorder?.release()
    audioRecordRef.value = null
    val bytes = audioStream.toByteArray()
    audioStream.reset()
    return bytes
}
