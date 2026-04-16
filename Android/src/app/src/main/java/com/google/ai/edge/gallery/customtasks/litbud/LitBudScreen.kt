/*
 * LitBud — Offline AI Reading Tutor for Children
 * Apache 2.0 License (same as Google AI Edge Gallery fork)
 */

package com.google.ai.edge.gallery.customtasks.litbud

import android.graphics.Bitmap
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.ui.common.LiveCameraView
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

// LitBud brand green — readable, child-friendly
private val LitBudGreen = Color(0xFF1B8A6B)
private val LitBudGreenLight = Color(0xFFE8F5F0)

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

    // Store the latest frame from the camera stream
    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !modelReady -> ModelLoadingPanel()

            uiState.phase == LitBudPhase.CAPTURE -> CapturePanel(
                onBitmapAvailable = { bitmap ->
                    latestBitmap = bitmap
                },
                onCapture = {
                    val bmp = latestBitmap
                    if (bmp != null) {
                        viewModel.captureAndOcr(bmp, model)
                    }
                },
                bottomPadding = bottomPadding,
            )

            uiState.phase == LitBudPhase.PROCESSING -> ProcessingPanel()

            uiState.phase == LitBudPhase.RESULT -> ResultPanel(
                ocrText = uiState.ocrText,
                capturedBitmap = uiState.capturedBitmap,
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
        // Camera viewfinder — fills most of the screen
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

            // Overlay hint at top
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

        // Capture button
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
private fun ProcessingPanel() {
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
                text = "Reading your book page...",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

// ─── OCR result ───────────────────────────────────────────────────────────────

@Composable
private fun ResultPanel(
    ocrText: String,
    capturedBitmap: Bitmap?,
    onTryAnotherPage: () -> Unit,
    bottomPadding: Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding),
    ) {
        // Captured image thumbnail + OCR text — scrollable
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Thumbnail
            if (capturedBitmap != null) {
                Image(
                    bitmap = capturedBitmap.asImageBitmap(),
                    contentDescription = "Captured book page",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }

            // Section header
            Text(
                text = "Book text found:",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                ),
                color = LitBudGreen,
            )

            // Extracted text — 20sp minimum per skill rules
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

        // Action buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // "Read It Aloud!" — placeholder for Feature 2 (disabled for now)
            Button(
                onClick = { /* Feature 2 — coming in Days 8-10 */ },
                enabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LitBudGreen,
                    disabledContainerColor = LitBudGreen.copy(alpha = 0.38f),
                ),
            ) {
                Text(
                    text = "Read It Aloud!",
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
