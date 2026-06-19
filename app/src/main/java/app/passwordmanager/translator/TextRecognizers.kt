package app.passwordmanager.translator

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/**
 * Recognizes text from an image using the right on-device script recognizer.
 *
 * ML Kit ships a separate recognizer per script family — Latin, Devanagari (Hindi/Marathi…),
 * Chinese, Japanese and Korean — and each one can only decode its own script.
 *
 * - When the source language is known, only that script's recognizer runs (the fast path —
 *   one inference instead of five, and the other four native models never even load).
 * - When the source is auto/unknown, every recognizer runs concurrently and the result with
 *   the most text wins, so any of the five scripts is handled without the user choosing one.
 */
object TextRecognizers {
    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val devanagari by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }
    private val chinese by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val japanese by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val korean by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    /**
     * The single recognizer whose script matches [languageTag], or null when the language is
     * auto/unknown and every recognizer should be tried. Latin-script languages (English,
     * French, Vietnamese, …) all map to the Latin recognizer.
     */
    private fun recognizerFor(languageTag: String?): TextRecognizer? = when (languageTag) {
        null, TranslatorViewModel.AUTO_LANGUAGE -> null
        TranslateLanguage.CHINESE -> chinese
        TranslateLanguage.JAPANESE -> japanese
        TranslateLanguage.KOREAN -> korean
        TranslateLanguage.HINDI, TranslateLanguage.MARATHI -> devanagari
        else -> latin
    }

    /**
     * Source languages ML Kit can TRANSLATE but has NO on-device OCR recognizer for — ML Kit vision
     * only reads Latin, Devanagari, Chinese, Japanese and Korean scripts. Scanning an image/PDF in
     * one of these would silently fall through to the Latin recognizer and return garbage, so callers
     * warn the user instead. Best-effort list of the common non-supported scripts (Indic non-Devanagari,
     * Arabic, Cyrillic, Thai, Greek, Hebrew, Georgian).
     */
    private val ocrUnsupported = setOf(
        TranslateLanguage.BENGALI, TranslateLanguage.GUJARATI, TranslateLanguage.KANNADA,
        TranslateLanguage.TAMIL, TranslateLanguage.TELUGU,
        TranslateLanguage.THAI, TranslateLanguage.ARABIC, TranslateLanguage.PERSIAN,
        TranslateLanguage.URDU, TranslateLanguage.HEBREW, TranslateLanguage.GREEK,
        TranslateLanguage.GEORGIAN, TranslateLanguage.RUSSIAN, TranslateLanguage.UKRAINIAN,
        TranslateLanguage.BELARUSIAN, TranslateLanguage.BULGARIAN, TranslateLanguage.MACEDONIAN
    )

    /** False only when [languageTag] is an explicitly-chosen source we cannot OCR (auto is always ok). */
    fun isOcrSupported(languageTag: String?): Boolean =
        languageTag == null || languageTag == TranslatorViewModel.AUTO_LANGUAGE || languageTag !in ocrUnsupported

    suspend fun recognize(image: InputImage, sourceLanguage: String? = null): String =
        bestResult(image, sourceLanguage)?.let { orderedText(it) }.orEmpty()

    /**
     * OCRs the image and returns each text block with its position + estimated font size, so the PDF
     * rebuilder can redraw translated text in place (preserving headings, columns, tables) instead of
     * reflowing everything into one column.
     */
    suspend fun recognizeBlocks(image: InputImage, sourceLanguage: String? = null): List<LayoutBlock> {
        val result = bestResult(image, sourceLanguage) ?: return emptyList()
        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val text = block.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            LayoutBlock(
                text = text,
                left = box.left.toFloat(),
                top = box.top.toFloat(),
                right = box.right.toFloat(),
                bottom = box.bottom.toFloat(),
                fontSizePx = estimateFontSize(block)
            )
        }.sortedWith(compareBy({ it.top }, { it.left }))
    }

    /** The winning OCR result: the matching recognizer when the source is known, else the richest of all five. */
    private suspend fun bestResult(image: InputImage, sourceLanguage: String?): Text? {
        val recognizer = recognizerFor(sourceLanguage)
        if (recognizer != null) {
            return runCatching { recognizer.process(image).await() }.getOrNull()
        }
        return coroutineScope {
            listOf(latin, devanagari, chinese, japanese, korean)
                .map { r -> async { runCatching { r.process(image).await() }.getOrNull() } }
                .awaitAll()
                .filterNotNull()
                .maxByOrNull { it.text.length }
        }
    }

    /**
     * Estimates a block's original glyph height in pixels from the median height of its lines (falling
     * back to the block box height). Drives proportional font sizing in the rebuilt PDF so headings
     * render bigger than body text instead of everything collapsing to one size.
     */
    private fun estimateFontSize(block: Text.TextBlock): Float {
        val lineHeights = block.lines.mapNotNull { it.boundingBox?.height()?.toFloat() }.filter { it > 0f }
        if (lineHeights.isNotEmpty()) {
            val sorted = lineHeights.sorted()
            return sorted[sorted.size / 2]
        }
        return block.boundingBox?.height()?.toFloat() ?: 0f
    }

    /**
     * Builds the page text in natural reading order. ML Kit returns recognized blocks in an arbitrary
     * order for multi-column / table-like layouts, which produced scrambled output. Sorting blocks
     * top-to-bottom (then left-to-right) by their bounding box and separating them with blank lines
     * restores a clean, paragraph-structured page that survives translation and lays out neatly in the PDF.
     */
    private fun orderedText(visionText: Text): String {
        val blocks = visionText.textBlocks
        if (blocks.isEmpty()) return visionText.text
        return blocks
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .joinToString("\n\n") { it.text }
    }
}
