package com.localai.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * First-launch compatibility guide. Shows what runs on which backend.
 * Called on fresh install and from Settings → Guide.
 */
@Composable
fun GuideDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 32.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Note",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    GuideContent()
                }

                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Got it!", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun GuideSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun GuideRow(title: String, description: String) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp,
        )
    }
}

/**
 * The compatibility matrix content. Shared by GuideDialog (first-launch +
 * Settings → Guide) so both entry points stay in sync.
 */
@Composable
fun GuideContent() {
    Text(
        "LocalAI runs models entirely on your device — no internet, no data leaving your phone.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(18.dp))

    GuideSectionTitle("TEXT CHAT")
    GuideRow("CPU (llama.cpp)", "Works on every Android phone. Any GGUF runs. Slowest but always reliable.")
    GuideRow("NPU (Hexagon)", "Snapdragon 8 Gen 2+. Best with Qwen-family GGUFs in Q4_0 / Q4_K_M. Other architectures may fall back to CPU.")
    GuideRow("NPU (LiteRT)", "Snapdragon 8 Gen 3+. Runs Google .litertlm bundles only. Fastest. Supports image input (vision).")

    Spacer(Modifier.height(16.dp))
    GuideSectionTitle("IMAGE GENERATION")
    GuideRow("CPU models", "MNN format. Works on any phone. Slow — 30 to 90 seconds per image.")
    GuideRow("NPU models", "Snapdragon 8 Gen 3+. SDXL at 1024x1024. DMD2 variants run in 6 steps instead of 20+.")
    GuideRow("Cloudflare (Cloud tab)", "Cloudflare Workers AI. Enter your API token in Settings → Cloud API. ~200 free images/day.")

    Spacer(Modifier.height(16.dp))
    GuideSectionTitle("WEB SEARCH")
    Text(
        "Tavily-powered search works with all chat backends. Results are injected as context before each reply. Toggle with the Web Search chip above the input.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(14.dp))
    Text(
        "The app only lists models your device can run.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
    )
}
