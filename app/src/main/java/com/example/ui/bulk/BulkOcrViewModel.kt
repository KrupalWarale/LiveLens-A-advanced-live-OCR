package com.example.ui.bulk

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ui.camera.CodeTextExtractor
import com.example.ui.camera.ContinuousOcrEngine
import com.example.ui.camera.OcrRow
import com.example.ui.camera.OcrSettingsStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

enum class BulkSeparatorOption(
    val key: String,
    val label: String,
    val description: String
) {
    NEW_LINE(
        key = "new_line",
        label = "New line",
        description = "Single line break between each image's text"
    ),
    EMPTY_LINE(
        key = "empty_line",
        label = "Empty line",
        description = "Blank line between each image's text"
    ),
    UNDERSCORES(
        key = "underscores",
        label = "___",
        description = "Three underscores divider between sections"
    ),
    DASHES(
        key = "dashes",
        label = "----",
        description = "Four dashes divider between sections"
    ),
    IMAGE_NUMBERING(
        key = "image_numbering",
        label = "Image numbering",
        description = "Image 1, Image 2, etc."
    );

    companion object {
        fun fromKey(key: String?): BulkSeparatorOption {
            return entries.firstOrNull { it.key == key } ?: EMPTY_LINE
        }
    }
}

data class NormalizedCropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val isFullImage: Boolean
        get() = left <= 0.005f && top <= 0.005f && right >= 0.995f && bottom >= 0.995f
}

data class BulkImageItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val croppedUri: Uri? = null,
    val cropRect: NormalizedCropRect? = null,
    val extractedText: String? = null,
    val isProcessing: Boolean = false,
    val errorMessage: String? = null
) {
    val displayUri: Uri
        get() = croppedUri ?: uri
}

data class BulkOcrUiState(
    val items: List<BulkImageItem> = emptyList(),
    val separatorOption: BulkSeparatorOption = BulkSeparatorOption.EMPTY_LINE,
    val isOverlapAppendEnabled: Boolean = true,
    val combinedDocument: String = "",
    val isProcessingAny: Boolean = false
)

class BulkOcrViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val ocrSettingsStore = OcrSettingsStore(application)
    private val textRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val _uiState = MutableStateFlow(
        BulkOcrUiState(
            separatorOption = BulkSeparatorOption.fromKey(
                prefs.getString(KEY_SEPARATOR, BulkSeparatorOption.EMPTY_LINE.key)
            ),
            isOverlapAppendEnabled = prefs.getBoolean(KEY_OVERLAP_APPEND, true)
        )
    )
    val uiState: StateFlow<BulkOcrUiState> = _uiState.asStateFlow()

    private fun currentSimilarityThreshold(): Double {
        return ocrSettingsStore.load().similarityThreshold
    }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val newItems = uris.map { uri ->
            BulkImageItem(
                uri = uri,
                isProcessing = true
            )
        }

        val threshold = currentSimilarityThreshold()
        _uiState.update { state ->
            val updatedItems = state.items + newItems
            state.copy(
                items = updatedItems,
                isProcessingAny = updatedItems.any { it.isProcessing },
                combinedDocument = buildCombinedDocument(
                    items = updatedItems,
                    separator = state.separatorOption,
                    isOverlapAppendEnabled = state.isOverlapAppendEnabled,
                    similarityThreshold = threshold
                )
            )
        }

        newItems.forEach { item ->
            extractTextForItem(item.id, item.uri)
        }
    }

    private fun extractTextForItem(itemId: String, uri: Uri) {
        viewModelScope.launch {
            try {
                val extracted = withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val inputImage = InputImage.fromFilePath(context, uri)
                    val visionText = processImage(inputImage)
                    val codeText = CodeTextExtractor.extractCode(visionText)
                    if (codeText.isNotBlank()) codeText else visionText.text.trim()
                }
                updateItemResult(itemId = itemId, text = extracted, error = null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract text from $uri", e)
                updateItemResult(
                    itemId = itemId,
                    text = "",
                    error = e.localizedMessage ?: "Failed to read image"
                )
            }
        }
    }

    private suspend fun processImage(inputImage: InputImage): Text =
        suspendCancellableCoroutine { continuation ->
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    if (continuation.isActive) {
                        continuation.resume(visionText)
                    }
                }
                .addOnFailureListener { exception ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
        }

    private fun updateItemResult(itemId: String, text: String, error: String?) {
        val threshold = currentSimilarityThreshold()
        _uiState.update { state ->
            val updatedItems = state.items.map { item ->
                if (item.id == itemId) {
                    item.copy(
                        extractedText = text,
                        isProcessing = false,
                        errorMessage = error
                    )
                } else {
                    item
                }
            }
            state.copy(
                items = updatedItems,
                isProcessingAny = updatedItems.any { it.isProcessing },
                combinedDocument = buildCombinedDocument(
                    items = updatedItems,
                    separator = state.separatorOption,
                    isOverlapAppendEnabled = state.isOverlapAppendEnabled,
                    similarityThreshold = threshold
                )
            )
        }
    }

    fun cropAndReExtractImage(itemId: String, cropRect: NormalizedCropRect) {
        val targetItem = _uiState.value.items.firstOrNull { it.id == itemId } ?: return
        if (targetItem.isProcessing) return

        val previousCroppedUri = targetItem.croppedUri
        val threshold = currentSimilarityThreshold()

        _uiState.update { state ->
            val updatedItems = state.items.map { item ->
                if (item.id == itemId) {
                    item.copy(
                        isProcessing = true,
                        errorMessage = null,
                        cropRect = if (cropRect.isFullImage) null else cropRect,
                        croppedUri = if (cropRect.isFullImage) null else item.croppedUri
                    )
                } else {
                    item
                }
            }
            state.copy(
                items = updatedItems,
                isProcessingAny = updatedItems.any { it.isProcessing },
                combinedDocument = buildCombinedDocument(
                    items = updatedItems,
                    separator = state.separatorOption,
                    isOverlapAppendEnabled = state.isOverlapAppendEnabled,
                    similarityThreshold = threshold
                )
            )
        }

        if (cropRect.isFullImage) {
            deleteCachedCropFile(previousCroppedUri)
            extractTextForItem(itemId = itemId, uri = targetItem.uri)
            return
        }

        viewModelScope.launch {
            var orientedBitmap: Bitmap? = null
            var croppedBitmap: Bitmap? = null
            try {
                val context = getApplication<Application>()
                val (newCroppedUri, extracted) = withContext(Dispatchers.IO) {
                    orientedBitmap = loadOrientedBitmap(
                        context = context,
                        uri = targetItem.uri,
                        maxDimension = 2560
                    )
                    val source = orientedBitmap ?: throw IllegalStateException("Unable to decode image for cropping")

                    val leftPx = (cropRect.left.coerceIn(0f, 1f) * source.width).roundToInt()
                        .coerceIn(0, (source.width - 1).coerceAtLeast(0))
                    val topPx = (cropRect.top.coerceIn(0f, 1f) * source.height).roundToInt()
                        .coerceIn(0, (source.height - 1).coerceAtLeast(0))
                    val rightPx = (cropRect.right.coerceIn(0f, 1f) * source.width).roundToInt()
                        .coerceIn(leftPx + 1, source.width)
                    val bottomPx = (cropRect.bottom.coerceIn(0f, 1f) * source.height).roundToInt()
                        .coerceIn(topPx + 1, source.height)

                    croppedBitmap = Bitmap.createBitmap(
                        source,
                        leftPx,
                        topPx,
                        rightPx - leftPx,
                        bottomPx - topPx
                    )
                    val cropped = croppedBitmap!!

                    val cropFile = File(
                        context.cacheDir,
                        "bulk_crop_${itemId}_${System.currentTimeMillis()}.jpg"
                    )
                    FileOutputStream(cropFile).use { out ->
                        cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }

                    val inputImage = InputImage.fromBitmap(cropped, 0)
                    val visionText = processImage(inputImage)
                    val codeText = CodeTextExtractor.extractCode(visionText)
                    val resultText = if (codeText.isNotBlank()) codeText else visionText.text.trim()
                    Uri.fromFile(cropFile) to resultText
                }

                deleteCachedCropFile(previousCroppedUri)
                updateCroppedItemResult(
                    itemId = itemId,
                    croppedUri = newCroppedUri,
                    cropRect = cropRect,
                    text = extracted,
                    error = null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to crop and extract text from ${targetItem.uri}", e)
                updateItemResult(
                    itemId = itemId,
                    text = targetItem.extractedText.orEmpty(),
                    error = e.localizedMessage ?: "Failed to crop image"
                )
            } finally {
                if (croppedBitmap != null && croppedBitmap !== orientedBitmap) {
                    croppedBitmap?.recycle()
                }
                orientedBitmap?.recycle()
            }
        }
    }

    private fun updateCroppedItemResult(
        itemId: String,
        croppedUri: Uri,
        cropRect: NormalizedCropRect,
        text: String,
        error: String?
    ) {
        val threshold = currentSimilarityThreshold()
        _uiState.update { state ->
            val updatedItems = state.items.map { item ->
                if (item.id == itemId) {
                    item.copy(
                        croppedUri = croppedUri,
                        cropRect = cropRect,
                        extractedText = text,
                        isProcessing = false,
                        errorMessage = error
                    )
                } else {
                    item
                }
            }
            state.copy(
                items = updatedItems,
                isProcessingAny = updatedItems.any { it.isProcessing },
                combinedDocument = buildCombinedDocument(
                    items = updatedItems,
                    separator = state.separatorOption,
                    isOverlapAppendEnabled = state.isOverlapAppendEnabled,
                    similarityThreshold = threshold
                )
            )
        }
    }

    private fun deleteCachedCropFile(uri: Uri?) {
        if (uri == null || uri.scheme != "file") return
        val path = uri.path ?: return
        runCatching {
            val file = File(path)
            if (file.exists() && file.name.startsWith("bulk_crop_")) {
                file.delete()
            }
        }
    }

    fun removeImage(itemId: String) {
        val threshold = currentSimilarityThreshold()
        val removedItem = _uiState.value.items.firstOrNull { it.id == itemId }
        deleteCachedCropFile(removedItem?.croppedUri)
        _uiState.update { state ->
            val updatedItems = state.items.filterNot { it.id == itemId }
            state.copy(
                items = updatedItems,
                isProcessingAny = updatedItems.any { it.isProcessing },
                combinedDocument = buildCombinedDocument(
                    items = updatedItems,
                    separator = state.separatorOption,
                    isOverlapAppendEnabled = state.isOverlapAppendEnabled,
                    similarityThreshold = threshold
                )
            )
        }
    }

    fun moveImage(fromIndex: Int, toIndex: Int) {
        val threshold = currentSimilarityThreshold()
        _uiState.update { state ->
            if (fromIndex !in state.items.indices || toIndex !in state.items.indices || fromIndex == toIndex) {
                return@update state
            }
            val mutable = state.items.toMutableList()
            val moved = mutable.removeAt(fromIndex)
            mutable.add(toIndex, moved)
            state.copy(
                items = mutable,
                combinedDocument = buildCombinedDocument(
                    items = mutable,
                    separator = state.separatorOption,
                    isOverlapAppendEnabled = state.isOverlapAppendEnabled,
                    similarityThreshold = threshold
                )
            )
        }
    }

    fun clearAllImages() {
        _uiState.value.items.forEach { deleteCachedCropFile(it.croppedUri) }
        _uiState.update { state ->
            state.copy(
                items = emptyList(),
                isProcessingAny = false,
                combinedDocument = ""
            )
        }
    }

    fun setSeparatorOption(option: BulkSeparatorOption) {
        prefs.edit().putString(KEY_SEPARATOR, option.key).apply()
        val threshold = currentSimilarityThreshold()
        _uiState.update { state ->
            state.copy(
                separatorOption = option,
                combinedDocument = buildCombinedDocument(
                    items = state.items,
                    separator = option,
                    isOverlapAppendEnabled = state.isOverlapAppendEnabled,
                    similarityThreshold = threshold
                )
            )
        }
    }

    fun toggleOverlapAppend() {
        val threshold = currentSimilarityThreshold()
        _uiState.update { state ->
            val nextEnabled = !state.isOverlapAppendEnabled
            prefs.edit().putBoolean(KEY_OVERLAP_APPEND, nextEnabled).apply()
            state.copy(
                isOverlapAppendEnabled = nextEnabled,
                combinedDocument = buildCombinedDocument(
                    items = state.items,
                    separator = state.separatorOption,
                    isOverlapAppendEnabled = nextEnabled,
                    similarityThreshold = threshold
                )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        _uiState.value.items.forEach { deleteCachedCropFile(it.croppedUri) }
        textRecognizer.close()
    }

    companion object {
        private const val TAG = "BulkOcrViewModel"
        private const val PREFS_NAME = "bulk_ocr_prefs"
        private const val KEY_SEPARATOR = "bulk_separator_option"
        private const val KEY_OVERLAP_APPEND = "bulk_overlap_append_enabled"

        fun buildCombinedDocument(
            items: List<BulkImageItem>,
            separator: BulkSeparatorOption,
            isOverlapAppendEnabled: Boolean = false,
            similarityThreshold: Double = ContinuousOcrEngine.DEFAULT_SIMILARITY_THRESHOLD
        ): String {
            val indexedTexts = items.mapIndexedNotNull { index, item ->
                val raw = item.extractedText
                if (raw.isNullOrBlank()) null else (index + 1) to raw
            }
            return mergeTextsWithOverlap(
                indexedTexts = indexedTexts,
                separator = separator,
                isOverlapAppendEnabled = isOverlapAppendEnabled,
                similarityThreshold = similarityThreshold
            )
        }

        fun mergeTextsWithOverlap(
            indexedTexts: List<Pair<Int, String>>,
            separator: BulkSeparatorOption,
            isOverlapAppendEnabled: Boolean,
            similarityThreshold: Double = ContinuousOcrEngine.DEFAULT_SIMILARITY_THRESHOLD
        ): String {
            if (indexedTexts.isEmpty()) return ""

            if (!isOverlapAppendEnabled) {
                val numberedTexts = indexedTexts.mapNotNull { (imageNumber, raw) ->
                    val text = raw.trim()
                    if (text.isNotBlank()) imageNumber to text else null
                }

                if (numberedTexts.isEmpty()) return ""

                return when (separator) {
                    BulkSeparatorOption.NEW_LINE ->
                        numberedTexts.joinToString("\n") { it.second }

                    BulkSeparatorOption.EMPTY_LINE ->
                        numberedTexts.joinToString("\n\n") { it.second }

                    BulkSeparatorOption.UNDERSCORES ->
                        numberedTexts.joinToString("\n___\n") { it.second }

                    BulkSeparatorOption.DASHES ->
                        numberedTexts.joinToString("\n----\n") { it.second }

                    BulkSeparatorOption.IMAGE_NUMBERING ->
                        numberedTexts.joinToString("\n\n") { (imageNumber, text) ->
                            "Image $imageNumber:\n$text"
                        }
                }
            }

            // Simple fuzzy-matching suffix-prefix overlap append (no Max Vote, no extra cleanup, no speed mode)
            val output = StringBuilder()
            var previousRows: List<OcrRow> = emptyList()

            for ((imageNumber, rawText) in indexedTexts) {
                if (rawText.isBlank()) continue
                val currentRows = toOcrRows(rawText)
                if (currentRows.isEmpty()) continue

                if (previousRows.isEmpty()) {
                    if (separator == BulkSeparatorOption.IMAGE_NUMBERING) {
                        output.append("Image ").append(imageNumber).append(":\n")
                    }
                    output.append(ContinuousOcrEngine.buildOriginalOutput(currentRows))
                } else {
                    val overlapCount = ContinuousOcrEngine.detectSuffixPrefixOverlap(
                        previousRows = previousRows,
                        currentRows = currentRows,
                        similarityThreshold = similarityThreshold
                    )
                    val rowsToAppend = if (overlapCount > 0) {
                        currentRows.subList(overlapCount, currentRows.size)
                    } else {
                        currentRows
                    }

                    if (rowsToAppend.isNotEmpty()) {
                        val appendedText = ContinuousOcrEngine.buildOriginalOutput(rowsToAppend)
                        if (overlapCount > 0) {
                            if (output.isNotEmpty()) output.append('\n')
                            output.append(appendedText)
                        } else {
                            when (separator) {
                                BulkSeparatorOption.NEW_LINE ->
                                    output.append('\n').append(appendedText)

                                BulkSeparatorOption.EMPTY_LINE ->
                                    output.append("\n\n").append(appendedText)

                                BulkSeparatorOption.UNDERSCORES ->
                                    output.append("\n___\n").append(appendedText)

                                BulkSeparatorOption.DASHES ->
                                    output.append("\n----\n").append(appendedText)

                                BulkSeparatorOption.IMAGE_NUMBERING ->
                                    output.append("\n\nImage ").append(imageNumber).append(":\n").append(appendedText)
                            }
                        }
                    }
                }
                previousRows = currentRows
            }

            return output.toString()
        }

        private fun toOcrRows(rawText: String): List<OcrRow> {
            val lines = rawText.lines().filter { it.isNotBlank() }
            val rows = ArrayList<OcrRow>(lines.size)
            for ((idx, line) in lines.withIndex()) {
                val normalized = ContinuousOcrEngine.normalizeLineText(line)
                if (normalized.isEmpty()) continue
                val top = idx * 40
                rows.add(
                    OcrRow(
                        originalText = line,
                        normalizedText = normalized,
                        top = top,
                        bottom = top + 30,
                        left = 0,
                        right = 500
                    )
                )
            }
            return rows
        }
    }
}
