package com.contexa.android.ui.ask

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.contexa.android.api.ArtifactStatus
import com.contexa.android.api.ArtifactView
import com.contexa.android.api.Source
import com.contexa.android.ui.components.SourceQuote
import com.contexa.android.ui.components.Tag
import com.contexa.android.ui.documents.formatInstant
import com.contexa.android.ui.documents.languageLabel

/**
 * 답변 하나: 질문, 답변 본문, 인용한 발언. 아직 쓰는 중이면 다 쓸 때까지 기다린다.
 *
 * @param onOpenSource 인용한 발언을 원문 뷰어에서 연다
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnswerScreen(viewModel: AnswerViewModel, onBack: () -> Unit, onOpenSource: (Source) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, viewModel) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("답변") },
                navigationIcon = { TextButton(onClick = onBack) { Text("질문") } },
            )
        },
    ) { inner ->
        val view = state.view
        when {
            view != null -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Question(view) }
                item { Body(view, state, onRetry = viewModel::reload) }
                if (view.citations.isNotEmpty()) {
                    item { Text("인용한 발언", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                    // 본문의 [1], [2]는 이 목록의 순서다.
                    itemsIndexed(view.citations) { index, source ->
                        SourceQuote(
                            source = source,
                            documentTitle = state.documentTitles[source.documentId],
                            onOpen = { onOpenSource(source) },
                            number = index + 1,
                        )
                    }
                }
            }

            state.loading -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            else -> Column(Modifier.padding(inner).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("이 글을 열 수 없습니다.", style = MaterialTheme.typography.titleMedium)
                state.loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = viewModel::reload) { Text("다시 불러오기") }
            }
        }
    }
}

@Composable
private fun Question(view: ArtifactView) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${kindLabel(view.kind)} · ${languageLabel(view.lang)} · ${formatInstant(view.createdAt)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            view.question,
            style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Body(view: ArtifactView, state: AnswerUiState, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (view.status) {
            ArtifactStatus.PENDING -> OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.gaveUp) {
                        Text(STILL_WAITING_MESSAGE, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onRetry) { Text("다시 확인하기") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.tertiary, strokeWidth = 2.dp)
                            Text("AI가 근거를 읽고 답을 쓰는 중입니다…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            ArtifactStatus.FAILED -> Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "답을 쓰지 못했습니다.",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    view.failure?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            ArtifactStatus.PUBLISHED -> {
                if (view.stale.isNotEmpty()) {
                    // 답을 쓴 뒤에 참고한 카드나 줄이 바뀌었다. 답이 지금 사실과 다를 수 있다.
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "근거가 바뀌었습니다. 이 답을 쓴 뒤에 참고한 내용 ${view.stale.size}건이 달라졌습니다. 다시 질문해 확인하세요.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                Text(
                    view.content.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.fillMaxWidth(),
                )
                Tag("AI가 쓴 답변")
            }
        }
        state.loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
