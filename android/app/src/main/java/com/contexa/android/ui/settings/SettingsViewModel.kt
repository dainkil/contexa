package com.contexa.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contexa.android.api.AuthApi
import com.contexa.android.api.userMessage
import com.contexa.android.session.SessionState
import com.contexa.android.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    /** 로그인한 이메일. 기기에 저장돼 있어 바로 보인다. */
    val email: String = "",
    /** 가입할 때 적은 이름. server에서 읽어 오기 전에는 null. */
    val name: String? = null,
    val loadError: String? = null,
)

/** 설정: 내 정보를 보여 주고 로그아웃한다. */
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
        viewModelScope.launch { session.signOut() }
    }
}
