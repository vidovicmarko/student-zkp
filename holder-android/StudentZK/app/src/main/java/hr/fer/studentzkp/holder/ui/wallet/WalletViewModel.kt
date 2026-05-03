package hr.fer.studentzkp.holder.ui.wallet

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hr.fer.studentzkp.holder.data.model.StoredCredential
import hr.fer.studentzkp.holder.domain.CredentialRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class WalletUiState(
    val credentials: List<StoredCredential> = emptyList(),
    val isLoading: Boolean = false,
    val issuanceError: String? = null,
    val showAddSheet: Boolean = false,
    val studentIdInput: String = "",
)

class WalletViewModel(private val repository: CredentialRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    init {
        loadCredentials()
    }

    fun loadCredentials() {
        viewModelScope.launch {
            val creds = withContext(Dispatchers.IO) { repository.getAllCredentials() }
            _uiState.value = _uiState.value.copy(credentials = creds)
        }
    }

    fun showAddSheet() {
        _uiState.value = _uiState.value.copy(showAddSheet = true, issuanceError = null)
    }

    fun dismissAddSheet() {
        _uiState.value = _uiState.value.copy(showAddSheet = false, studentIdInput = "", issuanceError = null)
    }

    fun onStudentIdChanged(value: String) {
        _uiState.value = _uiState.value.copy(studentIdInput = value, issuanceError = null)
    }

    fun issueCredential() {
        val studentId = _uiState.value.studentIdInput.trim()
        if (studentId.isBlank()) {
            _uiState.value = _uiState.value.copy(issuanceError = "Please enter a student ID")
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, issuanceError = null)
        viewModelScope.launch {
            repository.devIssueAndStore(studentId)
                .onSuccess {
                    val creds = withContext(Dispatchers.IO) { repository.getAllCredentials() }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        showAddSheet = false,
                        studentIdInput = "",
                        credentials = creds,
                    )
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        issuanceError = err.message ?: "Failed to issue credential",
                    )
                }
        }
    }
}
