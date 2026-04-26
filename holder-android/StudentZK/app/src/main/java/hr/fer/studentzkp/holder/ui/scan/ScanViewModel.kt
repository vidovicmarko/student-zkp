package hr.fer.studentzkp.holder.ui.scan

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hr.fer.studentzkp.holder.data.model.VerificationResult
import hr.fer.studentzkp.holder.domain.CredentialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class ScanUiState(
    val isScanning: Boolean = true,
    val lastScanned: String? = null,
    val isVerifying: Boolean = false,
    val result: VerificationResult? = null,
    val error: String? = null,
)

class ScanViewModel(private val repository: CredentialRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    fun onQrDetected(raw: String) {
        if (_uiState.value.isVerifying) return
        if (raw == _uiState.value.lastScanned && _uiState.value.result != null) return

        _uiState.value = _uiState.value.copy(
            isScanning = false,
            lastScanned = raw,
            isVerifying = true,
            result = null,
            error = null,
        )
        viewModelScope.launch {
            val result = repository.verifySdJwt(raw)
            _uiState.value = _uiState.value.copy(
                isVerifying = false,
                result = result,
            )
        }
    }

    fun resetScan() {
        _uiState.value = ScanUiState()
    }
}
