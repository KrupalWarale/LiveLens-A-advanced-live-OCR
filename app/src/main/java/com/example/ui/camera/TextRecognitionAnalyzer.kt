package com.example.ui.camera

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

class TextRecognitionAnalyzer(
    private val isScanningActive: () -> Boolean,
    private val ocrEngine: ContinuousOcrEngine = ContinuousOcrEngine(),
    private val onObservationProcessed: (OcrObservationResult) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "TextRecognitionAnalyzer"
    }

    private val isProcessing = AtomicBoolean(false)

    @Volatile
    private var lastProcessedTimestamp = 0L

    private var completedInCurrentBatch = 0
    private var appendedInCurrentBatch = 0
    private var overlapInCurrentBatch = 0

    // Single reusable ML Kit TextRecognizer instance (never recreated per frame)
    private val textRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun isTaskInFlight(): Boolean = isProcessing.get()

    fun resetSession() {
        completedInCurrentBatch = 0
        appendedInCurrentBatch = 0
        overlapInCurrentBatch = 0
        lastProcessedTimestamp = 0L
        ocrEngine.reset()
    }

    fun undoLastAppend(): OcrObservationResult {
        completedInCurrentBatch = 0
        appendedInCurrentBatch = 0
        overlapInCurrentBatch = 0
        return ocrEngine.undoLastAppend()
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (!isScanningActive()) {
            imageProxy.close()
            return
        }

        // Enforce maximum 1 OCR task in flight; drop incoming frame without queueing
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val now = SystemClock.uptimeMillis()
        val requiredIntervalMs = ocrEngine.requiredIntervalMs()
        if (lastProcessedTimestamp > 0L && now - lastProcessedTimestamp < requiredIntervalMs) {
            isProcessing.set(false)
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            isProcessing.set(false)
            imageProxy.close()
            return
        }

        lastProcessedTimestamp = now
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText: Text ->
                val result = ocrEngine.processVisionText(visionText)
                val targetBatchSize = ocrEngine.settings.batchSize.coerceAtLeast(1)

                completedInCurrentBatch += 1
                appendedInCurrentBatch += result.appendedRowCount
                overlapInCurrentBatch += result.overlapRowCount

                if (completedInCurrentBatch >= targetBatchSize) {
                    val batchedResult = result.copy(
                        appendedRowCount = appendedInCurrentBatch,
                        overlapRowCount = overlapInCurrentBatch
                    )
                    completedInCurrentBatch = 0
                    appendedInCurrentBatch = 0
                    overlapInCurrentBatch = 0
                    onObservationProcessed(batchedResult)
                }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Text recognition failed", exception)
            }
            .addOnCompleteListener {
                imageProxy.close()
                isProcessing.set(false)
            }
    }

    fun close() {
        textRecognizer.close()
    }
}
