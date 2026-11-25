package com.contexa.android.ui.reviews

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contexa.android.api.DocumentApi
import com.contexa.android.api.NodeType
import com.contexa.android.api.RecheckCause
import com.contexa.android.api.ReviewApi
import com.contexa.android.api.ReviewItem
import com.contexa.android.api.ReviewState
import com.contexa.android.api.Verdict
import com.contexa.android.api.isStale
import com.contexa.android.api.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import retrofit2.HttpException

/** 묶음 하나에서 고르던 것. 묶음이 바뀌면(버전이 오르면) 열쇠가 달라져 저절로 비워진다. */
data class ReviewDraft(
    /** 줄 id → 따로 고른 판단 */
    val choices: Map<String, Verdict> = emptyMap(),
    /** 고치는 중이면 칸 이름 → 값. 아니면 null. */
    val values: Map<String, String>? = null,
)

fun draftKey(item: ReviewItem): String = "${item.id}:${item.version}"

/**
 * 검토함의 탭. 확인할 것·처리한 것은 묶음의 상태(ReviewState)로, 다시 확인은 server의 따로 된 목록으로 읽는다.
 * 다시 확인 = 이미 확인된 카드인데 기대는 카드나 발언이 그 뒤에 바뀐 것.
 */
enum class ReviewTab(val label: String) {
    OPEN("확인할 것"),
    RECHECK("다시 확인"),
    DONE("처리한 것"),
}

data class ReviewsUiState(
    val tab: ReviewTab = ReviewTab.OPEN,
    /** 종류 거르기. null이면 모든 종류. */
    val type: NodeType? = null,
    val loading: Boolean = true,
    val items: List<ReviewItem> = emptyList(),
    /** 다음 쪽을 부를 때 보낼 값. 더 없으면 null. */
    val next: String? = null,
    val loadingMore: Boolean = false,
    val loadError: String? = null,
    val moreError: String? = null,
    /** 문서 id → 제목. 근거 원문에 문서 이름을 붙인다. */
    val documentTitles: Map<String, String> = emptyMap(),
    val drafts: Map<String, ReviewDraft> = emptyMap(),
    /** 다시 확인 탭에서 묶음 id → 다시 봐야 하는 이유. */
    val causes: Map<String, List<RecheckCause>> = emptyMap(),
    /** 판단을 보내는 중인 묶음의 id. */
    val busyId: String? = null,
    /** 한 번 보여 주고 지우는 알림. */
    val notice: String? = null,
)

/** 검토함: AI가 뽑은 후보를 카드 묶음 단위로 확인·보류·거절·정정한다. */
class ReviewsViewModel(
    private val api: ReviewApi,
    private val documents: DocumentApi,
    private val projectId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ReviewsUiState())
    val state: StateFlow<ReviewsUiState> = _state

    private var loadJob: Job? = null

    /** 첫 쪽부터 다시 불러온다. 보이던 묶음은 새 것이 올 때까지 그대로 둔다. */
    fun reload() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, loadError = null, moreError = null, loadingMore = false) }
        loadJob = viewModelScope.launch {
            val current = _state.value
            try {
                val page = page(current, after = null)
                _state.update { it.copy(loading = false, items = page.items, causes = page.causes, next = page.next) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = userMessage(e)) }
            }
            loadTitles()
        }
    }

    /** 문서 이름은 꾸밈이다. 못 불러와도 검토는 할 수 있으므로 실패는 넘긴다. */
    private suspend fun loadTitles() {
        try {
            val titles = documents.list(projectId).associate { it.document.id to it.document.title }
            _state.update { it.copy(documentTitles = titles) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    fun loadMore() {
        val current = _state.value
        val after = current.next ?: return
        if (current.loading || current.loadingMore) return
        _state.update { it.copy(loadingMore = true, moreError = null) }
        loadJob = viewModelScope.launch {
            try {
                val page = page(current, after = after)
                _state.update { state ->
                    val known = state.items.map { it.id }.toSet()
                    state.copy(
                        loadingMore = false,
                        items = state.items + page.items.filter { it.id !in known },
                        causes = state.causes + page.causes,
                        next = page.next,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false, moreError = userMessage(e)) }
            }
        }
    }

    private data class Loaded(val items: List<ReviewItem>, val causes: Map<String, List<RecheckCause>>, val next: String?)

    /** 지금 탭의 한 쪽. 다시 확인 목록은 종류로 거르는 주소가 없어서 받은 뒤에 거른다. */
    private suspend fun page(current: ReviewsUiState, after: String?): Loaded = when (current.tab) {
        ReviewTab.RECHECK -> {
            val page = api.rechecks(projectId, after = after)
            val kept = page.items.filter { current.type == null || it.review.owner.type == current.type }
            Loaded(kept.map { it.review }, kept.associate { it.review.id to it.causes }, page.next)
        }
        ReviewTab.OPEN, ReviewTab.DONE -> {
            val state = if (current.tab == ReviewTab.OPEN) ReviewState.OPEN else ReviewState.DONE
            val page = api.list(projectId, state, current.type, after = after)
            Loaded(page.items, emptyMap(), page.next)
        }
    }

    fun showTab(tab: ReviewTab) {
        if (_state.value.tab == tab) return
        _state.update { it.copy(tab = tab, items = emptyList(), causes = emptyMap(), next = null) }
        reload()
    }

    fun filterType(type: NodeType?) {
        if (_state.value.type == type) return
        _state.update { it.copy(type = type, items = emptyList(), next = null) }
        reload()
    }

    fun noticeShown() {
        _state.update { it.copy(notice = null) }
    }

    private fun editDraft(item: ReviewItem, change: (ReviewDraft) -> ReviewDraft) {
        _state.update { state ->
            val key = draftKey(item)
            state.copy(drafts = state.drafts + (key to change(state.drafts[key] ?: ReviewDraft())))
        }
    }

    /** @param verdict null이면 "따름"으로 되돌린다. */
    fun choose(item: ReviewItem, edgeId: String, verdict: Verdict?) = editDraft(item) { draft ->
        draft.copy(choices = if (verdict == null) draft.choices - edgeId else draft.choices + (edgeId to verdict))
    }

    /** @param values null이면 고치기를 그만둔다. */
    fun editValues(item: ReviewItem, values: Map<String, String>?) = editDraft(item) { it.copy(values = values) }

    /**
     * 판단을 보낸다.
     *
     * @param verdict     묶음 버튼으로 내린 판단. 고른 줄만 반영하거나 고친 내용만 저장할 때는 null
     * @param corrections 바뀐 카드 값
     */
    fun act(item: ReviewItem, verdict: Verdict?, corrections: Map<String, JsonElement> = emptyMap()) {
        if (_state.value.busyId != null) return
        val choices = _state.value.drafts[draftKey(item)]?.choices.orEmpty()
        val action = buildAction(item, verdict, choices, corrections)
        if (action == null) {
            _state.update { it.copy(notice = "보낼 판단이 없습니다.") }
            return
        }
        _state.update { it.copy(busyId = item.id) }
        viewModelScope.launch {
            try {
                val result = api.act(projectId, item.id, action)
                _state.update { state ->
                    val updated = result.item
                    state.copy(
                        busyId = null,
                        notice = resultMessage(verdict, corrections.isNotEmpty(), result.impacts),
                        drafts = state.drafts - draftKey(item),
                        // 처리가 끝나 이 탭에서 빠지는 묶음은 지우고, 남는 묶음은 새 내용으로 바꾼다.
                        // 다시 확인 탭에서는 묶음을 처리하면 server가 그 카드의 "다시 확인"을 닫는다.
                        items = state.items.mapNotNull { existing ->
                            when {
                                existing.id != updated.id -> existing
                                staysIn(updated, state.tab) -> updated
                                else -> null
                            }
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busyId = null, notice = userMessage(e)) }
                // 그사이 묶음이 바뀌었다. 새로 읽어 와야 다시 판단할 수 있다.
                if (isStale(e)) reload()
            }
        }
    }

    /**
     * 다시 봤고 바뀐 것이 없다고 닫는다. 고치거나 철회하려면 묶음 버튼(act)을 쓴다.
     * 그사이 다른 사람이 닫았으면(409) 목록을 새로 읽는다.
     */
    fun keep(item: ReviewItem) {
        if (_state.value.busyId != null) return
        _state.update { it.copy(busyId = item.id) }
        viewModelScope.launch {
            try {
                api.resolveRecheck(projectId, item.owner.type, item.owner.id)
                _state.update { state ->
                    state.copy(
                        busyId = null,
                        notice = "바뀐 것 없음으로 닫았습니다.",
                        items = state.items.filter { it.id != item.id },
                        causes = state.causes - item.id,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busyId = null, notice = userMessage(e)) }
                if (e is HttpException && e.code() == 409) reload()
            }
        }
    }
}

/** 처리한 뒤에도 이 탭에 남는 묶음인가. 다시 확인 탭에서는 처리하면 빠진다. */
fun staysIn(item: ReviewItem, tab: ReviewTab): Boolean = when (tab) {
    ReviewTab.OPEN -> item.state == ReviewState.OPEN
    ReviewTab.DONE -> item.state == ReviewState.DONE
    ReviewTab.RECHECK -> false
}
