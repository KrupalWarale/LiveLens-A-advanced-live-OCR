package com.example.ui.camera

import com.google.mlkit.vision.text.Text
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * High-performance code extractor for ML Kit Latin Text Recognition.
 * Uses TextBlock -> Line -> Element hierarchy with minimal per-frame allocations
 * and precompiled regex patterns for symbol/operator normalization.
 */
object CodeTextExtractor {

    private val REGEX_DOT_BOTH_SPACES = Regex("""([a-zA-Z0-9_])\s+\.\s+([a-zA-Z0-9_])""")
    private val REGEX_DOT_LEFT_SPACE = Regex("""([a-zA-Z0-9_])\s+\.([a-zA-Z0-9_])""")
    private val REGEX_DOT_RIGHT_SPACE = Regex("""([a-zA-Z0-9_])\.\s+([a-zA-Z0-9_])""")
    private val REGEX_SAFE_CALL_BOTH_SPACES = Regex("""([a-zA-Z0-9_])\s+\?\.\s+([a-zA-Z0-9_])""")
    private val REGEX_SAFE_CALL_LEFT_SPACE = Regex("""([a-zA-Z0-9_])\s+\?\.([a-zA-Z0-9_])""")
    private val REGEX_SPACE_BEFORE_COMMA = Regex("""\s+,""")
    private val REGEX_SPACE_BEFORE_SEMICOLON = Regex("""\s+;""")
    private val REGEX_COLON_TYPE_ANNOTATION = Regex("""([a-zA-Z0-9_])\s+:\s+([a-zA-Z0-9_])""")

    private class VisualLineGroup(firstLine: Text.Line) {
        val lines = ArrayList<Text.Line>(2).apply { add(firstLine) }
        private var centerYSum: Int = firstLine.boundingBox!!.centerY()
        var minTop: Int = firstLine.boundingBox!!.top
            private set

        val avgCenterY: Float
            get() = centerYSum.toFloat() / lines.size

        fun add(line: Text.Line) {
            lines.add(line)
            val box = line.boundingBox!!
            centerYSum += box.centerY()
            if (box.top < minTop) minTop = box.top
        }
    }

    fun extractCode(visionText: Text): String {
        val blocks = visionText.textBlocks
        if (blocks.isEmpty()) return ""

        val validLines = ArrayList<Text.Line>()
        var totalWidth = 0
        var totalChars = 0
        var totalHeight = 0
        var globalMinLeft = Int.MAX_VALUE

        for (block in blocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                validLines.add(line)
                totalWidth += box.width()
                totalChars += line.text.length
                totalHeight += box.height()
                if (box.left < globalMinLeft) globalMinLeft = box.left
            }
        }

        if (validLines.isEmpty()) {
            return normalizeCodeSymbols(visionText.text)
        }

        val avgCharWidth = (totalWidth.toFloat() / totalChars.coerceAtLeast(1)).coerceIn(6f, 60f)
        val avgLineHeight = (totalHeight.toFloat() / validLines.size).coerceIn(12f, 120f)
        val verticalTolerance = avgLineHeight * 0.45f

        // Sort top-to-bottom by vertical center
        validLines.sortBy { it.boundingBox!!.centerY() }

        // Cluster vertically aligned segments into visual lines
        val visualLines = ArrayList<VisualLineGroup>(validLines.size)
        for (line in validLines) {
            val centerY = line.boundingBox!!.centerY()
            var matchedGroup: VisualLineGroup? = null
            for (group in visualLines) {
                if (abs(centerY - group.avgCenterY) < verticalTolerance) {
                    matchedGroup = group
                    break
                }
            }
            if (matchedGroup != null) {
                matchedGroup.add(line)
            } else {
                visualLines.add(VisualLineGroup(line))
            }
        }

        visualLines.sortBy { it.minTop }

        val outputBuilder = StringBuilder(totalChars + visualLines.size * 8)
        val lineBuilder = StringBuilder(128)
        val minElemGap = avgCharWidth * 0.45f
        val minSegGap = avgCharWidth * 0.4f

        for (groupIndex in visualLines.indices) {
            val group = visualLines[groupIndex].lines
            if (group.size > 1) {
                group.sortBy { it.boundingBox!!.left }
            }

            lineBuilder.setLength(0)

            val firstBox = group[0].boundingBox!!
            val indentOffset = (firstBox.left - globalMinLeft).coerceAtLeast(0)
            val indentSpaces = (indentOffset / avgCharWidth).roundToInt().coerceIn(0, 36)
            repeat(indentSpaces) { lineBuilder.append(' ') }

            for (segIdx in group.indices) {
                val segment = group[segIdx]
                if (segIdx > 0) {
                    val prevRight = group[segIdx - 1].boundingBox!!.right
                    val gap = segment.boundingBox!!.left - prevRight
                    val spaces = if (gap > minSegGap) {
                        (gap / avgCharWidth).roundToInt().coerceIn(1, 8)
                    } else {
                        1
                    }
                    repeat(spaces) { lineBuilder.append(' ') }
                }

                val elements = segment.elements
                if (elements.isEmpty()) {
                    lineBuilder.append(segment.text)
                } else {
                    for (i in elements.indices) {
                        val elem = elements[i]
                        lineBuilder.append(elem.text)

                        if (i < elements.size - 1) {
                            val elemBox = elem.boundingBox
                            val nextBox = elements[i + 1].boundingBox
                            val elemGap = if (elemBox != null && nextBox != null) {
                                nextBox.left - elemBox.right
                            } else {
                                0
                            }
                            val spaces = if (elemGap > minElemGap) {
                                (elemGap / avgCharWidth).roundToInt().coerceIn(1, 6)
                            } else {
                                1
                            }
                            repeat(spaces) { lineBuilder.append(' ') }
                        }
                    }
                }
            }

            val formattedLine = normalizeCodeSymbols(lineBuilder.toString())
            if (formattedLine.isNotBlank()) {
                if (outputBuilder.isNotEmpty()) outputBuilder.append('\n')
                outputBuilder.append(formattedLine)
            }
        }

        return outputBuilder.toString()
    }

    /**
     * Normalizes programming symbols, operators, and special characters commonly misidentified
     * by Latin OCR.
     */
    fun normalizeCodeSymbols(rawText: String): String {
        var text = rawText
            .replace('\u00A0', ' ')
            .replace('\u2007', ' ')
            .replace('\u202F', ' ')
            .replace('“', '"')
            .replace('”', '"')
            .replace('„', '"')
            .replace('‘', '\'')
            .replace('’', '\'')
            .replace('´', '`')
            .replace('—', '-')
            .replace('–', '-')
            .replace('−', '-')
            .replace('―', '-')
            .replace("- >", "->")
            .replace("~>", "->")
            .replace("= >", "=>")
            .replace("==>", "=>")
            .replace("! =", "!=")
            .replace("≠", "!=")
            .replace("= = =", "===")
            .replace("= =", "==")
            .replace("+ =", "+=")
            .replace("- =", "-=")
            .replace("* =", "*=")
            .replace("/ =", "/=")
            .replace("< =", "<=")
            .replace("≤", "<=")
            .replace("> =", ">=")
            .replace("≥", ">=")
            .replace("& &", "&&")
            .replace("| |", "||")
            .replace("+ +", "++")
            .replace("- -", "--")
            .replace(": :", "::")
            .replace("? .", "?.")
            .replace("? :", "?:")
            .replace("! !", "!!")
            .replace("…", "...")
            .replace(". . .", "...")
            .replace(". .", "..")
            .replace("«", "<<")
            .replace("»", ">>")

        text = REGEX_DOT_BOTH_SPACES.replace(text, "$1.$2")
        text = REGEX_DOT_LEFT_SPACE.replace(text, "$1.$2")
        text = REGEX_DOT_RIGHT_SPACE.replace(text, "$1.$2")
        text = REGEX_SAFE_CALL_BOTH_SPACES.replace(text, "$1?.$2")
        text = REGEX_SAFE_CALL_LEFT_SPACE.replace(text, "$1?.$2")
        text = REGEX_SPACE_BEFORE_COMMA.replace(text, ",")
        text = REGEX_SPACE_BEFORE_SEMICOLON.replace(text, ";")
        text = REGEX_COLON_TYPE_ANNOTATION.replace(text, "$1: $2")

        return text
    }
}
