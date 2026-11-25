package com.contexa.android.ui.context

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contexa.android.api.ContextApi
import com.contexa.android.api.DocumentApi
import com.contexa.android.api.NameCard
import com.contexa.android.api.NameForm
import com.contexa.android.api.NameRequest
import com.contexa.android.api.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonUiState(
    val loading: Boolean = true,
    val card: NameCard? = null,
    val loadError: String? = null,
    /** 이름이 겹치는 다른 인물·기관. 아직 못 찾았으면 null. */
    val similar: List<SimilarPerson>? = null,
    val similarError: String? = null,
    /** 확인·거절을 보내는 중인 표기. */
    val busy: NameForm? = null,
    /** 한 번 보여 줄 알림. */
    val notice: String? = null,
    /** 문서 id → 제목. 원문 인용에 문서 이름을 붙인다. */
    val documentTitles: Map<String, String> = emptyMap(),
)

/**
 * 인물·기관 한 명의 이름 검수: 언어별 표기를 확인·거절하고, 근거 원문과 이름이 겹치는 다른 인물을 본다.
 * 같은 사람으로 합치는 일은 하지 않는다(자동 병합 금지).
 */
class PersonViewModel(
    private val api: ContextApi,
    private val documents: DocumentApi,
    private val projectId: String,
    private val entityId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(PersonUiState())
    val state: StateFlow<PersonUiState> = _state

    private var loadJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val card = try {
                api.nameCard(projectId, entityId).also { card ->
                    _state.update { it.copy(loading = false, card = card, loadError = null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = userMessage(e)) }
                return@launch
            }
            if (_state.value.documentTitles.isEmpty() && card.sources.isNotEmpty()) loadTitles()
            loadSimilar(card)
        }
    }

    /** 표기마다 이름 찾기를 불러 겹치는 인물을 모은다. 못 찾아도 이름 검수는 할 수 있으므로 이유만 적는다. */
    private suspend fun loadSimilar(card: NameCard) {
        try {
            val found = searchSurfaces(card).map { surface -> surface to api.resolveEntity(projectId, surface) }
            _state.update { it.copy(similar = similarPeople(entityId, found), similarError = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(similarError = userMessage(e)) }
        }
    }

    /** 문서 이름은 꾸밈이다. 못 불러와도 내용은 읽을 수 있으므로 실패는 넘긴다. */
    private suspend fun loadTitles() {
        try {
            val titles = documents.list(projectId).associate { it.document.id to it.document.title }
            _state.update { it.copy(documentTitles = titles) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * 표기를 확인하거나 거절한다. 확인할 때는 그 표기가 나온 발언을 근거로 함께 보낸다(없으면 근거 없이).
     * 바뀐 표기는 이 인물에 기대는 카드에 영향을 주므로, 그 수를 알려 준다.
     */
    fun act(form: NameForm, action: NameAction) {
        val card = _state.value.card ?: return
        if (_state.value.busy != null) return
        val evidence = if (action == NameAction.CONFIRM) sourcesFor(form, card.sources).firstOrNull()?.span?.id else null
        _state.update { it.copy(busy = form) }
        viewModelScope.launch {
            try {
                val changed = api.name(
                    projectId,
                    entityId,
                    NameRequest(form.lang, form.form, confirmed = action == NameAction.CONFIRM, evidenceSpanId = evidence),
                )
                _state.update { it.copy(busy = null, notice = nameChangedMessage(action, changed.impacts.size)) }
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = null, notice = userMessage(e)) }
            }
        }
    }

    fun noticeShown() {
        _state.update { it.copy(notice = null) }
    }
}
