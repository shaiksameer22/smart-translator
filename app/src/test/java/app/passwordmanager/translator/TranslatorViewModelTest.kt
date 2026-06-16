package app.passwordmanager.translator

import android.app.Application
import android.net.Uri
import com.google.mlkit.nl.translate.TranslateLanguage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslatorViewModelTest {

    private val application = mockk<Application>(relaxed = true)
    private val ocrEngine = mockk<IOcrEngine>()
    private val translationEngine = mockk<ITranslationEngine>()
    private val pdfProcessor = mockk<IPdfProcessor>()
    private val historyRepository = mockk<TranslationHistoryRepository>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: TranslatorViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { historyRepository.history } returns flowOf(emptyList())
        viewModel = TranslatorViewModel(application, ocrEngine, translationEngine, pdfProcessor, historyRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `processImage updates originalText and translates`() = runTest {
        val uri = mockk<Uri>()
        val extractedText = "Hola Mundo"
        val translatedText = "Hello World"

        coEvery { ocrEngine.extractTextFromImage(uri, any()) } coAnswers { delay(1000); extractedText }
        coEvery { translationEngine.detectLanguage(extractedText) } returns TranslateLanguage.ENGLISH
        coEvery { translationEngine.translateText(extractedText, any(), any()) } returns translatedText

        viewModel.processImage(uri)

        // Let the coroutine start and park in the (suspending) OCR call: isProcessing should be true
        runCurrent()
        assertTrue(viewModel.isProcessing)

        advanceUntilIdle()

        assertEquals(extractedText, viewModel.originalText)
        assertEquals(translatedText, viewModel.translatedText)
        assertFalse(viewModel.isProcessing)
    }

    @Test
    fun `processPdf OCRs every page, translates each, and builds a translated PDF`() = runTest {
        val uri = mockk<Uri>()
        val pdfUri = mockk<Uri>()
        val pages = listOf("Page one", "Page two")

        coEvery { pdfProcessor.extractPages(uri, any(), any()) } coAnswers { delay(1000); pages }
        coEvery { translationEngine.detectLanguage(any()) } returns TranslateLanguage.ENGLISH
        coEvery {
            translationEngine.translatePages(pages, any(), any(), any(), any())
        } returns listOf("Translated one", "Translated two")
        coEvery { pdfProcessor.createTranslatedPdf(any()) } returns pdfUri

        viewModel.processPdf(uri)

        runCurrent()
        assertTrue(viewModel.isProcessing)

        advanceUntilIdle()

        // Each page is translated independently and rejoined with page markers.
        assertTrue(viewModel.originalText.contains("Page one"))
        assertTrue(viewModel.originalText.contains("Page two"))
        assertTrue(viewModel.translatedText.contains("Translated one"))
        assertTrue(viewModel.translatedText.contains("Translated two"))
        assertEquals(pdfUri, viewModel.translatedPdfUri)
        assertFalse(viewModel.isProcessing)
        coVerify(exactly = 1) { pdfProcessor.createTranslatedPdf(listOf("Translated one", "Translated two")) }
    }

    @Test
    fun `changing PDF language does not auto-rebuild, but explicit retranslate rebuilds without re-OCR`() = runTest {
        val uri = mockk<Uri>()
        val pdfUri = mockk<Uri>()
        val pages = listOf("Page one", "Page two")

        coEvery { pdfProcessor.extractPages(uri, any(), any()) } returns pages
        coEvery { translationEngine.detectLanguage(any()) } returns TranslateLanguage.ENGLISH
        coEvery { translationEngine.translatePages(pages, any(), any(), any(), any()) } returns listOf("t1", "t2")
        coEvery { pdfProcessor.createTranslatedPdf(any()) } returns pdfUri

        viewModel.processPdf(uri)
        advanceUntilIdle()
        assertEquals(pdfUri, viewModel.translatedPdfUri)

        // A PDF rebuild is heavy, so a language change must NOT auto-rebuild (no extra createTranslatedPdf).
        viewModel.updateLanguages(TranslatorViewModel.AUTO_LANGUAGE, TranslateLanguage.GERMAN)
        advanceUntilIdle()
        coVerify(exactly = 1) { pdfProcessor.createTranslatedPdf(any()) }

        // An explicit Retranslate rebuilds from the cached pages — rebuilds the PDF but does NOT re-OCR.
        viewModel.retranslate()
        advanceUntilIdle()

        assertEquals(pdfUri, viewModel.translatedPdfUri)
        coVerify(exactly = 1) { pdfProcessor.extractPages(uri, any(), any()) }
        coVerify(exactly = 2) { pdfProcessor.createTranslatedPdf(any()) }
    }

    @Test
    fun `cancelProcessing stops an in-flight job without showing an error`() = runTest {
        val uri = mockk<Uri>()
        // Park the job in a long-running OCR call so we can cancel mid-flight.
        coEvery { pdfProcessor.extractPages(uri, any(), any()) } coAnswers { delay(10_000); listOf("x") }

        viewModel.processPdf(uri)
        runCurrent()
        assertTrue(viewModel.isProcessing)

        viewModel.cancelProcessing()
        advanceUntilIdle()

        assertFalse(viewModel.isProcessing)
        assertEquals(null, viewModel.errorMessage)
        assertEquals(null, viewModel.translatedPdfUri)
        coVerify(exactly = 0) { translationEngine.translatePages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancelling a text job clears isProcessing and shows no error`() = runTest {
        coEvery { translationEngine.detectLanguage(any()) } returns TranslateLanguage.SPANISH
        coEvery { translationEngine.translateText(any(), any(), any()) } coAnswers { delay(10_000); "x" }

        viewModel.processText("Hola mundo")
        runCurrent()
        assertTrue(viewModel.isProcessing)

        viewModel.cancelProcessing()
        advanceUntilIdle()

        assertFalse(viewModel.isProcessing)
        assertEquals(null, viewModel.errorMessage)
    }

    @Test
    fun `retryLastAction clears the error and re-runs the last action`() = runTest {
        coEvery { translationEngine.detectLanguage(any()) } returns TranslateLanguage.SPANISH
        // First attempt fails, second succeeds.
        coEvery { translationEngine.translateText(any(), any(), any()) } answers
            { throw RuntimeException("boom") } andThen "Hello world"

        viewModel.processText("Hola mundo")
        advanceUntilIdle()
        assertEquals("boom", viewModel.errorMessage)

        viewModel.retryLastAction()
        advanceUntilIdle()

        assertEquals(null, viewModel.errorMessage)
        assertEquals("Hello world", viewModel.translatedText)
    }

    @Test
    fun `explicit non-OCR source language is rejected before scanning`() = runTest {
        val uri = mockk<Uri>()
        viewModel.updateLanguages(TranslateLanguage.TELUGU, TranslateLanguage.ENGLISH)

        viewModel.processImage(uri)
        advanceUntilIdle()

        assertEquals(false, viewModel.errorMessage.isNullOrBlank())
        assertFalse(viewModel.isProcessing)
        coVerify(exactly = 0) { ocrEngine.extractTextFromImage(any(), any()) }
    }

    @Test
    fun `processPdf reports an error and builds no PDF when every page is blank`() = runTest {
        val uri = mockk<Uri>()
        coEvery { pdfProcessor.extractPages(uri, any(), any()) } returns listOf("", "  ")

        viewModel.processPdf(uri)
        advanceUntilIdle()

        assertEquals(null, viewModel.translatedPdfUri)
        assertFalse(viewModel.isProcessing)
        coVerify(exactly = 0) { translationEngine.translatePages(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { pdfProcessor.createTranslatedPdf(any()) }
    }

    @Test
    fun `auto-detection is used when sourceLanguage is AUTO`() = runTest {
        val uri = mockk<Uri>()
        val extractedText = "Some text"
        val detectedLang = TranslateLanguage.FRENCH
        val translatedText = "Translated text"

        viewModel.updateLanguages(TranslatorViewModel.AUTO_LANGUAGE, TranslateLanguage.ENGLISH)

        coEvery { ocrEngine.extractTextFromImage(uri, any()) } returns extractedText
        coEvery { translationEngine.detectLanguage(extractedText) } returns detectedLang
        coEvery { translationEngine.translateText(extractedText, detectedLang, any()) } returns translatedText

        viewModel.processImage(uri)
        advanceUntilIdle()

        assertEquals(translatedText, viewModel.translatedText)
    }

    @Test
    fun `processText translates typed text without OCR`() = runTest {
        val input = "Bonjour le monde"
        val translated = "Hello world"

        coEvery { translationEngine.detectLanguage(input) } returns TranslateLanguage.FRENCH
        coEvery { translationEngine.translateText(input, any(), any()) } returns translated

        viewModel.processText(input)
        advanceUntilIdle()

        assertEquals(input, viewModel.originalText)
        assertEquals(translated, viewModel.translatedText)
        assertFalse(viewModel.isProcessing)
        coVerify(exactly = 0) { ocrEngine.extractTextFromImage(any(), any()) }
    }

    @Test
    fun `retranslate re-runs translation on existing text without OCR`() = runTest {
        val extracted = "Hola mundo"

        viewModel.updateLanguages(TranslatorViewModel.AUTO_LANGUAGE, TranslateLanguage.ENGLISH)
        coEvery { translationEngine.detectLanguage(extracted) } returns TranslateLanguage.SPANISH
        coEvery { translationEngine.translateText(extracted, any(), TranslateLanguage.ENGLISH) } returns "Hello world"

        viewModel.processText(extracted)
        advanceUntilIdle()
        assertEquals("Hello world", viewModel.translatedText)

        // Change the target language and retranslate the same text — no re-upload/OCR.
        viewModel.updateLanguages(TranslatorViewModel.AUTO_LANGUAGE, TranslateLanguage.FRENCH)
        coEvery { translationEngine.translateText(extracted, any(), TranslateLanguage.FRENCH) } returns "Bonjour le monde"

        viewModel.retranslate()
        advanceUntilIdle()

        assertEquals("Bonjour le monde", viewModel.translatedText)
        assertEquals(extracted, viewModel.originalText)
        coVerify(exactly = 0) { ocrEngine.extractTextFromImage(any(), any()) }
    }

    @Test
    fun `changing target language auto-retranslates existing text`() = runTest {
        val extracted = "Hola mundo"

        coEvery { translationEngine.detectLanguage(extracted) } returns TranslateLanguage.SPANISH
        coEvery { translationEngine.translateText(extracted, any(), TranslateLanguage.ENGLISH) } returns "Hello world"
        viewModel.processText(extracted)
        advanceUntilIdle()
        assertEquals("Hello world", viewModel.translatedText)

        // Just changing the language should re-translate automatically (no processText call).
        coEvery { translationEngine.translateText(extracted, any(), TranslateLanguage.GERMAN) } returns "Hallo Welt"
        viewModel.updateLanguages(TranslatorViewModel.AUTO_LANGUAGE, TranslateLanguage.GERMAN)
        advanceUntilIdle()

        assertEquals("Hallo Welt", viewModel.translatedText)
        coVerify(exactly = 0) { ocrEngine.extractTextFromImage(any(), any()) }
    }

    @Test
    fun `explicit source language is passed to OCR and skips auto-detection`() = runTest {
        val uri = mockk<Uri>()
        val extractedText = "Some Hindi text"
        val translatedText = "Some English text"

        viewModel.updateLanguages(TranslateLanguage.HINDI, TranslateLanguage.ENGLISH)

        coEvery { ocrEngine.extractTextFromImage(uri, TranslateLanguage.HINDI) } returns extractedText
        coEvery {
            translationEngine.translateText(extractedText, TranslateLanguage.HINDI, TranslateLanguage.ENGLISH)
        } returns translatedText

        viewModel.processImage(uri)
        advanceUntilIdle()

        assertEquals(translatedText, viewModel.translatedText)
        coVerify(exactly = 1) { ocrEngine.extractTextFromImage(uri, TranslateLanguage.HINDI) }
        coVerify(exactly = 0) { translationEngine.detectLanguage(any()) }
    }

    @Test
    fun `updateLanguages updates state`() {
        val source = TranslateLanguage.FRENCH
        val target = TranslateLanguage.GERMAN

        viewModel.updateLanguages(source, target)

        assertEquals(source, viewModel.sourceLanguage)
        assertEquals(target, viewModel.targetLanguage)
    }

    @Test
    fun `translate is not called if originalText is blank`() = runTest {
        val uri = mockk<Uri>()
        coEvery { ocrEngine.extractTextFromImage(uri, any()) } returns ""

        viewModel.processImage(uri)
        advanceUntilIdle()

        assertEquals("", viewModel.originalText)
        assertEquals("", viewModel.translatedText)
    }
}
