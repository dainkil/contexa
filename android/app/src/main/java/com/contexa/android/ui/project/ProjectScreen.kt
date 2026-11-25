package com.contexa.android.ui.project

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.contexa.android.api.NodeRef
import com.contexa.android.appViewModel
import com.contexa.android.ui.components.BackButton
import com.contexa.android.ui.settings.SettingsContent
import com.contexa.android.ui.settings.SettingsViewModel
import com.contexa.android.ui.context.ContextScreen
import com.contexa.android.ui.context.ContextViewModel
import com.contexa.android.ui.ask.AskScreen
import com.contexa.android.ui.ask.AskViewModel
import com.contexa.android.ui.documents.DocumentsScreen
import com.contexa.android.ui.documents.DocumentsViewModel
import com.contexa.android.ui.reviews.ReviewsScreen
import com.contexa.android.ui.reviews.ReviewsViewModel
import kotlinx.coroutines.launch

/** 프로젝트 안의 탭. */
enum class ProjectTab(val label: String, val icon: ImageVector) {
    DOCUMENTS("자료함", Icons.AutoMirrored.Filled.List),
    REVIEWS("검토함", Icons.Filled.CheckCircle),
    ASK("질문", Icons.Filled.Search),
    CONTEXT("맥락", Icons.Filled.Info),
    SETTINGS("설정", Icons.Filled.Settings),
}

/**
 * 프로젝트 안 화면. 아래 탭으로 자료함·검토함·질문·맥락·설정을 오간다.
 *
 * @param canReview      소유자·검토자인가(확인·정정을 할 수 있나)
 * @param roleLabel      이 프로젝트에서의 내 역할. 설정에 적는다.
 * @param onOpenDocument 원문 뷰어를 연다. spanId를 주면 그 발언 자리로 간다.
 * @param onOpenHistory  결정 항목의 이력을 연다.
 * @param onOpenTrace    카드의 근거·영향을 따라가 본다.
 * @param onOpenPerson   인물·기관의 이름 화면을 연다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    projectId: String,
    title: String,
    canReview: Boolean,
    roleLabel: String?,
    onBack: () -> Unit,
    onOpenDocument: (documentId: String, spanId: String?) -> Unit,
    onOpenHistory: (decisionItemId: String) -> Unit,
    onOpenTrace: (NodeRef) -> Unit,
    onOpenPerson: (entityId: String) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(ProjectTab.DOCUMENTS) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton(onBack) },
            )
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    ProjectTab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Icon(entry.icon, contentDescription = null) },
                            label = { Text(entry.label, style = MaterialTheme.typography.labelLarge, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                // SEED의 아래 탭처럼 고른 탭은 짙은 색, 나머지는 옅은 회색. 알약 표시는 그리지 않는다.
                                indicatorColor = Color.Transparent,
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        // 키보드가 올라오면 그만큼 줄인다(고치기 칸이 키보드에 가리지 않게). 아래 탭 높이는 이미 뺐으므로 겹쳐 빼지 않는다.
        val content = Modifier.padding(inner).consumeWindowInsets(inner).imePadding()
        when (tab) {
            ProjectTab.DOCUMENTS -> DocumentsScreen(
                viewModel = appViewModel(key = "documents") { DocumentsViewModel(it.documentApi, projectId) },
                onOpen = { documentId -> onOpenDocument(documentId, null) },
                modifier = content,
            )

            ProjectTab.REVIEWS -> ReviewsScreen(
                viewModel = appViewModel(key = "reviews") { ReviewsViewModel(it.reviewApi, it.documentApi, projectId) },
                canReview = canReview,
                onNotice = { message -> scope.launch { snackbar.showSnackbar(message) } },
                onOpenSource = { source -> onOpenDocument(source.documentId, source.span.id) },
                onOpenPerson = onOpenPerson,
                modifier = content,
            )

            ProjectTab.ASK -> AskScreen(
                viewModel = appViewModel(key = "ask") { AskViewModel(it.generationApi, projectId) },
                onOpenSource = { source -> onOpenDocument(source.documentId, source.span.id) },
                modifier = content,
            )

            ProjectTab.CONTEXT -> ContextScreen(
                viewModel = appViewModel(key = "context") { ContextViewModel(it.contextApi, projectId) },
                onNotice = { message -> scope.launch { snackbar.showSnackbar(message) } },
                onOpenHistory = onOpenHistory,
                onOpenTrace = onOpenTrace,
                onOpenPerson = onOpenPerson,
                modifier = content,
            )

            ProjectTab.SETTINGS -> SettingsContent(
                viewModel = appViewModel(key = "settings") { SettingsViewModel(it.authApi, it.session) },
                modifier = content,
                role = roleLabel,
            )
        }
    }
}
