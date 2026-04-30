/*
 * LitBud — Offline AI Reading Tutor for Children
 * Apache 2.0 License (same as Google AI Edge Gallery fork)
 */

package com.google.ai.edge.gallery.customtasks.litbud

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint as NativePaint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
// Dark text for use on LitBudGreenLight surfaces — onSurface is light in dark mode,
// making text invisible on the always-light LitBudGreenLight background.
private val LitBudGreenLightText = Color(0xFF1A3D2B)
private val LitBudOrange = Color(0xFFE07B2A)
private val LitBudRed = Color(0xFFD32F2F)
private val LitBudGray = Color(0xFF9E9E9E)   // neutral — word not yet reached

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

    // Solid background on the outer box prevents the Android window background (black)
    // from showing through during the single-frame gap between phase transitions.
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            // PROCESSING and COACHING are checked BEFORE !modelReady so the spinner
            // stays on screen even if ModelManagerViewModel briefly changes state during
            // inference (which would otherwise flip the screen to ModelLoadingPanel).
            uiState.phase == LitBudPhase.PROCESSING -> ProcessingPanel(
                message = "Reading your book page..."
            )

            uiState.phase == LitBudPhase.COACHING -> ProcessingPanel(
                message = "Listening and thinking of some tips for you..."
            )

            // WORD_DRILL checked before !modelReady so the drill screen stays
            // visible even if ModelManagerViewModel briefly changes state.
            uiState.phase == LitBudPhase.WORD_DRILL -> WordDrillScreen(
                uiState = uiState,
                model = model,
                viewModel = viewModel,
                bottomPadding = bottomPadding,
            )

            !modelReady -> ModelLoadingPanel()

            uiState.phase == LitBudPhase.CAPTURE -> CapturePanel(
                onBitmapAvailable = { bitmap -> latestBitmap = bitmap },
                onCapture = {
                    val bmp = latestBitmap
                    if (bmp != null) viewModel.captureAndOcr(bmp, model)
                },
                bottomPadding = bottomPadding,
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

            uiState.phase == LitBudPhase.RESULT -> ResultPanel(
                ocrText = uiState.ocrText,
                wordResults = uiState.wordResults,
                coachingText = uiState.coachingText,
                capturedBitmap = uiState.capturedBitmap,
                onReadAgain = { viewModel.tryReadingAgain() },
                onPracticeWords = if (FuzzyMatcher.needsHelp(uiState.wordResults).isNotEmpty()) {
                    { viewModel.startDrill(model) }
                } else null,
                onTryAnotherPage = { viewModel.reset() },
                onMyProgress = { viewModel.showDashboard() },
                bottomPadding = bottomPadding,
            )

            uiState.phase == LitBudPhase.DASHBOARD -> {
                val progressEntries by viewModel.progressFlow.collectAsState(initial = emptyList())
                DashboardPanel(
                    entries = progressEntries,
                    onBack = { viewModel.hideDashboard() },
                    bottomPadding = bottomPadding,
                )
            }

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
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
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

@SuppressLint("MissingPermission") // Permission is checked at runtime before AudioRecord is created
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

    // Runtime permission state — false until the OS grants RECORD_AUDIO
    var micPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micPermissionGranted = granted
    }

    // Request mic permission as soon as this screen appears, if not already granted
    LaunchedEffect(Unit) {
        if (!micPermissionGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

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
                    color = LitBudGreenLightText,
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
                    if (!micPermissionGranted) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@Button
                    }
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
                    text = when {
                        isRecording -> "Done Reading"
                        !micPermissionGranted -> "Allow Microphone"
                        else -> "Read It Aloud!"
                    },
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
    onPracticeWords: (() -> Unit)?,
    onTryAnotherPage: () -> Unit,
    onMyProgress: () -> Unit,
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
                        color = LitBudGreenLightText,
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
                                    WordStatus.CORRECT -> LitBudGreen
                                    WordStatus.STRUGGLING -> LitBudOrange
                                    WordStatus.MISSED -> LitBudRed
                                    WordStatus.NOT_REACHED -> LitBudGray
                                }
                                val bold = result.status == WordStatus.STRUGGLING || result.status == WordStatus.MISSED
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
                    LegendItem(color = LitBudGreen, label = "Read correctly")
                    LegendItem(color = LitBudOrange, label = "A little tricky")
                    LegendItem(color = LitBudRed, label = "Needs more practice")
                    LegendItem(color = LitBudGray, label = "Not reached yet")
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
                        color = LitBudGreenLightText,
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

            if (onPracticeWords != null) {
                Button(
                    onClick = onPracticeWords,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LitBudOrange),
                ) {
                    Text(
                        text = "Practice Missed Words",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        ),
                    )
                }
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

            OutlinedButton(
                onClick = onMyProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.BarChart,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = LitBudGreen,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "My Progress",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                    color = LitBudGreen,
                )
            }
        }
    }
}

// ─── Word Drill ───────────────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
@Composable
private fun WordDrillScreen(
    uiState: LitBudUiState,
    model: com.google.ai.edge.gallery.data.Model,
    viewModel: LitBudViewModel,
    bottomPadding: Dp,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isRecording by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(5) }
    val audioRecordRef = remember { mutableStateOf<AudioRecord?>(null) }
    val audioStream = remember { ByteArrayOutputStream() }

    // TTS — built-in Android offline speech synthesis so the child can hear the word
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val t = TextToSpeech(context) { /* init status ignored — speak() is a no-op if not ready */ }
        tts.value = t
        onDispose {
            t.stop()
            t.shutdown()
            tts.value = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            audioRecordRef.value?.release()
            audioRecordRef.value = null
        }
    }

    // Countdown timer for the 5-second drill recording window
    LaunchedEffect(isRecording) {
        if (isRecording) {
            secondsLeft = 5
            while (isRecording && secondsLeft > 0) {
                delay(1_000)
                secondsLeft--
            }
        }
    }

    val currentWord = uiState.drillWords.getOrNull(uiState.drillIndex) ?: ""
    val totalWords = uiState.drillWords.size
    val isLastWord = uiState.drillIndex >= totalWords - 1

    // Auto-speak the word whenever a new drill word appears (SHOWING_WORD state)
    LaunchedEffect(currentWord, uiState.drillState) {
        if (uiState.drillState == DrillState.SHOWING_WORD && currentWord.isNotEmpty()) {
            delay(500) // brief pause so the screen renders before speaking
            tts.value?.speak(currentWord, TextToSpeech.QUEUE_FLUSH, null, "drill_word")
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (uiState.drillState) {
            DrillState.FETCHING_TIP -> ProcessingPanel("Getting your practice tip...")
            DrillState.EVALUATING -> ProcessingPanel("Checking... one moment!")

            DrillState.SHOWING_WORD -> DrillWordPanel(
                word = currentWord,
                wordIndex = uiState.drillIndex,
                totalWords = totalWords,
                tip = uiState.drillTip,
                triesLeft = uiState.drillTriesLeft,
                isRecording = isRecording,
                secondsLeft = secondsLeft,
                onHearWord = {
                    tts.value?.speak(currentWord, TextToSpeech.QUEUE_FLUSH, null, "drill_word")
                },
                onMicTap = {
                    if (!isRecording) {
                        coroutineScope.launch {
                            isRecording = true
                            val bytes = recordAudio(
                                context = context,
                                audioRecordRef = audioRecordRef,
                                audioStream = audioStream,
                                autoStopSeconds = 5,
                                isRecordingCheck = { isRecording },
                                onAutoStop = { isRecording = false },
                            )
                            isRecording = false
                            if (bytes.isNotEmpty()) {
                                viewModel.recordAndEvaluateDrillWord(bytes, model)
                            }
                        }
                    } else {
                        // Child tapped to stop early
                        isRecording = false
                        val bytes = stopAudioRecord(
                            audioRecordRef = audioRecordRef,
                            audioStream = audioStream,
                        )
                        if (bytes.isNotEmpty()) {
                            viewModel.recordAndEvaluateDrillWord(bytes, model)
                        }
                    }
                },
                bottomPadding = bottomPadding,
            )

            DrillState.WORD_CORRECT -> DrillCelebrationPanel(
                word = currentWord,
                sentence = uiState.drillSentence,
                isLastWord = isLastWord,
                onNext = { viewModel.advanceDrill(model) },
                onFinish = { viewModel.finishDrill() },
                bottomPadding = bottomPadding,
            )

            DrillState.WORD_FAILED -> DrillEncouragementPanel(
                word = currentWord,
                isLastWord = isLastWord,
                onNext = { viewModel.advanceDrill(model) },
                onFinish = { viewModel.finishDrill() },
                bottomPadding = bottomPadding,
            )

            DrillState.COMPLETE -> DrillCompletePanel(
                wordCount = totalWords,
                onFinish = { viewModel.finishDrill() },
                bottomPadding = bottomPadding,
            )
        }
    }
}

@Composable
private fun DrillWordPanel(
    word: String,
    wordIndex: Int,
    totalWords: Int,
    tip: String,
    triesLeft: Int,
    isRecording: Boolean,
    secondsLeft: Int,
    onHearWord: () -> Unit,
    onMicTap: () -> Unit,
    bottomPadding: Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Progress header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(LitBudGreen)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                text = "Word ${wordIndex + 1} of $totalWords",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                ),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Large word display
            Surface(
                color = LitBudGreenLight,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = word.uppercase(),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 52.sp,
                        letterSpacing = 6.sp,
                    ),
                    color = LitBudGreen,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 24.dp, horizontal = 16.dp),
                )
            }

            // "Hear it again" button — child can tap to hear the word spoken aloud again
            OutlinedButton(
                onClick = onHearWord,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LitBudGreen),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, LitBudGreen.copy(alpha = 0.6f)),
            ) {
                Text(
                    text = "🔊  Hear it again",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                )
            }

            // Phonics tip speech bubble
            if (tip.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "LitBud tip:",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                            color = LitBudGreen,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = tip,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 18.sp,
                                lineHeight = 26.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // Try counter — filled ★ for remaining, ☆ for used
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = buildString {
                        repeat(triesLeft) { append("★ ") }
                        repeat(3 - triesLeft) { append("☆ ") }
                    }.trimEnd(),
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp),
                    color = LitBudOrange,
                )
                Text(
                    text = if (triesLeft == 3) "3 tries" else if (triesLeft == 1) "Last try!" else "$triesLeft tries left",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    color = if (triesLeft == 1) LitBudRed else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Mic button area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isRecording) {
                Text(
                    text = "Listening... $secondsLeft s",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                    ),
                    color = LitBudRed,
                )
            } else {
                Text(
                    text = "Tap the button and say the word!",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Button(
                onClick = onMicTap,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) LitBudRed else LitBudGreen,
                ),
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = if (isRecording) "Done!" else "Say it!",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DrillCelebrationPanel(
    word: String,
    sentence: String,
    isLastWord: Boolean,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    bottomPadding: Dp,
) {
    // Scale-in bounce animation when the celebration panel appears
    var triggered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { triggered = true }
    val starScale by animateFloatAsState(
        targetValue = if (triggered) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "starScale",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 28.dp, end = 28.dp, bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "⭐",
            fontSize = 80.sp,
            modifier = Modifier.scale(starScale),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "You said it!",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 32.sp,
            ),
            color = LitBudGreen,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = word.uppercase(),
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                letterSpacing = 4.sp,
            ),
            color = LitBudGreen,
        )

        if (sentence.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Surface(
                color = LitBudGreenLight,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = sentence,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 20.sp,
                        lineHeight = 28.sp,
                    ),
                    color = LitBudGreenLightText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = if (isLastWord) onFinish else onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LitBudGreen),
        ) {
            Text(
                text = if (isLastWord) "All Done!" else "Next Word →",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                ),
            )
        }
    }
}

@Composable
private fun DrillEncouragementPanel(
    word: String,
    isLastWord: Boolean,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    bottomPadding: Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 28.dp, end = 28.dp, bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "💪",
            fontSize = 64.sp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "That's tricky!",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 28.sp,
            ),
            color = LitBudOrange,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Keep practising \"${word}\" — you'll get it!",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 18.sp,
                lineHeight = 26.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = if (isLastWord) onFinish else onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LitBudOrange),
        ) {
            Text(
                text = if (isLastWord) "All Done!" else "Next Word →",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                ),
            )
        }
    }
}

@Composable
private fun DrillCompletePanel(
    wordCount: Int,
    onFinish: () -> Unit,
    bottomPadding: Dp,
) {
    var triggered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { triggered = true }
    val scale by animateFloatAsState(
        targetValue = if (triggered) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "completeScale",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 28.dp, end = 28.dp, bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "🎉",
            fontSize = 80.sp,
            modifier = Modifier.scale(scale),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Amazing practice!",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 30.sp,
            ),
            color = LitBudGreen,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "You worked on $wordCount ${if (wordCount == 1) "word" else "words"} today. Great job!",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 20.sp,
                lineHeight = 28.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onFinish,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LitBudGreen),
        ) {
            Text(
                text = "Read Another Page",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                ),
            )
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Text(
        text = "● $label",
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
        color = color,
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

// ─── Dashboard ────────────────────────────────────────────────────────────────

@Composable
private fun DashboardPanel(
    entries: List<ProgressEntity>,
    onBack: () -> Unit,
    bottomPadding: Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(bottom = bottomPadding),
    ) {
        // Header bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(LitBudGreen)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = 0.7f)),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(20.dp),
                    tint = Color.White,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "Back",
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
                    color = Color.White,
                )
            }
            Text(
                text = "My Progress",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                ),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (entries.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BarChart,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = LitBudGreen.copy(alpha = 0.4f),
                        )
                        Text(
                            text = "Read your first page to see your progress!",
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // Chart title
                Text(
                    text = "Reading Accuracy Over Time",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    ),
                    color = LitBudGreen,
                )

                // Canvas line chart
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AccuracyLineChart(
                        entries = entries,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .padding(16.dp),
                    )
                }

                // Summary stats
                val avgAccuracy = entries.map { it.accuracyPercent }.average().toFloat()
                val bestAccuracy = entries.maxOf { it.accuracyPercent }
                val sessionCount = entries.size

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = LitBudGreenLight,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatRow(label = "Sessions completed", value = "$sessionCount")
                        StatRow(
                            label = "Average accuracy",
                            value = "%.0f%%".format(avgAccuracy),
                        )
                        StatRow(
                            label = "Best accuracy",
                            value = "%.0f%%".format(bestAccuracy),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccuracyLineChart(
    entries: List<ProgressEntity>,
    modifier: Modifier = Modifier,
) {
    val green = LitBudGreen
    val gridColor = Color(0xFFCCCCCC)
    val labelColor = Color(0xFF555555)
    val density = LocalDensity.current

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Left margin for Y-axis labels, bottom margin for X-axis labels
        val leftMargin = 48.dp.toPx()
        val bottomMargin = 24.dp.toPx()
        val chartW = w - leftMargin
        val chartH = h - bottomMargin

        val gridLinePaint = NativePaint().apply {
            color = gridColor.toArgb()
            strokeWidth = 1.dp.toPx()
            isAntiAlias = true
        }
        val labelPaint = NativePaint().apply {
            color = labelColor.toArgb()
            textSize = with(density) { 12.sp.toPx() }
            isAntiAlias = true
            textAlign = NativePaint.Align.RIGHT
        }
        val xLabelPaint = NativePaint().apply {
            color = labelColor.toArgb()
            textSize = with(density) { 11.sp.toPx() }
            isAntiAlias = true
            textAlign = NativePaint.Align.CENTER
        }

        // Draw horizontal grid lines at 0, 25, 50, 75, 100%
        val yLevels = listOf(0f, 25f, 50f, 75f, 100f)
        for (level in yLevels) {
            val yPos = chartH - (level / 100f) * chartH
            drawContext.canvas.nativeCanvas.drawLine(
                leftMargin, yPos, w, yPos, gridLinePaint,
            )
            drawContext.canvas.nativeCanvas.drawText(
                "${level.toInt()}%",
                leftMargin - 6.dp.toPx(),
                yPos + (labelPaint.textSize / 3f),
                labelPaint,
            )
        }

        if (entries.size < 2) {
            // Single point — just draw the dot
            val xPos = leftMargin + chartW / 2f
            val yPos = chartH - (entries[0].accuracyPercent / 100f) * chartH
            drawCircle(color = green, radius = 6.dp.toPx(), center = Offset(xPos, yPos))
            drawContext.canvas.nativeCanvas.drawText(
                "1", xPos, h, xLabelPaint,
            )
            return@Canvas
        }

        // Calculate X positions for each session
        val xStep = chartW / (entries.size - 1).toFloat()
        val points = entries.mapIndexed { i, entry ->
            val x = leftMargin + i * xStep
            val y = chartH - (entry.accuracyPercent.coerceIn(0f, 100f) / 100f) * chartH
            Offset(x, y)
        }

        // Draw lines between points
        for (i in 0 until points.size - 1) {
            drawLine(
                color = green,
                start = points[i],
                end = points[i + 1],
                strokeWidth = 3.dp.toPx(),
            )
        }

        // Draw dots and X-axis labels
        points.forEachIndexed { i, pt ->
            drawCircle(color = green, radius = 6.dp.toPx(), center = pt)
            drawCircle(color = Color.White, radius = 3.dp.toPx(), center = pt)
            // X-axis labels — show every label if ≤10 sessions, else every other
            if (entries.size <= 10 || i % 2 == 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    "${i + 1}", pt.x, h, xLabelPaint,
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            ),
            color = LitBudGreen,
        )
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

    // Guard: AudioRecord constructor can return STATE_UNINITIALIZED if the mic
    // hardware is still being released from a previous session (e.g. "Read Again"
    // tapped quickly). Calling startRecording() in that state throws
    // IllegalStateException and crashes the app.
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        android.util.Log.e("LitBud", "AudioRecord failed to initialize — mic may still be busy")
        recorder.release()
        audioRecordRef.value = null
        return ByteArray(0)
    }

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
        // Guard: stopAudioRecord() may have already stopped+released this recorder
        // (user tapped Stop while the IO loop was still running). Calling stop() on
        // a released AudioRecord throws IllegalStateException — catch it safely.
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
            recorder.release()
        } catch (e: IllegalStateException) {
            android.util.Log.w("LitBud", "AudioRecord stop/release ignored: ${e.message}")
        }
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
