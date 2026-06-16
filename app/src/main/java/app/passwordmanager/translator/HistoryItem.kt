package app.passwordmanager.translator

import kotlinx.serialization.Serializable

@Serializable
data class HistoryItem(
    val id: String,
    val originalText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val timestamp: Long = System.currentTimeMillis()
)
