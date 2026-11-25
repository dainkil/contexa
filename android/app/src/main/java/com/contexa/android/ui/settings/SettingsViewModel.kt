package com.contexa.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contexa.android.api.AuthApi
import com.contexa.android.api.userMessage
import com.contexa.android.session.SessionState
import com.contexa.android.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class SettingsUiState(
    /** 로그인한 이메일. 기기에 저장돼 있어 바로 보인다. */
    val email: String = "",
    /** 가입할 때 적은 이름. server에서 읽어 오기 전에는 null. */
    val name: String? = null,
    val loadError: String? = null,
    /** 로그아웃하는 중. 두 번 누르지 않게 버튼을 막는다. */
    val signingOut: Boolean = false,
)

/** server에 토큰 폐기를 기다리는 가장 긴 시간. 연결이 없어도 로그아웃은 이만큼만 늦어진다. */
const val LOGOUT_TIMEOUT_MILLIS = 3_000L

/**
 * 로그아웃 순서: server에 토큰 폐기를 부탁하고(revoke), 결과와 상관없이 기기의 세션을 비운다(clear).
 * revoke가 실패하거나(연결 없음, 이미 만료) 화면이 닫혀 취소돼도 clear는 반드시 한다.
 * 세션을 먼저 비우면 토큰이 사라져 폐기 요청을 보낼 수 없으므로 순서를 지킨다.
 */
suspend fun revokeThenClear(revoke: suspend () -> Unit, clear: suspend () -> Unit) {
    try {
        revoke()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 폐기하지 못한 토큰은 server에서 시간이 지나면 만료된다.
    } finally {
        withContext(NonCancellable) { clear() }
    }
}

/** 설정: 내 정보를 보여 주고 로그아웃한다(server의 토큰도 폐기). */
class SettingsViewModel(private val api: AuthApi, private val session: SessionStore) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(email = (session.state.value as? SessionState.SignedIn)?.email.orEmpty()),
    )
    val state: StateFlow<SettingsUiState> = _state

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            try {
                val me = api.me()
                _state.update { it.copy(email = me.email, name = me.displayName, loadError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadError = userMessage(e)) }
            }
        }
    }

    /** 로그아웃하면 세션이 비워져 화면이 로그인으로 바뀐다. 다른 계정으로 로그인할 때도 이것을 쓴다. */
    fun signOut() {
        if (_state.value.signingOut) return
        _state.update { it.copy(signingOut = true) }
        viewModelScope.launch {
            revokeThenClear(
                revoke = { withTimeoutOrNull(LOGOUT_TIMEOUT_MILLIS) { api.logout() } },
                clear = { session.signOut() },
            )
        }
    }
}
