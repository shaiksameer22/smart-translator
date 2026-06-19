package app.passwordmanager.translator

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
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
    ): List<PageLayout> = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val fileDescriptor: ParcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Could not open the selected PDF file.")

        val renderer = PdfRenderer(fileDescriptor)
        val totalPages = renderer.pageCount
        val pages = ArrayList<PageLayout>(totalPages)

        // Failures propagate to the caller (shown as an error banner); cleanup runs regardless.
        try {
            for (i in 0 until totalPages) {
                val page = renderer.openPage(i)
                // Render at 2x for sharper OCR on text-dense pages, then recycle to keep memory flat
                // across hundreds of pages.
                val width = page.width * 2
                val height = page.height * 2
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } finally {
                    page.close()
                }

                // recycle() in finally so a failed/cancelled OCR can't leak the ~8 MB bitmap.
                try {
                    val image = InputImage.fromBitmap(bitmap, 0)
                    pages.add(PageLayout(width, height, TextRecognizers.recognizeBlocks(image, sourceLanguage)))
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

    override suspend fun createTranslatedPdf(pages: List<PageLayout>): Uri = withContext(Dispatchers.IO) {
        val document = PdfDocument()
        val paint = TextPaint().apply {
            color = Color.BLACK
            isAntiAlias = true
        }
        // Smaller, lighter paint for the footer page number.
        val accentPaint = TextPaint().apply {
            color = ACCENT_COLOR
            textSize = ACCENT_TEXT_SIZE
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }

        // An empty document is invalid; emit a single placeholder page so callers always get a PDF.
        val sourcePages = pages.ifEmpty { listOf(PageLayout(PAGE_WIDTH, PAGE_HEIGHT, emptyList())) }

        try {
            sourcePages.forEachIndexed { index, pageLayout ->
                val pageInfo = PdfDocument.PageInfo
                    .Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1)
                    .create()
                val page = document.startPage(pageInfo)
                drawPage(page.canvas, pageLayout, paint)

                // Centered footer page number.
                page.canvas.drawText("Page ${index + 1}", PAGE_WIDTH / 2f, FOOTER_BASELINE, accentPaint)
                document.finishPage(page)
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

    /**
     * Redraws each translated block at its original (scaled) position with a font size proportional to
     * the original glyph height — so headings stay large and columns/tables keep their layout. The
     * source bitmap is fitted into the printable A4 area (aspect preserved) and centered.
     */
    private fun drawPage(canvas: android.graphics.Canvas, layout: PageLayout, paint: TextPaint) {
        if (layout.blocks.isEmpty()) return

        // Scale the page bitmap to fit the printable area, preserving aspect ratio, then center it.
        val scale = minOf(
            CONTENT_WIDTH.toFloat() / layout.widthPx,
            CONTENT_HEIGHT.toFloat() / layout.heightPx
        )
        val offsetX = MARGIN + (CONTENT_WIDTH - layout.widthPx * scale) / 2f
        val offsetY = MARGIN + (CONTENT_HEIGHT - layout.heightPx * scale) / 2f

        // Median original font size on the page → blocks noticeably larger are treated as headings
        // (rendered bold) so the translated PDF keeps the original's visual hierarchy.
        val sizes = layout.blocks.map { it.fontSizePx }.sorted()
        val medianSize = sizes[sizes.size / 2]

        for (block in layout.blocks) {
            val fontSize = (block.fontSizePx * scale).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
            val isHeading = block.fontSizePx >= medianSize * HEADING_RATIO
            paint.textSize = fontSize
            paint.typeface = if (isHeading) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            // Block width in points; guarantee a minimum so a narrow box still wraps readably.
            val blockWidth = ((block.right - block.left) * scale)
                .coerceAtLeast(fontSize * 4f)
                .toInt()
                .coerceAtMost(CONTENT_WIDTH)

            val textLayout = StaticLayout.Builder
                .obtain(block.text, 0, block.text.length, paint, blockWidth)
                .setLineSpacing(LINE_SPACING_EXTRA, 1f)
                .build()

            val x = offsetX + block.left * scale
            val y = offsetY + block.top * scale

            canvas.save()
            canvas.translate(x, y)
            textLayout.draw(canvas)
            canvas.restore()
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
        const val LINE_SPACING_EXTRA = 2f
        const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
        const val CONTENT_HEIGHT = PAGE_HEIGHT - 2 * MARGIN

        // Proportional-font clamps so tiny captions stay legible and huge titles don't blow the layout.
        const val MIN_FONT_SIZE = 7f
        const val MAX_FONT_SIZE = 32f
        // A block this much taller than the page median is treated as a heading (rendered bold).
        const val HEADING_RATIO = 1.3f

        // Footer page-number furniture.
        const val ACCENT_TEXT_SIZE = 9f
        const val ACCENT_COLOR = 0xFF999999.toInt() // light grey
        const val FOOTER_BASELINE = PAGE_HEIGHT - MARGIN + ACCENT_TEXT_SIZE
    }
}
