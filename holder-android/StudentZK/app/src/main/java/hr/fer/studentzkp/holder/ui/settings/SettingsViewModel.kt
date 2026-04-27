package hr.fer.studentzkp.holder.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hr.fer.studentzkp.holder.domain.CredentialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class SettingsUiState(
    val serverUrl: String = "",
    val urlInput: String = "",
    val isCheckingHealth: Boolean = false,
    val healthStatus: String? = null,
    val saved: Boolean = false,
)

class SettingsViewModel(private val repository: CredentialRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        val url = repository.getServerUrl()
        _uiState.value = SettingsUiState(serverUrl = url, urlInput = url)
    }

    fun onUrlChanged(value: String) {
        _uiState.value = _uiState.value.copy(urlInput = value, saved = false, healthStatus = null)
    }

    fun saveUrl() {
        val url = _uiState.value.urlInput.trim()
        if (url.isNotEmpty()) {
            repository.saveServerUrl(url)
            _uiState.value = _uiState.value.copy(serverUrl = url, saved = true)
        }
    }

    fun checkHealth() {
        _uiState.value = _uiState.value.copy(isCheckingHealth = true, healthStatus = null)
        viewModelScope.launch {
            val ok = repository.checkServerHealth()
            _uiState.value = _uiState.value.copy(
                isCheckingHealth = false,
                healthStatus = if (ok) "✓ Server is reachable" else "✗ Server unreachable",
            )
        }
    }
}
