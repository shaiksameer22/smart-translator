package app.passwordmanager.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.passwordmanager.translator.rememberTtsController
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale
import kotlin.math.abs

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val themeMode by viewModel.themeMode.collectAsState()
    val speechRate by viewModel.speechRate.collectAsState()
    val speechPitch by viewModel.speechPitch.collectAsState()
    val context = LocalContext.current
    val tts = rememberTtsController()

    // All ML Kit translate languages with readable English names, sorted by name (computed once).
    val languages = remember {
        TranslateLanguage.getAllLanguages()
            .map { code ->
                code to Locale(code).getDisplayLanguage(Locale.ENGLISH)
                    .ifBlank { code }
                    .replaceFirstChar { it.uppercase() }
            }
            .sortedBy { it.second }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SettingsHeader()

        // LazyColumn so the ~59 language rows are virtualized instead of all composed at once
        // (that non-lazy list was the main source of scroll jank).
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                SectionTitle(Icons.Default.DarkMode, "Appearance")
                AppearanceCard(themeMode, viewModel::setThemeMode)
                Spacer(Modifier.height(28.dp))
            }

            item {
                SectionTitle(Icons.Default.RecordVoiceOver, "Voice")
                VoiceCard(speechRate, speechPitch, viewModel, tts, context)
                Spacer(Modifier.height(28.dp))
            }

            item {
                SectionTitle(Icons.Default.Language, "Offline languages")
                Text(
                    "Download a pack to translate that language fully offline. Each is ~30–40 MB and " +
                        "downloads once — Wi-Fi is recommended.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
                viewModel.languageError?.let { err ->
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                    )
                }
            }

            itemsIndexed(languages, key = { _, item -> item.first }) { index, (code, name) ->
                // Round only the outer corners so the column reads as one grouped card.
                val shape = when {
                    languages.size == 1 -> MaterialTheme.shapes.large
                    index == 0 -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                    index == languages.lastIndex -> RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
                    else -> RectangleShape
                }
                Surface(color = MaterialTheme.colorScheme.surface, shape = shape) {
                    LanguageRow(
                        name = name,
                        downloaded = code in viewModel.downloadedLanguages,
                        downloading = code in viewModel.downloadingLanguages,
                        showDivider = index != languages.lastIndex,
                        onDownload = { viewModel.downloadLanguage(code) },
                        onDelete = { viewModel.deleteLanguage(code) }
                    )
                }
            }

            item {
                Spacer(Modifier.height(28.dp))
                SectionTitle(Icons.Default.Info, "About")
                AboutCard()
            }
        }
    }
}

@Composable
private fun AppearanceCard(themeMode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    SettingsCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = mode == themeMode, onClick = { onSelect(mode) })
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = mode == themeMode, onClick = { onSelect(mode) })
                    Spacer(Modifier.width(12.dp))
                    Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun VoiceCard(
    speechRate: Float,
    speechPitch: Float,
    viewModel: SettingsViewModel,
    tts: app.passwordmanager.translator.TtsController,
    context: android.content.Context
) {
    SettingsCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Style",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val styles = listOf("Deep" to 0.7f, "Normal" to 1.0f, "Bright" to 1.4f, "High" to 1.8f)
                styles.forEach { (label, pitch) ->
                    FilterChip(
                        selected = abs(speechPitch - pitch) < 0.06f,
                        onClick = { viewModel.setSpeechPitch(pitch) },
                        label = { Text(label, maxLines = 1) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Speed",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    String.format(Locale.US, "%.1f×", speechRate),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = speechRate,
                onValueChange = { viewModel.setSpeechRate(it) },
                valueRange = 0.5f..2.0f,
                steps = 14
            )

            Spacer(Modifier.height(4.dp))

            OutlinedButton(
                onClick = {
                    tts.speak("This is a preview of the selected voice.", "en", context, speechRate, speechPitch)
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Preview voice")
            }
        }
    }
}

@Composable
private fun AboutCard() {
    SettingsCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Smart Translator",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Version 1.0",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "On-device OCR, translation, speech and text-to-speech. Works offline once language packs are downloaded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        content()
    }
}

@Composable
private fun LanguageRow(
    name: String,
    downloaded: Boolean,
    downloading: Boolean,
    showDivider: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            when {
                downloading -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Downloading…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                downloaded -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete $name pack",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    IconButton(onClick = onDownload) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Download $name pack",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
private fun SectionTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
