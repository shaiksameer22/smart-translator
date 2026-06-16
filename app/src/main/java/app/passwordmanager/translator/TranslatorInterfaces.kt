package app.passwordmanager.translator

import android.net.Uri

interface IOcrEngine {
    suspend fun extractTextFromImage(uri: Uri, sourceLanguage: String? = null): String
}

interface ITranslationEngine {
    suspend fun translateText(text: String, sourceLang: String, targetLang: String): String

    /**
     * Translates many texts (e.g. PDF pages) reusing a SINGLE translator and downloading the
     * language model only once — far faster than calling [translateText] per page. Returns one
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
    /** OCRs the PDF and returns one entry per page, preserving page structure for re-export. */
    suspend fun extractPages(uri: Uri, sourceLanguage: String? = null, onProgress: (Float) -> Unit = {}): List<String>

    /** Renders the (already translated) per-page text into a PDF saved to Downloads; returns its URI. */
    suspend fun createTranslatedPdf(pages: List<String>): Uri
}
