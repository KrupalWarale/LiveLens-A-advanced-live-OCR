package com.example.ui.bulk

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ui.camera.CodeTextExtractor
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
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

data class BulkImageItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val extractedText: String? = null,
    val isProcessing: Boolean = false,
    val errorMessage: String? = null
)

data class BulkOcrUiState(
    val items: List<BulkImageItem> = emptyList(),
    val separatorOption: BulkSeparatorOption = BulkSeparatorOption.EMPTY_LINE,
    val combinedDocument: String = "",
    val isProcessingAny: Boolean = false
)

class BulkOcrViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val textRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val _uiState = MutableStateFlow(
        BulkOcrUiState(
            separatorOption = BulkSeparatorOption.fromKey(
                prefs.getString(KEY_SEPARATOR, BulkSeparatorOption.EMPTY_LINE.key)
            )
        )
    )
    val uiState: StateFlow<BulkOcrUiState> = _uiState.asStateFlow()

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val newItems = uris.map { uri ->
            BulkImageItem(
                uri = uri,
                isProcessing = true
            )
        }

        _uiState.update { state ->
            val updatedItems = state.items + newItems
            state.copy(
                items = updatedItems,
                isProcessingAny = updatedItems.any { it.isProcessing },
                combinedDocument = buildCombinedDocument(updatedItems, state.separatorOption)
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
                combinedDocument = buildCombinedDocument(updatedItems, state.separatorOption)
            )
        }
    }

    fun removeImage(itemId: String) {
        _uiState.update { state ->
            val updatedItems = state.items.filterNot { it.id == itemId }
            state.copy(
                items = updatedItems,
                isProcessingAny = updatedItems.any { it.isProcessing },
                combinedDocument = buildCombinedDocument(updatedItems, state.separatorOption)
            )
        }
    }

    fun moveImage(fromIndex: Int, toIndex: Int) {
        _uiState.update { state ->
            if (fromIndex !in state.items.indices || toIndex !in state.items.indices || fromIndex == toIndex) {
                return@update state
            }
            val mutable = state.items.toMutableList()
            val moved = mutable.removeAt(fromIndex)
            mutable.add(toIndex, moved)
            state.copy(
                items = mutable,
                combinedDocument = buildCombinedDocument(mutable, state.separatorOption)
            )
        }
    }

    fun clearAllImages() {
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
        _uiState.update { state ->
            state.copy(
                separatorOption = option,
                combinedDocument = buildCombinedDocument(state.items, option)
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        textRecognizer.close()
    }

    companion object {
        private const val TAG = "BulkOcrViewModel"
        private const val PREFS_NAME = "bulk_ocr_prefs"
        private const val KEY_SEPARATOR = "bulk_separator_option"

        fun buildCombinedDocument(
            items: List<BulkImageItem>,
            separator: BulkSeparatorOption
        ): String {
            val numberedTexts = items.mapIndexedNotNull { index, item ->
                val text = item.extractedText?.trim().orEmpty()
                if (text.isNotBlank()) (index + 1) to text else null
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
    }
}
