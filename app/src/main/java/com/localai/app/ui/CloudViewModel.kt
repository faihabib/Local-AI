package com.localai.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localai.app.data.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CloudState(
    val prompt: String = "",
    val selectedModel: String = CloudAiClient.MODEL_FLUX,
    val steps: Int = 4,
    val isGenerating: Boolean = false,
    val status: String = "",
    val image: Bitmap? = null,
    val error: String? = null,
    val hasCredentials: Boolean = false,
)

@HiltViewModel
class CloudViewModel @Inject constructor(
    application: Application,
    private val prefs: UserPreferencesRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(CloudState())
    val state: StateFlow<CloudState> = _state.asStateFlow()

    init {
        // Detect whether credentials have been saved yet
        viewModelScope.launch {
            val id = prefs.cfAccountIdFlow.firstOrNull() ?: ""
            val tk = prefs.cfApiTokenFlow.firstOrNull() ?: ""
            _state.value = _state.value.copy(hasCredentials = id.isNotBlank() && tk.isNotBlank())
        }
    }

    fun refreshCredentials() {
        viewModelScope.launch {
            val id = prefs.cfAccountIdFlow.firstOrNull() ?: ""
            val tk = prefs.cfApiTokenFlow.firstOrNull() ?: ""
            _state.value = _state.value.copy(hasCredentials = id.isNotBlank() && tk.isNotBlank())
        }
    }

    fun setPrompt(p: String) { _state.value = _state.value.copy(prompt = p) }
    fun setModel(m: String) {
        val defaultSteps = if (m == CloudAiClient.MODEL_SDXL) 20 else 4
        _state.value = _state.value.copy(selectedModel = m, steps = defaultSteps)
    }
    fun setSteps(s: Int) { _state.value = _state.value.copy(steps = s.coerceIn(1, 30)) }
    fun dismissError() { _state.value = _state.value.copy(error = null) }

    fun generate() {
        if (_state.value.isGenerating) return
        if (_state.value.prompt.isBlank()) {
            _state.value = _state.value.copy(error = "Enter a prompt first")
            return
        }

        viewModelScope.launch {
            val id = prefs.cfAccountIdFlow.firstOrNull() ?: ""
            val tk = prefs.cfApiTokenFlow.firstOrNull() ?: ""
            if (id.isBlank() || tk.isBlank()) {
                _state.value = _state.value.copy(
                    error = "Set Cloudflare credentials in Settings → Cloud API",
                    hasCredentials = false,
                )
                return@launch
            }

            _state.value = _state.value.copy(
                isGenerating = true, status = "Contacting Cloudflare…",
                error = null, image = null,
            )

            try {
                val bmp = CloudAiClient.generate(
                    accountId = id,
                    apiToken = tk,
                    prompt = _state.value.prompt,
                    model = _state.value.selectedModel,
                    steps = _state.value.steps,
                )
                _state.value = _state.value.copy(
                    isGenerating = false, status = "Done",
                    image = bmp,
                )
            } catch (t: Throwable) {
                Log.e("CloudVM", "generate failed", t)
                _state.value = _state.value.copy(
                    isGenerating = false,
                    status = "",
                    error = t.message ?: "Generation failed",
                )
            }
        }
    }
}
