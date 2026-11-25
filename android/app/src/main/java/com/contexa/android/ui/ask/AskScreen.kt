package com.contexa.android.ui.ask

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.contexa.android.api.ArtifactStatus
import com.contexa.android.api.GenKind
import com.contexa.android.api.Source
import com.contexa.android.ui.components.ChoiceChip
import com.contexa.android.ui.components.ScreenTitle
import com.contexa.android.ui.components.Tag

/**
 * 질문 탭: 대화처럼 묻는다. 내 질문은 오른쪽, AI의 답은 왼쪽에 쌓이고, 아래 입력 칸으로 이어서 묻는다.
 *
 * @param onOpenSource 답이 인용한 발언을 원문 뷰어에서 연다
 */
@Composable
fun AskScreen(viewModel: AskViewModel, onOpenSource: (Source) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()

    // 화면이 보이는 동안에만 불러오고 답을 기다린다.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, viewModel) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }

    // 새 질문이나 새 답이 생기면 맨 아래로 간다. 차례마다 질문·답 두 칸이다.
    val answered = state.turns.count { it.view?.status == ArtifactStatus.PUBLISHED }
    LaunchedEffect(state.turns.size, answered) {
        if (state.turns.isNotEmpty()) list.scrollToItem(state.turns.size * 2 - 1)
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTitle("질문", modifier = Modifier.padding(horizontal = 16.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.turns.isNotEmpty() -> LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.turns.forEach { turn ->
                        item(key = "q:${turn.id}") { QuestionBubble(turn.question, turn.kind) }
                        item(key = "a:${turn.id}") { AnswerBubble(turn, state.gaveUp, onOpenSource) }
                    }
                }

                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.loadError == null -> Text(
                    "프로젝트에 대해 물어보세요.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        state.loadError?.let { message ->
            Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::reload) { Text("다시 불러오기") }
            }
        }
        if (state.gaveUp) {
            Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(STILL_WAITING_MESSAGE, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::reload) { Text("다시 확인") }
            }
        }

        InputBar(
            text = state.input,
            briefing = state.briefing,
            error = questionError(state.input) ?: state.sendError,
            sending = state.sending,
            onChange = viewModel::editInput,
            onToggleBriefing = viewModel::toggleBriefing,
            onSend = viewModel::send,
        )
    }
}

private val Mine = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
private val Theirs = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)

/** 내 질문. 브리핑을 요청한 차례면 위에 꼬리표를 단다. */
@Composable
private fun QuestionBubble(question: String, kind: GenKind) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (kind == GenKind.BRIEFING) Tag(BRIEFING_LABEL)
        Surface(color = MaterialTheme.colorScheme.primary, shape = Mine, modifier = Modifier.widthIn(max = 300.dp)) {
            Text(
                question,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

private const val BRIEFING_LABEL = "인수인계 브리핑"

/** 답 하나. 쓰는 중이면 그렇다고, 실패했으면 이유를, 다 썼으면 본문과 인용한 발언을 보여 준다. */
@Composable
private fun AnswerBubble(turn: ChatTurn, gaveUp: Boolean, onOpenSource: (Source) -> Unit) {
    val view = turn.view
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = Theirs, modifier = Modifier.widthIn(max = 320.dp)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (view?.status) {
                    null, ArtifactStatus.PENDING -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!gaveUp) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            if (gaveUp) "아직 답이 없습니다." else "답을 쓰는 중…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    ArtifactStatus.FAILED -> Text(
                        listOfNotNull("답을 쓰지 못했습니다.", view.failure).joinToString("\n"),
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                        color = MaterialTheme.colorScheme.error,
                    )

                    ArtifactStatus.PUBLISHED -> {
                        Text(
                            view.content.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                        )
                        if (view.stale.isNotEmpty()) {
                            // 답을 쓴 뒤에 참고한 내용이 바뀌었다. 답이 지금 사실과 다를 수 있다.
                            Text(
                                "이 답을 쓴 뒤 근거가 바뀌었습니다. 다시 물어보세요.",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (view.citations.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            // 본문의 [1], [2]는 이 목록의 순서다. 누르면 원문에서 그 발언을 연다.
                            view.citations.forEachIndexed { index, source ->
                                Text(
                                    "[${index + 1}] " + listOfNotNull(source.speaker, source.text).joinToString(": "),
                                    style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().clickable { onOpenSource(source) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 아래 입력 칸. 브리핑 칩을 켜면 입력 칸은 브리핑의 초점이 되고, 비워 둬도 보낼 수 있다. */
@Composable
private fun InputBar(
    text: String,
    briefing: Boolean,
    error: String?,
    sending: Boolean,
    onChange: (String) -> Unit,
    onToggleBriefing: () -> Unit,
    onSend: () -> Unit,
) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ChoiceChip(
            label = BRIEFING_LABEL,
            selected = briefing,
            onClick = onToggleBriefing,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp),
        )
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onChange,
                placeholder = { Text(if (briefing) "무엇을 중심으로 정리할까요? (비워도 됩니다)" else "질문을 입력하세요") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(
                onClick = onSend,
                enabled = canSend(text, briefing) && !sending,
                shape = CircleShape,
                modifier = Modifier.size(52.dp),
            ) {
                if (sending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "보내기")
                }
            }
        }
    }
}
