package com.contexa.android.ui.context

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contexa.android.api.ContextApi
import com.contexa.android.api.AcceptanceRequest
import com.contexa.android.api.ContextPacket
import com.contexa.android.api.Direction
import com.contexa.android.api.DocumentApi
import com.contexa.android.api.TaskStatus
import com.contexa.android.api.TaskStatusRequest
import com.contexa.android.api.isStale
import com.contexa.android.api.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 확정 = 사람이 확인해 지금 유효한 결정, 할 일 = 확인된 할 일, 사람 = 확인된 인물·기관과 그 언어별 표기.
 * AI 후보 카드는 검토함에서만 다룬다.
 */
enum class ContextSection(val label: String) { CONFIRMED("확정"), TASKS("할 일"), PEOPLE("사람") }

data class ContextUiState(
    val section: ContextSection = ContextSection.CONFIRMED,
    val loading: Boolean = true,
    val loadError: String? = null,
    /** 확정된 결정을 안건별로. */
    val groups: List<TopicGroup> = emptyList(),
    /** "확인" 칸에 적을 알아야 하는 사항. */
    val notices: List<Notice> = emptyList(),
    /** 내게 맡겨진 할 일만 본다. */
    val mine: Boolean = false,
    val tasks: List<TaskRow> = emptyList(),
    /** 확인된 인물·기관. */
    val people: List<PersonRow> = emptyList(),
    /** 바꾸는 중인 할 일의 id. */
    val busyId: String? = null,
    /** 한 번 보여 줄 알림. */
    val notice: String? = null,
)

/** 맥락 탭: 확정된 결정과 확인된 할 일. */
class ContextViewModel(private val api: ContextApi, private val projectId: String) : ViewModel() {

    private val _state = MutableStateFlow(ContextUiState())
    val state: StateFlow<ContextUiState> = _state

    private var loadJob: Job? = null

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val mine = _state.value.mine
                val current = api.state(projectId)
                val tasks = api.tasks(projectId, mine)
                _state.update {
                    it.copy(
                        loading = false,
                        loadError = null,
                        groups = confirmedBoard(current),
                        notices = notices(current),
                        tasks = taskRows(tasks).filter { row -> row.canChange },
                        people = personRows(current),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = userMessage(e)) }
            }
        }
    }

    fun show(section: ContextSection) {
        _state.update { it.copy(section = section) }
    }

    fun showMine(mine: Boolean) {
        if (_state.value.mine == mine) return
        _state.update { it.copy(mine = mine, loading = true) }
        reload()
    }

    fun changeStatus(row: TaskRow, next: TaskStatus) = change(row.task.node.id) {
        val changed = api.taskStatus(projectId, row.task.node.id, TaskStatusRequest(next, row.task.node.revision))
        statusChangedMessage(next, changed.impacts.size)
    }

    fun accept(row: TaskRow, accepted: Boolean) = change(row.task.node.id) {
        api.acceptance(projectId, row.task.node.id, AcceptanceRequest(accepted))
        if (accepted) "수락했습니다." else "거절했습니다."
    }

    /** 카드 하나를 바꾸고 새로 읽는다. 조건이 따라 바뀔 수 있어 화면 전체를 다시 불러온다. */
    private fun change(id: String, send: suspend () -> String) {
        if (_state.value.busyId != null) return
        _state.update { it.copy(busyId = id) }
        viewModelScope.launch {
            try {
                val message = send()
                _state.update { it.copy(busyId = null, notice = message) }
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busyId = null, notice = userMessage(e)) }
                if (isStale(e)) reload()
            }
        }
    }

    fun noticeShown() {
        _state.update { it.copy(notice = null) }
    }
}

data class PacketUiState(
    val loading: Boolean = true,
    val packet: ContextPacket? = null,
    val loadError: String? = null,
    val direction: Direction = Direction.BASIS,
    /** 문서 id → 제목. 원문 인용에 문서 이름을 붙인다. */
    val documentTitles: Map<String, String> = emptyMap(),
)

/**
 * 카드 하나에서 읽어 온 맥락(결정 이력, 근거 따라가기).
 *
 * @param load 방향을 받아 읽어 온다. 이력처럼 방향이 없으면 무시한다.
 */
class PacketViewModel(
    private val documents: DocumentApi,
    private val projectId: String,
    private val load: suspend (Direction) -> ContextPacket,
) : ViewModel() {

    private val _state = MutableStateFlow(PacketUiState())
    val state: StateFlow<PacketUiState> = _state

    private var loadJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val packet = load(_state.value.direction)
                _state.update { it.copy(loading = false, packet = packet, loadError = null) }
                if (_state.value.documentTitles.isEmpty() && packet.sources.isNotEmpty()) loadTitles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = userMessage(e)) }
            }
        }
    }

    fun pick(direction: Direction) {
        if (_state.value.direction == direction) return
        _state.update { it.copy(direction = direction, loading = true) }
        reload()
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
}
