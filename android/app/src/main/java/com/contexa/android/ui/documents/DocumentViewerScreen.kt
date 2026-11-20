package com.contexa.android.ui.documents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.contexa.android.api.DocumentView
import com.contexa.android.api.Span
import com.contexa.android.api.Visibility
import com.contexa.android.ui.components.FullScreenDialog
import com.contexa.android.ui.components.PrimaryButton
import com.contexa.android.ui.components.SecondaryButton
import com.contexa.android.ui.components.Tag
import com.contexa.android.ui.theme.evidence
import kotlinx.coroutines.launch

/** 발언 목록 앞에 놓이는 항목 수(머리말 하나). 근거 발언으로 스크롤할 때 더한다. */
private const val HEADER_ITEMS = 1

/**
 * 원문 뷰어. 판을 골라 발언을 순서대로 본다.
 * 근거 발언 id로 열었으면 그 발언을 노란색으로 칠하고 그 자리로 스크롤한다.
 *
 * @param canRevise 발언 정정은 소유자·검토자만 한다.
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        view?.document?.title ?: if (state.loadError != null) "이 문서를 열 수 없습니다" else "문서",
                        style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("문서 목록") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        when {
            view != null -> LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item { ViewerHeader(state, view, viewModel) }

                if (view.opened.spans.isNotEmpty()) {
                    items(view.opened.spans, key = { it.ref.id }) { span ->
                        SpanRow(
                            span = span,
                            highlighted = span.ref.id == state.highlightId,
                            onRevise = if (canRevise) ({ viewModel.startRevising(span) }) else null,
                        )
                    }
                } else {
                    item { UnparsedContent(view.opened.content.orEmpty()) }
                }
            }

            state.loading -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            else -> Column(Modifier.padding(inner).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "없는 문서이거나, 다른 사람의 개인 메모이거나, 이 프로젝트의 문서가 아닙니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = viewModel::reload) { Text("다시 불러오기") }
            }
        }
    }

    state.newVersion?.let { content ->
        FullScreenDialog(
            title = "${(state.latestNo ?: 0) + 1}판 올리기",
            onClose = viewModel::closeNewVersion,
            action = { PrimaryButton("올리기", onClick = viewModel::uploadNewVersion, loading = state.uploadingVersion) },
        ) {
            Text(
                "고친 회의록 전체를 붙여 넣으세요. 이전 판과 그 판의 발언·근거는 지워지지 않습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ContentField(
                content = content,
                onChange = viewModel::editNewVersion,
                onLoad = { text, _ -> viewModel.editNewVersion(text) },
                onReject = viewModel::newVersionFailed,
                error = state.newVersionError,
            )
        }
    }

    state.revising?.let { span ->
        AlertDialog(
            onDismissRequest = viewModel::stopRevising,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("${span.seq}번 발언 정정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "전사 오류만 고치세요. 이전 내용은 정정 이력으로 남고, 이 발언을 근거로 쓴 카드는 다시 확인이 필요하다고 표시됩니다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = state.reviseText,
                        onValueChange = viewModel::editRevision,
                        label = { Text(span.speakerLabel ?: "내용") },
                        isError = state.reviseError != null,
                        supportingText = state.reviseError?.let { { Text(it) } },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                PrimaryButton(
                    "정정하기",
                    onClick = viewModel::saveRevision,
                    enabled = state.canSaveRevision,
                    loading = state.savingRevision,
                )
            },
            dismissButton = { TextButton(onClick = viewModel::stopRevising) { Text("취소") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewerHeader(state: ViewerUiState, view: DocumentView, viewModel: DocumentViewerViewModel) {
    val document = view.document
    val opened = view.opened
    val latestNo = state.latestNo ?: opened.versionNo
    val highlighted = state.highlightId?.let { id -> opened.spans.find { it.ref.id == id } }

    Column(modifier = Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(kindLabel(document.kind))
            Tag(languageLabel(document.language))
            Tag(if (document.visibility == Visibility.PRIVATE) "나만 보기" else "구성원 모두")
            if (document.externalOk) Tag("외부 공유 허용")
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            VersionPicker(view, latestNo, onPick = viewModel::openVersion)
            SecondaryButton("새 판 올리기", onClick = viewModel::openNewVersion)
        }

        ProcessingSteps(state.openedVersion)

        if (opened.versionNo != latestNo) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "지금 보는 것은 ${opened.versionNo}판입니다. 최신은 ${latestNo}판입니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.openVersion(null) }) { Text("최신 판 보기") }
                }
            }
        }

        if (highlighted != null) {
            Text(
                "근거로 연결된 ${highlighted.seq}번 발언을 노란색으로 표시했습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun VersionPicker(view: DocumentView, latestNo: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SecondaryButton("${view.opened.versionNo}판 ▾", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            view.versions.forEach { version ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${version.versionNo}판 · ${formatInstant(version.createdAt)}" +
                                if (version.versionNo == latestNo) " (최신)" else "",
                        )
                    },
                    onClick = {
                        open = false
                        onPick(version.versionNo)
                    },
                )
            }
        }
    }
}

/** 발언 하나. 원문은 고치지 않고 그대로 보여 준다. 언어가 섞이므로 방향은 내용에 따라 정한다. */
@Composable
private fun SpanRow(span: Span, highlighted: Boolean, onRevise: (() -> Unit)?) {
    val background = if (highlighted) MaterialTheme.colorScheme.evidence else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            span.seq.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(28.dp).padding(top = 4.dp).semantics { contentDescription = "${span.seq}번 발언" },
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (span.speakerLabel != null || span.ref.revision > 1) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    span.speakerLabel?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                textDirection = TextDirection.Content,
                            ),
                        )
                    }
                    if (span.ref.revision > 1) Tag("정정됨")
                }
            }
            Text(
                span.text,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (onRevise != null) {
            TextButton(
                onClick = onRevise,
                modifier = Modifier.semantics { contentDescription = "${span.seq}번 발언 정정" },
            ) {
                Text("정정", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun UnparsedContent(content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "아직 발언으로 자르지 않았습니다. 자르기가 끝나면 발언마다 번호가 붙고, 근거 링크로 바로 찾아올 수 있습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                content,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}
