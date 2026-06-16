package app.passwordmanager.translator

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Wraps Android [TextToSpeech] and exposes an observable [isSpeaking] flag so the UI can show a
 * play/stop toggle. Text-to-Speech has no real "pause", so stopping ends the current utterance.
 */
class TtsController(context: Context) {
    private val speakingState = mutableStateOf(false)
    val isSpeaking: Boolean get() = speakingState.value

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, null).apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post { speakingState.value = true }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post { speakingState.value = false }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post { speakingState.value = false }
            }
        })
    }

    fun speak(text: String, languageCode: String, context: Context, rate: Float = 1f, pitch: Float = 1f) {
        if (text.isBlank()) return
        val result = tts.setLanguage(Locale(languageCode))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Toast.makeText(context, "Voice isn't available for this language", Toast.LENGTH_SHORT).show()
            return
        }
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        // TextToSpeech rejects input longer than getMaxSpeechInputLength() (~4000 chars) and speaks
        // nothing — cap so a whole-PDF translation at least reads the start instead of silently failing.
        val maxLen = TextToSpeech.getMaxSpeechInputLength()
        val safeText = if (text.length > maxLen) text.substring(0, maxLen) else text
        speakingState.value = true
        val status = tts.speak(safeText, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (status == TextToSpeech.ERROR) {
            speakingState.value = false
            Toast.makeText(context, "Couldn't read this text aloud", Toast.LENGTH_SHORT).show()
        }
    }

    /** Plays the text, or stops if something is already playing. */
    fun toggle(text: String, languageCode: String, context: Context, rate: Float = 1f, pitch: Float = 1f) {
        if (isSpeaking) stop() else speak(text, languageCode, context, rate, pitch)
    }

    fun stop() {
        tts.stop()
        speakingState.value = false
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private companion object {
        const val UTTERANCE_ID = "utterance"
    }
}

@Composable
fun rememberTtsController(): TtsController {
    val context = LocalContext.current
    val controller = remember { TtsController(context) }
    DisposableEffect(Unit) {
        onDispose { controller.shutdown() }
    }
    return controller
}
