package app.passwordmanager.translator

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.nl.translate.TranslateLanguage
import java.io.File
import java.util.Locale

/** On-screen translation preview cap; the full text is always available via Copy/Share/PDF. */
private const val TRANSLATION_PREVIEW_CHARS = 4000

/** Max text put on the clipboard or in a share intent — beyond this the Binder transaction can crash. */
private const val SAFE_IPC_CHARS = 100_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(
    viewModel: TranslatorViewModel,
    speechRate: Float = 1f,
    speechPitch: Float = 1f
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val tts = rememberTtsController()

    var tempUri by remember { mutableStateOf<Uri?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { viewModel.processImage(it) } }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean -> if (success) tempUri?.let { viewModel.processImage(it) } }

    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.processPdf(it) } }

    var inputText by remember { mutableStateOf("") }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                inputText = spoken
                viewModel.processText(spoken)
            }
        }
    }

    fun launchSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now…")
            // Hint the recognizer with the chosen source language (unless it's auto-detect).
            if (viewModel.sourceLanguage != TranslatorViewModel.AUTO_LANGUAGE) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, viewModel.sourceLanguage)
            }
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Speech recognition isn't available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    fun createTempUri(): Uri {
        val directory = File(context.cacheDir, "Images")
        if (!directory.exists()) directory.mkdirs()
        // Best-effort cleanup so old camera captures don't accumulate in the cache forever.
        directory.listFiles()?.forEach { it.delete() }
        val file = File.createTempFile("camera_", ".jpg", directory)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    // All languages ML Kit Translate supports, with readable English names, sorted by name.
    val targetLanguages = remember {
        TranslateLanguage.getAllLanguages()
            .map { code ->
                code to Locale(code).getDisplayLanguage(Locale.ENGLISH)
                    .ifBlank { code }
                    .replaceFirstChar { it.uppercase() }
            }
            .sortedBy { it.second }
    }
    val sourceLanguages = remember {
        listOf(TranslatorViewModel.AUTO_LANGUAGE to "Auto Detect") + targetLanguages
    }

    // Keep the last error around so it stays visible while the banner animates out.
    var lastError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel.errorMessage) { viewModel.errorMessage?.let { lastError = it } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        GradientHeader()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            CaptureCard(
                onPickImage = { imagePicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                onCamera = {
                    val uri = createTempUri()
                    tempUri = uri
                    cameraLauncher.launch(uri)
                },
                onPickPdf = { pdfPicker.launch("application/pdf") }
            )

            Spacer(Modifier.height(16.dp))

            TextInputCard(
                value = inputText,
                onValueChange = { inputText = it },
                onMic = { launchSpeech() },
                onTranslate = { viewModel.processText(inputText) }
            )

            Spacer(Modifier.height(16.dp))

            LanguageBar(viewModel, sourceLanguages, targetLanguages)

            AnimatedVisibility(visible = viewModel.originalText.isNotEmpty()) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = { viewModel.retranslate() },
                        enabled = !viewModel.isProcessing,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.Autorenew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Retranslate")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            AnimatedVisibility(
                visible = viewModel.errorMessage != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    ErrorBanner(lastError ?: "", onRetry = { viewModel.retryLastAction() })
                    Spacer(Modifier.height(16.dp))
                }
            }

            AnimatedVisibility(
                visible = viewModel.isProcessing,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ProcessingView(
                    progress = viewModel.progress,
                    status = viewModel.statusMessage,
                    detail = viewModel.statusDetail,
                    onCancel = { viewModel.cancelProcessing() }
                )
            }

            AnimatedVisibility(
                visible = !viewModel.isProcessing && viewModel.originalText.isNotEmpty(),
                enter = fadeIn(tween(350)) + expandVertically(tween(350)),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    // Show only a preview on screen — a full PDF translation can be millions of
                    // characters, which makes the Text layout (and tab transitions) lag. Remembered so
                    // it isn't re-substringed on every recomposition. Copy/Share/PDF use the full text.
                    val translationPreview = remember(viewModel.translatedText) {
                        val full = viewModel.translatedText
                        if (full.length > TRANSLATION_PREVIEW_CHARS) {
                            full.take(TRANSLATION_PREVIEW_CHARS) +
                                "\n\n… (preview only — use Copy/Share or the saved PDF for the full text)"
                        } else {
                            full
                        }
                    }
                    TranslationCard(
                        text = translationPreview,
                        isSpeaking = tts.isSpeaking,
                        onToggleSpeak = { tts.toggle(viewModel.translatedText, viewModel.targetLanguage, context, speechRate, speechPitch) },
                        onCopy = {
                            // Clipboard goes over Binder — cap very large text to avoid TransactionTooLargeException.
                            val full = viewModel.translatedText
                            val copied = if (full.length > SAFE_IPC_CHARS) full.take(SAFE_IPC_CHARS) else full
                            clipboardManager.setText(AnnotatedString(copied))
                            val msg = if (full.length > SAFE_IPC_CHARS)
                                "Copied first $SAFE_IPC_CHARS characters" else "Copied to clipboard"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        onShare = {
                            val full = viewModel.translatedText
                            val pdfUri = viewModel.translatedPdfUri
                            if (full.length > SAFE_IPC_CHARS && pdfUri != null) {
                                // Too big to put in an intent extra — share the saved PDF instead.
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, pdfUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share translated PDF"))
                            } else {
                                val text = if (full.length > SAFE_IPC_CHARS) full.take(SAFE_IPC_CHARS) else full
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share translation"))
                            }
                        }
                    )
                    viewModel.translatedPdfUri?.let { pdfUri ->
                        Spacer(Modifier.height(16.dp))
                        PdfSavedCard(
                            onOpen = {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(pdfUri, "application/pdf")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (e: ActivityNotFoundException) {
                                    Toast.makeText(context, "No PDF viewer installed", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onShare = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, pdfUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share translated PDF"))
                            }
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun PdfSavedCard(onOpen: () -> Unit, onShare: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Translated PDF saved to Downloads",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open")
                }
                FilledTonalButton(onClick = onShare, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
            }
        }
    }
}

@Composable
private fun GradientHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary
                    )
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Translate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Smart Translator",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    "Scan & translate any text",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun TextInputCard(
    value: String,
    onValueChange: (String) -> Unit,
    onMic: () -> Unit,
    onTranslate: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Type or speak",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Enter text to translate") },
                shape = MaterialTheme.shapes.medium,
                minLines = 2,
                maxLines = 6,
                trailingIcon = {
                    IconButton(onClick = onMic) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Speak",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onTranslate,
                enabled = value.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Translate")
            }
        }
    }
}

@Composable
private fun CaptureCard(onPickImage: () -> Unit, onCamera: () -> Unit, onPickPdf: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Capture source",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CaptureButton(Icons.Default.Image, "Image", Modifier.weight(1f), onPickImage)
                CaptureButton(Icons.Default.PhotoCamera, "Camera", Modifier.weight(1f), onCamera)
                CaptureButton(Icons.Default.Description, "PDF", Modifier.weight(1f), onPickPdf)
            }
        }
    }
}

@Composable
private fun CaptureButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun LanguageBar(
    viewModel: TranslatorViewModel,
    sourceLanguages: List<Pair<String, String>>,
    targetLanguages: List<Pair<String, String>>
) {
    var showSourceMenu by remember { mutableStateOf(false) }
    var showTargetMenu by remember { mutableStateOf(false) }
    var swapRotation by remember { mutableStateOf(0f) }
    val rotation by animateFloatAsState(swapRotation, animationSpec = tween(450), label = "swap")

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(Modifier.weight(1f)) {
            LanguagePill(
                label = sourceLanguages.find { it.first == viewModel.sourceLanguage }?.second ?: "Source",
                onClick = { showSourceMenu = true }
            )
            DropdownMenu(expanded = showSourceMenu, onDismissRequest = { showSourceMenu = false }) {
                sourceLanguages.forEach { (code, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        viewModel.updateLanguages(code, viewModel.targetLanguage)
                        showSourceMenu = false
                    })
                }
            }
        }

        IconButton(
            onClick = {
                swapRotation += 180f
                viewModel.swapLanguages()
            },
            enabled = viewModel.sourceLanguage != TranslatorViewModel.AUTO_LANGUAGE
        ) {
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription = "Swap languages",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.rotate(rotation)
            )
        }

        Box(Modifier.weight(1f)) {
            LanguagePill(
                label = targetLanguages.find { it.first == viewModel.targetLanguage }?.second ?: "Target",
                onClick = { showTargetMenu = true }
            )
            DropdownMenu(expanded = showTargetMenu, onDismissRequest = { showTargetMenu = false }) {
                targetLanguages.forEach { (code, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        viewModel.updateLanguages(viewModel.sourceLanguage, code)
                        showTargetMenu = false
                    })
                }
            }
        }
    }
}

@Composable
private fun LanguagePill(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ProcessingView(progress: Float, status: String?, detail: String?, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            // Phase title from the ViewModel ("Reading PDF" → "Translating PDF" → "Processing the
            // PDF"); falls back to a generic message for the image/text flows that set no status.
            Text(
                status ?: "Recognizing & translating…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (progress > 0f) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onCancel, shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun TranslationCard(
    text: String,
    isSpeaking: Boolean,
    onToggleSpeak: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary
                    )
                )
            )
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Translation",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Row {
                        IconButton(onClick = onToggleSpeak) {
                            Icon(
                                if (isSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                                contentDescription = if (isSpeaking) "Stop" else "Listen",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        IconButton(onClick = onCopy) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        IconButton(onClick = onShare) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Share",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onRetry,
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Try again")
            }
        }
    }
}
