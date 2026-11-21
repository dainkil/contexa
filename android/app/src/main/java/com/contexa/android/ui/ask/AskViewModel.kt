package com.contexa.android.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contexa.android.api.ArtifactStatus
import com.contexa.android.api.ArtifactView
import com.contexa.android.api.AskRequest
import com.contexa.android.api.GenerationApi
import com.contexa.android.api.userMessage
import com.contexa.android.ui.documents.POLL_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 대화의 한 차례: 내 질문과 그 답. view가 null이면 방금 보내서 아직 열어 보지 못했다(쓰는 중으로 그린다). */
data class ChatTurn(val id: String, val question: String, val view: ArtifactView?)

data class AskUiState(
    /** 입력 칸에 쓰고 있는 질문. */
    val input: String = "",
    val sending: Boolean = false,
    val sendError: String? = null,
    val loading: Boolean = true,
    /** 지난 질문과 답. 오래된 것부터. */
    val turns: List<ChatTurn> = emptyList(),
    val loadError: String? = null,
    /** 오래 기다려서 다시 열어 보기를 멈췄다. */
    val gaveUp: Boolean = false,
)

/** 질문 탭: 대화처럼 묻고 답을 받는다. 지난 질문도 같은 대화에 이어서 보인다. */
class AskViewModel(private val api: GenerationApi, private val projectId: String) : ViewModel() {

    private val _state = MutableStateFlow(AskUiState())
    val state: StateFlow<AskUiState> = _state

    private val reloads = MutableStateFlow(0)

    /**
     * 대화를 불러오고, 쓰는 중인 답이 있으면 POLL_MS마다 그 답만 다시 연다. MAX_POLLS번을 넘기면 멈추고 reload()를 기다린다.
     * 화면이 보이는 동안에만 돌도록 화면이 부른다(끝나지 않는다. 취소로 멈춘다).
     */
    suspend fun watch() {
        reloads.collectLatest {
            _state.update { it.copy(gaveUp = false) }
            var ok = loadAll()
            var polls = 0
            while (ok && _state.value.turns.any { isWaiting(it.view) }) {
                if (++polls > MAX_POLLS) {
                    _state.update { it.copy(gaveUp = true) }
                    break
                }
                delay(POLL_MS)
                ok = refreshWaiting()
            }
        }
    }

    fun reload() {
        reloads.update { it + 1 }
    }

    /** 최근 질문 MAX_TURNS개를 연다. 이미 다 쓴 답은 다시 열지 않는다. */
    private suspend fun loadAll(): Boolean = guarded {
        val done = _state.value.turns.mapNotNull { it.view }.filter { it.status == ArtifactStatus.PUBLISHED }.associateBy { it.id }
        val turns = api.list(projectId).take(MAX_TURNS).reversed().map { row ->
            ChatTurn(row.id, row.question, done[row.id] ?: api.get(projectId, row.id))
        }
        _state.update { it.copy(loading = false, turns = turns, loadError = null) }
    }

    private suspend fun refreshWaiting(): Boolean = guarded {
        val turns = _state.value.turns.map { turn ->
            if (isWaiting(turn.view)) turn.copy(view = api.get(projectId, turn.id)) else turn
        }
        _state.update { it.copy(turns = turns, loadError = null) }
    }

    private suspend fun guarded(load: suspend () -> Unit): Boolean = try {
        load()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _state.update { it.copy(loading = false, loadError = userMessage(e)) }
        false
    }

    fun editInput(text: String) {
        _state.update { it.copy(input = text, sendError = null) }
    }

    /** 질문을 보낸다. 답할 언어는 질문의 글자를 보고 정한다. 보내면 대화 끝에 바로 붙이고 답을 기다린다. */
    fun send() {
        val current = _state.value
        if (!canAsk(current.input) || current.sending) return
        val question = current.input.trim()
        _state.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            try {
                val requested = api.ask(projectId, AskRequest(question = question))
                _state.update {
                    it.copy(sending = false, input = "", turns = it.turns + ChatTurn(requested.artifactId, question, null))
                }
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(sending = false, sendError = userMessage(e)) }
            }
        }
    }
}
