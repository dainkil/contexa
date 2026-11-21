package com.contexa.android.ui.reviews

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.contexa.android.api.NodeType
import com.contexa.android.api.ReviewState
import com.contexa.android.api.Source
import com.contexa.android.ui.components.ChoiceChip
import com.contexa.android.ui.components.SecondaryButton

/**
 * 검토함 탭: AI가 뽑은 후보를 카드 묶음 단위로 확인·보류·거절·정정한다.
 *
 * @param canReview     소유자·검토자만 판단한다
 * @param onNotice      처리 결과 알림을 띄운다
 * @param onOpenSource  근거 발언을 원문 뷰어에서 연다
 * @param onGoDocuments 자료함 탭으로 간다
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewsScreen(
    viewModel: ReviewsViewModel,
    canReview: Boolean,
    onNotice: (String) -> Unit,
    onOpenSource: (Source) -> Unit,
    onGoDocuments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 이 탭이 보일 때마다 새로 읽는다. 그사이 문서를 올렸거나 발언을 정정했을 수 있다.
    LaunchedEffect(viewModel) { viewModel.reload() }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            viewModel.noticeShown()
            onNotice(it)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("검토함", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "AI가 뽑은 후보를 근거 원문과 함께 보고, 카드 묶음 단위로 확인·보류·거절합니다. 확인한 것만 현재 결정과 할 일에 반영됩니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    TabChip("확인할 것", state.tab == ReviewState.OPEN) { viewModel.showTab(ReviewState.OPEN) }
                    TabChip("처리한 것", state.tab == ReviewState.DONE) { viewModel.showTab(ReviewState.DONE) }
                    TypeFilter(state.type, onPick = viewModel::filterType)
                }
                if (!canReview) {
                    Text(
                        "확인·정정은 소유자와 검토자가 합니다. 구성원은 볼 수만 있습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        state.loadError?.let { message ->
            item {
                Column {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::reload) { Text("다시 불러오기") }
                }
            }
        }

        when {
            state.items.isNotEmpty() -> items(state.items, key = { it.id }) { item ->
                ReviewCard(
                    item = item,
                    draft = state.drafts[draftKey(item)] ?: ReviewDraft(),
                    canReview = canReview,
                    busy = state.busyId == item.id,
                    documentTitles = state.documentTitles,
                    onChoose = { edgeId, verdict -> viewModel.choose(item, edgeId, verdict) },
                    onEdit = { values -> viewModel.editValues(item, values) },
                    onAct = { verdict, corrections -> viewModel.act(item, verdict, corrections) },
                    onOpenSource = onOpenSource,
                )
            }

            state.loading -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.loadError == null && state.next == null -> item { EmptyReviews(state.tab, onGoDocuments) }
        }

        // 끝까지 내리면 다음 쪽을 잇는다.
        if (state.next != null && !state.loading) {
            item(key = "more:${state.next}") {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    val moreError = state.moreError
                    if (moreError != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(moreError, color = MaterialTheme.colorScheme.error)
                            SecondaryButton("더 보기", onClick = viewModel::loadMore)
                        }
                    } else {
                        LaunchedEffect(Unit) { viewModel.loadMore() }
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    ChoiceChip(label, selected = selected, onClick = onClick)
}

@Composable
private fun TypeFilter(type: NodeType?, onPick: (NodeType?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("${type?.let(::typeLabel) ?: "모든 종류"} ▾", style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf<NodeType?>(null) + FILTER_TYPES).forEach { option ->
                DropdownMenuItem(
                    text = { Text(option?.let(::typeLabel) ?: "모든 종류", style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyReviews(tab: ReviewState, onGoDocuments: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tab == ReviewState.OPEN) {
                Text("확인할 것이 없습니다.", style = MaterialTheme.typography.titleLarge)
                Text(
                    "문서를 올리면 AI가 뽑은 결정·할 일 후보가 여기로 들어옵니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryButton("문서 올리러 가기", onClick = onGoDocuments)
            } else {
                Text(
                    "아직 처리한 묶음이 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
