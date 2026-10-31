package com.localai.app.ui

import com.boswelja.markdown.material3.MarkdownDocument
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val MAX_ATTACH_BYTES = 20_000

private enum class AttachCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val mimeTypes: Array<String>,
    val extensions: Set<String>,
    val isTextReadable: Boolean
) {
    TEXT(
        title = "Text files",
        subtitle = ".txt · .md · .csv · .log · .json · .xml · .html",
        icon = Icons.Default.Description,
        mimeTypes = arrayOf("text/*", "application/json", "application/xml", "application/xhtml+xml"),
        extensions = setOf("txt","md","markdown","csv","tsv","log","json","xml","html","htm","yaml","yml"),
        isTextReadable = true
    ),
    SPECIALIZED(
        title = "Specialized files",
        subtitle = ".ini · .conf · .env · .sh · .kt · .py · .js",
        icon = Icons.Default.Terminal,
        mimeTypes = arrayOf("*/*"),
        extensions = setOf(
            "ini","conf","cfg","env","properties","sh","bash","zsh","bat","cmd","ps1",
            "kt","kts","java","py","rb","go","rs","c","h","cpp","hpp","cs","swift",
            "php","js","jsx","ts","tsx","vue","svelte","css","scss","sass","less",
            "sql","lua","r","pl","toml"
        ),
        isTextReadable = true
    ),
    BINARY(
        title = "Binary files",
        subtitle = ".bin · .zip · .jar · .war · .apk",
        icon = Icons.Default.Widgets,
        mimeTypes = arrayOf("application/*"),
        extensions = setOf("bin","zip","jar","war","apk","so","dylib","dll","exe","class","dex"),
        isTextReadable = false
    ),
    IMAGE(
        title = "Image files",
        subtitle = ".jpg · .png · .gif  (requires a vision model)",
        icon = Icons.Default.Image,
        mimeTypes = arrayOf("image/*"),
        extensions = setOf("jpg","jpeg","png","gif","webp","bmp","heic","heif"),
        isTextReadable = false
    )
}

@OptIn(ExperimentalMaterial3Api::class)
// Height of the input bar in pixels, so the error banner can sit above it
private val chatInputHeightState = androidx.compose.runtime.mutableStateOf(0)

@Composable
fun ChatScreen(
    viewModel: LlamaViewModel = hiltViewModel(),
    fullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var pendingModelUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCategory by remember { mutableStateOf<AttachCategory?>(null) }
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var pendingModelUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) pendingModelUris = uris }

    val attachPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val category = pendingCategory
        pendingCategory = null
        if (uri != null && category != null) {
            if (category == AttachCategory.IMAGE) {
                viewModel.attachImage(uri)
            } else {
                handleAttachment(context, uri, category) { block ->
                    input = if (input.isBlank()) block else "$input\n\n$block"
                }
            }
        }
    }

    val litertlmPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment ?: "model.litertlm"
            val dst = java.io.File(context.filesDir, name.substringAfterLast('/'))
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(dst).use { out -> input.copyTo(out) }
                }
                viewModel.loadLiteRtModel(context, dst)
            } catch (e: Exception) {
                // surfaced via VM error
            }
        }
    }

    val mmprojPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.loadMmproj(uri) }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                conversations = state.conversations,
                currentId = state.currentConversationId,
                modelName = state.modelName,
                onNewChat = {
                    viewModel.newConversation()
                    scope.launch { drawerState.close() }
                },
                onSelect = { id ->
                    viewModel.loadConversation(id)
                    scope.launch { drawerState.close() }
                },
                onDelete = { id -> viewModel.deleteConversation(id) },
                onOpenModelPicker = {
                    scope.launch { drawerState.close() }
                    showModelPicker = true
                }
            )
        }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                if (!fullscreen) {
                    ChatHeader(
                        modelName = state.modelName,
                        visionCapable = state.visionCapable,
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onModelClick = { showModelPicker = true }
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .then(if (fullscreen) Modifier.statusBarsPadding() else Modifier)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { onToggleFullscreen() }
                        )
                    }
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                if (state.progress >= 0f || state.status.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth()) {
                        if (state.progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth().height(2.dp)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(2.dp)
                            )
                        }
                        if (state.status.isNotEmpty()) {
                            Text(
                                state.status, fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (state.messages.isEmpty()) {
                    EmptyChatState(
                        modelLoaded = state.modelLoaded,
                        onAttachClick = { attachPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, top = 16.dp,
                            bottom = 150.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(state.messages) { msg ->
                            if (msg.isUser) {
                                UserMessage(msg)
                            } else {
                                AssistantMessage(
                                    msg = msg,
                                    onCopy = { text ->
                                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cb.setPrimaryClip(ClipData.newPlainText("LocalAI", text))
                                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                    },
                                    onRegenerate = {
                                        val lastUser = state.messages.lastOrNull { it.isUser }?.text
                                        if (!lastUser.isNullOrBlank()) viewModel.send(lastUser)
                                    }
                                )
                            }
                        }
                        if (state.isGenerating) item { GeneratingIndicator() }
                    }
                }

                state.error?.let { rawErr ->
                    val isRamWarn = rawErr.startsWith("RAM_WARN::")
                    val displayErr = if (isRamWarn) {
                        val parts = rawErr.split("::")
                        if (parts.size >= 2) parts[1] else rawErr
                    } else rawErr

                    Surface(color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(bottom = with(androidx.compose.ui.platform.LocalDensity.current) { chatInputHeightState.value.toDp() })) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text(displayErr, color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f), fontSize = 13.sp)
                            if (isRamWarn) {
                                TextButton(onClick = { viewModel.forceLoadLastRamError() }) {
                                    Text("Load anyway")
                                }
                            } else {
                                TextButton(onClick = { viewModel.dismissError() }) { Text("Dismiss") }
                            }
                        }
                    }
                }

                if (state.pendingImages.isNotEmpty()) {
                    ImageAttachments(
                        images = state.pendingImages,
                        onRemove = { viewModel.removeImage(it) }
                    )
                }
                }   // close inner Column

                ChatInputArea(
                    modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { chatInputHeightState.value = it.height },
                    value = input,
                    onValueChange = { input = it },
                    enabled = state.modelLoaded && !state.isGenerating,
                    placeholder = if (state.modelLoaded) "Type a message…" else "Load a model first",
                    onSend = {
                        val t = input.trim()
                        if (t.isNotEmpty() || state.pendingImages.isNotEmpty()) {
                            viewModel.send(t)
                            input = ""
                        }
                    },
                    showAttachMenu = showAttachMenu,
                    onAttachClick = { showAttachMenu = true },
                    onAttachDismiss = { showAttachMenu = false },
                    onCategorySelected = { cat ->
                        pendingCategory = cat
                        attachPicker.launch(cat.mimeTypes)
                    },
                    modelName = state.modelName,
                    webSearchEnabled = state.webSearchEnabled,
                    isSearching = state.isSearching,
                    searchSource = state.lastSearchSource,
                    onToggleWebSearch = { viewModel.toggleWebSearch() },
                    hasAttachments = state.pendingImages.isNotEmpty(),
                )
            }
        }
    }

    if (pendingModelUris.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingModelUris = emptyList() },
            title = { Text("Choose execution backend") },
            text = { Text("GGUF models can run on the CPU (llama.cpp) or the NPU (Hexagon). NPU is faster but may fall back to CPU on unsupported hardware.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.loadModelsOnHexagon(pendingModelUris)
                    pendingModelUris = emptyList()
                }) { Text("NPU (Hexagon)") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingModelUris.firstOrNull()?.let { viewModel.loadModel(it) }
                        pendingModelUris = emptyList()
                    }) { Text("CPU") }
                    TextButton(onClick = { pendingModelUris = emptyList() }) { Text("Cancel") }
                }
            }
        )
    }

    if (showModelPicker) {
        val installed = remember(showModelPicker) { viewModel.listInstalledModels() }
        val mmprojs = remember(showModelPicker) { viewModel.listInstalledMmprojs() }
        Dialog(
            onDismissRequest = { showModelPicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(0.94f).padding(vertical = 32.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Choose a model",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {

                        SectionHeader(Icons.Default.Memory, "NPU (Hexagon)",
                            MaterialTheme.colorScheme.primary)
                        if (installed.isEmpty()) {
                            EmptyHint("No GGUF models installed.")
                        } else {
                            installed.forEach { file ->
                                ModelRowItem(
                                    file = file,
                                    isCurrent = state.npuModelLoaded && state.modelName == file.name,
                                    accent = MaterialTheme.colorScheme.primary,
                                    backendLabel = "NPU",
                                    onLoad = {
                                        viewModel.loadGgufOnHexagon(file)
                                        showModelPicker = false
                                    },
                                    onDelete = {
                                        viewModel.deleteInstalledModel(file)
                                        showModelPicker = false
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        SectionHeader(Icons.Default.SmartToy, "CPU (llama.cpp)",
                            MaterialTheme.colorScheme.tertiary)
                        if (installed.isEmpty()) {
                            EmptyHint("No GGUF models installed.")
                        } else {
                            installed.forEach { file ->
                                ModelRowItem(
                                    file = file,
                                    isCurrent = !state.npuModelLoaded && state.modelName == file.name,
                                    accent = MaterialTheme.colorScheme.tertiary,
                                    backendLabel = "CPU",
                                    onLoad = {
                                        viewModel.loadInstalledModel(file)
                                        showModelPicker = false
                                    },
                                    onDelete = {
                                        viewModel.deleteInstalledModel(file)
                                        showModelPicker = false
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))
                        SectionHeader(Icons.Default.Memory, "NPU (LiteRT)",
                            MaterialTheme.colorScheme.secondary)

                        val liteRtModels = remember(showModelPicker) {
                            viewModel.listInstalledLiteRtModels()
                        }
                        if (liteRtModels.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            liteRtModels.forEach { file ->
                                ModelRowItem(
                                    file = file,
                                    isCurrent = state.liteRtModelLoaded && state.modelName == file.name,
                                    accent = MaterialTheme.colorScheme.secondary,
                                    backendLabel = "LITERT",
                                    onLoad = {
                                        viewModel.loadLiteRtModel(context, file)
                                        showModelPicker = false
                                    },
                                    onDelete = {
                                        viewModel.deleteInstalledLiteRtModel(file)
                                        showModelPicker = false
                                    }
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        OutlinedButton(
                            onClick = {
                                showModelPicker = false
                                litertlmPicker.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Browse .litertlm") }

                        if (mmprojs.isNotEmpty()) {
                            Spacer(Modifier.height(20.dp))
                            SectionHeader(Icons.Default.Description, "Vision projectors",
                                MaterialTheme.colorScheme.secondary)
                            mmprojs.forEach { file ->
                                ModelRowItem(
                                    file = file,
                                    isCurrent = state.mmprojName == file.name,
                                    accent = MaterialTheme.colorScheme.secondary,
                                    backendLabel = "MMPROJ",
                                    onLoad = {
                                        viewModel.loadMmprojFromFile(file)
                                        showModelPicker = false
                                    },
                                    onDelete = {
                                        viewModel.deleteInstalledMmproj(file)
                                        showModelPicker = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                showModelPicker = false
                                filePicker.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Browse model") }
                        OutlinedButton(
                            onClick = {
                                showModelPicker = false
                                mmprojPicker.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Browse mmproj") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationDrawer(
    conversations: List<ConversationMeta>,
    currentId: String?,
    modelName: String?,
    onNewChat: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenModelPicker: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 12.dp)
        ) {
            Text(
                "LocalAI",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp)
            )
            Text(
                modelName ?: "No model loaded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, bottom = 12.dp)
            )

            Surface(
                onClick = onNewChat,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.Add, "New chat",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("New chat",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            Spacer(Modifier.height(12.dp))

            if (conversations.isEmpty()) {
                Text(
                    "No conversations yet.\nYour chats appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp)
                )
            } else {
                val groups = groupByDate(conversations)
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    groups.forEach { (label, items) ->
                        item(key = "h_$label") {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp)
                            )
                        }
                        items(items, key = { it.id }) { convo ->
                            ConversationRow(
                                convo = convo,
                                isActive = convo.id == currentId,
                                onSelect = { onSelect(convo.id) },
                                onDelete = { onDelete(convo.id) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Surface(
                onClick = onOpenModelPicker,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.SmartToy, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Model",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(modelName ?: "None",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Default.ExpandMore, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    convo: ConversationMeta,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(10.dp),
        color = if (isActive) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    convo.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${convo.messageCount} message${if (convo.messageCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Default.Delete, "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp))
            }
        }
    }
}

private fun groupByDate(conversations: List<ConversationMeta>): Map<String, List<ConversationMeta>> {
    val now = System.currentTimeMillis()
    val day = 24 * 60 * 60 * 1000L
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val todayStart = cal.timeInMillis
    val yesterdayStart = todayStart - day

    val order = listOf("Today", "Yesterday", "7 Days", "30 Days", "Older")
    val buckets = linkedMapOf<String, MutableList<ConversationMeta>>()
    order.forEach { buckets[it] = mutableListOf() }

    conversations.sortedByDescending { it.updatedAt }.forEach { c ->
        val label = when {
            c.updatedAt >= todayStart -> "Today"
            c.updatedAt >= yesterdayStart -> "Yesterday"
            now - c.updatedAt < 7 * day -> "7 Days"
            now - c.updatedAt < 30 * day -> "30 Days"
            else -> "Older"
        }
        buckets[label]?.add(c)
    }
    return buckets.filterValues { it.isNotEmpty() }
}

@Composable
private fun EmptyChatState(
    modelLoaded: Boolean,
    onAttachClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.AutoAwesome, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("How can I help you?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (modelLoaded) "Ask a question or attach a file to get started."
            else "Load a model to start chatting.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (modelLoaded) {
            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = onAttachClick,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Attach a file")
            }
        }
    }
}

@Composable
private fun ChatHeader(
    modelName: String?,
    visionCapable: Boolean,
    onMenuClick: () -> Unit,
    onModelClick: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.Menu, "Chats",
                    tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Text Chat",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 2.dp)
            )
            Spacer(Modifier.weight(1f))
            ModelChip(
                name = modelName,
                visionCapable = visionCapable,
                onClick = onModelClick
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun ModelChip(name: String?, visionCapable: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Default.SmartToy, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(name ?: "No model",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 130.dp),
                overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ExpandMore, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = color)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp))
    }
}

@Composable
private fun ModelRowItem(
    file: File,
    isCurrent: Boolean,
    accent: Color,
    backendLabel: String,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isCurrent) accent.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(
            1.dp,
            if (isCurrent) accent.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(file.name,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Badge(backendLabel, accent)
                }
                Spacer(Modifier.height(2.dp))
                Text("${file.length() / (1024 * 1024)} MB",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onLoad) {
                Icon(Icons.AutoMirrored.Filled.Send, "Load", tint = accent)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Delete",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ImageAttachments(images: List<File>, onRemove: (File) -> Unit) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(images) { file ->
            Box(modifier = Modifier.size(100.dp)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxSize()
                ) {
                    val bmp = remember(file.absolutePath) {
                        try {
                            val f = File(file.absolutePath)
                            if (f.exists())
                                android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                            else null
                        } catch (_: Throwable) { null }
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = file.name,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Image, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
                // Remove (X) button at top-right
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-6).dp)
                        .size(24.dp)
                        .clickable { onRemove(file) }
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                // Filename overlay at bottom
                Surface(
                    shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

private fun handleAttachment(
    context: Context,
    uri: Uri,
    category: AttachCategory,
    onReady: (String) -> Unit
) {
    val name = queryName(context, uri) ?: "attachment"
    val ext = name.substringAfterLast('.', "").lowercase()

    if (ext.isNotEmpty() && ext !in category.extensions) {
        Toast.makeText(context, "Wrong category for .$ext", Toast.LENGTH_LONG).show()
        return
    }

    if (!category.isTextReadable) {
        Toast.makeText(context,
            "Text models can't read ${category.title.lowercase()}",
            Toast.LENGTH_LONG).show()
        return
    }

    val (readName, content, error) = readTextAttachment(context, uri)
    when {
        error != null -> Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        content != null -> {
            val sizeKb = content.length / 1024.0
            val block = buildString {
                append("--- Attached: ").append(readName).append(" ---\n")
                append(content)
                if (!content.endsWith("\n")) append("\n")
                append("--- end of ").append(readName).append(" ---")
            }
            onReady(block)
            Toast.makeText(context, "Attached $readName (%.1f KB)".format(sizeKb),
                Toast.LENGTH_SHORT).show()
        }
    }
}

private fun queryName(context: Context, uri: Uri): String? =
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) { null }

private fun readTextAttachment(
    context: Context,
    uri: Uri
): Triple<String, String?, String?> {
    return try {
        val name = queryName(context, uri) ?: "attachment.txt"
        val content = context.contentResolver.openInputStream(uri)?.use { stream ->
            val buf = ByteArray(MAX_ATTACH_BYTES)
            var total = 0
            while (total < MAX_ATTACH_BYTES) {
                val n = stream.read(buf, total, MAX_ATTACH_BYTES - total)
                if (n <= 0) break
                total += n
            }
            val hasMore = stream.read() != -1
            val text = String(buf, 0, total, Charsets.UTF_8)
            val sample = text.take(512)
            val nonPrintable = sample.count { c ->
                c.code < 9 || (c.code in 14..31) || c.code == 127
            }
            if (sample.isNotEmpty() && nonPrintable.toFloat() / sample.length > 0.05f) {
                return Triple(name, null, "Binary content detected — cannot attach")
            }
            if (hasMore) "$text\n… [truncated at ${MAX_ATTACH_BYTES / 1000} KB]" else text
        } ?: ""
        Triple(name, content, null)
    } catch (e: Exception) {
        Triple("attachment", null, "Read failed: ${e.message}")
    }
}

@Composable
private fun UserMessage(msg: ChatMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (msg.imagePath != null) {
                    val bmp = remember(msg.imagePath) {
                        try {
                            val f = java.io.File(msg.imagePath)
                            if (f.exists()) BitmapFactory.decodeFile(msg.imagePath) else null
                        } catch (_: Throwable) { null }
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Attached image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Text(msg.text, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(msg.timestamp), fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.DoneAll, null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun MessageActionIcon(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(28.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * The markdown renderer panics on SHORT_REFERENCE_LINK nodes — any bare
 * `[text]` not followed by a URL. LLM output frequently contains patterns
 * like "see [section 2]" which trips it. Flatten all bare brackets to plain
 * text so rendering can never fail.
 */
private fun sanitizeMarkdown(src: String): String {
    var out = src
    // [text][ref]  →  text
    out = Regex("\\[([^\\]]+)\\]\\[[^\\]]*\\]").replace(out) { it.groupValues[1] }
    // ![alt][ref]  →  alt
    out = Regex("!\\[([^\\]]+)\\]\\[[^\\]]*\\]").replace(out) { it.groupValues[1] }
    // [text] not followed by ( →  text
    out = Regex("(?<!!)\\[([^\\]]+)\\](?!\\()").replace(out) { it.groupValues[1] }
    // Drop orphan reference-definition lines " [ref]: url"
    out = out.lines()
        .filterNot { Regex("^\\s*\\[[^\\]]+\\]:\\s*\\S+").containsMatchIn(it) }
        .joinToString("\n")
    return out
}

@Composable
private fun AssistantMessage(
    msg: ChatMessage,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.size(30.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.AutoAwesome, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.widthIn(max = 340.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    MarkdownDocument(sanitizeMarkdown(msg.text))
                }
            }
            Spacer(Modifier.height(2.dp))
            var vote by remember { mutableIntStateOf(0) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MessageActionIcon(Icons.Default.ContentCopy, "Copy") { onCopy(msg.text) }
                MessageActionIcon(Icons.Default.Refresh, "Regenerate") { onRegenerate() }
                MessageActionIcon(Icons.Default.ThumbUp, "Good",
                    tint = if (vote == 1) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                ) { vote = if (vote == 1) 0 else 1 }
                MessageActionIcon(Icons.Default.ThumbDown, "Bad",
                    tint = if (vote == -1) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                ) { vote = if (vote == -1) 0 else -1 }
                MessageActionIcon(Icons.Default.MoreHoriz, "More") { }
                Spacer(Modifier.weight(1f))
                Text(formatTime(msg.timestamp), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun GeneratingIndicator() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(30.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(10.dp))
        Text("Generating…", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChatInputArea(
    modifier: Modifier = Modifier,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    onSend: () -> Unit,
    showAttachMenu: Boolean,
    onAttachClick: () -> Unit,
    onAttachDismiss: () -> Unit,
    onCategorySelected: (AttachCategory) -> Unit,
    modelName: String?,
    webSearchEnabled: Boolean,
    isSearching: Boolean,
    searchSource: String?,
    onToggleWebSearch: () -> Unit,
    hasAttachments: Boolean = false,
) {
    Surface(
        color = androidx.compose.ui.graphics.Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // ---- Web search chip: floating, transparent ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .wrapContentWidth()
                    .padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
            ) {
                Surface(
                    onClick = onToggleWebSearch,
                    shape = RoundedCornerShape(14.dp),
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(
                        1.dp,
                        if (webSearchEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Language,
                            contentDescription = "Web Search",
                            tint = if (webSearchEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Web Search",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (webSearchEnabled) FontWeight.SemiBold
                                         else FontWeight.Normal,
                            color = if (webSearchEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = value, onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(placeholder) },
                    leadingIcon = {
                        Box {
                            IconButton(onClick = onAttachClick, enabled = enabled) {
                                Icon(Icons.Default.AttachFile, "Attach file",
                                    tint = if (enabled) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(
                                expanded = showAttachMenu,
                                onDismissRequest = onAttachDismiss,
                                shape = RoundedCornerShape(16.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 6.dp,
                                shadowElevation = 8.dp
                            ) {
                                AttachCategory.entries.forEach { cat ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                cat.title,
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                        },
                                        onClick = { onCategorySelected(cat) },
                                        leadingIcon = {
                                            Icon(
                                                cat.icon,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    },
                        shape = RoundedCornerShape(26.dp),
                        maxLines = 5,
                        enabled = enabled,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background,
                            disabledContainerColor = MaterialTheme.colorScheme.background,
                        )
                    )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = onSend,
                    enabled = enabled && (value.isNotBlank() || hasAttachments),
                    modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
            // ---- Search status text below the input ----
            if (isSearching) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Searching…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (webSearchEnabled && searchSource != null) {
                Text(
                    "via $searchSource",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Verified, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("${modelName ?: "LocalAI"} · Not for medical, legal or safety-critical advice",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            }   // close opaque background Column
        }
    }
}

private fun formatTime(ts: Long): String = try {
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
} catch (_: Exception) { "" }
