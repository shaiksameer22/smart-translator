package app.passwordmanager.settings

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * Thin wrapper over ML Kit's [RemoteModelManager] for managing the on-device translation language
 * packs (download / list / delete). ML Kit's download returns a plain completion Task with NO
 * progress callback, so callers can only show an indeterminate "Downloading…" state, not a percentage.
 */
class LanguageModelManager {
    private val manager = RemoteModelManager.getInstance()

    private companion object {
        // Generous ceiling for an explicit ~30–40 MB pack download; a stall fails after this.
        const val DOWNLOAD_TIMEOUT_MS = 180_000L
    }

    /** Language codes (e.g. "hi", "te") whose packs are currently downloaded on this device. */
    suspend fun downloadedCodes(): Set<String> =
        manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            .map { it.language }
            .toSet()

    /**
     * Downloads the pack for [code]; allowed over any network (Wi-Fi recommended for first use).
     * Bounded by a timeout so a download that stalls on throttled mobile data fails (and clears the
     * spinner) instead of hanging forever.
     */
    suspend fun download(code: String) {
        val model = TranslateRemoteModel.Builder(code).build()
        withTimeout(DOWNLOAD_TIMEOUT_MS) {
            manager.download(model, DownloadConditions.Builder().build()).await()
        }
    }

    /** Removes the downloaded pack for [code] to free storage. */
    suspend fun delete(code: String) {
        val model = TranslateRemoteModel.Builder(code).build()
        manager.deleteDownloadedModel(model).await()
    }
}
