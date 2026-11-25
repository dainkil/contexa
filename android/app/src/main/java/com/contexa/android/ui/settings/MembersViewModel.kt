package com.contexa.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contexa.android.api.AuthApi
import com.contexa.android.api.Member
import com.contexa.android.api.MemberRequest
import com.contexa.android.api.Role
import com.contexa.android.api.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MembersUiState(
    val members: List<Member> = emptyList(),
    /** 처음 읽는 중. 목록이 있으면 다시 읽어도 조용히 바꾼다. */
    val loading: Boolean = true,
    val loadError: String? = null,
    /** 넣을 사람의 이메일과 역할 */
    val email: String = "",
    val role: Role = Role.MEMBER,
    val inviting: Boolean = false,
    val inviteError: String? = null,
    /** 역할을 바꾸는 중인 사람의 userId */
    val changingId: String? = null,
    val changeError: String? = null,
    /** 한 번 보여 줄 알림. 보여 준 뒤 noticeShown()으로 비운다. */
    val notice: String? = null,
) {
    /** 목록에서 읽은 나의 역할. 못 읽었으면 null(프로젝트를 열 때 받은 역할을 그대로 쓴다). */
    val myRole: Role? get() = myRole(members)
}

/**
 * 프로젝트 구성원: 목록, 넣기, 역할 바꾸기.
 * 목록의 내 역할은 화면 권한(검토할 수 있는지)에도 쓰므로 프로젝트 화면이 다시 보일 때마다 refresh한다.
 */
class MembersViewModel(private val api: AuthApi, private val projectId: String) : ViewModel() {

    private val _state = MutableStateFlow(MembersUiState())
    val state: StateFlow<MembersUiState> = _state

    private var loadJob: Job? = null

    /** 목록을 다시 읽는다. 이미 읽는 중이면 그 결과를 기다린다. */
    fun refresh() {
        if (loadJob?.isActive == true) return
        load()
    }

    /** 넣거나 바꾼 뒤처럼 바뀐 것이 확실할 때: 읽던 것을 버리고 새로 읽는다. */
    private fun load() {
        loadJob?.cancel()
        _state.update { it.copy(loading = it.members.isEmpty(), loadError = null) }
        loadJob = viewModelScope.launch {
            try {
                val members = api.listMembers(projectId)
                _state.update { it.copy(loading = false, members = members, loadError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = userMessage(e)) }
            }
        }
    }

    fun editEmail(email: String) {
        _state.update { it.copy(email = email, inviteError = null) }
    }

    fun pickRole(role: Role) {
        _state.update { it.copy(role = role) }
    }

    /** 가입한 사람을 이메일로 넣는다. 이미 구성원이면 server가 역할만 바꾼다. */
    fun invite() {
        val current = _state.value
        if (current.inviting) return
        val email = current.email.trim()
        inviteError(email)?.let { message ->
            _state.update { it.copy(inviteError = message) }
            return
        }
        val existing = memberByEmail(current.members, email)
        val role = current.role
        _state.update { it.copy(inviting = true, inviteError = null) }
        viewModelScope.launch {
            try {
                api.setMember(projectId, MemberRequest(email, role))
                _state.update {
                    it.copy(
                        inviting = false,
                        email = "",
                        role = Role.MEMBER,
                        notice = memberNotice(existing?.displayName ?: email, role, existed = existing != null),
                    )
                }
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(inviting = false, inviteError = userMessage(e)) }
            }
        }
    }

    /** 구성원의 역할을 바꾼다. 소유자만 다른 사람의 이메일을 받으므로, 이메일이 없으면 바꿀 수 없다. */
    fun changeRole(member: Member, role: Role) {
        val email = member.email ?: return
        if (_state.value.changingId != null || member.role == role) return
        _state.update { it.copy(changingId = member.userId, changeError = null) }
        viewModelScope.launch {
            try {
                api.setMember(projectId, MemberRequest(email, role))
                _state.update {
                    it.copy(changingId = null, notice = memberNotice(member.displayName, role, existed = true))
                }
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(changingId = null, changeError = userMessage(e)) }
            }
        }
    }

    fun noticeShown() {
        _state.update { it.copy(notice = null) }
    }
}
