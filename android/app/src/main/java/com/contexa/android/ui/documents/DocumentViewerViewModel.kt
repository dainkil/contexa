package com.contexa.android.ui.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contexa.android.api.DocumentApi
import com.contexa.android.api.DocumentView
import com.contexa.android.api.NewVersionRequest
import com.contexa.android.api.ReviseSpanRequest
import com.contexa.android.api.STALE_MESSAGE
import com.contexa.android.api.Span
import com.contexa.android.api.VersionStatus
import com.contexa.android.api.isStale
import com.contexa.android.api.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ViewerUiState(
    val loading: Boolean = true,
    val view: DocumentView? = null,
    val loadError: String? = null,
    /** 근거로 연결돼 노랗게 칠할 발언. 판을 바꾸면 지운다. */
    val highlightId: String? = null,
    /** 한 번 보여 주고 지우는 알림(올림·정정 결과). */
    val notice: String? = null,
    /** 새 판 창이 열려 있으면 그 본문. 닫혀 있으면 null. */
    val newVersion: String? = null,
    val newVersionError: String? = null,
    val uploadingVersion: Boolean = false,
    /** 정정 창이 열려 있으면 그 발언. */
    val revising: Span? = null,
    val reviseText: String = "",
    val reviseError: String? = null,
    val savingRevision: Boolean = false,
) {
    /** 지금 연 판의 처리 상태. */
    val openedVersion: VersionStatus?
        get() = view?.let { v -> v.versions.find { it.sourceVersionId == v.opened.sourceVersionId } }

    val latestNo: Int?
        get() = view?.let { v -> v.versions.lastOrNull()?.versionNo ?: v.opened.versionNo }

    val canSaveRevision: Boolean
        get() = revising != null && reviseText.isNotBlank() && reviseText.trim() != revising.text.trim()
}

/**
 * 원문 뷰어. 판을 골라 발언을 순서대로 본다.
 *
 * @param spanId 근거 발언 id. 있으면 그 발언이 들어 있는 판을 연다.
 */
class DocumentViewerViewModel(
    private val api: DocumentApi,
    private val projectId: String,
    private val documentId: String,
    spanId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(ViewerUiState(highlightId = spanId))
    val state: StateFlow<ViewerUiState> = _state

    /** 열 판. null이면 최신 판(또는 근거 발언이 들어 있는 판). */
    private var version: Int? = null
    private val reloads = MutableStateFlow(0)

    /** 문서를 불러오고, 연 판이 처리 중인 동안 POLL_MS마다 다시 불러온다. 화면이 보이는 동안에만 돌도록 화면이 부른다. */
    suspend fun watch() {
        reloads.collectLatest {
            while (loadOnce() && isActive(_state.value.openedVersion)) {
                delay(POLL_MS)
            }
        }
    }

    fun reload() {
        reloads.update { it + 1 }
    }

    private suspend fun loadOnce(): Boolean = try {
        val span = _state.value.highlightId
        val view = if (span != null) {
            api.open(projectId, documentId, span = span)
        } else {
            api.open(projectId, documentId, version = version)
        }
        _state.update { it.copy(loading = false, view = view, loadError = null) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _state.update { it.copy(loading = false, loadError = userMessage(e)) }
        false
    }

    /** @param versionNo null이면 최신 판. */
    fun openVersion(versionNo: Int?) {
        version = versionNo
        _state.update { it.copy(highlightId = null) }
        reload()
    }

    fun noticeShown() {
        _state.update { it.copy(notice = null) }
    }

    // ── 새 판 올리기 ──

    fun openNewVersion() {
        _state.update { it.copy(newVersion = "", newVersionError = null) }
    }

    fun closeNewVersion() {
        if (!_state.value.uploadingVersion) _state.update { it.copy(newVersion = null) }
    }

    fun editNewVersion(content: String) {
        _state.update { it.copy(newVersion = content, newVersionError = null) }
    }

    fun newVersionFailed(message: String) {
        _state.update { it.copy(newVersionError = message) }
    }

    fun uploadNewVersion() {
        val content = _state.value.newVersion ?: return
        if (_state.value.uploadingVersion) return
        if (content.isBlank()) {
            _state.update { it.copy(newVersionError = EMPTY_CONTENT_MESSAGE) }
            return
        }
        _state.update { it.copy(uploadingVersion = true, newVersionError = null) }
        viewModelScope.launch {
            try {
                val started = api.uploadVersion(projectId, documentId, NewVersionRequest(content))
                _state.update {
                    it.copy(
                        uploadingVersion = false,
                        newVersion = null,
                        notice = "${started.version.versionNo}판을 올렸습니다.",
                    )
                }
                openVersion(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 이전 판과 내용이 같으면 server가 거절한다. 그 문장을 그대로 보여 준다.
                _state.update { it.copy(uploadingVersion = false, newVersionError = userMessage(e)) }
            }
        }
    }

    // ── 발언 정정 ──

    fun startRevising(span: Span) {
        _state.update { it.copy(revising = span, reviseText = span.text, reviseError = null) }
    }

    fun stopRevising() {
        if (!_state.value.savingRevision) _state.update { it.copy(revising = null) }
    }

    fun editRevision(text: String) {
        _state.update { it.copy(reviseText = text, reviseError = null) }
    }

    /** 내가 본 발언 버전을 함께 보낸다. 그사이 다른 사람이 고쳤으면 server가 막고, 새로 불러온다. */
    fun saveRevision() {
        val current = _state.value
        val span = current.revising ?: return
        if (!current.canSaveRevision || current.savingRevision) return
        _state.update { it.copy(savingRevision = true, reviseError = null) }
        viewModelScope.launch {
            try {
                val changed = api.reviseSpan(
                    projectId,
                    span.ref.id,
                    ReviseSpanRequest(expectedRevision = span.ref.revision, text = current.reviseText.trim()),
                )
                _state.update {
                    it.copy(savingRevision = false, revising = null, notice = revisedMessage(changed.impacts))
                }
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isStale(e)) {
                    _state.update { it.copy(savingRevision = false, revising = null, notice = STALE_MESSAGE) }
                    reload()
                } else {
                    _state.update { it.copy(savingRevision = false, reviseError = userMessage(e)) }
                }
            }
        }
    }
}
