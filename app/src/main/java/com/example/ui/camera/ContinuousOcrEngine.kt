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

private class RowVoteBucket(initialText: String) {
    private val counts = LinkedHashMap<String, Int>(4)
    var winnerText: String = initialText
        private set
    private var winnerVotes: Int = 1

    init {
        counts[initialText] = 1
    }

    fun castVote(candidateText: String): String {
        val updated = (counts[candidateText] ?: 0) + 1
        counts[candidateText] = updated
        if (updated > winnerVotes) {
            winnerVotes = updated
            winnerText = candidateText
        }
        return winnerText
    }
}

/**
 * Continuous downward-scrolling OCR engine with backend-only normalization,
 * fuzzy full-frame suffix-prefix overlap detection, row-boundary preservation,
 * multi-step undo for appended chunks, adaptive speed detection, and an optional
 * real-time Max Vote algorithm for text consensus and duplicate-line cleanup.
 */
class ContinuousOcrEngine(
    initialSettings: OcrSettings = OcrSettings()
) {
    private val lock = Any()

    @Volatile
    var settings: OcrSettings = initialSettings
        private set

    private val accumulatedRows = ArrayList<OcrRow>()
    private val rowVotes = ArrayList<RowVoteBucket>()
    private val appendHistory = ArrayDeque<AppendHistoryEntry>()
    private var previousFrameRows: List<OcrRow> = emptyList()
    private var lastNormalizedFrameText: String? = null

    @Volatile
    var speedMode: OcrSpeedMode = OcrSpeedMode.NORMAL
        private set

    @Volatile
    var stabilityCounter: Int = 0
        private set

    fun updateSettings(newSettings: OcrSettings) {
        synchronized(lock) {
            settings = newSettings
            if (stabilityCounter >= newSettings.stationaryThreshold) {
                speedMode = OcrSpeedMode.SLOW
            } else if (speedMode == OcrSpeedMode.SLOW) {
                speedMode = OcrSpeedMode.NORMAL
            }
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
        synchronized(lock) {
            accumulatedRows.clear()
            rowVotes.clear()
            appendHistory.clear()
            previousFrameRows = emptyList()
            lastNormalizedFrameText = null
            speedMode = OcrSpeedMode.NORMAL
            stabilityCounter = 0
        }
    }

    /**
     * Removes the most recently appended chunk of rows, restoring the prior frame reference
     * so repeated undo taps cleanly step backward through every append operation.
     */
    fun undoLastAppend(): OcrObservationResult = synchronized(lock) {
        val lastEntry = appendHistory.removeLastOrNull()
        if (lastEntry != null) {
            val removeCount = lastEntry.appendedCount.coerceAtMost(accumulatedRows.size)
            repeat(removeCount) {
                if (accumulatedRows.isNotEmpty()) {
                    accumulatedRows.removeAt(accumulatedRows.size - 1)
                }
                if (rowVotes.isNotEmpty()) {
                    rowVotes.removeAt(rowVotes.size - 1)
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
        OcrObservationResult(
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
     * Processes an ordered list of extracted [OcrRow]s from a single observation.
     */
    fun processRows(currentRows: List<OcrRow>): OcrObservationResult = synchronized(lock) {
        processRowsLocked(currentRows)
    }

    private fun processRowsLocked(currentRows: List<OcrRow>): OcrObservationResult {
        val activeSettings = settings
        val similarityThreshold = activeSettings.similarityThreshold
        val stationaryThreshold = activeSettings.stationaryThreshold
        val useMaxVote = activeSettings.maxVoteEnabled

        val orderedRaw = enforceTopToBottomOrder(currentRows)
        val orderedCurrentRows = if (useMaxVote) {
            deduplicateConsecutiveInFrame(orderedRaw, similarityThreshold)
        } else {
            orderedRaw
        }
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
            appendRowsWithVotes(orderedCurrentRows)
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

        // Fast-path stationary guard: identical normalized frame votes on tail and never duplicates text
        val prevNormalizedFrame = normalizeFrameText(previousFrameRows)
        if (currentNormalizedFrame == prevNormalizedFrame) {
            if (useMaxVote) {
                castVotesOnTailSlice(orderedCurrentRows, similarityThreshold)
            }
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
        var overlapRows = detectSuffixPrefixOverlap(
            previousRows = previousFrameRows,
            currentRows = orderedCurrentRows,
            similarityThreshold = similarityThreshold
        )

        var rowsToAppend = if (overlapRows > 0) {
            if (useMaxVote) {
                castVotesOnSuffixOverlap(
                    overlapCount = overlapRows,
                    currentRows = orderedCurrentRows,
                    similarityThreshold = similarityThreshold
                )
            }
            orderedCurrentRows.subList(overlapRows, orderedCurrentRows.size)
        } else if (
            isContainedInRecentTail(
                accumulatedRows = accumulatedRows,
                currentRows = orderedCurrentRows,
                recentWindowSize = maxOf(previousFrameRows.size + 3, orderedCurrentRows.size + 2),
                similarityThreshold = similarityThreshold
            )
        ) {
            if (useMaxVote) {
                castVotesOnTailSlice(orderedCurrentRows, similarityThreshold)
            }
            emptyList()
        } else if (useMaxVote) {
            // Max Vote Cleanup fallback: align against recent accumulated tail when noisy edges
            // or upward jitter broke strict suffix-prefix matching, then remove duplicate lines.
            val (tailMatchedCount, filteredRemaining) = cleanupDuplicatesWithMaxVote(
                currentRows = orderedCurrentRows,
                similarityThreshold = similarityThreshold
            )
            overlapRows = tailMatchedCount
            filteredRemaining
        } else {
            orderedCurrentRows
        }

        if (useMaxVote && rowsToAppend.isNotEmpty()) {
            rowsToAppend = stripTailDuplicateLinesWithVote(
                candidateRows = rowsToAppend
            )
        }

        if (rowsToAppend.isNotEmpty()) {
            appendHistory.addLast(
                AppendHistoryEntry(
                    appendedCount = rowsToAppend.size,
                    previousFrameRowsBeforeAppend = previousFrameRows
                )
            )
            appendRowsWithVotes(rowsToAppend)
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

    private fun appendRowsWithVotes(rows: List<OcrRow>) {
        for (row in rows) {
            accumulatedRows.add(row)
            rowVotes.add(RowVoteBucket(row.originalText))
        }
    }

    private fun castVoteAt(index: Int, incomingRow: OcrRow) {
        if (index !in accumulatedRows.indices || index !in rowVotes.indices) return
        val winner = rowVotes[index].castVote(incomingRow.originalText)
        val existing = accumulatedRows[index]
        if (winner != existing.originalText) {
            accumulatedRows[index] = existing.copy(
                originalText = winner,
                normalizedText = normalizeLineText(winner)
            )
        }
    }

    private fun castVotesOnSuffixOverlap(
        overlapCount: Int,
        currentRows: List<OcrRow>,
        similarityThreshold: Double
    ) {
        val accStart = (accumulatedRows.size - overlapCount).coerceAtLeast(0)
        val count = minOf(overlapCount, accumulatedRows.size - accStart, currentRows.size)
        for (i in 0 until count) {
            val accIdx = accStart + i
            val sim = computeSimilarityWithThreshold(
                accumulatedRows[accIdx].normalizedText,
                currentRows[i].normalizedText,
                similarityThreshold
            )
            if (sim >= similarityThreshold) {
                castVoteAt(accIdx, currentRows[i])
            }
        }
    }

    private fun castVotesOnTailSlice(
        currentRows: List<OcrRow>,
        similarityThreshold: Double
    ) {
        val tailStart = (accumulatedRows.size - MAX_VOTE_WINDOW).coerceAtLeast(0)
        for (currRow in currentRows) {
            if (currRow.normalizedText.length < 4) continue
            for (idx in accumulatedRows.lastIndex downTo tailStart) {
                val sim = computeSimilarityWithThreshold(
                    accumulatedRows[idx].normalizedText,
                    currRow.normalizedText,
                    similarityThreshold
                )
                if (sim >= similarityThreshold) {
                    castVoteAt(idx, currRow)
                    break
                }
            }
        }
    }

    /**
     * Collapses consecutive identical or physically overlapping near-identical lines inside
     * a single frame (e.g. duplicate ML Kit bounding-box detections for the same visual row),
     * while preserving distinct sequentially ordered rows like "line 1" followed by "line 2".
     */
    private fun deduplicateConsecutiveInFrame(
        rows: List<OcrRow>,
        similarityThreshold: Double
    ): List<OcrRow> {
        if (rows.size <= 1) return rows
        val result = ArrayList<OcrRow>(rows.size)
        for (row in rows) {
            val prev = result.lastOrNull()
            val isDuplicate = if (prev != null && row.normalizedText.length >= 4) {
                if (prev.normalizedText == row.normalizedText) {
                    true
                } else {
                    verticalOverlapRatio(prev, row) >= 0.45 &&
                        !differsOnlyInDigits(prev.normalizedText, row.normalizedText) &&
                        computeSimilarityWithThreshold(
                            prev.normalizedText,
                            row.normalizedText,
                            similarityThreshold
                        ) >= similarityThreshold
                }
            } else {
                false
            }

            if (isDuplicate && prev != null) {
                if (row.originalText.length > prev.originalText.length) {
                    result[result.lastIndex] = row
                }
            } else {
                result.add(row)
            }
        }
        return result
    }

    private fun verticalOverlapRatio(a: OcrRow, b: OcrRow): Double {
        val heightA = (a.bottom - a.top)
        val heightB = (b.bottom - b.top)
        if (heightA <= 0 || heightB <= 0) return 0.0
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (overlap <= 0) return 0.0
        return overlap.toDouble() / minOf(heightA, heightB).toDouble()
    }

    private fun differsOnlyInDigits(a: String, b: String): Boolean {
        if (a.length != b.length || a == b) return false
        var sawDigitDiff = false
        for (i in a.indices) {
            if (a[i] != b[i]) {
                if (a[i].isDigit() && b[i].isDigit()) {
                    sawDigitDiff = true
                } else {
                    return false
                }
            }
        }
        return sawDigitDiff
    }

    /**
     * Max-Vote tail-window anchor & duplicate cleanup:
     * Searches the recent [MAX_VOTE_WINDOW] accumulated rows for a multi-row anchor (>= 2 rows)
     * or long unique row (>= 10 chars) matching inside [currentRows]. Matched rows cast votes
     * on their accumulated counterparts; any rows before/within the anchor are discarded as
     * duplicates, and if the anchor sits well above the bottom of the accumulated document
     * (upward camera jitter), trailing lines inside that already-scanned range are suppressed.
     */
    private fun cleanupDuplicatesWithMaxVote(
        currentRows: List<OcrRow>,
        similarityThreshold: Double
    ): Pair<Int, List<OcrRow>> {
        val tailStart = (accumulatedRows.size - MAX_VOTE_WINDOW).coerceAtLeast(0)
        val tailSize = accumulatedRows.size - tailStart
        if (tailSize <= 0 || currentRows.isEmpty()) return 0 to currentRows

        val rowMin = minOf(similarityThreshold, 0.78)

        // Find the strongest monotonic block match between tail and currentRows
        var bestAnchorEndAcc = -1
        var bestAnchorEndCurr = -1
        var bestMatchCount = 0
        var bestAvgSim = 0.0
        var bestMatchedPairs: List<Pair<Int, Int>> = emptyList()

        for (startCurr in currentRows.indices) {
            for (startTail in (accumulatedRows.lastIndex) downTo tailStart) {
                val firstSim = lineMatchSimilarity(
                    accumulatedRows[startTail].normalizedText,
                    currentRows[startCurr].normalizedText,
                    rowMin
                )
                if (firstSim < rowMin) continue

                val pairs = ArrayList<Pair<Int, Int>>()
                pairs.add(startTail to startCurr)
                var sumSim = firstSim
                var matchedChars = currentRows[startCurr].normalizedText.length

                var ti = startTail + 1
                var ci = startCurr + 1
                while (ti < accumulatedRows.size && ci < currentRows.size) {
                    val directSim = lineMatchSimilarity(
                        accumulatedRows[ti].normalizedText,
                        currentRows[ci].normalizedText,
                        rowMin
                    )
                    if (directSim >= rowMin) {
                        pairs.add(ti to ci)
                        sumSim += directSim
                        matchedChars += currentRows[ci].normalizedText.length
                        ti++
                        ci++
                    } else if (ti + 1 < accumulatedRows.size &&
                        lineMatchSimilarity(accumulatedRows[ti + 1].normalizedText, currentRows[ci].normalizedText, rowMin) >= rowMin
                    ) {
                        // Tolerate 1 dropped line in previous tail
                        ti++
                    } else if (ci + 1 < currentRows.size &&
                        lineMatchSimilarity(accumulatedRows[ti].normalizedText, currentRows[ci + 1].normalizedText, rowMin) >= rowMin
                    ) {
                        // Tolerate 1 noisy inserted line in current frame
                        ci++
                    } else {
                        break
                    }
                }

                val count = pairs.size
                val avgSim = sumSim / count
                if (avgSim < similarityThreshold) continue

                val valid = (count >= 2 && matchedChars >= 12) ||
                    (count >= 3 && matchedChars >= 6) ||
                    (count == 1 && matchedChars >= 10 && startTail >= accumulatedRows.size - 2)
                if (!valid) continue

                val isBetter = when {
                    count >= 2 && bestMatchCount < 2 -> true
                    count < 2 && bestMatchCount >= 2 -> false
                    count != bestMatchCount -> count > bestMatchCount
                    avgSim != bestAvgSim -> avgSim > bestAvgSim
                    else -> pairs.last().first > bestAnchorEndAcc
                }
                if (isBetter) {
                    bestMatchCount = count
                    bestAvgSim = avgSim
                    bestAnchorEndAcc = pairs.last().first
                    bestAnchorEndCurr = pairs.last().second
                    bestMatchedPairs = pairs
                }
            }
        }

        if (bestMatchedPairs.isEmpty()) {
            return 0 to currentRows
        }

        // Cast votes on every matched accumulated row so cleaner observations win
        for ((accIdx, currIdx) in bestMatchedPairs) {
            castVoteAt(accIdx, currentRows[currIdx])
        }

        val remainingTailRows = (accumulatedRows.size - 1) - bestAnchorEndAcc
        val trailingCurr = currentRows.subList(bestAnchorEndCurr + 1, currentRows.size)

        // Upward jitter guard: if anchor sits above the document bottom and trailing lines
        // fit within the already-captured tail below the anchor, append nothing.
        if (remainingTailRows >= 2 && trailingCurr.size <= remainingTailRows) {
            return bestMatchCount to emptyList()
        }

        // If the anchor ended above existing tail rows, strip any trailing lines that already
        // exist in the remaining accumulated tail below the anchor (and cast votes on them).
        if (remainingTailRows > 0 && trailingCurr.isNotEmpty()) {
            val filtered = ArrayList<OcrRow>(trailingCurr.size)
            val skippedTailStart = bestAnchorEndAcc + 1
            for (row in trailingCurr) {
                var matchedTailIdx = -1
                if (row.normalizedText.length >= 4) {
                    for (idx in skippedTailStart until accumulatedRows.size) {
                        val sim = lineMatchSimilarity(
                            accumulatedRows[idx].normalizedText,
                            row.normalizedText,
                            similarityThreshold
                        )
                        if (sim >= maxOf(similarityThreshold, 0.85)) {
                            matchedTailIdx = idx
                            break
                        }
                    }
                }
                if (matchedTailIdx != -1) {
                    castVoteAt(matchedTailIdx, row)
                } else {
                    filtered.add(row)
                }
            }
            return bestMatchCount to filtered
        }

        return bestMatchCount to trailingCurr
    }

    /**
     * Strips immediate boundary-adjacent exact duplicate lines (repeated last row)
     * while casting a vote on the existing row, without touching legitimate repeated
     * blocks later in a document.
     */
    private fun stripTailDuplicateLinesWithVote(
        candidateRows: List<OcrRow>
    ): List<OcrRow> {
        if (candidateRows.isEmpty() || accumulatedRows.isEmpty()) return candidateRows

        val lastAccIdx = accumulatedRows.lastIndex
        val lastNorm = accumulatedRows[lastAccIdx].normalizedText
        if (lastNorm.length < 4) return candidateRows

        var startIndex = 0
        while (startIndex < candidateRows.size && candidateRows[startIndex].normalizedText == lastNorm) {
            castVoteAt(lastAccIdx, candidateRows[startIndex])
            startIndex++
        }
        return if (startIndex == 0) candidateRows else candidateRows.subList(startIndex, candidateRows.size)
    }

    private fun lineMatchSimilarity(a: String, b: String, threshold: Double): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a.length <= 3 && b.length <= 3) return 0.0
        if (differsOnlyInDigits(a, b)) return 0.0
        return computeSimilarityWithThreshold(a, b, threshold)
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
        const val DEFAULT_SIMILARITY_THRESHOLD = 0.80
        private const val MAX_VOTE_WINDOW = 60

        private val REGEX_MULTI_WHITESPACE = Regex("""[ \t\u00A0\u2007\u202F]+""")
        private val REGEX_LINE_BREAKS = Regex("""\r\n|\r|\n""")

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

        fun enforceTopToBottomOrder(rows: List<OcrRow>): List<OcrRow> {
            if (rows.size <= 1) return rows
            return rows.sortedWith(
                compareBy<OcrRow> { it.top }
                    .thenBy { (it.top + it.bottom) / 2 }
                    .thenBy { it.left }
            )
        }

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

        fun normalizeLineText(rawText: String): String {
            if (rawText.isBlank()) return ""
            val singleLine = REGEX_LINE_BREAKS.replace(rawText, " ")
            return REGEX_MULTI_WHITESPACE.replace(singleLine, " ")
                .trim()
                .lowercase(Locale.ROOT)
        }

        fun normalizeFrameText(rows: List<OcrRow>): String {
            if (rows.isEmpty()) return ""
            return rows.asSequence()
                .map { it.normalizedText }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }

        fun buildOriginalOutput(rows: List<OcrRow>): String {
            if (rows.isEmpty()) return ""
            return rows.joinToString("\n") { it.originalText }
        }

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

                if (bestK == 0 || fullSimilarity > bestSimilarity + 0.02) {
                    bestK = k
                    bestSimilarity = fullSimilarity
                }
            }

            return bestK
        }

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

        private fun levenshteinDistanceBounded(s1: String, s2: String, maxDistance: Int): Int {
            val (short, long) = if (s1.length <= s2.length) s1 to s2 else s2 to s1
            var prev = IntArray(short.length + 1) { it }
            var curr = IntArray(short.length + 1)

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
                val tmp = prev
                prev = curr
                curr = tmp
            }
            return prev[short.length]
        }
    }
}
