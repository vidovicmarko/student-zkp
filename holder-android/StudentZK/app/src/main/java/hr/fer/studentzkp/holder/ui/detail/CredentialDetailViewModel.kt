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
    val indexedAttributes: List<Pair<Int, String>> = emptyList(),
    val selectedIndices: Set<Int> = emptySet(),
    val presentationJson: String? = null,
    val presentationError: String? = null,
    val isGenerating: Boolean = false,
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
            val (attrs, indexed) = if (cred != null) {
                withContext(Dispatchers.Default) {
                    try {
                        val data = BbsVcUtils.parse(cred.bbsVcJson)
                        BbsVcUtils.getAttributeNames(data.messages) to
                            BbsVcUtils.getIndexedAttributes(data.messages)
                    } catch (_: Exception) {
                        emptyList<String>() to emptyList<Pair<Int, String>>()
                    }
                }
            } else emptyList<String>() to emptyList()
            _uiState.value = DetailUiState(
                credential = cred,
                attributeNames = attrs,
                indexedAttributes = indexed,
                selectedIndices = indexed.map { it.first }.toSet(),
            )
        }
    }

    fun toggleAttribute(index: Int) {
        val current = _uiState.value.selectedIndices
        val updated = if (index in current) current - index else current + index
        _uiState.value = _uiState.value.copy(
            selectedIndices = updated,
            presentationJson = null,
            presentationError = null,
        )
    }

    fun generatePresentation() {
        val state = _uiState.value
        if (state.selectedIndices.isEmpty()) {
            _uiState.value = state.copy(presentationError = "Select at least one attribute to disclose")
            return
        }
        _uiState.value = state.copy(isGenerating = true, presentationError = null, presentationJson = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                repository.buildSelectivePresentation(
                    credentialId,
                    state.selectedIndices.sorted(),
                )
            }
            result.fold(
                onSuccess = { _uiState.value = _uiState.value.copy(presentationJson = it, isGenerating = false) },
                onFailure = { _uiState.value = _uiState.value.copy(presentationError = it.message ?: "Failed", isGenerating = false) },
            )
        }
    }

    fun clearPresentation() {
        _uiState.value = _uiState.value.copy(presentationJson = null, presentationError = null)
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
