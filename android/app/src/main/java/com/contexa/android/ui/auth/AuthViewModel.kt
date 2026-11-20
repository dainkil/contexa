package com.contexa.android.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contexa.android.api.AuthApi
import com.contexa.android.api.LoginRequest
import com.contexa.android.api.SignUpRequest
import com.contexa.android.api.userMessage
import com.contexa.android.session.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val loading: Boolean = false,
    /** server가 거절한 이유(이메일·비밀번호가 틀림 등). */
    val serverError: String? = null,
)

/** 로그인과 가입. 성공하면 세션이 채워지고, 화면은 세션을 보고 바뀐다. */
class AuthViewModel(private val api: AuthApi, private val session: SessionStore) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state

    fun signIn(email: String, password: String) = submit {
        val token = api.login(LoginRequest(email, password)).token
        session.signIn(token, email)
    }

    /** 가입하면 바로 로그인한다. */
    fun signUp(displayName: String, email: String, password: String) = submit {
        api.signUp(SignUpRequest(email = email, displayName = displayName.trim(), password = password))
        val token = api.login(LoginRequest(email, password)).token
        session.signIn(token, email)
    }

    private fun submit(action: suspend () -> Unit) {
        if (_state.value.loading) return
        _state.value = AuthUiState(loading = true)
        viewModelScope.launch {
            try {
                action()
                _state.update { it.copy(loading = false) }
            } catch (e: Exception) {
                _state.value = AuthUiState(serverError = userMessage(e))
            }
        }
    }
}
