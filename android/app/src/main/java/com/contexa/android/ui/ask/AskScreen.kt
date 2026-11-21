package com.contexa.android.ui.ask

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.contexa.android.api.ArtifactSummary
import com.contexa.android.api.GenKind
import com.contexa.android.ui.components.ChoiceChip
import com.contexa.android.ui.components.PrimaryButton
import com.contexa.android.ui.components.SecondaryButton
import com.contexa.android.ui.components.Tag
import com.contexa.android.ui.documents.LANGUAGES
import com.contexa.android.ui.documents.formatInstant
import com.contexa.android.ui.documents.languageLabel

fun kindLabel(kind: GenKind): String = when (kind) {
    GenKind.ANSWER -> "질문"
    GenKind.BRIEFING -> "브리핑"
    GenKind.DRAFT -> "초안"
}

/**
 * 질문 탭: 프로젝트의 확인된 결정과 근거를 바탕으로 AI에게 묻는다. 지난 질문을 다시 열 수 있다.
 *
 * @param onOpenAnswer 답변 화면을 연다
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen(viewModel: AskViewModel, onOpenAnswer: (artifactId: String) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 이 탭이 보일 때마다 새로 읽는다. 답변 화면에 다녀오면 "쓰는 중"이 "답변 있음"으로 바뀌어 있다.
    LaunchedEffect(viewModel) { viewModel.reload() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("질문", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "확인된 결정과 회의 원문을 근거로 답합니다. 답변에는 근거 발언이 번호로 붙습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val error = questionError(state.question) ?: state.askError
                OutlinedTextField(
                    value = state.question,
                    onValueChange = viewModel::editQuestion,
                    placeholder = { Text("예: 인쇄는 언제 발주하기로 했나요?") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("답변 언어", style = MaterialTheme.typography.titleSmall)
                    LANGUAGES.forEach { language ->
                        ChoiceChip(language.label, selected = state.lang == language.code, onClick = { viewModel.pickLang(language.code) })
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrimaryButton(
                        "질문하기",
                        onClick = { viewModel.ask(onOpenAnswer) },
                        enabled = canAsk(state.question),
                        loading = state.asking,
                    )
                    // 질문 없이 프로젝트 현황을 정리해 받는다.
                    SecondaryButton("브리핑 받기", onClick = { viewModel.askBriefing(onOpenAnswer) }, enabled = !state.asking)
                }
            }
        }

        item { Text("지난 질문", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }

        state.loadError?.let { message ->
            item {
                Column {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::reload) { Text("다시 불러오기") }
                }
            }
        }

        when {
            state.history.isNotEmpty() -> items(state.history, key = { it.id }) { row ->
                HistoryRow(row, onClick = { onOpenAnswer(row.id) })
            }

            state.loading -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.loadError == null -> item {
                Text(
                    "아직 질문한 적이 없습니다. 내가 한 질문만 여기에 보입니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryRow(row: ArtifactSummary, onClick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${kindLabel(row.kind)} · ${languageLabel(row.lang)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                row.question,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.published) {
                    Tag(
                        "답변 있음",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        container = MaterialTheme.colorScheme.primaryContainer,
                    )
                } else {
                    // 목록만으로는 쓰는 중인지 실패했는지 알 수 없다. 열어 보면 나온다.
                    Tag("답변 없음")
                }
                Text(
                    formatInstant(row.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
