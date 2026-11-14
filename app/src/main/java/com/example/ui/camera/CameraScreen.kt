package com.example.ui.camera

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.util.Size
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.SavedSnippetsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@Composable
fun CameraScreen(
    onNavigateToSavedList: () -> Unit = {},
    onNavigateToBulkOcr: () -> Unit = {}
) {
    val context: Context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { SavedSnippetsRepository.getInstance(context) }

    val cameraController: LifecycleCameraController = remember { LifecycleCameraController(context) }
    var detectedText: String by remember { mutableStateOf("") }
    var ocrSpeedMode: OcrSpeedMode by remember { mutableStateOf(OcrSpeedMode.NORMAL) }
    var appendedEventCounter: Int by remember { mutableIntStateOf(0) }
    var canUndoAppend: Boolean by remember { mutableStateOf(false) }
    var isScanningActive: Boolean by remember { mutableStateOf(true) }
    var isTorchOn: Boolean by remember { mutableStateOf(false) }

    var isCopied: Boolean by remember { mutableStateOf(false) }
    var isSaved: Boolean by remember { mutableStateOf(false) }
    var showSettingsDialog: Boolean by remember { mutableStateOf(false) }

    val settingsStore = remember { OcrSettingsStore(context) }
    var ocrSettings by remember { mutableStateOf(settingsStore.load()) }
    val ocrEngine = remember { ContinuousOcrEngine(initialSettings = ocrSettings) }
    val analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val textAnalyzer = remember {
        TextRecognitionAnalyzer(
            isScanningActive = { isScanningActive },
            ocrEngine = ocrEngine,
            onObservationProcessed = { result ->
                ocrSpeedMode = result.speedMode
                if (isScanningActive) {
                    detectedText = result.accumulatedText
                    canUndoAppend = result.canUndo
                    if (result.appendedRowCount > 0) {
                        appendedEventCounter += 1
                    }
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraController.enableTorch(false)
            cameraController.clearImageAnalysisAnalyzer()
            cameraController.unbind()
            textAnalyzer.close()
            analysisExecutor.shutdown()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val totalScreenHeight = maxHeight
        val minPanelHeight = 140.dp
        val maxPanelHeight = totalScreenHeight * 0.78f
        var customPanelHeight by remember { mutableStateOf(totalScreenHeight * 0.32f) }
        val currentPanelHeight = customPanelHeight.coerceIn(minPanelHeight, maxPanelHeight)
        val hasText = detectedText.isNotBlank()

        // 1. Full-screen Camera Preview
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .testTag("camera_preview_view"),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(AndroidColor.BLACK)
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_START
                }.also { previewView ->
                    startCodeRecognition(
                        cameraController = cameraController,
                        lifecycleOwner = lifecycleOwner,
                        previewView = previewView,
                        analysisExecutor = analysisExecutor,
                        textAnalyzer = textAnalyzer
                    )
                }
            }
        )

        // 2. Top Floating Controls: Flash Toggle (Left) & Compact Navigation Panel (Right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(15, 15, 15).copy(alpha = 0.55f))
                    .border(
                        width = 1.dp,
                        color = if (isTorchOn) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.25f),
                        shape = CircleShape
                    )
                    .clickable {
                        isTorchOn = !isTorchOn
                        cameraController.enableTorch(isTorchOn)
                    }
                    .testTag("flash_toggle_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isTorchOn) Icons.Filled.FlashOn else Icons.Outlined.FlashOff,
                    contentDescription = if (isTorchOn) "Flash On" else "Flash Off",
                    tint = if (isTorchOn) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(24.dp)
                )
            }

            // Compact Themed Navigation Panel (Saved List, Bulk Image OCR, Settings)
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(15, 15, 15).copy(alpha = 0.58f))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 4.dp)
                    .testTag("navigation_panel"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavPanelIconButton(
                    icon = Icons.AutoMirrored.Outlined.FormatListBulleted,
                    contentDescription = "View saved list",
                    testTag = "view_saved_list_button",
                    onClick = onNavigateToSavedList
                )

                NavPanelDivider()

                NavPanelIconButton(
                    icon = Icons.Outlined.AddPhotoAlternate,
                    contentDescription = "Bulk image OCR",
                    testTag = "bulk_ocr_nav_button",
                    onClick = onNavigateToBulkOcr
                )

                NavPanelDivider()

                NavPanelIconButton(
                    icon = Icons.Outlined.Settings,
                    contentDescription = "LiveLens settings and features",
                    testTag = "settings_button",
                    onClick = { showSettingsDialog = true }
                )
            }
        }

        // 3. Bottom Glass Extracted Text Panel (Resizable via Swipe Up/Down)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(currentPanelHeight)
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(15, 15, 15).copy(alpha = 0.35f))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("extracted_text_panel")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Drag Handle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { change, dragAmount ->
                                change.consume()
                                customPanelHeight = (customPanelHeight - dragAmount.toDp())
                                    .coerceIn(minPanelHeight, maxPanelHeight)
                            }
                        }
                        .testTag("panel_resize_handle"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.45f))
                    )
                }

                // Panel Header: Status Badge, Shifted Action Icons (Copy, Save, Share), & Start/Stop Button (Top-Right)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        val indicatorColor = when {
                            !isScanningActive -> Color(0xFFFFB300)
                            ocrSpeedMode == OcrSpeedMode.NORMAL -> Color(0xFF00E676)
                            else -> Color(0xFFFF5252)
                        }
                        val indicatorLabel = when {
                            !isScanningActive -> "PAUSED"
                            ocrSpeedMode == OcrSpeedMode.NORMAL -> "LIVE OCR"
                            else -> "SLOW OCR"
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(indicatorColor)
                                .testTag("ocr_speed_status_dot")
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = indicatorLabel,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.75f),
                            letterSpacing = 0.8.sp,
                            maxLines = 1
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        PanelActionIcon(
                            icon = Icons.AutoMirrored.Outlined.Undo,
                            contentDescription = "Undo last appended lines",
                            isHighlighted = false,
                            enabled = canUndoAppend,
                            testTag = "undo_append_button",
                            onClick = {
                                val undoResult = textAnalyzer.undoLastAppend()
                                detectedText = undoResult.accumulatedText
                                canUndoAppend = undoResult.canUndo
                                ocrSpeedMode = undoResult.speedMode
                            }
                        )

                        PanelActionIcon(
                            icon = Icons.Outlined.DeleteSweep,
                            contentDescription = "Clear text",
                            isHighlighted = false,
                            enabled = hasText,
                            testTag = "clear_text_button",
                            onClick = {
                                detectedText = ""
                                appendedEventCounter = 0
                                canUndoAppend = false
                                ocrSpeedMode = OcrSpeedMode.NORMAL
                                textAnalyzer.resetSession()
                            }
                        )

                        PanelActionIcon(
                            icon = if (isCopied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                            contentDescription = "Copy text",
                            isHighlighted = isCopied,
                            enabled = hasText,
                            testTag = "copy_text_button",
                            onClick = {
                                clipboardManager.setText(AnnotatedString(detectedText))
                                isCopied = true
                                coroutineScope.launch {
                                    delay(1500)
                                    isCopied = false
                                }
                            }
                        )

                        PanelActionIcon(
                            icon = if (isSaved) Icons.Filled.Check else Icons.Outlined.BookmarkAdd,
                            contentDescription = "Save to list",
                            isHighlighted = isSaved,
                            enabled = hasText,
                            testTag = "save_text_button",
                            onClick = {
                                if (repository.addSnippet(detectedText)) {
                                    isSaved = true
                                    coroutineScope.launch {
                                        delay(1500)
                                        isSaved = false
                                    }
                                }
                            }
                        )

                        PanelActionIcon(
                            icon = Icons.Outlined.Share,
                            contentDescription = "Share text",
                            isHighlighted = false,
                            enabled = hasText,
                            testTag = "share_text_button",
                            onClick = {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    putExtra(Intent.EXTRA_TEXT, detectedText)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share code snippet"))
                            }
                        )

                        Spacer(modifier = Modifier.width(2.dp))

                        // Start/Stop Button at Top-Right of Extracted-Text Container
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isScanningActive) Color(0xFF2E7D32) else Color(0xFFD32F2F))
                                .border(
                                    width = 2.dp,
                                    color = if (isScanningActive) Color(0xFF00E676) else Color(0xFFFF5252),
                                    shape = CircleShape
                                )
                                .clickable { isScanningActive = !isScanningActive }
                                .testTag("scan_toggle_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isScanningActive) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isScanningActive) "Stop Scanning" else "Start Scanning",
                                tint = Color.White,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Scrollable Monospace Code Output with smart bottom auto-scroll
                val scrollState = rememberScrollState()
                val isNearBottom by remember {
                    derivedStateOf {
                        scrollState.maxValue == 0 ||
                            scrollState.value >= (scrollState.maxValue - 48).coerceAtLeast(0)
                    }
                }
                var autoScrollEnabled by remember { mutableStateOf(true) }
                var isProgrammaticScroll by remember { mutableStateOf(false) }

                LaunchedEffect(scrollState.value, scrollState.maxValue) {
                    if (!isProgrammaticScroll) {
                        autoScrollEnabled = isNearBottom
                    }
                }

                LaunchedEffect(appendedEventCounter, scrollState.maxValue) {
                    if (appendedEventCounter > 0 && autoScrollEnabled && scrollState.maxValue > scrollState.value) {
                        isProgrammaticScroll = true
                        try {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        } finally {
                            isProgrammaticScroll = false
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(bottom = 8.dp)
                ) {
                    val displayText = when {
                        hasText -> detectedText
                        isScanningActive -> "Point camera at code (tap to focus)..."
                        else -> "Scanning paused. Tap Start to resume."
                    }

                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("detected_text_view"),
                        text = displayText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = if (hasText) Color.White.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.50f)
                    )
                }
            }
        }
    }

    if (showSettingsDialog) {
        OcrTuningSettingsDialog(
            settings = ocrSettings,
            onSettingsChanged = { updated ->
                ocrSettings = updated
                settingsStore.save(updated)
                ocrEngine.updateSettings(updated)
            },
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
private fun NavPanelIconButton(
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = 0.92f),
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
private fun NavPanelDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(20.dp)
            .background(Color.White.copy(alpha = 0.18f))
    )
}

@Composable
private fun OcrTuningSettingsDialog(
    settings: OcrSettings,
    onSettingsChanged: (OcrSettings) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF18181C))
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(24.dp))
                .padding(20.dp)
                .testTag("ocr_settings_dialog")
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF00E676).copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "OCR Settings",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Tune speed, batching & matching",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Batch Size
                SettingSliderItem(
                    title = "Batch Size",
                    subtitle = "Completed OCR frames grouped per UI update",
                    valueLabel = "${settings.batchSize} ${if (settings.batchSize == 1) "frame" else "frames"}",
                    value = settings.batchSize.toFloat(),
                    valueRange = OcrSettings.MIN_BATCH_SIZE.toFloat()..OcrSettings.MAX_BATCH_SIZE.toFloat(),
                    steps = OcrSettings.MAX_BATCH_SIZE - OcrSettings.MIN_BATCH_SIZE - 1,
                    testTag = "batch_size_slider",
                    onValueChange = { raw ->
                        val updatedBatch = raw.roundToInt().coerceIn(
                            OcrSettings.MIN_BATCH_SIZE,
                            OcrSettings.MAX_BATCH_SIZE
                        )
                        if (updatedBatch != settings.batchSize) {
                            onSettingsChanged(settings.copy(batchSize = updatedBatch))
                        }
                    }
                )

                // 2. Fast Mode FPS
                SettingSliderItem(
                    title = "Fast Mode FPS",
                    subtitle = "Active scanning rate when text is changing",
                    valueLabel = "${settings.fastModeFps} FPS (${settings.normalIntervalMs} ms)",
                    accentColor = Color(0xFF00E676),
                    value = settings.fastModeFps.toFloat(),
                    valueRange = OcrSettings.MIN_FAST_FPS.toFloat()..OcrSettings.MAX_FAST_FPS.toFloat(),
                    steps = OcrSettings.MAX_FAST_FPS - OcrSettings.MIN_FAST_FPS - 1,
                    testTag = "fast_mode_fps_slider",
                    onValueChange = { raw ->
                        val updatedFps = raw.roundToInt().coerceIn(
                            OcrSettings.MIN_FAST_FPS,
                            OcrSettings.MAX_FAST_FPS
                        )
                        if (updatedFps != settings.fastModeFps) {
                            onSettingsChanged(settings.copy(fastModeFps = updatedFps))
                        }
                    }
                )

                // 3. Slow Mode FPS
                val slowStepCount = ((OcrSettings.MAX_SLOW_FPS - OcrSettings.MIN_SLOW_FPS) / 0.5f).roundToInt() - 1
                SettingSliderItem(
                    title = "Slow Mode FPS",
                    subtitle = "Power-saving rate when screen is stationary",
                    valueLabel = "${String.format(Locale.US, "%.1f", settings.slowModeFps)} FPS (${settings.slowIntervalMs} ms)",
                    accentColor = Color(0xFFFF5252),
                    value = settings.slowModeFps,
                    valueRange = OcrSettings.MIN_SLOW_FPS..OcrSettings.MAX_SLOW_FPS,
                    steps = slowStepCount,
                    testTag = "slow_mode_fps_slider",
                    onValueChange = { raw ->
                        val snapped = ((raw * 2f).roundToInt() / 2f).coerceIn(
                            OcrSettings.MIN_SLOW_FPS,
                            OcrSettings.MAX_SLOW_FPS
                        )
                        if (snapped != settings.slowModeFps) {
                            onSettingsChanged(settings.copy(slowModeFps = snapped))
                        }
                    }
                )

                // 4. Fuzzy Matching Percentage
                val fuzzySteps = ((OcrSettings.MAX_FUZZY_PERCENT - OcrSettings.MIN_FUZZY_PERCENT) / 5) - 1
                SettingSliderItem(
                    title = "Fuzzy Matching",
                    subtitle = "Similarity threshold for scroll overlap & Slow Mode",
                    valueLabel = "${settings.fuzzyMatchPercent}%",
                    value = settings.fuzzyMatchPercent.toFloat(),
                    valueRange = OcrSettings.MIN_FUZZY_PERCENT.toFloat()..OcrSettings.MAX_FUZZY_PERCENT.toFloat(),
                    steps = fuzzySteps,
                    testTag = "fuzzy_matching_slider",
                    onValueChange = { raw ->
                        val snapped = (((raw / 5f).roundToInt()) * 5).coerceIn(
                            OcrSettings.MIN_FUZZY_PERCENT,
                            OcrSettings.MAX_FUZZY_PERCENT
                        )
                        if (snapped != settings.fuzzyMatchPercent) {
                            onSettingsChanged(settings.copy(fuzzyMatchPercent = snapped))
                        }
                    }
                )

                // 5. Stationary Trigger Observations
                SettingSliderItem(
                    title = "Stationary Trigger",
                    subtitle = "Identical observations before entering Slow Mode",
                    valueLabel = "${settings.stationaryThreshold} obs",
                    value = settings.stationaryThreshold.toFloat(),
                    valueRange = OcrSettings.MIN_STATIONARY_THRESHOLD.toFloat()..OcrSettings.MAX_STATIONARY_THRESHOLD.toFloat(),
                    steps = OcrSettings.MAX_STATIONARY_THRESHOLD - OcrSettings.MIN_STATIONARY_THRESHOLD - 1,
                    testTag = "stationary_threshold_slider",
                    onValueChange = { raw ->
                        val updated = raw.roundToInt().coerceIn(
                            OcrSettings.MIN_STATIONARY_THRESHOLD,
                            OcrSettings.MAX_STATIONARY_THRESHOLD
                        )
                        if (updated != settings.stationaryThreshold) {
                            onSettingsChanged(settings.copy(stationaryThreshold = updated))
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { onSettingsChanged(OcrSettings()) },
                    modifier = Modifier.testTag("reset_ocr_settings_button")
                ) {
                    Text(
                        text = "Reset Defaults",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 13.sp
                    )
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_settings_dialog_button")
                ) {
                    Text(
                        text = "Done",
                        color = Color(0xFF00E676),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSliderItem(
    title: String,
    subtitle: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    testTag: String,
    onValueChange: (Float) -> Unit,
    accentColor: Color? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (accentColor != null) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF00E676).copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = valueLabel,
                    color = Color(0xFF00E676),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.56f),
            fontSize = 11.sp
        )

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF00E676),
                activeTrackColor = Color(0xFF00E676),
                inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
    }
}

@Composable
private fun PanelActionIcon(
    icon: ImageVector,
    contentDescription: String,
    isHighlighted: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(31.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (isHighlighted) Color(0xFF2E7D32).copy(alpha = 0.70f)
                else Color.White.copy(alpha = 0.16f)
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.20f),
                shape = RoundedCornerShape(9.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(16.dp)
        )
    }
}

private fun startCodeRecognition(
    cameraController: LifecycleCameraController,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    analysisExecutor: ExecutorService,
    textAnalyzer: TextRecognitionAnalyzer
) {
    cameraController.imageAnalysisTargetSize = CameraController.OutputSize(Size(1920, 1080))
    cameraController.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
    cameraController.isTapToFocusEnabled = true
    cameraController.isPinchToZoomEnabled = true

    cameraController.setImageAnalysisAnalyzer(analysisExecutor, textAnalyzer)
    cameraController.bindToLifecycle(lifecycleOwner)
    previewView.controller = cameraController
}
