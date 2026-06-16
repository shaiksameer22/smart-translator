package app.passwordmanager.translator

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

class TranslationEngine : ITranslationEngine {
    override suspend fun translateText(text: String, sourceLang: String, targetLang: String): String {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(targetLang)
            .build()

        val translator = Translation.getClient(options)
        val conditions = DownloadConditions.Builder().build()

        // Let failures propagate so the caller can show an error; always close the client.
        return try {
            downloadModelWithRetry(translator, conditions)
            translateThroughClient(translator, text)
        } finally {
            translator.close()
        }
    }

    override suspend fun translatePages(
        pages: List<String>,
        sourceLang: String,
        targetLang: String,
        onModelDownloadStart: () -> Unit,
        onPageTranslated: (Int) -> Unit
    ): List<String> {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(targetLang)
            .build()

        // One client + one model download for the whole batch — the key win for large PDFs, where
        // the old per-page approach re-created the client and re-checked the model hundreds of times.
        val translator = Translation.getClient(options)
        val conditions = DownloadConditions.Builder().build()

        return try {
            onModelDownloadStart()
            downloadModelWithRetry(translator, conditions)

            val results = ArrayList<String>(pages.size)
            pages.forEachIndexed { index, page ->
                results.add(if (page.isBlank()) "" else translateThroughClient(translator, page))
                onPageTranslated(index)
            }
            results
        } finally {
            translator.close()
        }
    }

    /**
     * Translates [text] through an already-prepared [translator]. ML Kit's translate() is built for
     * short snippets — large inputs (a full PDF page or a long pasted document) translate slowly,
     * drop text, or fail outright — so we split into bounded chunks on sentence/paragraph boundaries
     * and translate each through the same client.
     */
    private suspend fun translateThroughClient(translator: Translator, text: String): String {
        // Translate block-by-block (blocks are separated by blank lines from the OCR/reading-order
        // step) so the page's paragraph structure survives. ML Kit collapses newlines inside a single
        // translate() call, so we re-insert the blank lines ourselves between translated blocks — this
        // is what keeps the output PDF clean and ordered instead of one scrambled wall of text.
        val blocks = text.split(BLOCK_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        if (blocks.isEmpty()) return ""

        val builder = StringBuilder()
        blocks.forEachIndexed { index, block ->
            // Collapse OCR wrap-lines within a block into spaces so a paragraph translates as one flow.
            val paragraph = block.replace(WRAP_NEWLINES, " ")
            if (paragraph.length <= MAX_CHUNK_CHARS) {
                builder.append(translateChunkWithRetry(translator, paragraph))
            } else {
                // Oversized paragraph: translate its chunks and rejoin with spaces (loop, not
                // joinToString, because the translate call is suspending).
                val chunks = TextChunker.chunk(paragraph, MAX_CHUNK_CHARS)
                chunks.forEachIndexed { ci, chunk ->
                    builder.append(translateChunkWithRetry(translator, chunk))
                    if (ci < chunks.size - 1) builder.append(' ')
                }
            }
            if (index < blocks.size - 1) builder.append("\n\n")
        }
        return builder.toString()
    }

    /**
     * Translates a single chunk, retrying once on failure. Individual translate() calls occasionally
     * fail transiently (the model is loaded lazily on first call), so one quick retry smooths over
     * those hiccups; a second failure propagates so the caller can surface the error.
     */
    private suspend fun translateChunkWithRetry(translator: Translator, chunk: String): String {
        return try {
            translator.translate(chunk).await()
        } catch (e: CancellationException) {
            throw e // Never retry a cancelled job — let cancellation propagate.
        } catch (e: Exception) {
            delay(RETRY_DELAY_MS)
            translator.translate(chunk).await()
        }
    }

    /**
     * Downloads the language pack, retrying transient failures. ML Kit fetches each language
     * model (~30 MB) on first use; on flaky mobile networks the download Task frequently fails
     * with "1 out of 1 underlying tasks failed" but succeeds on a retry. After all attempts
     * fail we throw a clear, actionable message instead of ML Kit's opaque one.
     */
    private suspend fun downloadModelWithRetry(translator: Translator, conditions: DownloadConditions) {
        var lastError: Exception? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                // Bound each attempt: on throttled/flaky mobile networks the download can stall forever
                // (the Task never completes or fails), which froze the UI on "Downloading language
                // pack…". A timeout turns that stall into a retryable, reportable failure.
                withTimeout(DOWNLOAD_TIMEOUT_MS) {
                    translator.downloadModelIfNeeded(conditions).await()
                }
                return
            } catch (e: TimeoutCancellationException) {
                lastError = e // stalled download (not user cancellation) — fall through and retry
                if (attempt < MAX_DOWNLOAD_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
            } catch (e: CancellationException) {
                throw e // Never retry a cancelled job — let cancellation propagate.
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_DOWNLOAD_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
            }
        }
        throw IllegalStateException(
            "Couldn't download the language pack — this often gets stuck on mobile data. " +
                "Connect to Wi-Fi and tap Try again.",
            lastError
        )
    }

    override suspend fun detectLanguage(text: String): String? {
        val languageIdentifier = LanguageIdentification.getClient()
        return try {
            val languageCode = languageIdentifier.identifyLanguage(text).await()
            if (languageCode == "und") null else languageCode
        } catch (e: Exception) {
            null
        } finally {
            languageIdentifier.close()
        }
    }

    companion object {
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1500L
        // Per-attempt ceiling for the one-time model download. Long enough for a real download on a
        // slow connection, short enough that a stalled one fails (then retries) instead of hanging.
        private const val DOWNLOAD_TIMEOUT_MS = 60_000L
        // Conservative per-call ceiling: keeps each translate() request small enough to be reliable
        // and responsive, while still batching whole paragraphs together.
        private const val MAX_CHUNK_CHARS = 2000
        // Blank line(s) separate OCR blocks/paragraphs; a run of whitespace+newline is a wrap inside one.
        private val BLOCK_SEPARATOR = Regex("\\n\\s*\\n")
        private val WRAP_NEWLINES = Regex("\\s*\\n\\s*")
    }
}

/**
 * Pure, ML-Kit-free text splitter. Extracted from [TranslationEngine] so the chunking logic — the
 * tricky part — can be unit-tested without any Android/ML Kit runtime. Keeping it stateless and
 * dependency-free means tests just call [chunk] with a size and assert on the resulting list.
 */
internal object TextChunker {
    /**
     * Splits [text] into chunks no larger than [maxChars], breaking on paragraph boundaries first and
     * falling back to sentence (then word/character) boundaries for paragraphs that are themselves too
     * long. Rejoining the translated chunks with newlines preserves the rough layout.
     *
     * Edge cases: an empty string returns `[""]` so callers preserve blank pages; a single token longer
     * than [maxChars] is hard-split at character boundaries so no chunk ever exceeds the ceiling.
     */
    fun chunk(text: String, maxChars: Int): List<String> {
        // Preserve blank input as a single empty chunk so callers keep blank pages/segments intact.
        if (text.isEmpty()) return listOf("")
        if (text.length <= maxChars) return listOf(text)

        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                chunks.add(current.toString())
                current.clear()
            }
        }

        for (paragraph in text.split("\n")) {
            if (paragraph.length > maxChars) {
                flush()
                chunks.addAll(splitLongParagraph(paragraph, maxChars))
                continue
            }
            if (current.isNotEmpty() && current.length + paragraph.length + 1 > maxChars) flush()
            if (current.isNotEmpty()) current.append('\n')
            current.append(paragraph)
        }
        flush()
        return chunks
    }

    private fun splitLongParagraph(paragraph: String, maxChars: Int): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                result.add(current.toString())
                current.clear()
            }
        }

        for (sentence in paragraph.split(Regex("(?<=[.!?。！？])\\s+"))) {
            if (sentence.length > maxChars) {
                flush()
                // A single sentence (or word with no breaks) longer than the ceiling: hard-split it at
                // character boundaries so every emitted chunk stays within [maxChars].
                var i = 0
                while (i < sentence.length) {
                    val end = minOf(i + maxChars, sentence.length)
                    result.add(sentence.substring(i, end))
                    i = end
                }
                continue
            }
            if (current.isNotEmpty() && current.length + sentence.length + 1 > maxChars) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        flush()
        return result
    }
}
