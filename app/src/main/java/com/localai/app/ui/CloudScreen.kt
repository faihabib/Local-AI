package com.localai.app.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudScreen(viewModel: CloudViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Refresh credential flag whenever the tab is entered
    LaunchedEffect(Unit) { viewModel.refreshCredentials() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Cloudflare",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Generate images via Cloudflare Workers AI. Free tier: ~200/day (Flux) or ~115/day (SDXL Lightning).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!state.hasCredentials) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "No credentials set",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Go to Settings → Cloud API and paste your Cloudflare Account ID and API Token.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // ---- Model selector ----
        Text("Model", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelChip(
                label = "Flux Schnell",
                selected = state.selectedModel == CloudAiClient.MODEL_FLUX,
                onClick = { viewModel.setModel(CloudAiClient.MODEL_FLUX) },
            )
            ModelChip(
                label = "SDXL Lightning",
                selected = state.selectedModel == CloudAiClient.MODEL_SDXL_LT,
                onClick = { viewModel.setModel(CloudAiClient.MODEL_SDXL_LT) },
            )
            ModelChip(
                label = "SDXL Base",
                selected = state.selectedModel == CloudAiClient.MODEL_SDXL,
                onClick = { viewModel.setModel(CloudAiClient.MODEL_SDXL) },
            )
        }

        // ---- Steps slider ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Steps",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text("${state.steps}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold)
        }
        Slider(
            value = state.steps.toFloat(),
            onValueChange = { viewModel.setSteps(it.toInt()) },
            valueRange = 1f..30f,
            steps = 29,
        )
        val hint = when (state.selectedModel) {
            CloudAiClient.MODEL_FLUX ->
                "Auto: 4. Flux Schnell always runs 4 steps internally — this value is ignored by the API."
            CloudAiClient.MODEL_SDXL_LT ->
                "Auto: 4. Lightning works best at 4–8 steps. Higher values slow it down with little gain."
            else ->
                "Auto: 20. SDXL Base benefits from 20–30 steps. Lower = faster, higher = more detail."
        }
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- Prompt ----
        OutlinedTextField(
            value = state.prompt,
            onValueChange = { viewModel.setPrompt(it) },
            placeholder = { Text("Describe the image you want…") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            maxLines = 6,
            shape = RoundedCornerShape(14.dp),
        )

        // ---- Generate button ----
        Button(
            onClick = { viewModel.generate() },
            enabled = !state.isGenerating && state.prompt.isNotBlank() && state.hasCredentials,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (state.isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
            } else {
                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                if (state.isGenerating) (state.status.ifBlank { "Generating…" }) else "Generate",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // ---- Preview (always visible, shows placeholder while empty) ----
        ImagePreview(image = state.image, isGenerating = state.isGenerating)

        // ---- Save / Share (only when an image exists) ----
        if (state.image != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { saveBitmapToGallery(context, state.image!!) },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
                OutlinedButton(
                    onClick = { shareImage(context, state.image!!) },
                    modifier = Modifier.weight(1f),
                ) { Text("Share") }
            }
        }

        // ---- Error ----
        state.error?.let { err ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.dismissError() }) { Text("OK") }
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ModelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
