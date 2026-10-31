package com.localai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun AdvancedSettingsDialog(
    onDismiss: () -> Unit,
    initialAspect: String,
    initialScheduler: String,
    initialKarras: Boolean,
    initialSteps: Int,
    initialCfg: Float,
    initialBatch: Int,
    initialDenoise: Float,
    initialSeed: String,
    width: Int,
    height: Int,
    onConfirm: (
        aspect: String, scheduler: String, karras: Boolean,
        steps: Int, cfg: Float, batch: Int, denoise: Float, seed: String
    ) -> Unit
) {
    var aspect by remember { mutableStateOf(initialAspect) }
    var scheduler by remember { mutableStateOf(initialScheduler) }   // "" = auto (model default)
    var karras by remember { mutableStateOf(initialKarras) }
    var steps by remember { mutableFloatStateOf(initialSteps.toFloat()) }
    var cfg by remember { mutableFloatStateOf(initialCfg) }
    var batch by remember { mutableFloatStateOf(initialBatch.toFloat()) }
    var denoise by remember { mutableFloatStateOf(initialDenoise) }
    var seed by remember { mutableStateOf(initialSeed) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Advanced Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(20.dp))

                // ---- Aspect Ratio ----
                Text("Aspect Ratio", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1:1", "3:4", "4:3", "Custom").forEach { label ->
                        val selected = aspect == label
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clickable { aspect = label },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(label,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // ---- Scheduler ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scheduler", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f))
                    Text("Karras", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Switch(checked = karras, onCheckedChange = { karras = it },
                        modifier = Modifier.height(24.dp))
                }
                Spacer(Modifier.height(8.dp))
                // Row 1: Auto, LCM, DPM++ 2M
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Auto", "LCM", "DPM++ 2M").forEach { label ->
                        val isActive = when (label) {
                            "Auto"      -> scheduler == ""
                            "LCM"       -> scheduler == "lcm"
                            else        -> scheduler == "dpm++2m"
                        }
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clickable {
                                    scheduler = when (label) {
                                        "Auto"    -> ""
                                        "LCM"     -> "lcm"
                                        else      -> "dpm++2m"
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(label,
                                    fontSize = 11.sp,
                                    color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Row 2: DPM++ 2M SDE, Euler A
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("DPM++ 2M SDE", "Euler A").forEach { label ->
                        val isActive = when (label) {
                            "DPM++ 2M SDE" -> scheduler == "dpm++2m_sde"
                            else           -> scheduler == "euler_a"
                        }
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clickable {
                                    scheduler = when (label) {
                                        "DPM++ 2M SDE" -> "dpm++2m_sde"
                                        else           -> "euler_a"
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(label,
                                    fontSize = 11.sp,
                                    color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    // filler to keep the 2 rows visually balanced
                    Spacer(Modifier.weight(1f))
                }
                if (scheduler == "") {
                    Spacer(Modifier.height(6.dp))
                    Text("Auto uses the model's built-in scheduler. LCM = 8 steps / CFG 1 / DMD2.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(18.dp))

                // ---- Steps ----
                ParamSliderRow("Steps", steps.toInt().toString(), steps, 1f..50f, steps = 49,
                    onChange = { steps = it })

                Spacer(Modifier.height(14.dp))

                // ---- CFG ----
                ParamSliderRow("CFG Scale", "%.2f".format(cfg), cfg, 0f..20f, steps = 39,
                    onChange = { cfg = it })

                Spacer(Modifier.height(14.dp))

                // ---- Batch ----
                ParamSliderRow("Batch Count", batch.toInt().toString(), batch, 1f..8f, steps = 6,
                    onChange = { batch = it })

                Spacer(Modifier.height(14.dp))

                // ---- Denoise ----
                ParamSliderRow("Denoise Strength", "%.2f".format(denoise), denoise, 0f..1f, steps = 19,
                    onChange = { denoise = it })

                Spacer(Modifier.height(18.dp))

                // ---- Seed ----
                OutlinedTextField(
                    value = seed,
                    onValueChange = { seed = it.filter { c -> c.isDigit() } },
                    label = { Text("Random Seed (Optional)") },
                    placeholder = { Text("empty = random") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(Modifier.height(24.dp))

                // ---- Actions ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        aspect = "1:1"; scheduler = ""; karras = false
                        steps = 8f; cfg = 1f; batch = 1f; denoise = 1f; seed = ""
                    }) {
                        Text("Reset", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = {
                        onConfirm(
                            aspect, scheduler, karras,
                            steps.toInt(), cfg, batch.toInt(), denoise, seed
                        )
                    }) {
                        Text("Confirm", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ParamSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps
        )
    }
}
