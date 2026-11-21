package com.contexa.android.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contexa.android.api.ArtifactSummary
import com.contexa.android.api.ArtifactView
import com.contexa.android.api.AskRequest
import com.contexa.android.api.DocumentApi
import com.contexa.android.api.GenKind
import com.contexa.android.api.GenerationApi
import com.contexa.android.api.userMessage
import com.contexa.android.ui.documents.POLL_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AskUiState(
    val question: String = "",
    /** 답변 언어 코드(ko·en·ar). */
    val lang: String = "ko",
    val asking: Boolean = false,
    val askError: String? = null,
    val loading: Boolean = true,
    /** 지난 질문. 최근 것부터. */
    val history: List<ArtifactSummary> = emptyList(),
    val loadError: String? = null,
)

/** 질문 탭: 질문을 보내고, 지난 질문을 다시 연다. */
class AskViewModel(private val api: GenerationApi, private val projectId: String) : ViewModel() {

    private val _state = MutableStateFlow(AskUiState())
    val state: StateFlow<AskUiState> = _state

    private var loadJob: Job? = null

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val history = api.list(projectId)
                _state.update { it.copy(loading = false, history = history, loadError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = userMessage(e)) }
            }
        }
    }

    fun editQuestion(text: String) {
        _state.update { it.copy(question = text, askError = null) }
    }

    fun pickLang(code: String) {
        _state.update { it.copy(lang = code) }
    }

    /** @param onAsked 새 글의 id를 받는다. 화면은 그 답변 화면으로 간다. */
    fun ask(onAsked: (artifactId: String) -> Unit) {
        val current = _state.value
        if (!canAsk(current.question)) return
        send(AskRequest(question = current.question.trim(), lang = current.lang), clearQuestion = true, onAsked)
    }

    /** 프로젝트 현황을 정리한 글(브리핑)을 요청한다. 쓰던 질문은 그대로 둔다. */
    fun askBriefing(onAsked: (artifactId: String) -> Unit) {
        send(AskRequest(GenKind.BRIEFING, BRIEFING_QUESTION, _state.value.lang), clearQuestion = false, onAsked)
    }

    private fun send(request: AskRequest, clearQuestion: Boolean, onAsked: (String) -> Unit) {
        if (_state.value.asking) return
        _state.update { it.copy(asking = true, askError = null) }
        viewModelScope.launch {
            try {
                val requested = api.ask(projectId, request)
                _state.update { it.copy(asking = false, question = if (clearQuestion) "" else it.question) }
                onAsked(requested.artifactId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(asking = false, askError = userMessage(e)) }
            }
        }
    }
}

data class AnswerUiState(
    val loading: Boolean = true,
    val view: ArtifactView? = null,
    val loadError: String? = null,
    /** 오래 기다려서 다시 열어 보기를 멈췄다. */
    val gaveUp: Boolean = false,
    /** 문서 id → 제목. 인용에 문서 이름을 붙인다. */
    val documentTitles: Map<String, String> = emptyMap(),
)

/** 답변 하나. 아직 쓰는 중이면 다 쓸 때까지 다시 열어 본다. */
class AnswerViewModel(
    private val api: GenerationApi,
    private val documents: DocumentApi,
    private val projectId: String,
    private val artifactId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(AnswerUiState())
    val state: StateFlow<AnswerUiState> = _state

    private val reloads = MutableStateFlow(0)

    /**
     * 글을 열고, 쓰는 중(PENDING)이면 POLL_MS마다 다시 연다. MAX_POLLS번을 넘기면 멈추고 reload()를 기다린다.
     * 화면이 보이는 동안에만 돌도록 화면이 부른다(끝나지 않는다. 취소로 멈춘다).
     */
    suspend fun watch() {
        reloads.collectLatest {
            _state.update { it.copy(gaveUp = false) }
            var polls = 0
            while (loadOnce() && isWaiting(_state.value.view)) {
                if (++polls > MAX_POLLS) {
                    _state.update { it.copy(gaveUp = true) }
                    break
                }
                delay(POLL_MS)
            }
            if (_state.value.documentTitles.isEmpty() && _state.value.view?.citations?.isNotEmpty() == true) loadTitles()
        }
    }

    fun reload() {
        reloads.update { it + 1 }
    }

    private suspend fun loadOnce(): Boolean = try {
        val view = api.get(projectId, artifactId)
        _state.update { it.copy(loading = false, view = view, loadError = null) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _state.update { it.copy(loading = false, loadError = userMessage(e)) }
        false
    }

    /** 문서 이름은 꾸밈이다. 못 불러와도 답변은 읽을 수 있으므로 실패는 넘긴다. */
    private suspend fun loadTitles() {
        try {
            val titles = documents.list(projectId).associate { it.document.id to it.document.title }
            _state.update { it.copy(documentTitles = titles) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
