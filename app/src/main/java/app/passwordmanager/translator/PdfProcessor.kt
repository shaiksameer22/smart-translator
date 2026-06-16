package app.passwordmanager.translator

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.text.StaticLayout
import android.text.TextPaint
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfProcessor(private val context: Context) : IPdfProcessor {

    override suspend fun extractPages(
        uri: Uri,
        sourceLanguage: String?,
        onProgress: (Float) -> Unit
    ): List<String> = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val fileDescriptor: ParcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Could not open the selected PDF file.")

        val renderer = PdfRenderer(fileDescriptor)
        val totalPages = renderer.pageCount
        val pages = ArrayList<String>(totalPages)

        // Failures propagate to the caller (shown as an error banner); cleanup runs regardless.
        try {
            for (i in 0 until totalPages) {
                val page = renderer.openPage(i)
                // Render at 2x for sharper OCR on text-dense pages, then recycle to keep memory flat
                // across hundreds of pages.
                val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } finally {
                    page.close()
                }

                // recycle() in finally so a failed/cancelled OCR can't leak the ~8 MB bitmap.
                try {
                    val image = InputImage.fromBitmap(bitmap, 0)
                    pages.add(TextRecognizers.recognize(image, sourceLanguage))
                } finally {
                    bitmap.recycle()
                }

                onProgress((i + 1).toFloat() / totalPages)
            }
        } finally {
            renderer.close()
            fileDescriptor.close()
        }

        return@withContext pages
    }

    override suspend fun createTranslatedPdf(pages: List<String>): Uri = withContext(Dispatchers.IO) {
        val document = PdfDocument()
        // Default typeface (no setTypeface): renders Latin and falls back for non-Latin scripts
        // (Hindi, Chinese, etc.) so we never drop glyphs the chosen font lacks.
        val paint = TextPaint().apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
            isAntiAlias = true
        }
        // Smaller, lighter paint shared by the footer page number and the running source-page header.
        val accentPaint = TextPaint().apply {
            color = ACCENT_COLOR
            textSize = ACCENT_TEXT_SIZE
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }

        // An empty document is invalid; emit a single placeholder page so callers always get a PDF.
        val sourcePages = pages.ifEmpty { listOf("No content") }

        var pageNumber = 1
        try {
            sourcePages.forEachIndexed { sourceIndex, pageText ->
                // StaticLayout needs non-empty content; keep blank source pages as blank output pages.
                val text = pageText.ifBlank { " " }
                val layout = StaticLayout.Builder
                    .obtain(text, 0, text.length, paint, CONTENT_WIDTH)
                    .setLineSpacing(LINE_SPACING_EXTRA, 1f)
                    .build()

                // A single source page can hold more text than fits on one output page, so paginate
                // by slicing the layout into vertical bands that each fit the printable area. BODY_HEIGHT
                // (not CONTENT_HEIGHT) leaves room at the bottom so body text never sits under the footer.
                var startLine = 0
                var firstBandOfSource = true
                do {
                    val bandTop = layout.getLineTop(startLine)
                    var endLine = startLine
                    while (endLine < layout.lineCount &&
                        layout.getLineBottom(endLine) - bandTop <= BODY_HEIGHT
                    ) {
                        endLine++
                    }
                    if (endLine == startLine) endLine = startLine + 1 // guarantee forward progress

                    val pageInfo = PdfDocument.PageInfo
                        .Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber)
                        .create()
                    val page = document.startPage(pageInfo)
                    val canvas = page.canvas

                    // Faint running header on the first output page produced for each source page, so the
                    // reader can see where each original page begins.
                    if (firstBandOfSource) {
                        canvas.drawText(
                            "— Page ${sourceIndex + 1} —",
                            PAGE_WIDTH / 2f,
                            HEADER_BASELINE,
                            accentPaint
                        )
                    }

                    canvas.save()
                    canvas.translate(MARGIN.toFloat(), (BODY_TOP - bandTop).toFloat())
                    // Clip to this band so spill-over lines aren't drawn over the footer or next page.
                    canvas.clipRect(0f, bandTop.toFloat(), CONTENT_WIDTH.toFloat(), (bandTop + BODY_HEIGHT).toFloat())
                    layout.draw(canvas)
                    canvas.restore()

                    // Centered footer page number, drawn after the body so it's never clipped away.
                    canvas.drawText("Page $pageNumber", PAGE_WIDTH / 2f, FOOTER_BASELINE, accentPaint)

                    document.finishPage(page)

                    pageNumber++
                    firstBandOfSource = false
                    startLine = endLine
                } while (startLine < layout.lineCount)
            }

            return@withContext writeToDownloads(document)
        } catch (e: OutOfMemoryError) {
            // PdfDocument buffers every page in RAM until writeTo, so very large documents can exhaust
            // memory. Convert the Error into a normal exception so the caller shows an error banner
            // instead of crashing the app.
            throw IllegalStateException(
                "This PDF is too large to rebuild on this device. Try a smaller PDF or split it into parts.",
                e
            )
        } finally {
            document.close()
        }
    }

    private fun writeToDownloads(document: PdfDocument): Uri {
        val resolver = context.contentResolver
        val fileName = "translated_${System.currentTimeMillis()}.pdf"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Couldn't create the translated PDF in Downloads.")

        // On any failure (incl. OOM during writeTo) delete the still-pending row so we don't leave an
        // orphaned, invisible 0-byte entry in Downloads.
        try {
            resolver.openOutputStream(itemUri)?.use { document.writeTo(it) }
                ?: throw IllegalStateException("Couldn't write the translated PDF.")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw t
        }

        return itemUri
    }

    private companion object {
        // A4 at 72 dpi, in PostScript points.
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 40
        const val TEXT_SIZE = 12f
        const val LINE_SPACING_EXTRA = 2f
        const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
        const val CONTENT_HEIGHT = PAGE_HEIGHT - 2 * MARGIN

        // Header (source-page marker) and footer (page number) furniture: smaller, lighter text.
        const val ACCENT_TEXT_SIZE = 9f
        const val ACCENT_COLOR = 0xFF999999.toInt() // light grey
        const val HEADER_HEIGHT = 14   // vertical band reserved at the top for the running header
        const val FOOTER_HEIGHT = 16   // vertical band reserved at the bottom for the page number

        // Body band: the printable area minus the header/footer reservations so text never overlaps them.
        const val BODY_TOP = MARGIN + HEADER_HEIGHT
        const val BODY_HEIGHT = CONTENT_HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT

        // Baselines for the furniture, kept within the top/bottom margins.
        const val HEADER_BASELINE = MARGIN + ACCENT_TEXT_SIZE
        const val FOOTER_BASELINE = PAGE_HEIGHT - MARGIN + ACCENT_TEXT_SIZE
    }
}
