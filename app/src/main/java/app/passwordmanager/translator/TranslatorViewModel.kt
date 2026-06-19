package app.passwordmanager.translator

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class TranslatorViewModel @JvmOverloads constructor(
    application: Application,
    private val ocrEngine: IOcrEngine = OcrEngine(application),
    private val translationEngine: ITranslationEngine = TranslationEngine(),
    private val pdfProcessor: IPdfProcessor = PdfProcessor(application),
    private val historyRepository: TranslationHistoryRepository = TranslationHistoryRepository(application)
) : AndroidViewModel(application) {

    companion object {
        const val AUTO_LANGUAGE = "auto"
        // Cap text stored per history entry — a whole-PDF translation must not bloat DataStore.
        private const val HISTORY_TEXT_CAP = 5000
    }

    var originalText by mutableStateOf("")
        private set

    var translatedText by mutableStateOf("")
        private set

    var isProcessing by mutableStateOf(false)
        private set

    var progress by mutableStateOf(0f)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** Set once a translated PDF has been written to Downloads; drives the "Saved" card. */
    var translatedPdfUri by mutableStateOf<Uri?>(null)
        private set

    /** The current phase shown in the processing popup ("Reading PDF" / "Translating PDF" / …). */
    var statusMessage by mutableStateOf<String?>(null)
        private set

    /** Secondary line under the phase title (e.g. "Page 3 of 12" or the one-time download note). */
    var statusDetail by mutableStateOf<String?>(null)
        private set

    /** The currently running processing coroutine, so it can be cancelled by [cancelProcessing]. */
    private var currentJob: Job? = null

    /** Re-invokable last process* call, used by [retryLastAction] after an error. */
    private var lastAction: (() -> Unit)? = null

    /**
     * The OCR'd page layouts from the most recent PDF, cached so a language change can re-translate and
     * rebuild the PDF without re-scanning hundreds of pages. Null when the last source was an
     * image or typed text.
     */
    private var pdfPages: List<PageLayout>? = null

    var sourceLanguage by mutableStateOf(AUTO_LANGUAGE)
    var targetLanguage by mutableStateOf(TranslateLanguage.ENGLISH)

    val history: StateFlow<List<HistoryItem>> = historyRepository.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun processImage(uri: Uri) {
        lastAction = { processImage(uri) } // set before the guard so Retry can re-run after fixing the language
        if (!ensureOcrSupported()) return
        pdfPages = null // source is an image, not a PDF
        val previous = currentJob
        currentJob = viewModelScope.launch {
            previous?.cancelAndJoin() // fully stop any prior job before touching shared state
            startProcessing()
            try {
                originalText = ocrEngine.extractTextFromImage(uri, sourceLanguage)
                handleExtractedText()
            } catch (e: CancellationException) {
                throw e // Let cancellation propagate; never surface it as an error.
            } catch (e: Exception) {
                errorMessage = e.message ?: "Could not read text from the image."
            } finally {
                isProcessing = false
            }
        }
    }

    fun processPdf(uri: Uri) {
        lastAction = { processPdf(uri) } // set before the guard so Retry can re-run after fixing the language
        if (!ensureOcrSupported()) return
        pdfPages = null // drop any previous PDF's cached pages until this one is OCR'd
        val previous = currentJob
        currentJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            startProcessing()
            try {
                // Phase 1 (0–50%): OCR every page, keeping page structure intact.
                val ocrSource = sourceLanguage.takeIf { it != AUTO_LANGUAGE }
                // The extractPages progress callback only reports an overall 0..1 fraction (no page
                // count), so phase-1 detail is expressed as a percentage rather than "page X of N".
                statusMessage = "Reading PDF"
                statusDetail = null
                val layouts = pdfProcessor.extractPages(uri, ocrSource) { p ->
                    progress = p * 0.5f
                    statusDetail = "${(p * 100).toInt()}%"
                }
                // Cache the OCR'd layouts so a later language change rebuilds the PDF without re-scanning.
                pdfPages = layouts

                // OCR consumed the first half of the bar; translation + build fill the second half.
                translateAndBuildPdf(layouts, progressBase = 0.5f)
            } catch (e: CancellationException) {
                throw e // Let cancellation propagate; never surface it as an error.
            } catch (e: Exception) {
                errorMessage = e.message ?: "Could not process the PDF."
            } finally {
                statusMessage = null
                statusDetail = null
                isProcessing = false
            }
        }
    }

    /**
     * Re-translates and rebuilds the PDF from already-OCR'd [pages] — used when the user changes the
     * target/source language after a PDF translation, so we skip re-scanning hundreds of pages.
     */
    private fun retranslatePdf(layouts: List<PageLayout>) {
        lastAction = { retranslatePdf(layouts) }
        val previous = currentJob
        currentJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            startProcessing()
            try {
                // No OCR this time, so translation + build own the whole progress bar.
                translateAndBuildPdf(layouts, progressBase = 0f)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.message ?: "Could not process the PDF."
            } finally {
                statusMessage = null
                statusDetail = null
                isProcessing = false
            }
        }
    }

    /**
     * Shared by first-time processing and language-change re-runs: translates every page through one
     * translator, rebuilds the translated PDF, and records history. [progressBase] is where the
     * translation phase starts on the bar (0.5 after OCR, 0 when re-translating cached pages).
     */
    private suspend fun translateAndBuildPdf(layouts: List<PageLayout>, progressBase: Float) {
        originalText = joinPages(layouts)
        if (layouts.all { it.blocks.isEmpty() }) {
            errorMessage = "No readable text was found in this PDF. Try a clearer scan."
            return
        }

        // Detect the source language once from a representative page, then reuse it for all blocks.
        val sample = layouts.firstOrNull { it.blocks.isNotEmpty() }?.text.orEmpty()
        val actualSourceLang = resolveSourceLanguage(sample)
        val span = 1f - progressBase

        // Flatten every block across all pages into one batch so the whole document translates through
        // ONE translator with the model downloaded once. Block geometry is rebuilt from the same order.
        val blockTexts = layouts.flatMap { page -> page.blocks.map { it.text } }
        val pageCount = layouts.size

        statusMessage = "Translating PDF"
        val translatedTexts = translationEngine.translatePages(
            blockTexts,
            actualSourceLang,
            targetLanguage,
            onModelDownloadStart = {
                statusDetail = "Downloading language pack… (first time only)"
            },
            onPageTranslated = { index ->
                progress = progressBase + (index + 1).toFloat() / blockTexts.size * span
                statusDetail = "Block ${index + 1} of ${blockTexts.size} ($pageCount pages)"
            }
        )

        // Rebuild each page's layout with translated text, keeping every block's position + font size.
        var cursor = 0
        val translatedLayouts = layouts.map { page ->
            val blocks = page.blocks.map { block ->
                block.copy(text = translatedTexts.getOrElse(cursor) { block.text }).also { cursor++ }
            }
            page.copy(blocks = blocks)
        }
        translatedText = joinPages(translatedLayouts)

        // Rebuild the document as a translated PDF saved to Downloads.
        statusMessage = "Processing the PDF"
        statusDetail = null
        translatedPdfUri = pdfProcessor.createTranslatedPdf(translatedLayouts)

        recordHistory(originalText, translatedText, actualSourceLang, targetLanguage)
    }

    private fun joinPages(layouts: List<PageLayout>): String =
        layouts.mapIndexed { i, page -> "--- Page ${i + 1} ---\n${page.text}" }.joinToString("\n\n")

    fun processText(text: String) {
        lastAction = { processText(text) }
        pdfPages = null // source is typed/spoken text, not a PDF
        val previous = currentJob
        currentJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            startProcessing()
            try {
                originalText = text
                handleExtractedText()
            } catch (e: CancellationException) {
                throw e // Let cancellation propagate; never surface it as an error.
            } catch (e: Exception) {
                errorMessage = e.message ?: "Could not translate the text."
            } finally {
                isProcessing = false
            }
        }
    }

    /**
     * Cancels the in-flight OCR/translation job (if any) and clears the processing UI. Safe to call
     * when nothing is running. The cancelled coroutine unwinds via CancellationException, which the
     * process* functions rethrow rather than showing as an error — so cleanup happens here.
     */
    fun cancelProcessing() {
        currentJob?.cancel()
        currentJob = null
        isProcessing = false
        progress = 0f
        statusMessage = null
        statusDetail = null
    }

    /** Clears the error and re-runs the most recent image/PDF/text action (wired to the Retry button). */
    fun retryLastAction() {
        errorMessage = null
        lastAction?.invoke()
    }

    /**
     * Guards against scanning a source language ML Kit has no OCR recognizer for (e.g. Telugu, Tamil,
     * Arabic, Cyrillic) — those would silently produce garbage. Auto-Detect is always allowed.
     */
    private fun ensureOcrSupported(): Boolean {
        if (sourceLanguage != AUTO_LANGUAGE && !TextRecognizers.isOcrSupported(sourceLanguage)) {
            errorMessage = "On-device text scanning isn't available for this language. " +
                "Choose Auto-Detect, or scan a Latin / Hindi / Chinese / Japanese / Korean document."
            return false
        }
        return true
    }

    /** Records a translation in history, capping stored text so a full-document entry can't bloat DataStore. */
    private suspend fun recordHistory(original: String, translated: String, source: String, target: String) {
        if (translated.isBlank()) return
        historyRepository.addHistoryItem(
            HistoryItem(
                id = UUID.randomUUID().toString(),
                originalText = original.take(HISTORY_TEXT_CAP),
                translatedText = translated.take(HISTORY_TEXT_CAP),
                sourceLanguage = source,
                targetLanguage = target
            )
        )
    }

    /**
     * Re-runs translation on the text already extracted from the last image/PDF/typed input,
     * using the currently selected languages. Lets the user change the target (or source)
     * language and translate again without re-uploading or re-scanning. For PDF sources this
     * also rebuilds the translated PDF (from cached pages), so the saved file matches the new
     * language instead of going stale.
     */
    fun retranslate() {
        val pages = pdfPages
        if (pages != null) {
            retranslatePdf(pages)
        } else if (originalText.isNotBlank()) {
            processText(originalText)
        }
    }

    private fun startProcessing() {
        isProcessing = true
        progress = 0f
        errorMessage = null
        originalText = ""
        translatedText = ""
        translatedPdfUri = null
        statusMessage = null
        statusDetail = null
    }

    /**
     * Resolves the effective source language: the user's explicit choice, or — for Auto-Detect —
     * the language identified from [sample]. Language-ID can return a code ML Kit Translate has no
     * model for (notably Devanagari siblings: Marathi "mr", Nepali "ne", Sanskrit "sa" for Hindi
     * OCR); fromLanguageTag() returns null for those, so we fall back to Hindi rather than crash.
     */
    private suspend fun resolveSourceLanguage(sample: String): String {
        return if (sourceLanguage == AUTO_LANGUAGE) {
            val detected = translationEngine.detectLanguage(sample)
            detected?.let { TranslateLanguage.fromLanguageTag(it) } ?: TranslateLanguage.HINDI
        } else {
            sourceLanguage
        }
    }

    private suspend fun handleExtractedText() {
        if (originalText.isBlank()) {
            errorMessage = "No readable text was found. Try a clearer or higher-contrast image."
            return
        }
        translate()
    }

    private suspend fun translate() {
        if (originalText.isNotBlank()) {
            val actualSourceLang = resolveSourceLanguage(originalText)

            translatedText = translationEngine.translateText(
                originalText,
                actualSourceLang,
                targetLanguage
            )

            recordHistory(originalText, translatedText, actualSourceLang, targetLanguage)
        }
    }

    fun updateLanguages(source: String, target: String) {
        val changed = source != sourceLanguage || target != targetLanguage
        sourceLanguage = source
        targetLanguage = target
        // Auto-retranslate cheap text/image results on a language change. PDF rebuilds are heavy
        // (re-translate every page + rewrite the file), so for PDFs we wait for an explicit Retranslate
        // tap instead of firing a multi-minute job on every dropdown nudge.
        if (changed && pdfPages == null) retranslate()
    }

    /** Swaps source and target. No-op when the source is Auto-Detect (which has no fixed code). */
    fun swapLanguages() {
        if (sourceLanguage != AUTO_LANGUAGE) {
            val previousSource = sourceLanguage
            sourceLanguage = targetLanguage
            targetLanguage = previousSource
            // Same heavy-PDF guard as updateLanguages — text/image auto-retranslates, PDF waits for Retranslate.
            if (pdfPages == null) retranslate()
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepository.clearHistory()
        }
    }
}
