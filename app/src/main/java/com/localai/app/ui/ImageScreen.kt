package com.localai.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageScreen(viewModel: ImageViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAdvanced by remember { mutableStateOf(false) }
    // NPU mode toggle — read once from SharedPreferences on first compose
    val ctxForPrefs = LocalContext.current
    var npuMode by remember(ctxForPrefs) {
        mutableStateOf(
            prefs(ctxForPrefs).getBoolean("use_npu_image", false)
        )
    }
    var npuModelDir by remember(ctxForPrefs) {
        mutableStateOf(
            prefs(ctxForPrefs).getString("npu_model_dir", "") ?: ""
        )
    }
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }

    val sdModelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.loadSDModel(uri) }

    val npuZipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importNpuModel(uri) { path, backend ->
                if (path != null) {
                    npuModelDir = path
                    prefs(ctxForPrefs).edit()
                        .putString("npu_model_dir", path)
                        .putString("npu_backend_type", backend)
                        .apply()
                }
            }
        }
    }

    val loraPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.loadLoRA(uri) }

    Scaffold(
        topBar = {
            ImageHeader()
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- Unified Image Model Panel ----
            ImgBorderedCard {
                Column(Modifier.padding(16.dp)) {
                    Text("Image Generation Model",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    
                    // Mode Selector Row
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (npuMode) "NPU Mode (Snapdragon 8 Gen 3+)" else "CPU Mode (Any device, slower)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            npuMode = !npuMode
                            prefs(ctxForPrefs).edit().putBoolean("use_npu_image", npuMode).apply()
                        }) {
                            Text(if (npuMode) "Switch to CPU" else "Turn on NPU")
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Current: " + (if (npuMode) {
                            if (npuModelDir.isEmpty()) "(none)" else File(npuModelDir).name
                        } else {
                            state.sdModelName ?: "(none)"
                        }), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                           modifier = Modifier.weight(1f))

                        if (npuMode && npuModelDir.isNotEmpty()) {
                            TextButton(onClick = {
                                viewModel.deleteNpuModel()
                                npuModelDir = ""
                            }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    if (npuMode) {
                        OutlinedButton(
                            onClick = { npuZipPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("Import NPU model (.zip)") }

                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            FilledTonalButton(
                                onClick = { viewModel.startNpuService(npuModelDir) },
                                enabled = npuModelDir.isNotEmpty(),
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) { Text("Start backend") }
                            OutlinedButton(
                                onClick = { viewModel.stopNpuService() },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) { Text("Stop") }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { showPicker = true },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("Select CPU Model") }

                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { loraPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("Load LoRA (optional)") }
                    }

                    if (state.importProgress >= 0f) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { state.importProgress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(state.importStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ---- Status and Preview ----
            if (state.isGenerating) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp).padding(bottom = 8.dp)
                )
            }
            if (state.status.isNotEmpty() && !state.status.startsWith("Done")) {
                Text(
                    state.status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            if (state.isGenerating && state.totalSteps > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Step ${state.currentStep} / ${state.totalSteps}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            ImagePreview(image = state.image, isGenerating = state.isGenerating)

            // ---- Actions row: Save / Share / Regenerate / More ----
            if (state.image != null) {
                ImgBorderedCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ImageActionButton(
                            icon = Icons.Default.Download,
                            label = "Save",
                            onClick = { saveBitmapToGallery(context, state.image!!) }
                        )
                        ImageActionButton(
                            icon = Icons.Default.Share,
                            label = "Share",
                            onClick = { shareImage(context, state.image!!) }
                        )
                        ImageActionButton(
                            icon = Icons.Default.Refresh,
                            label = "Regenerate",
                            onClick = { if (npuMode && npuModelDir.isNotEmpty()) viewModel.generateNpu() else viewModel.generate() }
                        )
                        MoreActionButton(
                            onCopyPrompt = {
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("Prompt", state.prompt))
                                Toast.makeText(context, "Prompt copied", Toast.LENGTH_SHORT).show()
                            },
                            onCopyFullSettings = {
                                val text = buildString {
                                    appendLine("Prompt: ${state.prompt}")
                                    appendLine("Negative: ${state.negativePrompt}")
                                    appendLine("Model: ${state.sdModelName ?: "none"}")
                                    appendLine("LoRA: ${state.loraName ?: "none"} @ ${"%.2f".format(state.loraScale)}")
                                    appendLine("Steps: ${state.steps}")
                                    appendLine("CFG: ${state.cfg}")
                                    appendLine("Size: ${state.width}×${state.height}")
                                    appendLine("Seed: ${state.seed}")
                                }
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("Settings", text))
                                Toast.makeText(context, "Settings copied", Toast.LENGTH_SHORT).show()
                            },
                            onOpenPicturesFolder = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(
                                            android.net.Uri.parse("content://media/external/images/media"),
                                            "image/*"
                                        )
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "No gallery app found", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDeleteImage = { viewModel.clearImage() }
                        )
                    }
                }
            }

            // ---- PROMPT section ----
            ImgSectionLabel("PROMPT")
            PromptField(
                value = state.prompt,
                onValueChange = viewModel::setPrompt,
                placeholder = "Describe your image…",
                maxChars = 1000,
                enabled = !state.isGenerating
            )

            // ---- Negative prompt ----
            ImgSectionLabel("NEGATIVE PROMPT")
            PromptField(
                value = state.negativePrompt,
                onValueChange = viewModel::setNegative,
                placeholder = "What to avoid…",
                maxChars = 1000,
                enabled = !state.isGenerating
            )

            // ---- Generate button (prominent) ----
            Button(
                onClick = { 
                    if (state.isGenerating) {
                        viewModel.cancelGeneration()
                    } else {
                        if (npuMode) viewModel.generateNpu() else viewModel.generate()
                    }
                },
                enabled = if (state.isGenerating) true else (state.sdModelLoaded || (npuMode && npuModelDir.isNotEmpty())) && state.prompt.isNotBlank(),
                colors = if (state.isGenerating) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else androidx.compose.material3.ButtonDefaults.buttonColors(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                if (state.isGenerating) {
                    Icon(androidx.compose.material.icons.Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    if (state.isGenerating) "Cancel" else "Generate",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // ---- Parameters (tappable → Advanced Settings) ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvanced = true }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ImgSectionLabel("PARAMETERS")
                Spacer(Modifier.weight(1f))
                Text("Advanced ▸",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
            }

            // ---- Resolution chips ----
            ImgSectionLabel("RESOLUTION")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                listOf(384, 512, 640, 1024).forEach { size ->
                    ResolutionChip(
                        label = "$size×$size",
                        selected = state.width == size,
                        onClick = {
                            viewModel.setWidth(size)
                            viewModel.setHeight(size)
                        }
                    )
                }
            }

            // ---- LoRA status ----
            if (state.loraName != null) {
                ImgBorderedCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("LoRA",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                state.loraName!!,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1
                            )
                        }
                        TextButton(onClick = { viewModel.clearLora() }) {
                            Text("Remove")
                        }
                    }
                }
            }

            // ---- Error ----
            state.error?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp
                        )
                        TextButton(onClick = { viewModel.dismissError() }) { Text("OK") }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- Model picker dialog ----
    if (showPicker) {
        val sdModels = remember(showPicker) { viewModel.listInstalledSDModels() }
        val loras    = remember(showPicker) { viewModel.listInstalledLoRAs() }

        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Choose model") },
            text = {
                Column {
                    if (sdModels.isEmpty() && loras.isEmpty()) {
                        Text("No models installed yet.\nTap \"Browse CPU Model\" to add one.")
                    } else {
                        if (sdModels.isNotEmpty()) {
                            Text("SD Models", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.height(6.dp))
                            sdModels.forEach { f ->
                                ModelRow(
                                    file = f,
                                    isCurrent = state.sdModelName == f.name,
                                    onSelect = {
                                        if (f.isDirectory) {
                                            // It's an MNN folder. Force NPU mode.
                                            npuMode = true
                                            npuModelDir = f.absolutePath
                                            prefs(ctxForPrefs).edit()
                                                .putBoolean("use_npu_image", true)
                                                .putString("npu_model_dir", f.absolutePath)
                                                .apply()
                                        } else {
                                            // It's a .safetensors file. Force CPU mode.
                                            npuMode = false
                                            npuModelDir = ""
                                            prefs(ctxForPrefs).edit()
                                                .putBoolean("use_npu_image", false)
                                                .remove("npu_model_dir")
                                                .apply()
                                        }
                                        viewModel.loadInstalledSDModel(f)
                                        showPicker = false
                                    },
                                    onDelete = {
                                        viewModel.deleteInstalledFile(f)
                                        showPicker = false
                                    }
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        if (loras.isNotEmpty()) {
                            Text("LoRAs", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.height(6.dp))
                            loras.forEach { f ->
                                ModelRow(
                                    file = f,
                                    isCurrent = state.loraName == f.name,
                                    onSelect = {
                                        viewModel.loadInstalledLoRA(f)
                                        showPicker = false
                                    },
                                    onDelete = {
                                        viewModel.deleteInstalledFile(f)
                                        showPicker = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPicker = false
                    sdModelPicker.launch(arrayOf("*/*"))
                }) { Text("Browse CPU Model") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPicker = false
                    loraPicker.launch(arrayOf("*/*"))
                }) { Text("Browse LoRA") }
            }
        )
    }


            // Advanced Settings dialog hook
            if (showAdvanced) {
                AdvancedSettingsDialog(
                    onDismiss = { showAdvanced = false },
                    initialAspect = state.aspectRatio,
                    initialScheduler = state.scheduler,
                    initialKarras = state.karras,
                    initialSteps = state.steps,
                    initialCfg = state.cfg,
                    initialBatch = state.batchCount,
                    initialDenoise = state.denoiseStrength,
                    initialSeed = "",
                    width = state.width,
                    height = state.height,
                    onConfirm = { aspect, sched, karras, steps, cfg, batch, denoise, seed ->
                        viewModel.applyAdvanced(aspect, sched, karras, steps, cfg, batch, denoise, seed)
                        showAdvanced = false
                    }
                )
            }
}

@Composable
private fun ImageHeader() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Image Generation",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SDModelChip(modelName: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Model",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = modelName ?: "No SD model loaded",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ImgSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun ImgBorderedCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

@Composable
private fun ImgRowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 0.5.dp,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun PromptField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    maxChars: Int,
    enabled: Boolean
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.length <= maxChars) onValueChange(it) },
                placeholder = { Text(placeholder) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                enabled = enabled,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${value.length}/$maxChars",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    showDivider: Boolean,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text(
                display,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range
        )
    }
    if (showDivider) ImgRowDivider()
}

@Composable
private fun ResolutionChip(
    label: String, 
    selected: Boolean, 
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Text(
            text = label,
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
}

@Composable
fun ImagePreview(image: Bitmap?, isGenerating: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Generated image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isGenerating) "Generating…" else "Your image will appear here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ImageActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick) {
            Icon(icon, contentDescription = label,
                modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MoreActionButton(
    onCopyPrompt: () -> Unit,
    onCopyFullSettings: () -> Unit,
    onOpenPicturesFolder: () -> Unit,
    onDeleteImage: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        ImageActionButton(
            icon = Icons.Default.MoreHoriz,
            label = "More",
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Copy prompt") },
                onClick = { expanded = false; onCopyPrompt() }
            )
            DropdownMenuItem(
                text = { Text("Copy settings") },
                onClick = { expanded = false; onCopyFullSettings() }
            )
            DropdownMenuItem(
                text = { Text("Open in gallery") },
                onClick = { expanded = false; onOpenPicturesFolder() }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Delete image", color = MaterialTheme.colorScheme.error) },
                onClick = { expanded = false; onDeleteImage() }
            )
        }
    }
}

@Composable
private fun ModelRow(file: File, isCurrent: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.sp
            )
            Text(
                if (file.isDirectory) "Folder" else "${file.length() / (1024 * 1024)} MB",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onSelect) { Text("Load") }
        TextButton(
            onClick = onDelete,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) { Text("Remove") }
    }
}

/** Saves a Bitmap to the system gallery via MediaStore (works on Android 10+ without permission). */
fun saveBitmapToGallery(context: Context, bitmap: Bitmap) {
    try {
        val filename = "localai_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/LocalAI")
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Toast.makeText(context, "Saved to Pictures/LocalAI", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Could not save image", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}


/** Shares a Bitmap via the system share sheet. */
fun shareImage(context: Context, bitmap: Bitmap) {
    try {
        val cacheDir = File(context.cacheDir, "share")
        cacheDir.mkdirs()
        val file = File(cacheDir, "localai_${System.currentTimeMillis()}.png")
        java.io.FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share image"))
    } catch (e: Exception) {
        Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

// ---------- Image Model Catalog ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageModelCatalogSection(
    state: ImageState,
    onDownload: (ImageModel) -> Unit,
    onCancel: (String) -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val models = if (selectedTab == 0) ImageModelCatalog.cpuModels else ImageModelCatalog.npuModels

    Column(Modifier.fillMaxWidth()) {
        Text(
            "Available Models",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("CPU Models") },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("NPU Models") },
            )
        }

        Spacer(Modifier.height(10.dp))

        models.forEach { model ->
            ImageModelRow(
                model = model,
                downloadState = state.downloads[model.id],
                onDownload = { onDownload(model) },
                onCancel = { onCancel(model.id) },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ImageModelRow(
    model: ImageModel,
    downloadState: ImageDownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (model.type == "npu")
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                if (model.type == "npu") "NPU" else "CPU",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (model.type == "npu")
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(model.sizeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }

                Spacer(Modifier.width(10.dp))

                when {
                    downloadState?.isComplete == true -> {
                        Text("Installed",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    downloadState?.error != null -> {
                        TextButton(onClick = onDownload) { Text("Retry") }
                    }
                    downloadState != null -> {
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    else -> {
                        FilledTonalButton(onClick = onDownload) {
                            Text("Download")
                        }
                    }
                }
            }

            if (downloadState != null && !downloadState.isComplete && downloadState.error == null) {
                Spacer(Modifier.height(8.dp))
                if (downloadState.bytesTotal > 0) {
                    LinearProgressIndicator(
                        progress = { downloadState.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(downloadState.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (downloadState?.error != null) {
                Spacer(Modifier.height(6.dp))
                Text(downloadState.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
