package hr.fer.studentzkp.holder.ui.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hr.fer.studentzkp.holder.data.model.StoredCredential
import hr.fer.studentzkp.holder.domain.CredentialRepository
import hr.fer.studentzkp.holder.util.SdJwtUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class DetailUiState(
    val credential: StoredCredential? = null,
    val disclosureNames: List<String> = emptyList(),
    val showQr: Boolean = false,
    val showDeleteDialog: Boolean = false,
)

class CredentialDetailViewModel(
    private val credentialId: String,
    private val repository: CredentialRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val cred = withContext(Dispatchers.IO) {
                repository.getAllCredentials().firstOrNull { it.id == credentialId }
            }
            val disclosures = if (cred != null) {
                withContext(Dispatchers.Default) {
                    try {
                        SdJwtUtils.parse(cred.sdJwt).disclosures.map { it.name }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            } else emptyList()
            _uiState.value = DetailUiState(
                credential = cred,
                disclosureNames = disclosures,
            )
        }
    }

    fun toggleQr() {
        _uiState.value = _uiState.value.copy(showQr = !_uiState.value.showQr)
    }

    fun confirmDelete() {
        _uiState.value = _uiState.value.copy(showDeleteDialog = true)
    }

    fun dismissDelete() {
        _uiState.value = _uiState.value.copy(showDeleteDialog = false)
    }

    fun doDelete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteCredential(credentialId) }
            onDeleted()
        }
    }
}
