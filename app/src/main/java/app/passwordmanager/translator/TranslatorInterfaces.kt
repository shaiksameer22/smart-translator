package app.passwordmanager.translator

import android.net.Uri

interface IOcrEngine {
    suspend fun extractTextFromImage(uri: Uri, sourceLanguage: String? = null): String
}

/**
 * One OCR'd text block with its position in the rendered page bitmap (pixels) and the estimated
 * original glyph height. Keeping the geometry lets the rebuilt PDF redraw each translated block at
 * its own place and size — so headings stay big, columns/tables keep their layout, and the reading
 * order matches the original (Google-Translate-style overlay) instead of one reflowed wall of text.
 */
data class LayoutBlock(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val fontSizePx: Float
)

/** A page's blocks plus the pixel size of the bitmap they were measured in (for scaling to A4). */
data class PageLayout(
    val widthPx: Int,
    val heightPx: Int,
    val blocks: List<LayoutBlock>
) {
    /** Flattened page text in reading order, for previews / history / language detection. */
    val text: String get() = blocks.joinToString("\n\n") { it.text }
}

interface ITranslationEngine {
    suspend fun translateText(text: String, sourceLang: String, targetLang: String): String

    /**
     * Translates many texts (e.g. PDF blocks) reusing a SINGLE translator and downloading the
     * language model only once — far faster than calling [translateText] per item. Returns one
     * result per input, aligned by index (blank inputs map to blank outputs).
     *
     * @param onModelDownloadStart invoked right before the (one-time) model download, so callers can
     *        surface a "downloading language pack" status instead of an unexplained freeze.
     * @param onPageTranslated invoked with each completed index, for progress reporting.
     */
    suspend fun translatePages(
        pages: List<String>,
        sourceLang: String,
        targetLang: String,
        onModelDownloadStart: () -> Unit = {},
        onPageTranslated: (Int) -> Unit = {}
    ): List<String>

    suspend fun detectLanguage(text: String): String?
}

interface IPdfProcessor {
    /** OCRs the PDF and returns one [PageLayout] per page, preserving block geometry for re-export. */
    suspend fun extractPages(uri: Uri, sourceLanguage: String? = null, onProgress: (Float) -> Unit = {}): List<PageLayout>

    /** Redraws the (already translated) page layouts into a PDF saved to Downloads; returns its URI. */
    suspend fun createTranslatedPdf(pages: List<PageLayout>): Uri
}
