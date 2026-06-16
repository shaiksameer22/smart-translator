package app.passwordmanager.translator

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "translation_history")

class TranslationHistoryRepository(private val context: Context) {
    private val historyKey = stringPreferencesKey("history_list")

    val history: Flow<List<HistoryItem>> = context.dataStore.data.map { preferences ->
        val historyJson = preferences[historyKey] ?: "[]"
        try {
            Json.decodeFromString<List<HistoryItem>>(historyJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun addHistoryItem(item: HistoryItem) {
        context.dataStore.edit { preferences ->
            val currentHistoryJson = preferences[historyKey] ?: "[]"
            val currentHistory = try {
                Json.decodeFromString<List<HistoryItem>>(currentHistoryJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }

            // Drop any prior entry for the same source text + language pair so re-translating (e.g.
            // changing the target language) updates the existing entry instead of stacking duplicates
            // that would evict genuinely distinct history under the 10-item cap.
            currentHistory.removeAll {
                it.originalText == item.originalText &&
                    it.sourceLanguage == item.sourceLanguage &&
                    it.targetLanguage == item.targetLanguage
            }

            // Add new item at the beginning
            currentHistory.add(0, item)

            // Keep only last 10
            val limitedHistory = currentHistory.take(10)

            preferences[historyKey] = Json.encodeToString(limitedHistory)
        }
    }

    suspend fun clearHistory() {
        context.dataStore.edit { it.remove(historyKey) }
    }
}
