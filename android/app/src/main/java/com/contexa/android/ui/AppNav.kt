package com.contexa.android.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.contexa.android.ContexaApp
import com.contexa.android.appViewModel
import com.contexa.android.session.SessionState
import com.contexa.android.ui.auth.AuthViewModel
import com.contexa.android.ui.auth.SignInScreen
import com.contexa.android.ui.auth.SignUpScreen
import com.contexa.android.ui.projects.ProjectsScreen
import com.contexa.android.ui.projects.ProjectsViewModel

/** 세션 상태에 따라 로그인 쪽 화면과 로그인 뒤 화면을 바꿔 보여 준다. */
@Composable
fun AppNav() {
    val session = (LocalContext.current.applicationContext as ContexaApp).container.session
    val state by session.state.collectAsStateWithLifecycle()

    when (val current = state) {
        SessionState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is SessionState.SignedOut -> AuthNav(expired = current.expired)
        is SessionState.SignedIn -> MainNav(email = current.email)
    }
}

@Composable
private fun AuthNav(expired: Boolean) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "sign-in") {
        composable("sign-in") {
            val viewModel = appViewModel { AuthViewModel(it.authApi, it.session) }
            SignInScreen(viewModel, expired = expired, onGoSignUp = { nav.navigate("sign-up") })
        }
        composable("sign-up") {
            val viewModel = appViewModel { AuthViewModel(it.authApi, it.session) }
            SignUpScreen(viewModel, onGoSignIn = { nav.popBackStack() })
        }
    }
}

@Composable
private fun MainNav(email: String) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "projects") {
        composable("projects") {
            val viewModel = appViewModel { ProjectsViewModel(it.authApi, it.session) }
            ProjectsScreen(
                viewModel = viewModel,
                email = email,
                onOpen = { nav.navigate("p/${Uri.encode(it.id)}?title=${Uri.encode(it.title)}") },
            )
        }
        composable("p/{projectId}?title={title}") { entry ->
            ProjectPlaceholder(
                title = entry.arguments?.getString("title").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
    }
}

/** 프로젝트 안 화면(자료함·검토함·질문·맥락)이 들어올 자리. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectPlaceholder(title: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content)) },
                navigationIcon = { TextButton(onClick = onBack) { Text("프로젝트") } },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).padding(24.dp)) {
            Text("자료함과 검토함은 다음 단계에서 만듭니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
