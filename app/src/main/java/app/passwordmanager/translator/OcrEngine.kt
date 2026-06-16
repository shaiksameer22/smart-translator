package app.passwordmanager.translator

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage

class OcrEngine(private val context: Context) : IOcrEngine {

    /**
     * Returns the recognized text, or an empty string if the image has no readable text.
     * Failures (bad URI, decode errors) are thrown so the caller can surface them as an
     * error instead of silently treating an error message as recognized text.
     */
    override suspend fun extractTextFromImage(uri: Uri, sourceLanguage: String?): String {
        val image = InputImage.fromFilePath(context, uri)
        return TextRecognizers.recognize(image, sourceLanguage)
    }
}
