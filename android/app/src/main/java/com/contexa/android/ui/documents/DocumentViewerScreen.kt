package com.contexa.android.ui.documents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.contexa.android.api.ReviewStatus
import com.contexa.android.api.Span
import com.contexa.android.ui.components.BackButton
import com.contexa.android.ui.components.PrimaryButton
import com.contexa.android.ui.context.TaskRow
import com.contexa.android.ui.theme.evidence
import kotlinx.coroutines.launch

/** 발언 목록 앞에 놓이는 항목 수(머리말 하나). 근거 발언으로 스크롤할 때 더한다. */
private const val HEADER_ITEMS = 1

/**
 * 원문 뷰어. 위에는 이 문서에서 나온 할 일과 담당자, 아래에는 발언을 순서대로 보여 준다.
 * 근거 발언 id로 열었으면 그 발언을 노란색으로 칠하고 그 자리로 스크롤한다.
 *
 * @param canRevise 수정(발언 정정)은 소유자·검토자만 한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(viewModel: DocumentViewerViewModel, canRevise: Boolean, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val list = rememberLazyListState()

    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, viewModel) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }

    // 알림을 지우면 이 효과가 다시 시작되므로, 스낵바는 화면 범위에서 따로 띄운다.
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.notice) {
        state.notice?.let { message ->
            viewModel.noticeShown()
            scope.launch { snackbar.showSnackbar(message) }
        }
    }

    // 근거 발언의 자리가 정해졌을 때만 스크롤한다. 처리 상태를 다시 불러와도 읽던 자리를 빼앗지 않는다.
    val highlightIndex = state.highlightId?.let { id -> state.view?.opened?.spans?.indexOfFirst { it.ref.id == id } } ?: -1
    LaunchedEffect(highlightIndex) {
        if (highlightIndex >= 0) list.scrollToItem(HEADER_ITEMS + highlightIndex)
    }

    val view = state.view
    val editing = canRevise && state.editing
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        view?.document?.title ?: if (state.loadError != null) "열 수 없는 문서" else "문서",
                        style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (canRevise && view != null && view.opened.spans.isNotEmpty()) {
                        TextButton(onClick = { viewModel.setEditing(!state.editing) }) {
                            Text(if (state.editing) "완료" else "수정")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        when {
            view != null -> LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                item { ViewerHeader(state, viewModel) }

                if (view.opened.spans.isNotEmpty()) {
                    items(view.opened.spans, key = { it.ref.id }) { span ->
                        SpanRow(
                            span = span,
                            highlighted = span.ref.id == state.highlightId,
                            onRevise = if (editing) ({ viewModel.startRevising(span) }) else null,
                        )
                    }
                } else {
                    item {
                        // 아직 발언으로 자르지 않은 문서는 올린 글을 그대로 보여 준다.
                        Text(
                            view.opened.content.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            state.loading -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            else -> Column(Modifier.padding(inner).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.loadError?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = viewModel::reload) { Text("다시 불러오기") }
            }
        }
    }

    state.revising?.let { span ->
        AlertDialog(
            onDismissRequest = viewModel::stopRevising,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("${span.seq}번 발언 수정", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = state.reviseText,
                    onValueChange = viewModel::editRevision,
                    label = span.speakerLabel?.let { { Text(it) } },
                    isError = state.reviseError != null,
                    supportingText = state.reviseError?.let { { Text(it) } },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                PrimaryButton(
                    "저장",
                    onClick = viewModel::saveRevision,
                    enabled = state.canSaveRevision,
                    loading = state.savingRevision,
                )
            },
            dismissButton = { TextButton(onClick = viewModel::stopRevising) { Text("취소") } },
        )
    }
}

/** 발언 목록 위: 처리 상태(끝났으면 없음), 이 문서에서 나온 할 일과 담당자, "발언" 구역 제목. */
@Composable
private fun ViewerHeader(state: ViewerUiState, viewModel: DocumentViewerViewModel) {
    val view = state.view ?: return
    val latestNo = state.latestNo ?: view.opened.versionNo

    Column(modifier = Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProcessingNotice(state.openedVersion)

        if (view.opened.versionNo != latestNo) {
            // 근거 링크로 예전 내용을 열었을 때만 나온다.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "수정되기 전 내용입니다.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.openVersion(null) }) { Text("최신 내용 보기") }
            }
        }

        state.loadError?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }

        Text("할 일", style = MaterialTheme.typography.titleMedium)
        val tasks = state.tasks
        when {
            state.tasksError != null ->
                Text(state.tasksError, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            tasks == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            tasks.isEmpty() -> Text(
                "이 문서에서 나온 할 일이 없습니다.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                tasks.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TaskLine(row)
                }
            }
        }

        Text("발언", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        if (state.editing) {
            Text(
                "고칠 발언을 누르세요.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 할 일 한 줄: 왼쪽에 할 일, 오른쪽에 맡은 사람. 아직 검토함에서 확인하지 않은 할 일은 그렇다고 적는다. */
@Composable
private fun TaskLine(row: TaskRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                row.task.label,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            )
            if (row.task.status != ReviewStatus.CONFIRMED) {
                Text("검토함에서 확인 전", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val assignees = row.assignees.joinToString(", ")
        Text(
            assignees.ifEmpty { "담당 미정" },
            style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
            color = if (assignees.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 발언 하나: 말한 사람과 내용. 원문은 고치지 않고 그대로 보여 준다. 언어가 섞이므로 방향은 내용에 따라 정한다.
 *
 * @param onRevise 수정 중일 때만 있다. 누르면 이 발언을 고치는 창이 열린다.
 */
@Composable
private fun SpanRow(span: Span, highlighted: Boolean, onRevise: (() -> Unit)?) {
    val background = if (highlighted) MaterialTheme.colorScheme.evidence else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .then(
                if (onRevise != null) {
                    Modifier.clickable(onClick = onRevise).semantics { contentDescription = "${span.seq}번 발언 수정" }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            span.speakerLabel?.let {
                Text(
                    if (span.ref.revision > 1) "$it · 수정됨" else it,
                    style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                span.text,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (onRevise != null) {
            Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
