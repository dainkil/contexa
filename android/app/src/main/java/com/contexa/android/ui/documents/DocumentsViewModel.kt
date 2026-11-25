package com.contexa.android.ui.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contexa.android.api.DocumentApi
import com.contexa.android.api.DocumentStatus
import com.contexa.android.api.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DocumentsUiState(
    val loading: Boolean = true,
    val documents: List<DocumentStatus> = emptyList(),
    val loadError: String? = null,
    /** 올리기 창이 열려 있으면 그 폼의 값. 닫혀 있으면 null. */
    val form: UploadFormValues? = null,
    val formErrors: UploadFormErrors = UploadFormErrors(),
    val uploading: Boolean = false,
    val uploadError: String? = null,
    /** 지켜보던 문서의 분석이 끝났다. 화면이 알린 뒤 analysisNoticeShown()으로 내린다. */
    val analysisDone: Boolean = false,
)

/** 자료함: 올린 문서와 가장 최근 판의 처리 상태. */
class DocumentsViewModel(private val api: DocumentApi, private val projectId: String) : ViewModel() {

    private val _state = MutableStateFlow(DocumentsUiState())
    val state: StateFlow<DocumentsUiState> = _state

    private val reloads = MutableStateFlow(0)

    /** 처리 중이던 문서. 다음에 불러왔을 때 끝났으면 알린다. */
    private var watching: Set<String> = emptySet()

    /**
     * 목록을 불러오고, 처리 중인 문서가 있는 동안 POLL_MS마다 다시 불러온다. 모두 끝나면 reload()를 기다린다.
     * 화면이 보이는 동안에만 돌도록 화면이 부른다(끝나지 않는다. 취소로 멈춘다).
     */
    suspend fun watch() {
        reloads.collectLatest {
            while (loadOnce() && _state.value.documents.any { isActive(it.latest) }) {
                delay(POLL_MS)
            }
        }
    }

    fun reload() {
        reloads.update { it + 1 }
    }

    private suspend fun loadOnce(): Boolean = try {
        val documents = api.list(projectId)
        val watch = finishedAnalysis(watching, documents)
        watching = watch.watching
        _state.update {
            it.copy(loading = false, documents = documents, loadError = null, analysisDone = it.analysisDone || watch.finished)
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _state.update { it.copy(loading = false, loadError = userMessage(e)) }
        false
    }

    fun analysisNoticeShown() {
        _state.update { it.copy(analysisDone = false) }
    }

    fun openForm() {
        _state.update { it.copy(form = UploadFormValues(), formErrors = UploadFormErrors(), uploadError = null) }
    }

    fun closeForm() {
        if (!_state.value.uploading) _state.update { it.copy(form = null) }
    }

    fun editForm(change: (UploadFormValues) -> UploadFormValues) {
        _state.update { state ->
            val form = state.form ?: return@update state
            val next = change(form)
            // 한 번 검사에 걸린 뒤에는 고칠 때마다 다시 검사해 오류 문장을 지운다.
            state.copy(form = next, formErrors = if (state.formErrors.ok) state.formErrors else validateUpload(next))
        }
    }

    fun uploadFailed(message: String) {
        _state.update { it.copy(uploadError = message) }
    }

    /** @param onUploaded 새 문서의 id를 받는다. 화면은 그 문서의 원문 뷰어로 간다. */
    fun upload(onUploaded: (documentId: String) -> Unit) {
        val form = _state.value.form ?: return
        if (_state.value.uploading) return
        val errors = validateUpload(form)
        if (!errors.ok) {
            _state.update { it.copy(formErrors = errors) }
            return
        }
        _state.update { it.copy(uploading = true, uploadError = null, formErrors = errors) }
        viewModelScope.launch {
            try {
                val started = api.upload(projectId, toDocumentInput(form))
                // 원문 뷰어에 갔다 돌아왔을 때 이미 끝났어도 알릴 수 있게 바로 지켜본다.
                watching = watching + started.version.documentId
                _state.update { it.copy(uploading = false, form = null) }
                onUploaded(started.version.documentId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(uploading = false, uploadError = userMessage(e)) }
            }
        }
    }
}
