package com.example.ui.camera

import com.google.mlkit.vision.text.Text
import java.util.Locale
import kotlin.math.floor

/**
 * Internal representation of a single ML Kit OCR line with original text,
 * backend-only normalized comparison text, and bounding-box coordinates.
 */
data class OcrRow(
    val originalText: String,
    val normalizedText: String,
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int
)

enum class OcrSpeedMode {
    NORMAL,
    SLOW
}

data class OcrObservationResult(
    val accumulatedText: String,
    val appendedRowCount: Int,
    val overlapRowCount: Int,
    val speedMode: OcrSpeedMode,
    val stabilityCounter: Int,
    val canUndo: Boolean = false
)

private data class AppendHistoryEntry(
    val appendedCount: Int,
    val previousFrameRowsBeforeAppend: List<OcrRow>
)

/**
 * Continuous downward-scrolling OCR engine with backend-only normalization,
 * fuzzy full-frame suffix-prefix overlap detection, row-boundary preservation,
 * multi-step undo for appended chunks, and adaptive speed detection.
 */
class ContinuousOcrEngine(
    initialSettings: OcrSettings = OcrSettings()
) {
    constructor(
        similarityThreshold: Double = OcrSettings.DEFAULT_FUZZY_MATCH_PERCENT / 100.0,
        stationaryThreshold: Int = OcrSettings.DEFAULT_STATIONARY_THRESHOLD
    ) : this(
        OcrSettings(
            fuzzyMatchPercent = (similarityThreshold * 100).toInt(),
            stationaryThreshold = stationaryThreshold
        )
    )

    @Volatile
    var settings: OcrSettings = initialSettings
        private set

    private val accumulatedRows = ArrayList<OcrRow>()
    private val appendHistory = ArrayDeque<AppendHistoryEntry>()
    private var previousFrameRows: List<OcrRow> = emptyList()
    private var lastNormalizedFrameText: String? = null

    var speedMode: OcrSpeedMode = OcrSpeedMode.NORMAL
        private set

    var stabilityCounter: Int = 0
        private set

    fun canUndo(): Boolean = appendHistory.isNotEmpty()

    fun updateSettings(newSettings: OcrSettings) {
        settings = newSettings
        if (stabilityCounter >= newSettings.stationaryThreshold) {
            speedMode = OcrSpeedMode.SLOW
        } else if (speedMode == OcrSpeedMode.SLOW) {
            speedMode = OcrSpeedMode.NORMAL
        }
    }

    fun requiredIntervalMs(): Long {
        val current = settings
        return when (speedMode) {
            OcrSpeedMode.NORMAL -> current.normalIntervalMs
            OcrSpeedMode.SLOW -> current.slowIntervalMs
        }
    }

    fun reset() {
        accumulatedRows.clear()
        appendHistory.clear()
        previousFrameRows = emptyList()
        lastNormalizedFrameText = null
        speedMode = OcrSpeedMode.NORMAL
        stabilityCounter = 0
    }

    /**
     * Removes the most recently appended chunk of rows, restoring the prior frame reference
     * so repeated undo taps cleanly step backward through every append operation.
     */
    fun undoLastAppend(): OcrObservationResult {
        val lastEntry = appendHistory.removeLastOrNull()
        if (lastEntry != null) {
            val removeCount = lastEntry.appendedCount.coerceAtMost(accumulatedRows.size)
            repeat(removeCount) {
                if (accumulatedRows.isNotEmpty()) {
                    accumulatedRows.removeAt(accumulatedRows.size - 1)
                }
            }
            previousFrameRows = lastEntry.previousFrameRowsBeforeAppend
            lastNormalizedFrameText = if (previousFrameRows.isEmpty()) {
                null
            } else {
                normalizeFrameText(previousFrameRows)
            }
            stabilityCounter = 0
            speedMode = OcrSpeedMode.NORMAL
        }
        return OcrObservationResult(
            accumulatedText = buildOriginalOutput(accumulatedRows),
            appendedRowCount = 0,
            overlapRowCount = 0,
            speedMode = speedMode,
            stabilityCounter = stabilityCounter,
            canUndo = appendHistory.isNotEmpty()
        )
    }

    /**
     * Processes a single ML Kit [Text] observation (batch size = 1).
     */
    fun processVisionText(visionText: Text): OcrObservationResult {
        val rows = extractRowsFromVisionText(visionText)
        return processRows(rows)
    }

    /**
     * Processes a batch of ordered observations sequentially and returns the aggregated result.
     */
    fun processBatch(batch: List<List<OcrRow>>): OcrObservationResult {
        var totalAppended = 0
        var totalOverlap = 0
        var latestResult = OcrObservationResult(
            accumulatedText = buildOriginalOutput(accumulatedRows),
            appendedRowCount = 0,
            overlapRowCount = 0,
            speedMode = speedMode,
            stabilityCounter = stabilityCounter,
            canUndo = appendHistory.isNotEmpty()
        )
        for (frameRows in batch) {
            latestResult = processRows(frameRows)
            totalAppended += latestResult.appendedRowCount
            totalOverlap += latestResult.overlapRowCount
        }
        return latestResult.copy(
            appendedRowCount = totalAppended,
            overlapRowCount = totalOverlap
        )
    }

    /**
     * Processes an ordered list of extracted [OcrRow]s from a single observation.
     */
    fun processRows(currentRows: List<OcrRow>): OcrObservationResult {
        val activeSettings = settings
        val similarityThreshold = activeSettings.similarityThreshold
        val stationaryThreshold = activeSettings.stationaryThreshold

        val orderedCurrentRows = enforceTopToBottomOrder(currentRows)
        val currentNormalizedFrame = normalizeFrameText(orderedCurrentRows)

        // Empty frame: keep accumulated output stable without appending
        if (orderedCurrentRows.isEmpty()) {
            updateStationarySpeedState(
                currentNormalizedFrame = currentNormalizedFrame,
                appendedAnyRows = false,
                similarityThreshold = similarityThreshold,
                stationaryThreshold = stationaryThreshold
            )
            return OcrObservationResult(
                accumulatedText = buildOriginalOutput(accumulatedRows),
                appendedRowCount = 0,
                overlapRowCount = 0,
                speedMode = speedMode,
                stabilityCounter = stabilityCounter,
                canUndo = appendHistory.isNotEmpty()
            )
        }

        // Initial non-empty observation
        if (accumulatedRows.isEmpty()) {
            appendHistory.addLast(
                AppendHistoryEntry(
                    appendedCount = orderedCurrentRows.size,
                    previousFrameRowsBeforeAppend = previousFrameRows
                )
            )
            accumulatedRows.addAll(orderedCurrentRows)
            previousFrameRows = orderedCurrentRows
            updateStationarySpeedState(
                currentNormalizedFrame = currentNormalizedFrame,
                appendedAnyRows = true,
                similarityThreshold = similarityThreshold,
                stationaryThreshold = stationaryThreshold
            )
            return OcrObservationResult(
                accumulatedText = buildOriginalOutput(accumulatedRows),
                appendedRowCount = orderedCurrentRows.size,
                overlapRowCount = 0,
                speedMode = speedMode,
                stabilityCounter = stabilityCounter,
                canUndo = appendHistory.isNotEmpty()
            )
        }

        // Fast-path stationary guard: identical normalized frame never duplicates text
        val prevNormalizedFrame = normalizeFrameText(previousFrameRows)
        if (currentNormalizedFrame == prevNormalizedFrame) {
            previousFrameRows = orderedCurrentRows
            updateStationarySpeedState(
                currentNormalizedFrame = currentNormalizedFrame,
                appendedAnyRows = false,
                similarityThreshold = similarityThreshold,
                stationaryThreshold = stationaryThreshold
            )
            return OcrObservationResult(
                accumulatedText = buildOriginalOutput(accumulatedRows),
                appendedRowCount = 0,
                overlapRowCount = orderedCurrentRows.size,
                speedMode = speedMode,
                stabilityCounter = stabilityCounter,
                canUndo = appendHistory.isNotEmpty()
            )
        }

        // Full-frame suffix-prefix overlap detection against previous observation
        val overlapRows = detectSuffixPrefixOverlap(
            previousRows = previousFrameRows,
            currentRows = orderedCurrentRows,
            similarityThreshold = similarityThreshold
        )

        val rowsToAppend = if (overlapRows > 0) {
            orderedCurrentRows.subList(overlapRows, orderedCurrentRows.size)
        } else if (
            isContainedInRecentTail(
                accumulatedRows = accumulatedRows,
                currentRows = orderedCurrentRows,
                recentWindowSize = maxOf(previousFrameRows.size + 3, orderedCurrentRows.size + 2),
                similarityThreshold = similarityThreshold
            )
        ) {
            // Stationary frame with transient dropped bottom line(s): do not duplicate
            emptyList()
        } else {
            orderedCurrentRows
        }

        if (rowsToAppend.isNotEmpty()) {
            appendHistory.addLast(
                AppendHistoryEntry(
                    appendedCount = rowsToAppend.size,
                    previousFrameRowsBeforeAppend = previousFrameRows
                )
            )
            accumulatedRows.addAll(rowsToAppend)
        }
        previousFrameRows = orderedCurrentRows

        updateStationarySpeedState(
            currentNormalizedFrame = currentNormalizedFrame,
            appendedAnyRows = rowsToAppend.isNotEmpty(),
            similarityThreshold = similarityThreshold,
            stationaryThreshold = stationaryThreshold
        )

        return OcrObservationResult(
            accumulatedText = buildOriginalOutput(accumulatedRows),
            appendedRowCount = rowsToAppend.size,
            overlapRowCount = overlapRows,
            speedMode = speedMode,
            stabilityCounter = stabilityCounter,
            canUndo = appendHistory.isNotEmpty()
        )
    }

    private fun updateStationarySpeedState(
        currentNormalizedFrame: String,
        appendedAnyRows: Boolean,
        similarityThreshold: Double,
        stationaryThreshold: Int
    ) {
        if (appendedAnyRows) {
            stabilityCounter = 0
            speedMode = OcrSpeedMode.NORMAL
            lastNormalizedFrameText = currentNormalizedFrame
            return
        }

        val previousBaseline = lastNormalizedFrameText
        val isMatch = previousBaseline != null && isFrameStationaryMatch(
            prevNormalized = previousBaseline,
            currNormalized = currentNormalizedFrame,
            similarityThreshold = similarityThreshold
        )

        if (isMatch) {
            stabilityCounter += 1
            if (stabilityCounter >= stationaryThreshold) {
                speedMode = OcrSpeedMode.SLOW
            }
        } else {
            stabilityCounter = 0
            speedMode = OcrSpeedMode.NORMAL
            lastNormalizedFrameText = currentNormalizedFrame
        }
    }

    private fun isFrameStationaryMatch(
        prevNormalized: String,
        currNormalized: String,
        similarityThreshold: Double
    ): Boolean {
        if (prevNormalized == currNormalized) return true
        if (prevNormalized.isEmpty() || currNormalized.isEmpty()) return false
        return computeSimilarityWithThreshold(
            prevNormalized,
            currNormalized,
            similarityThreshold
        ) >= similarityThreshold
    }

    companion object {
        const val NORMAL_INTERVAL_MS = 100L
        const val SLOW_INTERVAL_MS = 1000L
        const val DEFAULT_SIMILARITY_THRESHOLD = 0.80
        const val DEFAULT_STATIONARY_THRESHOLD = 10

        private val REGEX_MULTI_WHITESPACE = Regex("""[ \t\u00A0\u2007\u202F]+""")
        private val REGEX_LINE_BREAKS = Regex("""\r\n|\r|\n""")

        /**
         * Extracts individual [Text.Line] objects from ML Kit's TextBlock -> Line hierarchy,
         * preserving original text and bounding boxes in top-to-bottom row order.
         */
        fun extractRowsFromVisionText(visionText: Text): List<OcrRow> {
            val extracted = ArrayList<OcrRow>()
            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val original = line.text
                    if (original.isBlank()) continue
                    val normalized = normalizeLineText(original)
                    if (normalized.isEmpty()) continue
                    val box = line.boundingBox
                    extracted.add(
                        OcrRow(
                            originalText = original,
                            normalizedText = normalized,
                            top = box?.top ?: 0,
                            bottom = box?.bottom ?: 0,
                            left = box?.left ?: 0,
                            right = box?.right ?: 0
                        )
                    )
                }
            }
            return enforceTopToBottomOrder(extracted)
        }

        /**
         * Orders rows strictly from top to bottom using bounding-box coordinates,
         * breaking vertical ties left-to-right.
         */
        fun enforceTopToBottomOrder(rows: List<OcrRow>): List<OcrRow> {
            if (rows.size <= 1) return rows
            return rows.sortedWith(
                compareBy<OcrRow> { it.top }
                    .thenBy { (it.top + it.bottom) / 2 }
                    .thenBy { it.left }
            )
        }

        /**
         * Validates that bounding boxes are consistently ordered top-to-bottom
         * (allowing slight overlap from slanted camera angles).
         */
        fun validateRowOrdering(rows: List<OcrRow>): Boolean {
            if (rows.size <= 1) return true
            for (i in 1 until rows.size) {
                val prev = rows[i - 1]
                val curr = rows[i]
                if (curr.top < prev.top) return false
                if (curr.bottom < prev.top) return false
            }
            return true
        }

        /**
         * Backend-only normalization for a single OCR line.
         * - Lowercases text
         * - Normalizes line endings and repeated whitespace
         * - Preserves all meaningful characters, digits, punctuation, and code operators
         */
        fun normalizeLineText(rawText: String): String {
            if (rawText.isBlank()) return ""
            val singleLine = REGEX_LINE_BREAKS.replace(rawText, " ")
            return REGEX_MULTI_WHITESPACE.replace(singleLine, " ")
                .trim()
                .lowercase(Locale.ROOT)
        }

        /**
         * Converts an entire frame's OCR rows into a single normalized comparison string
         * with blank lines removed and uniform `\n` line endings.
         */
        fun normalizeFrameText(rows: List<OcrRow>): String {
            if (rows.isEmpty()) return ""
            return rows.asSequence()
                .map { it.normalizedText }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }

        /**
         * Reconstructs the user-visible document strictly from untouched [OcrRow.originalText] rows.
         */
        fun buildOriginalOutput(rows: List<OcrRow>): String {
            if (rows.isEmpty()) return ""
            return rows.joinToString("\n") { it.originalText }
        }

        /**
         * Finds the longest valid overlap between the suffix of [previousRows] and the prefix
         * of [currentRows] using full-frame normalized string comparison at [similarityThreshold]
         * while mapping cleanly to complete OCR line boundaries.
         */
        fun detectSuffixPrefixOverlap(
            previousRows: List<OcrRow>,
            currentRows: List<OcrRow>,
            similarityThreshold: Double = DEFAULT_SIMILARITY_THRESHOLD
        ): Int {
            if (previousRows.isEmpty() || currentRows.isEmpty()) return 0

            val maxOverlap = minOf(previousRows.size, currentRows.size)
            var bestK = 0
            var bestSimilarity = -1.0

            for (k in maxOverlap downTo 1) {
                val prevSuffix = previousRows.subList(previousRows.size - k, previousRows.size)
                val currPrefix = currentRows.subList(0, k)

                if (!validateRowOrdering(prevSuffix) || !validateRowOrdering(currPrefix)) {
                    continue
                }

                val prevSuffixStr = normalizeFrameText(prevSuffix)
                val currPrefixStr = normalizeFrameText(currPrefix)

                if (!meetsMinimumOverlapLength(k, prevSuffixStr, currPrefixStr)) {
                    continue
                }

                val fullSimilarity = computeSimilarityWithThreshold(
                    prevSuffixStr,
                    currPrefixStr,
                    similarityThreshold
                )
                if (fullSimilarity < similarityThreshold) {
                    continue
                }

                // Boundary-row guard: prevents a long interior match from swallowing
                // an unrelated leading/trailing row into the overlap.
                if (k >= 2) {
                    val boundaryMin = minOf(0.55, similarityThreshold * 0.70)
                    val firstRowSim = computeSimilarityWithThreshold(
                        prevSuffix.first().normalizedText,
                        currPrefix.first().normalizedText,
                        boundaryMin
                    )
                    val lastRowSim = computeSimilarityWithThreshold(
                        prevSuffix.last().normalizedText,
                        currPrefix.last().normalizedText,
                        boundaryMin
                    )
                    if (firstRowSim < boundaryMin || lastRowSim < boundaryMin) {
                        continue
                    }
                }

                // Prefer the highest similarity match; keep the longer k on near-ties (<= 2% delta)
                if (bestK == 0 || fullSimilarity > bestSimilarity + 0.02) {
                    bestK = k
                    bestSimilarity = fullSimilarity
                }
            }

            return bestK
        }

        /**
         * Avoids very short accidental 1-line collisions (e.g. a lone `"}"` or `"{"`),
         * while allowing multi-line overlaps (k >= 2) even on short tokens.
         */
        private fun meetsMinimumOverlapLength(
            rowCount: Int,
            prevSuffixStr: String,
            currPrefixStr: String
        ): Boolean {
            val minContentChars = minOf(
                prevSuffixStr.replace("\n", "").length,
                currPrefixStr.replace("\n", "").length
            )
            return if (rowCount >= 2) {
                minContentChars >= 2
            } else {
                minContentChars >= 4
            }
        }

        /**
         * Checks whether [currentRows] already appears as a contiguous block inside the
         * recent tail of [accumulatedRows] (handles transient dropped bottom lines on
         * stationary screens without globally deduplicating legitimate repeated code lines).
         */
        fun isContainedInRecentTail(
            accumulatedRows: List<OcrRow>,
            currentRows: List<OcrRow>,
            recentWindowSize: Int,
            similarityThreshold: Double = DEFAULT_SIMILARITY_THRESHOLD
        ): Boolean {
            if (currentRows.isEmpty() || accumulatedRows.size < currentRows.size) return false
            val windowStart = (accumulatedRows.size - recentWindowSize).coerceAtLeast(0)
            val window = accumulatedRows.subList(windowStart, accumulatedRows.size)
            if (window.size < currentRows.size) return false

            val currNorm = normalizeFrameText(currentRows)
            if (currNorm.replace("\n", "").length < 4 && currentRows.size < 2) return false

            val maxStart = window.size - currentRows.size
            for (start in maxStart downTo 0) {
                val slice = window.subList(start, start + currentRows.size)
                val sliceNorm = normalizeFrameText(slice)
                if (computeSimilarityWithThreshold(sliceNorm, currNorm, similarityThreshold) >= similarityThreshold) {
                    return true
                }
            }
            return false
        }

        /**
         * Computes normalized Levenshtein similarity in [0.0, 1.0].
         */
        fun computeSimilarity(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val maxLen = maxOf(a.length, b.length)
            val dist = levenshteinDistance(a, b)
            return 1.0 - (dist.toDouble() / maxLen.toDouble())
        }

        /**
         * Bounded Levenshtein distance using O(min(N, M)) space.
         */
        fun computeSimilarityWithThreshold(
            a: String,
            b: String,
            threshold: Double
        ): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val maxLen = maxOf(a.length, b.length)
            val minLen = minOf(a.length, b.length)
            if (minLen.toDouble() / maxLen.toDouble() < threshold) return 0.0

            val maxAllowedDistance = floor(maxLen * (1.0 - threshold)).toInt()
            val dist = levenshteinDistanceBounded(a, b, maxAllowedDistance)
            if (dist > maxAllowedDistance) return 0.0
            return 1.0 - (dist.toDouble() / maxLen.toDouble())
        }

        private fun levenshteinDistance(s1: String, s2: String): Int {
            val (short, long) = if (s1.length <= s2.length) s1 to s2 else s2 to s1
            val prev = IntArray(short.length + 1) { it }
            val curr = IntArray(short.length + 1)

            for (i in 1..long.length) {
                curr[0] = i
                val longChar = long[i - 1]
                for (j in 1..short.length) {
                    val cost = if (longChar == short[j - 1]) 0 else 1
                    curr[j] = minOf(
                        curr[j - 1] + 1,
                        prev[j] + 1,
                        prev[j - 1] + cost
                    )
                }
                curr.copyInto(prev)
            }
            return prev[short.length]
        }

        private fun levenshteinDistanceBounded(s1: String, s2: String, maxDistance: Int): Int {
            val (short, long) = if (s1.length <= s2.length) s1 to s2 else s2 to s1
            val prev = IntArray(short.length + 1) { it }
            val curr = IntArray(short.length + 1)

            for (i in 1..long.length) {
                curr[0] = i
                var rowMin = i
                val longChar = long[i - 1]
                for (j in 1..short.length) {
                    val cost = if (longChar == short[j - 1]) 0 else 1
                    val value = minOf(
                        curr[j - 1] + 1,
                        prev[j] + 1,
                        prev[j - 1] + cost
                    )
                    curr[j] = value
                    if (value < rowMin) rowMin = value
                }
                if (rowMin > maxDistance) return maxDistance + 1
                curr.copyInto(prev)
            }
            return prev[short.length]
        }
    }
}
