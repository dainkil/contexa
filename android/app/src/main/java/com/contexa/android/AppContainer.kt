package com.contexa.android

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.contexa.android.api.AuthApi
import com.contexa.android.api.DocumentApi
import com.contexa.android.api.buildHttpClient
import com.contexa.android.api.buildRetrofit
import com.contexa.android.session.KeystoreTokenCipher
import com.contexa.android.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 앱 전체가 하나씩만 갖는 것들. 화면은 여기서 꺼내 쓴다. */
class AppContainer(context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val session = SessionStore(context, KeystoreTokenCipher())

    private val retrofit = buildRetrofit(
        BuildConfig.API_BASE_URL,
        buildHttpClient(
            token = session::tokenOrNull,
            // 토큰이 만료·폐기됐다. 세션을 비우면 화면이 로그인으로 바뀐다.
            onUnauthorized = { appScope.launch { session.signOut(expired = true) } },
        ),
    )

    val authApi: AuthApi = retrofit.create(AuthApi::class.java)
    val documentApi: DocumentApi = retrofit.create(DocumentApi::class.java)

    init {
        appScope.launch { session.load() }
    }
}

class ContexaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** 화면의 ViewModel을 만든다. 필요한 것은 AppContainer에서 꺼낸다. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = (LocalContext.current.applicationContext as ContexaApp).container
    return viewModel(key = key) { create(container) }
}
