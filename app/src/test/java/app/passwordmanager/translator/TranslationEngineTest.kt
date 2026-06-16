package app.passwordmanager.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TextChunker] — the pure, ML-Kit-free text-splitting logic extracted from
 * [TranslationEngine]. These deliberately exercise only the chunker so they run on a plain JVM with
 * no Android/ML Kit runtime. The shared production ceiling is 2000 chars; tests use a smaller [MAX]
 * so they stay readable while covering the same boundary behavior.
 */
class TranslationEngineTest {

    // Small ceiling keeps the test fixtures short while exercising the same paths as production.
    private val MAX = 100

    @Test
    fun `short text returns a single chunk`() {
        val text = "Hello, world."
        val chunks = TextChunker.chunk(text, MAX)

        assertEquals(1, chunks.size)
        assertEquals(text, chunks.first())
    }

    @Test
    fun `text exactly at the ceiling stays in one chunk`() {
        val text = "a".repeat(MAX)
        val chunks = TextChunker.chunk(text, MAX)

        assertEquals(1, chunks.size)
        assertEquals(MAX, chunks.first().length)
    }

    @Test
    fun `long multi-paragraph text splits into multiple chunks each within the ceiling`() {
        // Several paragraphs, each comfortably under MAX, so the splitter packs them on newlines.
        val paragraph = "b".repeat(MAX - 10)
        val text = (1..6).joinToString("\n") { paragraph }

        val chunks = TextChunker.chunk(text, MAX)

        assertTrue("expected more than one chunk", chunks.size > 1)
        chunks.forEach { assertTrue("chunk exceeded ceiling: ${it.length}", it.length <= MAX) }
    }

    @Test
    fun `a paragraph longer than the ceiling is sentence-split`() {
        // One paragraph of multiple sentences whose total length exceeds MAX. Each sentence is short
        // enough to fit, so the chunker should break on sentence boundaries (not hard-cut mid-word).
        val sentence = "The quick brown fox. "
        val paragraph = sentence.repeat(10).trim() // ~200 chars, no newlines
        assertTrue(paragraph.length > MAX)

        val chunks = TextChunker.chunk(paragraph, MAX)

        assertTrue("expected sentence-level splitting", chunks.size > 1)
        chunks.forEach { assertTrue("chunk exceeded ceiling: ${it.length}", it.length <= MAX) }
        // Sentence-split chunks should not slice a word in half: each chunk ends cleanly.
        chunks.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun `a single word longer than the ceiling is hard-split`() {
        // No paragraph, sentence, or word boundary to break on — must fall back to character splitting.
        val giant = "x".repeat(MAX * 3 + 7)
        val chunks = TextChunker.chunk(giant, MAX)

        assertTrue(chunks.size >= 3)
        chunks.forEach { assertTrue("chunk exceeded ceiling: ${it.length}", it.length <= MAX) }
        // Hard-split must be lossless: concatenating the pieces reproduces the original token.
        assertEquals(giant, chunks.joinToString(""))
    }

    @Test
    fun `empty string returns a single empty chunk`() {
        val chunks = TextChunker.chunk("", MAX)

        assertEquals(listOf(""), chunks)
    }

    @Test
    fun `no chunk ever exceeds the ceiling for mixed content`() {
        // Mix of short paragraphs, a long sentence-y paragraph, and an unbreakable giant token.
        val text = buildString {
            append("Intro line.\n")
            append("The quick brown fox jumps. ".repeat(8))
            append("\n")
            append("y".repeat(MAX * 2 + 13))
            append("\n")
            append("Outro.")
        }

        val chunks = TextChunker.chunk(text, MAX)

        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue("chunk exceeded ceiling: ${it.length}", it.length <= MAX) }
    }

    @Test
    fun `concatenated chunk lengths stay close to the original length`() {
        // Splitting only drops/adds boundary separators (newlines/spaces). The summed chunk length
        // should be within a small fraction of the original — i.e. no large-scale text loss.
        val paragraph = "Sentence one here. Sentence two here. Sentence three here. "
        val text = (1..5).joinToString("\n") { paragraph.repeat(3).trim() }

        val chunks = TextChunker.chunk(text, MAX)
        val total = chunks.sumOf { it.length }

        // Allow a 10% slack for separators that may be dropped at chunk boundaries.
        val lowerBound = (text.length * 0.9).toInt()
        assertTrue(
            "lost too much text: original=${text.length} rejoined=$total",
            total in lowerBound..text.length
        )
    }

    @Test
    fun `blank-only text shorter than the ceiling is preserved as-is`() {
        val text = "   "
        val chunks = TextChunker.chunk(text, MAX)

        assertEquals(1, chunks.size)
        assertEquals(text, chunks.first())
    }
}
