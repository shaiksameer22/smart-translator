package app.passwordmanager.settings

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark")
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val rateKey = floatPreferencesKey("speech_rate")
    private val pitchKey = floatPreferencesKey("speech_pitch")

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[themeKey] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    val speechRate: Flow<Float> = context.settingsDataStore.data.map { it[rateKey] ?: 1f }
    val speechPitch: Flow<Float> = context.settingsDataStore.data.map { it[pitchKey] ?: 1f }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setSpeechRate(rate: Float) {
        context.settingsDataStore.edit { it[rateKey] = rate }
    }

    suspend fun setSpeechPitch(pitch: Float) {
        context.settingsDataStore.edit { it[pitchKey] = pitch }
    }
}

class SettingsViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: SettingsRepository = SettingsRepository(application),
    private val languageModels: LanguageModelManager = LanguageModelManager()
) : AndroidViewModel(application) {

    val themeMode: StateFlow<ThemeMode> = repository.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    val speechRate: StateFlow<Float> = repository.speechRate
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)

    val speechPitch: StateFlow<Float> = repository.speechPitch
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)

    /** Codes of language packs already downloaded on the device. */
    var downloadedLanguages by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Codes currently downloading (drives the per-row spinner — ML Kit gives no real percentage). */
    var downloadingLanguages by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Last language-pack error (e.g. a stalled download), shown in the offline-languages section. */
    var languageError by mutableStateOf<String?>(null)
        private set

    init {
        refreshDownloadedLanguages()
    }

    fun refreshDownloadedLanguages() {
        viewModelScope.launch {
            downloadedLanguages = runCatching { languageModels.downloadedCodes() }.getOrDefault(emptySet())
        }
    }

    fun downloadLanguage(code: String) {
        if (code in downloadingLanguages || code in downloadedLanguages) return
        viewModelScope.launch {
            downloadingLanguages = downloadingLanguages + code
            languageError = null
            try {
                languageModels.download(code)
                downloadedLanguages = downloadedLanguages + code
            } catch (e: TimeoutCancellationException) {
                languageError = "Download timed out — it often stalls on mobile data. Connect to Wi-Fi and try again."
            } catch (e: CancellationException) {
                throw e // a real cancellation (e.g. ViewModel cleared) — don't treat as a download error
            } catch (e: Exception) {
                languageError = "Couldn't download that pack — connect to Wi-Fi and try again."
            } finally {
                downloadingLanguages = downloadingLanguages - code
            }
        }
    }

    fun deleteLanguage(code: String) {
        viewModelScope.launch {
            runCatching { languageModels.delete(code) }
            downloadedLanguages = downloadedLanguages - code
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setSpeechRate(rate: Float) {
        viewModelScope.launch { repository.setSpeechRate(rate) }
    }

    fun setSpeechPitch(pitch: Float) {
        viewModelScope.launch { repository.setSpeechPitch(pitch) }
    }
}
