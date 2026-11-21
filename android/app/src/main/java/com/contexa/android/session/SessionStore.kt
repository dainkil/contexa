package com.contexa.android.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

sealed interface SessionState {
    /** 저장된 로그인을 아직 읽는 중. */
    data object Loading : SessionState

    /** @param expired 토큰이 만료·폐기돼서 로그아웃됐는가. */
    data class SignedOut(val expired: Boolean = false) : SessionState

    data class SignedIn(val email: String) : SessionState
}

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/**
 * 로그인 상태. server가 준 불투명 토큰(Bearer)과 로그인한 이메일만 둔다.
 * 토큰은 Keystore 키로 암호화해서 저장한다. server의 토큰 수명은 12시간이고 갱신이 없어서,
 * 만료되면 401을 받고 다시 로그인한다.
 */
class SessionStore(context: Context, private val cipher: TokenCipher) {

    private val store = context.applicationContext.sessionDataStore

    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state

    @Volatile
    private var token: String? = null

    /** 요청에 붙일 토큰. 네트워크 스레드에서 부른다. */
    fun tokenOrNull(): String? = token

    /** 앱이 뜰 때 한 번 부른다. */
    suspend fun load() {
        val prefs = store.data.first()
        val saved = prefs[TOKEN]?.let(cipher::decrypt)
        val email = prefs[EMAIL]
        if (saved.isNullOrEmpty() || email == null) {
            _state.value = SessionState.SignedOut()
        } else {
            token = saved
            _state.value = SessionState.SignedIn(email)
        }
    }

    suspend fun signIn(newToken: String, email: String) {
        store.edit {
            it[TOKEN] = cipher.encrypt(newToken)
            it[EMAIL] = email
        }
        token = newToken
        _state.value = SessionState.SignedIn(email)
    }

    /** @param expired server가 401을 줘서 로그아웃되는 경우 true. */
    suspend fun signOut(expired: Boolean = false) {
        if (token == null && _state.value is SessionState.SignedOut) return
        token = null
        store.edit { it.clear() }
        _state.value = SessionState.SignedOut(expired)
    }

    private companion object {
        val TOKEN = stringPreferencesKey("token")
        val EMAIL = stringPreferencesKey("email")
    }
}
