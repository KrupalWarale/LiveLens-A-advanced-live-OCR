package com.example.ui.bulk

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.data.SavedSnippetsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BulkOcrScreen(
    onBack: () -> Unit,
    viewModel: BulkOcrViewModel = viewModel()
) {
    BackHandler {
        onBack()
    }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { SavedSnippetsRepository.getInstance(context) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var previewItemIndex by remember { mutableStateOf<Int?>(null) }
    var isCopied by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }

    val multiplePhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                viewModel.addImages(uris)
            }
        }
    )

    val launchGalleryPicker: () -> Unit = {
        multiplePhotoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101012))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("bulk_ocr_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to camera",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Bulk Image OCR",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (uiState.items.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${uiState.items.size}",
                            color = Color(0xFF00E676),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (uiState.items.isNotEmpty()) {
                    IconButton(
                        onClick = { showClearConfirmDialog = true },
                        modifier = Modifier.testTag("bulk_clear_all_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = "Clear all images",
                            tint = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }

                IconButton(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier.testTag("bulk_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Bulk export settings",
                        tint = Color.White
                    )
                }
            }
        }

        if (uiState.items.isEmpty()) {
            // Empty State
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676).copy(alpha = 0.12f))
                            .border(
                                width = 1.dp,
                                color = Color(0xFF00E676).copy(alpha = 0.35f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AddPhotoAlternate,
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "Batch Scan Gallery Images",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Select multiple images, preview, remove, and reorder them. LiveLens extracts text in image order and merges everything into one document.",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Active separator indicator pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1C1C20))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                            .clickable { showSettingsDialog = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Separator: ${uiState.separatorOption.label}",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = launchGalleryPicker,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .height(50.dp)
                            .testTag("select_images_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AddPhotoAlternate,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Select Gallery Images",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else {
            // Selected Images Strip + Combined Document Output
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "IMAGE ORDER (TAP TO PREVIEW)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.65f),
                        letterSpacing = 1.sp
                    )

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF2E7D32).copy(alpha = 0.35f))
                            .border(
                                width = 1.dp,
                                color = Color(0xFF00E676).copy(alpha = 0.55f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable(onClick = launchGalleryPicker)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("add_more_images_button"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AddPhotoAlternate,
                            contentDescription = "Add more images",
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Add Images",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bulk_images_list"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(end = 4.dp)
                ) {
                    itemsIndexed(
                        items = uiState.items,
                        key = { _, item -> item.id }
                    ) { index, item ->
                        BulkImagePreviewCard(
                            item = item,
                            index = index,
                            totalCount = uiState.items.size,
                            onPreview = { previewItemIndex = index },
                            onRemove = { viewModel.removeImage(item.id) },
                            onMoveLeft = { viewModel.moveImage(index, index - 1) },
                            onMoveRight = { viewModel.moveImage(index, index + 1) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Combined Document Panel
                val hasDocumentText = uiState.combinedDocument.isNotBlank()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 14.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF1C1C20))
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .padding(14.dp)
                        .testTag("bulk_document_panel")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (uiState.isProcessingAny) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF00E676)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    text = if (uiState.isProcessingAny) "EXTRACTING..." else "COMBINED DOCUMENT",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.8f),
                                    letterSpacing = 1.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .clickable { showSettingsDialog = true }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Tune,
                                    contentDescription = null,
                                    tint = Color(0xFF00E676),
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = uiState.separatorOption.label,
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.78f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BulkActionIcon(
                                icon = if (isCopied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                                contentDescription = "Copy combined document",
                                isHighlighted = isCopied,
                                enabled = hasDocumentText,
                                testTag = "bulk_copy_button",
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(uiState.combinedDocument))
                                    isCopied = true
                                    coroutineScope.launch {
                                        delay(1500)
                                        isCopied = false
                                    }
                                }
                            )

                            BulkActionIcon(
                                icon = if (isSaved) Icons.Filled.Check else Icons.Outlined.BookmarkAdd,
                                contentDescription = "Save combined document to list",
                                isHighlighted = isSaved,
                                enabled = hasDocumentText,
                                testTag = "bulk_save_button",
                                onClick = {
                                    if (repository.addSnippet(uiState.combinedDocument)) {
                                        isSaved = true
                                        coroutineScope.launch {
                                            delay(1500)
                                            isSaved = false
                                        }
                                    }
                                }
                            )

                            BulkActionIcon(
                                icon = Icons.Outlined.Share,
                                contentDescription = "Share combined document",
                                isHighlighted = false,
                                enabled = hasDocumentText,
                                testTag = "bulk_share_button",
                                onClick = {
                                    shareDocumentText(context, uiState.combinedDocument)
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F0F12))
                            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        val displayText = when {
                            hasDocumentText -> uiState.combinedDocument
                            uiState.isProcessingAny -> "Running Google ML Kit text extraction..."
                            else -> "No readable text detected in the selected images."
                        }

                        Text(
                            text = displayText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = if (hasDocumentText) {
                                Color.White.copy(alpha = 0.95f)
                            } else {
                                Color.White.copy(alpha = 0.5f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("bulk_combined_text")
                        )
                    }
                }
            }
        }
    }

    // Bulk Export Settings Dialog (5 separator options)
    if (showSettingsDialog) {
        BulkExportSettingsDialog(
            selectedOption = uiState.separatorOption,
            onSelectOption = { option ->
                viewModel.setSeparatorOption(option)
            },
            onDismiss = { showSettingsDialog = false }
        )
    }

    // Individual Image & OCR Preview Dialog
    val activePreviewIdx = previewItemIndex
    if (activePreviewIdx != null && activePreviewIdx in uiState.items.indices) {
        val previewItem = uiState.items[activePreviewIdx]
        ImagePreviewDialog(
            item = previewItem,
            imageNumber = activePreviewIdx + 1,
            onDismiss = { previewItemIndex = null }
        )
    }

    // Clear All Images Confirmation Dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            containerColor = Color(0xFF1C1C20),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.8f),
            title = { Text("Clear Selected Images?") },
            text = { Text("This will remove all selected images and reset the combined document.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllImages()
                        showClearConfirmDialog = false
                    }
                ) {
                    Text("Clear All", color = Color(0xFFFF5252))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.75f))
                }
            }
        )
    }
}

@Composable
private fun BulkImagePreviewCard(
    item: BulkImageItem,
    index: Int,
    totalCount: Int,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(138.dp)
            .height(172.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C20))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
            .clickable(onClick = onPreview)
            .testTag("bulk_image_card_$index")
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = "Selected image ${index + 1}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Top-left Order Badge
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.75f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = "#${index + 1}",
                color = Color(0xFF00E676),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Top-right Remove Button
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.75f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                .clickable(onClick = onRemove)
                .testTag("remove_image_$index"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Remove image ${index + 1}",
                tint = Color(0xFFFF5252),
                modifier = Modifier.size(16.dp)
            )
        }

        // Bottom Reorder & Status Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.78f))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val canMoveLeft = index > 0
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .alpha(if (canMoveLeft) 1f else 0.3f)
                    .clip(CircleShape)
                    .clickable(enabled = canMoveLeft, onClick = onMoveLeft)
                    .testTag("move_left_$index"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Move image ${index + 1} earlier",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            // OCR Status Indicator
            when {
                item.isProcessing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF00E676)
                    )
                }
                !item.extractedText.isNullOrBlank() -> {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Text extracted",
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(16.dp)
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "No text detected",
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            val canMoveRight = index < totalCount - 1
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .alpha(if (canMoveRight) 1f else 0.3f)
                    .clip(CircleShape)
                    .clickable(enabled = canMoveRight, onClick = onMoveRight)
                    .testTag("move_right_$index"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Move image ${index + 1} later",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun BulkExportSettingsDialog(
    selectedOption: BulkSeparatorOption,
    onSelectOption: (BulkSeparatorOption) -> Unit,
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
                .testTag("bulk_export_settings_dialog")
        ) {
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
                        text = "Bulk Export Settings",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Document separator between images",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            BulkSeparatorOption.entries.forEach { option ->
                val isSelected = option == selectedOption
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) Color(0xFF2E7D32).copy(alpha = 0.25f)
                            else Color.White.copy(alpha = 0.04f)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) Color(0xFF00E676).copy(alpha = 0.6f)
                            else Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectOption(option) }
                        .minimumInteractiveComponentSize()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("separator_option_${option.key}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelectOption(option) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFF00E676),
                            unselectedColor = Color.White.copy(alpha = 0.55f)
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.label,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = option.description,
                            color = Color.White.copy(alpha = 0.58f),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("bulk_settings_done_button")
                ) {
                    Text(
                        text = "Done",
                        color = Color(0xFF00E676),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ImagePreviewDialog(
    item: BulkImageItem,
    imageNumber: Int,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF18181C))
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Image $imageNumber Preview",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close preview",
                        tint = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            AsyncImage(
                model = item.uri,
                contentDescription = "Full preview of image $imageNumber",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "EXTRACTED TEXT",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00E676),
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp, max = 180.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0F0F12))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                val previewText = when {
                    item.isProcessing -> "Extracting text..."
                    !item.extractedText.isNullOrBlank() -> item.extractedText
                    item.errorMessage != null -> "Error: ${item.errorMessage}"
                    else -> "No readable text found in this image."
                }
                Text(
                    text = previewText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun BulkActionIcon(
    icon: ImageVector,
    contentDescription: String,
    isHighlighted: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isHighlighted) Color(0xFF2E7D32).copy(alpha = 0.75f)
                else Color.White.copy(alpha = 0.14f)
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun shareDocumentText(context: Context, text: String) {
    if (text.isBlank()) return
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, text)
        type = "text/plain"
    }
    context.startActivity(Intent.createChooser(sendIntent, "Share extracted document"))
}
