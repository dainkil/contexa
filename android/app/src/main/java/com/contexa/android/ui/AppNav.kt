package com.contexa.android.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.contexa.android.ContexaApp
import com.contexa.android.api.Role
import com.contexa.android.appViewModel
import com.contexa.android.session.SessionState
import com.contexa.android.ui.auth.AuthViewModel
import com.contexa.android.ui.auth.SignInScreen
import com.contexa.android.ui.auth.SignUpScreen
import com.contexa.android.ui.documents.DocumentViewerScreen
import com.contexa.android.ui.documents.DocumentViewerViewModel
import com.contexa.android.ui.project.ProjectScreen
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
                onOpen = { nav.navigate("p/${Uri.encode(it.id)}?title=${Uri.encode(it.title)}&role=${it.role.name}") },
            )
        }

        // 프로젝트 안. 아래 탭으로 자료함·검토함·질문·맥락을 오간다.
        composable(
            "p/{projectId}?title={title}&role={role}",
            arguments = listOf(textArg("title"), textArg("role")),
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            val role = entry.arguments?.getString("role").orEmpty()
            ProjectScreen(
                projectId = projectId,
                title = entry.arguments?.getString("title").orEmpty(),
                canReview = canReview(role),
                onBack = { nav.popBackStack() },
                onOpenDocument = { documentId, spanId ->
                    val span = spanId?.let { "&span=${Uri.encode(it)}" }.orEmpty()
                    nav.navigate("p/${Uri.encode(projectId)}/d/${Uri.encode(documentId)}?role=$role$span")
                },
            )
        }

        // 원문 뷰어. span(근거 발언 id)을 주면 그 발언이 들어 있는 판을 열고 그 자리로 간다.
        composable(
            "p/{projectId}/d/{documentId}?role={role}&span={span}",
            arguments = listOf(
                textArg("role"),
                navArgument("span") {
                    type = NavType.StringType
                    nullable = true
                },
            ),
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            val documentId = entry.arguments?.getString("documentId").orEmpty()
            val role = entry.arguments?.getString("role").orEmpty()
            val span = entry.arguments?.getString("span")
            val viewModel = appViewModel { DocumentViewerViewModel(it.documentApi, projectId, documentId, span) }
            DocumentViewerScreen(
                viewModel = viewModel,
                canRevise = canReview(role),
                onBack = { nav.popBackStack() },
            )
        }
    }
}

/** 확인·정정은 소유자·검토자만 한다(server도 같은 규칙으로 막는다). */
private fun canReview(role: String) = role == Role.OWNER.name || role == Role.REVIEWER.name

/** 주소 뒤에 붙는 선택 값. 없으면 빈 글자. */
private fun textArg(name: String) = navArgument(name) {
    type = NavType.StringType
    defaultValue = ""
}
