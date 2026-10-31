package com.localai.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.firstOrNull
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

// ---------- Preferences ----------
object Prefs {
    const val NAME = "localai_prefs"

    const val TEMPERATURE = "temperature"
    const val TOP_P = "top_p"
    const val MAX_TOKENS = "max_tokens"
    const val CONTEXT_SIZE = "context_size"
    const val SYSTEM_PROMPT = "system_prompt"

    const val STEPS = "steps"
    const val CFG = "cfg"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val SEED = "seed"

    const val THREADS = "threads"
    const val USE_GPU = "use_gpu"
    const val KEEP_IN_MEMORY = "keep_in_memory"

    const val THEME_MODE = "theme_mode"

    const val MODEL_DIR = "model_dir"

    // --- NPU (MLLM QNN) ---
    const val USE_NPU          = "use_npu"
    const val MLLM_MODEL_PATH  = "mllm_model_path"
    const val MLLM_TOKENIZER   = "mllm_tok_path"
    const val MLLM_CONFIG      = "mllm_cfg_path"

    // --- NPU Advanced Settings dialog ---
    const val NPU_UI_SCHEDULER  = "npu_ui_scheduler"
    const val NPU_UI_KARRAS     = "npu_ui_karras"
    const val NPU_UI_ASPECT     = "npu_ui_aspect"
    const val NPU_UI_BATCH      = "npu_ui_batch"
    const val NPU_UI_DENOISE    = "npu_ui_denoise"
    const val NPU_UI_SEED       = "npu_ui_seed"
}

fun prefs(ctx: Context) = ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

// ---------- Root ----------
@Composable
fun SettingsScreen() {
    var currentSubScreen by remember { mutableStateOf<String?>(null) }

    // When in a sub-screen, intercept system back to return to the root
    // Settings list instead of exiting the app.
    BackHandler(enabled = currentSubScreen != null) {
        currentSubScreen = null
    }

    if (currentSubScreen != null) {
        SubSettingsScreen(
            title = currentSubScreen!!,
            onBack = { currentSubScreen = null }
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            BorderedSection {
                SettingsItem(Icons.Outlined.ChatBubbleOutline, "Text Chat",
                    "Customize chat behavior and context.", showDivider = true) {
                    currentSubScreen = "Text Chat"
                }
                SettingsItem(Icons.Outlined.Folder, "Storage & Files",
                    "Manage files, cache, and storage.", showDivider = true) {
                    currentSubScreen = "Storage & Files"
                }
                SettingsItem(Icons.Outlined.Download, "Download Models",
                "Fetch curated GGUFs from HuggingFace.", showDivider = true) {
                currentSubScreen = "Download Models"
            }

                SettingsItem(Icons.Outlined.Image, "Image Models",
                    "Download SD and SDXL image generation models.", showDivider = true) {
                    currentSubScreen = "Image Models"
                }

                SettingsItem(Icons.Outlined.Cloud, "Cloud API",
                    "Cloudflare Workers AI credentials.", showDivider = true) {
                    currentSubScreen = "Cloud API"
                }

                SettingsItem(Icons.Outlined.MenuBook, "Guide",
                    "Which backend runs which model.", showDivider = true) {
                    currentSubScreen = "Guide"
                }

            SettingsItem(Icons.Outlined.Palette, "Appearance",
                    "Themes, colors, layout, and display.", showDivider = true) {
                    currentSubScreen = "Appearance"
                }
                SettingsItem(Icons.Outlined.Settings, "Advanced",
                    "Advanced settings and system controls.", showDivider = false) {
                    currentSubScreen = "Advanced"
                }
            }
        }
    }
}

@Composable
fun SubSettingsScreen(title: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 14.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                    tint = MaterialTheme.colorScheme.primary)
            }
            Text(title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            when (title) {
                "Text Chat"        -> TextChatSection()
                "Storage & Files"  -> StorageSection()
                "Download Models"  -> DownloadModelsSection()
                "Image Models"     -> DownloadImageModelsSection()
                "Cloud API"        -> CloudApiSection()
                "Guide"            -> GuideSection()
                "Appearance"       -> AppearanceSection()
                "Advanced"         -> AdvancedSection()
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ---------- Text Chat ----------
@Composable
fun TextChatSection() {
    val ctx = LocalContext.current
    val p = prefs(ctx)

    var temperature by remember { mutableFloatStateOf(p.getFloat(Prefs.TEMPERATURE, 0.7f)) }
    var topP by remember { mutableFloatStateOf(p.getFloat(Prefs.TOP_P, 0.95f)) }
    var maxTokens by remember { mutableFloatStateOf(p.getInt(Prefs.MAX_TOKENS, 256).toFloat()) }
    var contextSize by remember { mutableFloatStateOf(p.getInt(Prefs.CONTEXT_SIZE, 4096).toFloat()) }
    var systemPrompt by remember {
        mutableStateOf(p.getString(Prefs.SYSTEM_PROMPT, "You are a helpful assistant.") ?: "")
    }

    val origTemp = p.getFloat(Prefs.TEMPERATURE, 0.7f)
    val origTopP = p.getFloat(Prefs.TOP_P, 0.95f)
    val origMax = p.getInt(Prefs.MAX_TOKENS, 256).toFloat()
    val origCtx = p.getInt(Prefs.CONTEXT_SIZE, 4096).toFloat()
    val origSys = p.getString(Prefs.SYSTEM_PROMPT, "You are a helpful assistant.") ?: ""

    val isDirty = temperature != origTemp ||
                  topP != origTopP ||
                  maxTokens != origMax ||
                  contextSize != origCtx ||
                  systemPrompt != origSys

    SectionLabel("SAMPLING")
    BorderedSection {
        SettingSlider("Temperature", "Higher = more creative, lower = more focused.",
            temperature, 0f..2f, showDivider = true,
            onChange = { temperature = it })

        SettingSlider("Top-P", "Nucleus sampling. Lower = more deterministic.",
            topP, 0f..1f, showDivider = true,
            onChange = { topP = it })

        SettingSlider("Max tokens", "Maximum length of generated output.",
            maxTokens, 128f..4096f, steps = 31, showDivider = true,
            onChange = { maxTokens = it })

        SettingSlider("Context size", "Total tokens kept in memory.",
            contextSize, 512f..8192f, steps = 15, showDivider = false,
            onChange = { contextSize = it })
    }

    Spacer(Modifier.height(20.dp))
    SectionLabel("SYSTEM PROMPT")
    BorderedSection {
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            minLines = 3, maxLines = 6
        )
    }

    Spacer(Modifier.height(20.dp))
    SectionLabel("ACTIONS")
    BorderedSection {
        Column(Modifier.padding(12.dp)) {
            Button(
                onClick = {
                    p.edit()
                        .putFloat(Prefs.TEMPERATURE, temperature)
                        .putFloat(Prefs.TOP_P, topP)
                        .putInt(Prefs.MAX_TOKENS, maxTokens.toInt())
                        .putInt(Prefs.CONTEXT_SIZE, contextSize.toInt())
                        .putString(Prefs.SYSTEM_PROMPT, systemPrompt)
                        .commit()
                    Toast.makeText(ctx, "Chat settings saved", Toast.LENGTH_SHORT).show()
                },
                enabled = isDirty,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Save", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    temperature = 0.7f
                    topP = 0.95f
                    maxTokens = 256f
                    contextSize = 4096f
                    systemPrompt = "You are a helpful assistant."
                    p.edit()
                        .putFloat(Prefs.TEMPERATURE, 0.7f)
                        .putFloat(Prefs.TOP_P, 0.95f)
                        .putInt(Prefs.MAX_TOKENS, 256)
                        .putInt(Prefs.CONTEXT_SIZE, 4096)
                        .putString(Prefs.SYSTEM_PROMPT, "You are a helpful assistant.")
                        .commit()
                    Toast.makeText(ctx, "Reset to defaults", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) { Text("Reset to defaults", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
fun StorageSection() {
    val ctx = LocalContext.current
    val p = prefs(ctx)

    val modelDir = p.getString(Prefs.MODEL_DIR,
        "/sdcard/Download/AI-Models") ?: "/sdcard/Download/AI-Models"

    val hasAllFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        Environment.isExternalStorageManager() else true

    val dir = File(modelDir)
    val files = if (hasAllFiles) {
        dir.listFiles()?.filter {
            it.name.endsWith(".gguf") || it.name.endsWith(".safetensors")
        } ?: emptyList()
    } else emptyList()
    val totalBytes = files.sumOf { it.length() }
    val cacheBytes = dirSize(ctx.cacheDir)
    val dataBytes = dirSize(ctx.filesDir.parentFile)

    SectionLabel("MODEL FOLDER")
    BorderedSection {
        SettingTextRow("Path", modelDir, showDivider = true)
        SettingTextRow("All-files access",
            if (hasAllFiles) "Granted" else "Not granted", showDivider = false)
    }

    if (!hasAllFiles) {
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .apply { data = Uri.parse("package:${ctx.packageName}") }
                    try { ctx.startActivity(intent) } catch (_: Throwable) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Grant all-files access", fontWeight = FontWeight.SemiBold)
        }
    }

    Spacer(Modifier.height(20.dp))
    SectionLabel("MODEL STORAGE")
    BorderedSection {
        SettingTextRow("Files", "${files.size}", showDivider = true)
        SettingTextRow("Total size", formatBytes(totalBytes), showDivider = false)
    }

    Spacer(Modifier.height(20.dp))
    SectionLabel("APP STORAGE")
    BorderedSection {
        SettingTextRow("Cache", formatBytes(cacheBytes), showDivider = true)
        SettingTextRow("Data", formatBytes(dataBytes), showDivider = false)
    }

    Spacer(Modifier.height(20.dp))
    SectionLabel("MAINTENANCE")
    BorderedSection {
        Column(Modifier.padding(12.dp)) {
            OutlinedButton(
                onClick = {
                    ctx.cacheDir.deleteRecursively()
                    ctx.cacheDir.mkdirs()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text("Clear cache", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { /* TODO */ },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text("Export logs", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun dirSize(dir: File?): Long {
    if (dir == null || !dir.exists()) return 0L
    return try {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Throwable) { 0L }
}

// ---------- Download Models ----------
@Composable
fun DownloadModelsSection() {
    val viewModel: LlamaViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Text(
        "Tap the download button to fetch a model. It will load automatically when done.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
    )

    ModelSection(
        label = "CPU MODELS",
        models = com.localai.app.ui.ModelCatalog.cpuModels,
        viewModel = viewModel,
        state = state,
        blurb = "Run on any Android phone via llama.cpp. Slower, but works everywhere.",
    )

    Spacer(Modifier.height(20.dp))

    ModelSection(
        label = "NPU (HEXAGON)",
        models = com.localai.app.ui.ModelCatalog.npuHexagonModels,
        viewModel = viewModel,
        state = state,
        blurb = "Run on the Hexagon NPU (Snapdragon 8 Gen 3). Q4_0 / Q4_K_M quants only.",
    )

    Spacer(Modifier.height(20.dp))

    ModelSection(
        label = "NPU (LITERT)",
        models = com.localai.app.ui.ModelCatalog.npuLiteRtModels,
        viewModel = viewModel,
        state = state,
        blurb = "Google's LiteRT runtime. .litertlm bundles only — cannot load GGUFs.",
    )

    // ---- Prominent Text Model Download Dialog ----
    val activeDownload = state.downloads.values.firstOrNull { 
        !it.isComplete && it.error == null && it.progress < 1f 
    }
    
    if (activeDownload != null) {
        val modelName = (com.localai.app.ui.ModelCatalog.cpuModels + 
                         com.localai.app.ui.ModelCatalog.npuHexagonModels + 
                         com.localai.app.ui.ModelCatalog.npuLiteRtModels)
                         .firstOrNull { it.id == activeDownload.modelId }?.displayName ?: "Model"
                         
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { /* Do not allow dismiss */ },
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Downloading $modelName",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(20.dp))
                    
                    LinearProgressIndicator(
                        progress = { activeDownload.progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    Spacer(Modifier.height(12.dp))
                    
                    val totalMb = if (activeDownload.bytesTotal > 0) activeDownload.bytesTotal / 1048576 else 0
                    val readMb = activeDownload.bytesRead / 1048576
                    val percent = (activeDownload.progress * 100).toInt()
                    
                    Text(
                        if (totalMb > 0) "$percent% - $readMb MB / $totalMb MB" else "$percent% - $readMb MB",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(16.dp))
                    
                    Text(
                        "Download continues in the background. You can switch to other apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    
                    TextButton(
                        onClick = { viewModel.cancelDownload(activeDownload.modelId) },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Cancel Download")
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    SectionLabel("FIND MORE MODELS")
    Text(
        "Browse these sites to find additional GGUFs or .litertlm files. Anything you download can be loaded via Browse model in the Chat picker.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
    )

    ExternalLinkRow(
        title = "Hugging Face — GGUF search",
        subtitle = "The largest collection of community models.",
        url = "https://huggingface.co/models?search=gguf",
    )
    ExternalLinkRow(
        title = "Hugging Face — bartowski",
        subtitle = "Reliable GGUF quantizer. Best-practice Q4_K_M / Q4_0 quants.",
        url = "https://huggingface.co/bartowski",
    )
    ExternalLinkRow(
        title = "Hugging Face — unsloth",
        subtitle = "High-quality quants, active with new model releases.",
        url = "https://huggingface.co/unsloth",
    )
    ExternalLinkRow(
        title = "Hugging Face — LiteRT community",
        subtitle = "Official .litertlm bundles for the NPU (LiteRT) runtime.",
        url = "https://huggingface.co/litert-community",
    )
    ExternalLinkRow(
        title = "ModelScope",
        subtitle = "Chinese community — mirrors many Western models.",
        url = "https://modelscope.cn/models?name=gguf",
    )
    ExternalLinkRow(
        title = "Ollama library",
        subtitle = "Browse model cards; each links to the upstream GGUF repo.",
        url = "https://ollama.com/library",
    )

    Spacer(Modifier.height(20.dp))
    SectionLabel("NOTES")
    Text(
        "\u2022 Files are 0.4-5 GB. Use Wi-Fi.\n" +
        "\u2022 Download continues while this screen is open.\n" +
        "\u2022 Once installed, the model appears in the Chat model picker.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun ExternalLinkRow(
    title: String,
    subtitle: String,
    url: String,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Surface(
        onClick = {
            try {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                )
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } catch (_: Throwable) {}
        },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(
                Icons.Outlined.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ModelSection(
    label: String,
    models: List<com.localai.app.ui.DownloadableModel>,
    viewModel: LlamaViewModel,
    state: com.localai.app.ui.UiState,
    blurb: String,
) {
    SectionLabel(label)
    Text(
        blurb,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
    )
    models.forEach { model ->
        val dl = state.downloads[model.id]
        val alreadyHave = viewModel.isCatalogModelInstalled(model)
        DownloadModelRow(
            model = model,
            downloadState = dl,
            isInstalled = alreadyHave,
            onDownload = { viewModel.startDownload(model) },
            onCancel = { viewModel.cancelDownload(model.id) }
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun DownloadModelRow(
    model: DownloadableModel,
    downloadState: DownloadState?,
    isInstalled: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(model.sizeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(10.dp))

                when {
                    downloadState != null && !downloadState.isComplete && downloadState.error == null -> {
                        TextButton(onClick = onCancel) {
                            Text("Cancel",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                    isInstalled -> {
                        Text("Installed",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    else -> {
                        FilledIconButton(
                            onClick = onDownload,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Outlined.Download, "Download",
                                modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            if (downloadState != null && !downloadState.isComplete) {
                Spacer(Modifier.height(10.dp))
                if (downloadState.bytesTotal > 0) {
                    LinearProgressIndicator(
                        progress = { downloadState.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(downloadState.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (downloadState?.error != null) {
                Spacer(Modifier.height(4.dp))
                Text(downloadState.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ---------- Appearance ----------
@Composable
fun AppearanceSection() {
    val ctx = LocalContext.current
    val p = prefs(ctx)
    var mode by remember { mutableStateOf(p.getString(Prefs.THEME_MODE, "System") ?: "System") }

    SectionLabel("THEME")
    BorderedSection {
        listOf("System", "Light", "Dark", "Dynamic (Material You)", "Monochrome").forEachIndexed { i, option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        mode = option
                        p.edit().putString(Prefs.THEME_MODE, option).apply()
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                RadioButton(selected = mode == option, onClick = {
                    mode = option
                    p.edit().putString(Prefs.THEME_MODE, option).apply()
                })
                Spacer(Modifier.width(12.dp))
                Text(option, style = MaterialTheme.typography.bodyLarge)
            }
            if (i != 4) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.5.dp
                )
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    BorderedSection {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                "Dynamic theme adapts to your wallpaper on Android 12+. The app applies changes instantly.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AdvancedSection() {
    val ctx = LocalContext.current
    val p = prefs(ctx)

    var threads by remember { mutableFloatStateOf(p.getInt(Prefs.THREADS, 4).toFloat()) }
    var useGpu by remember { mutableStateOf(p.getBoolean(Prefs.USE_GPU, false)) }
    var keepInMem by remember { mutableStateOf(p.getBoolean(Prefs.KEEP_IN_MEMORY, true)) }

    val origThreads = p.getInt(Prefs.THREADS, 4).toFloat()
    val origGpu = p.getBoolean(Prefs.USE_GPU, false)
    val origKeep = p.getBoolean(Prefs.KEEP_IN_MEMORY, true)

    val isDirty = threads != origThreads || useGpu != origGpu || keepInMem != origKeep

    SectionLabel("PERFORMANCE")
    BorderedSection {
        SettingSlider("Threads", "Fewer = less heat. 4 recommended.",
            threads, 1f..8f, steps = 7, showDivider = true,
            onChange = { threads = it })

        SettingToggle("Use GPU", "Adreno GPU acceleration. Requires model reload.",
            useGpu, showDivider = true,
            onChange = { useGpu = it })

        SettingToggle("Keep model in memory", "Faster responses, more RAM used.",
            keepInMem, showDivider = false,
            onChange = { keepInMem = it })
    }

    Spacer(Modifier.height(20.dp))
    SectionLabel("ACTIONS")
    BorderedSection {
        Column(Modifier.padding(12.dp)) {
            Button(
                onClick = {
                    p.edit()
                        .putInt(Prefs.THREADS, threads.toInt())
                        .putBoolean(Prefs.USE_GPU, useGpu)
                        .putBoolean(Prefs.KEEP_IN_MEMORY, keepInMem)
                        .commit()
                    Toast.makeText(ctx, "Advanced settings saved", Toast.LENGTH_SHORT).show()
                },
                enabled = isDirty,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Save", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    p.edit().clear().commit()
                    threads = 4f
                    useGpu = false
                    keepInMem = true
                    Toast.makeText(ctx, "All settings reset", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) { Text("Reset all settings", fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ---------- Reusable atoms ----------

/** A bordered, rounded container that groups related rows. */
@Composable
fun BorderedSection(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

/** A subtle divider between rows inside a BorderedSection. */
@Composable
fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 0.5.dp,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowBack, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp).rotate(180f))
    }
    if (showDivider) RowDivider()
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingTextRow(label: String, value: String, showDivider: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
    if (showDivider) RowDivider()
}

@Composable
fun SettingSlider(
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    showDivider: Boolean,
    onChange: (Float) -> Unit
) {
    // remember(value) re-seeds the local state whenever the incoming value
    // changes — so drags are smooth AND external resets propagate.
    var current by remember(value) { mutableFloatStateOf(value) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (current % 1f == 0f) current.toInt().toString()
                else "%.2f".format(current),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Slider(
            value = current,
            onValueChange = { current = it; onChange(it) },
            valueRange = range,
            steps = steps
        )
    }
    if (showDivider) RowDivider()
}

@Composable
fun SettingToggle(
    title: String,
    subtitle: String,
    value: Boolean,
    showDivider: Boolean,
    onChange: (Boolean) -> Unit
) {
    var current by remember { mutableStateOf(value) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = current, onCheckedChange = {
            current = it
            onChange(it)
        })
    }
    if (showDivider) RowDivider()
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824f)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576f)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

// ---------- Image Models (SD / SDXL) ----------
@Composable
fun DownloadImageModelsSection() {
    val viewModel: ImageViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Text(
        "Tap Download to fetch a model. It will install automatically when done.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
    )

    ImageModelCatalogSection(
        state = state,
        onDownload = { viewModel.downloadImageModel(it) },
        onCancel = { viewModel.cancelImageDownload(it) },
    )

    // ---- Prominent Download Dialog ----
    val activeDownload = state.downloads.values.firstOrNull { 
        !it.isComplete && it.error == null && it.progress < 1f 
    }
    
    if (activeDownload != null) {
        val model = (com.localai.app.ui.ImageModelCatalog.cpuModels + com.localai.app.ui.ImageModelCatalog.npuModels).firstOrNull { it.id == activeDownload.modelId }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { /* Do not allow dismiss by clicking outside */ },
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Downloading ${model?.displayName ?: "Model"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(20.dp))
                    
                    LinearProgressIndicator(
                        progress = { activeDownload.progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    Spacer(Modifier.height(12.dp))
                    
                    val totalMb = if (activeDownload.bytesTotal > 0) activeDownload.bytesTotal / 1048576 else 0
                    val readMb = activeDownload.bytesRead / 1048576
                    val percent = (activeDownload.progress * 100).toInt()
                    
                    Text(
                        if (totalMb > 0) "$percent% - $readMb MB / $totalMb MB" else "$percent% - $readMb MB",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(16.dp))
                    
                    Text(
                        "Download continues in the background. You can switch to other apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    
                    TextButton(
                        onClick = { viewModel.cancelImageDownload(activeDownload.modelId) },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Cancel Download")
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    SectionLabel("NOTES")
    Text(
        "\u2022 CPU models work on any device, but are slow.\n" +
        "\u2022 NPU models need Snapdragon 8 Gen 3 or newer.\n" +
        "\u2022 DMD2 variants are 3x faster (6 steps vs 20).\n" +
        "\u2022 Once installed, the model appears in the Generate tab's model picker.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp)
    )
}

// ---------- Cloud API (Cloudflare Workers AI) ----------
@Composable
fun CloudApiSection() {
    val ctx = LocalContext.current
    val vm: com.localai.app.ui.CloudViewModel = hiltViewModel()

    var accountId by remember { mutableStateOf("") }
    var apiToken by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    // Load existing values once
    LaunchedEffect(Unit) {
        val repo = com.localai.app.data.UserPreferencesRepository(ctx.applicationContext)
        accountId = repo.cfAccountIdFlow.firstOrNull() ?: ""
        apiToken  = repo.cfApiTokenFlow.firstOrNull() ?: ""
    }

    Text(
        "Paste your Cloudflare Account ID and API Token. These are stored privately on your device — they are never sent anywhere except to Cloudflare itself.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
    )

    OutlinedTextField(
        value = accountId,
        onValueChange = { accountId = it; saved = false },
        label = { Text("Account ID") },
        placeholder = { Text("32-character hex string") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = apiToken,
        onValueChange = { apiToken = it; saved = false },
        label = { Text("API Token") },
        placeholder = { Text("Bearer token from Cloudflare") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                val app = ctx.applicationContext as android.app.Application
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val repo = com.localai.app.data.UserPreferencesRepository(app)
                    repo.saveCfCredentials(accountId.trim(), apiToken.trim())
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        saved = true
                        vm.refreshCredentials()
                        android.widget.Toast.makeText(ctx, "Credentials saved", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.weight(1f),
        ) { Text(if (saved) "Saved ✓" else "Save") }
        OutlinedButton(
            onClick = {
                accountId = ""
                apiToken = ""
                val app = ctx.applicationContext as android.app.Application
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val repo = com.localai.app.data.UserPreferencesRepository(app)
                    repo.clearCfCredentials()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        vm.refreshCredentials()
                    }
                }
            },
            modifier = Modifier.weight(1f),
        ) { Text("Clear") }
    }

    Spacer(Modifier.height(20.dp))
    SectionLabel("HOW TO GET KEYS")
    Text(
        "\u2022 Account ID: dash.cloudflare.com → sidebar → Account details\n" +
        "\u2022 API Token: dash.cloudflare.com/profile/api-tokens → Create Token → Workers AI template\n" +
        "\u2022 Paste both here, then use the Cloud tab.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp)
    )
}

// ---------- Guide (compatibility matrix) ----------
@Composable
fun GuideSection() {
    Text(
        "Which backend runs which model.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
    )

    GuideContent()

    Spacer(Modifier.height(20.dp))
    SectionLabel("ABOUT")
    Text(
        "LocalAI runs models entirely on your device. No internet connection is required for CPU, Hexagon, or LiteRT backends — only the Cloudflare (Cloud) tab and Web Search use the network.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp)
    )
}
