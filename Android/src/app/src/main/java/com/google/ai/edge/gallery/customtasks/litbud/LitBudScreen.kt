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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
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
