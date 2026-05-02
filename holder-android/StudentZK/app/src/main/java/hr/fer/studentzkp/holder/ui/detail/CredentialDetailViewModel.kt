package hr.fer.studentzkp.holder.ui.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hr.fer.studentzkp.holder.data.model.StoredCredential
import hr.fer.studentzkp.holder.domain.CredentialRepository
import hr.fer.studentzkp.holder.util.BbsVcUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class DetailUiState(
    val credential: StoredCredential? = null,
    val attributeNames: List<String> = emptyList(),
    val showDeleteDialog: Boolean = false,
    val showPresentDialog: Boolean = false,
    val nonceInput: String = "",
    val audienceInput: String = "",
    val presentation: String? = null,
    val presentationError: String? = null,
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
            val attrs = if (cred != null) {
                withContext(Dispatchers.Default) {
                    try {
                        val data = BbsVcUtils.parse(cred.bbsVcJson)
                        BbsVcUtils.getAttributeNames(data.messages)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            } else emptyList()
            _uiState.value = DetailUiState(
                credential = cred,
                attributeNames = attrs,
            )
        }
    }

    fun openPresentDialog() {
        _uiState.value = _uiState.value.copy(
            showPresentDialog = true,
            nonceInput = "",
            audienceInput = "",
            presentation = null,
            presentationError = null,
        )
    }

    fun dismissPresentDialog() {
        _uiState.value = _uiState.value.copy(showPresentDialog = false)
    }

    fun onNonceChanged(v: String) {
        _uiState.value = _uiState.value.copy(nonceInput = v, presentationError = null)
    }

    fun onAudienceChanged(v: String) {
        _uiState.value = _uiState.value.copy(audienceInput = v, presentationError = null)
    }

    fun generatePresentation() {
        val s = _uiState.value
        val nonce = s.nonceInput.trim()
        val audience = s.audienceInput.trim()
        if (nonce.isEmpty() || audience.isEmpty()) {
            _uiState.value = s.copy(presentationError = "Nonce and audience are required")
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                repository.buildPresentation(credentialId, nonce, audience)
            }
            result.fold(
                onSuccess = { _uiState.value = _uiState.value.copy(presentation = it, presentationError = null) },
                onFailure = { _uiState.value = _uiState.value.copy(presentation = null, presentationError = it.message ?: "Failed to build presentation") },
            )
        }
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
