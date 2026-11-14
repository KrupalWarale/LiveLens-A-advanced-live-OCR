package com.example.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousOcrEngineTest {

    private fun makeRows(vararg lines: String, startTop: Int = 100, step: Int = 40): List<OcrRow> {
        return lines.mapIndexed { index, text ->
            val top = startTop + index * step
            OcrRow(
                originalText = text,
                normalizedText = ContinuousOcrEngine.normalizeLineText(text),
                top = top,
                bottom = top + 30,
                left = 20,
                right = 500
            )
        }
    }

    @Test
    fun `acceptance 1 - stationary screen does not duplicate text`() {
        val engine = ContinuousOcrEngine()
        val frame = makeRows("Line One", "Line Two", "Line Three")

        val first = engine.processRows(frame)
        val second = engine.processRows(frame)
        val third = engine.processRows(frame)

        assertEquals(3, first.appendedRowCount)
        assertEquals(0, second.appendedRowCount)
        assertEquals(0, third.appendedRowCount)
        assertEquals("Line One\nLine Two\nLine Three", third.accumulatedText)
    }

    @Test
    fun `acceptance 2 and 3 - downward scroll skips overlapping rows and appends only new original rows`() {
        val engine = ContinuousOcrEngine()
        val frame1 = makeRows("A_line", "B_line", "C_line", "D_line", "E_line")
        val frame2 = makeRows("D_line", "E_line", "F_line", "G_line", "H_line")

        engine.processRows(frame1)
        val result = engine.processRows(frame2)

        assertEquals(2, result.overlapRowCount)
        assertEquals(3, result.appendedRowCount)
        assertEquals(
            "A_line\nB_line\nC_line\nD_line\nE_line\nF_line\nG_line\nH_line",
            result.accumulatedText
        )
    }

    @Test
    fun `acceptance 4 - fuzzy matching at 80 percent tolerates minor OCR variation`() {
        val engine = ContinuousOcrEngine()
        val frame1 = makeRows(
            "fun calculateTotal(items: List<Int>): Int {",
            "    val subtotal = items.sum()",
            "    return subtotal + 10"
        )
        // Minor OCR variation on overlapping lines (still >= 80% similar)
        val frame2 = makeRows(
            "    val subtotal = items.sum()",
            "    return subtota1 + 10",
            "}"
        )

        engine.processRows(frame1)
        val result = engine.processRows(frame2)

        assertEquals(2, result.overlapRowCount)
        assertEquals(1, result.appendedRowCount)
        assertEquals(
            "fun calculateTotal(items: List<Int>): Int {\n    val subtotal = items.sum()\n    return subtotal + 10\n}",
            result.accumulatedText
        )
    }

    @Test
    fun `acceptance 5 - repeated legitimate source code lines are preserved and not globally deleted`() {
        val engine = ContinuousOcrEngine()
        val frame1 = makeRows(
            "fun first() {",
            "    val count = 0",
            "}",
            "fun second() {"
        )
        val frame2 = makeRows(
            "}",
            "fun second() {",
            "    val count = 0",
            "}"
        )

        engine.processRows(frame1)
        val result = engine.processRows(frame2)

        assertEquals(2, result.overlapRowCount)
        assertEquals(2, result.appendedRowCount)
        assertEquals(
            "fun first() {\n    val count = 0\n}\nfun second() {\n    val count = 0\n}",
            result.accumulatedText
        )
    }

    @Test
    fun `acceptance 6 - original indentation capitalization punctuation and line breaks remain untouched`() {
        val engine = ContinuousOcrEngine()
        val frame1 = makeRows(
            "class   MyService {",
            "    if (a + b == c && d != 0) {",
            "        println(\"Hello, World!\");"
        )
        val frame2 = makeRows(
            "    if (a + b == c && d != 0) {",
            "        println(\"Hello, World!\");",
            "    }"
        )

        // Normalized text must lowercase & collapse spaces while preserving code operators
        assertEquals("if (a + b == c && d != 0) {", frame1[1].normalizedText)

        engine.processRows(frame1)
        val result = engine.processRows(frame2)

        assertEquals(
            "class   MyService {\n    if (a + b == c && d != 0) {\n        println(\"Hello, World!\");\n    }",
            result.accumulatedText
        )
    }

    @Test
    fun `acceptance 7 and 8 - slow mode activates after 10 identical observations and resumes normal mode immediately on change`() {
        val engine = ContinuousOcrEngine()
        val frameA = makeRows("const alpha = 1;", "const beta = 2;")
        val frameB = makeRows("const beta = 2;", "const gamma = 3;")

        // Initial observation establishes baseline (counter = 0, mode = NORMAL)
        val initial = engine.processRows(frameA)
        assertEquals(OcrSpeedMode.NORMAL, initial.speedMode)
        assertEquals(0, initial.stabilityCounter)
        assertEquals(100L, engine.requiredIntervalMs())

        // 9 consecutive identical observations stay in NORMAL mode
        repeat(9) { index ->
            val r = engine.processRows(frameA)
            assertEquals(index + 1, r.stabilityCounter)
            assertEquals(OcrSpeedMode.NORMAL, r.speedMode)
            assertEquals(100L, engine.requiredIntervalMs())
        }

        // 10th consecutive identical observation activates SLOW mode (1000 ms)
        val slowResult = engine.processRows(frameA)
        assertEquals(10, slowResult.stabilityCounter)
        assertEquals(OcrSpeedMode.SLOW, slowResult.speedMode)
        assertEquals(1000L, engine.requiredIntervalMs())

        // Normalized text changes -> immediately resume NORMAL mode (100 ms) and reset counter to 0
        val resumed = engine.processRows(frameB)
        assertEquals(OcrSpeedMode.NORMAL, resumed.speedMode)
        assertEquals(0, resumed.stabilityCounter)
        assertEquals(100L, engine.requiredIntervalMs())
        assertEquals(1, resumed.appendedRowCount)
    }

    @Test
    fun `short single-character brace does not falsely match unrelated frame`() {
        val frame1 = makeRows("fun foo() {", "    return 1", "}")
        val frame2 = makeRows("}", "fun bar() {", "    return 2")

        // Only 1-row overlap of "}" (length 1) should be rejected as accidental short match
        val overlap = ContinuousOcrEngine.detectSuffixPrefixOverlap(frame1, frame2)
        assertEquals(0, overlap)
    }

    @Test
    fun `row extraction enforces top to bottom order using bounding boxes`() {
        val outOfOrder = listOf(
            OcrRow("bottom line", "bottom line", top = 300, bottom = 330, left = 10, right = 200),
            OcrRow("top line", "top line", top = 100, bottom = 130, left = 10, right = 200),
            OcrRow("middle line", "middle line", top = 200, bottom = 230, left = 10, right = 200)
        )

        val ordered = ContinuousOcrEngine.enforceTopToBottomOrder(outOfOrder)
        assertTrue(ContinuousOcrEngine.validateRowOrdering(ordered))
        assertEquals(listOf("top line", "middle line", "bottom line"), ordered.map { it.originalText })
    }

    @Test
    fun `shifted lines with shared prefix template select exact overlap instead of longer misaligned match`() {
        val engine = ContinuousOcrEngine()
        val frame1 = makeRows(
            "val item1 = list[0]",
            "val item2 = list[1]",
            "val item3 = list[2]",
            "val item4 = list[3]",
            "val item5 = list[4]"
        )
        val frame2 = makeRows(
            "val item4 = list[3]",
            "val item5 = list[4]",
            "val item6 = list[5]",
            "val item7 = list[6]",
            "val item8 = list[7]"
        )

        engine.processRows(frame1)
        val result = engine.processRows(frame2)

        assertEquals(2, result.overlapRowCount)
        assertEquals(3, result.appendedRowCount)
        assertEquals(
            "val item1 = list[0]\nval item2 = list[1]\nval item3 = list[2]\nval item4 = list[3]\nval item5 = list[4]\nval item6 = list[5]\nval item7 = list[6]\nval item8 = list[7]",
            result.accumulatedText
        )
    }

    @Test
    fun `custom OCR settings update fast slow FPS intervals fuzzy percentage and stationary threshold`() {
        val customSettings = OcrSettings(
            batchSize = 3,
            fastModeFps = 20,
            slowModeFps = 2.0f,
            fuzzyMatchPercent = 90,
            stationaryThreshold = 4
        )
        assertEquals(50L, customSettings.normalIntervalMs)
        assertEquals(500L, customSettings.slowIntervalMs)
        assertEquals(0.90, customSettings.similarityThreshold, 0.0001)

        val engine = ContinuousOcrEngine(initialSettings = customSettings)
        val frame = makeRows("fun ping() = true", "fun pong() = false")

        assertEquals(50L, engine.requiredIntervalMs())
        engine.processRows(frame)
        repeat(3) { engine.processRows(frame) }
        assertEquals(OcrSpeedMode.NORMAL, engine.speedMode)

        // 4th repeat hits custom stationaryThreshold = 4
        val slow = engine.processRows(frame)
        assertEquals(OcrSpeedMode.SLOW, slow.speedMode)
        assertEquals(500L, engine.requiredIntervalMs())

        // Batch processing merges ordered frames cleanly
        val batchResult = engine.processBatch(
            listOf(
                makeRows("fun pong() = false", "fun end() = Unit"),
                makeRows("fun end() = Unit", "val done = 1")
            )
        )
        assertEquals(2, batchResult.appendedRowCount)
        assertEquals(
            "fun ping() = true\nfun pong() = false\nfun end() = Unit\nval done = 1",
            batchResult.accumulatedText
        )
    }

    @Test
    fun `fuzzy matching against previous normalized text activates slow mode with minor OCR flicker`() {
        val engine = ContinuousOcrEngine(
            initialSettings = OcrSettings(
                fuzzyMatchPercent = 80,
                stationaryThreshold = 5
            )
        )
        val baseline = makeRows("val subtotal = items.sum()", "return subtotal + 10")
        val flickered = makeRows("val subtota1 = items.sum()", "return subtota1 + 10")

        engine.processRows(baseline)
        repeat(4) {
            val r = engine.processRows(flickered)
            assertEquals(OcrSpeedMode.NORMAL, r.speedMode)
            assertEquals(0, r.appendedRowCount)
        }

        // 5th fuzzy-matching stationary frame (>= 80% similarity) activates SLOW mode
        val slow = engine.processRows(flickered)
        assertEquals(OcrSpeedMode.SLOW, slow.speedMode)
        assertEquals(5, slow.stabilityCounter)

        // Changing settings to 100% strict rejects the flickered frame and resumes NORMAL mode
        engine.updateSettings(engine.settings.copy(fuzzyMatchPercent = 100))
        val resumed = engine.processRows(flickered)
        assertEquals(OcrSpeedMode.NORMAL, resumed.speedMode)
        assertEquals(0, resumed.stabilityCounter)
    }

    @Test
    fun `multi-step undo removes each appended batch in reverse order`() {
        val engine = ContinuousOcrEngine()

        // Append #1
        val r1 = engine.processRows(makeRows("line 1", "line 2"))
        assertTrue(r1.canUndo)
        assertEquals("line 1\nline 2", r1.accumulatedText)

        // Append #2
        val r2 = engine.processRows(makeRows("line 2", "line 3", "line 4"))
        assertTrue(r2.canUndo)
        assertEquals("line 1\nline 2\nline 3\nline 4", r2.accumulatedText)

        // Append #3
        val r3 = engine.processRows(makeRows("line 4", "line 5"))
        assertTrue(r3.canUndo)
        assertEquals("line 1\nline 2\nline 3\nline 4\nline 5", r3.accumulatedText)

        // Undo #1 removes "line 5"
        val u1 = engine.undoLastAppend()
        assertTrue(u1.canUndo)
        assertEquals("line 1\nline 2\nline 3\nline 4", u1.accumulatedText)

        // Undo #2 removes "line 3\nline 4"
        val u2 = engine.undoLastAppend()
        assertTrue(u2.canUndo)
        assertEquals("line 1\nline 2", u2.accumulatedText)

        // Undo #3 removes "line 1\nline 2" -> empty
        val u3 = engine.undoLastAppend()
        assertEquals(false, u3.canUndo)
        assertEquals("", u3.accumulatedText)
    }
}
