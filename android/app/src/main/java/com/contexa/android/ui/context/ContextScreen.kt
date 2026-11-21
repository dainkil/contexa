package com.contexa.android.ui.context

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.contexa.android.api.Fact
import com.contexa.android.api.NodeRef
import com.contexa.android.api.ReviewStatus
import com.contexa.android.api.TaskStatus
import com.contexa.android.ui.components.ChoiceChip
import com.contexa.android.ui.components.KeyValue
import com.contexa.android.ui.components.PrimaryButton
import com.contexa.android.ui.components.ScreenTitle
import com.contexa.android.ui.components.SecondaryButton
import com.contexa.android.ui.components.Tag
import com.contexa.android.ui.reviews.formatDay
import com.contexa.android.ui.reviews.stateLabel
import com.contexa.android.ui.reviews.statusLabel

/**
 * 맥락 탭: 사람이 확인해 확정된 것만 보여 준다(AI 후보를 판단하는 곳은 검토함이다).
 * "확정"은 안건에서 결정으로 이어지는 그림과 알아야 하는 사항, "할 일"은 확인된 할 일이다.
 *
 * @param onOpenHistory 결정 항목의 이력을 연다
 * @param onOpenTrace   할 일의 근거를 따라가 본다
 */
@Composable
fun ContextScreen(
    viewModel: ContextViewModel,
    onNotice: (String) -> Unit,
    onOpenHistory: (itemId: String) -> Unit,
    onOpenTrace: (NodeRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 이 탭이 보일 때마다 새로 읽는다. 검토함에서 확인한 것이 바로 반영돼 있어야 한다.
    LaunchedEffect(viewModel) { viewModel.reload() }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            onNotice(it)
            viewModel.noticeShown()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScreenTitle("맥락")
                Chips {
                    ContextSection.entries.forEach { section ->
                        Choice(section.label, selected = state.section == section) { viewModel.show(section) }
                    }
                }
            }
        }

        state.loadError?.let { message ->
            item {
                Column {
                    Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::reload) { Text("다시 불러오기") }
                }
            }
        }

        when (state.section) {
            ContextSection.CONFIRMED -> confirmed(state, onOpenHistory)
            ContextSection.TASKS -> tasks(state, viewModel, onOpenTrace)
        }
    }
}

private fun LazyListScope.confirmed(state: ContextUiState, onOpenHistory: (String) -> Unit) {
    if (state.notices.isNotEmpty()) {
        item(key = "notices") { NoticeBox(state.notices) }
    }
    when {
        state.groups.isNotEmpty() -> items(state.groups, key = { "topic:${it.topic?.node?.id}" }) { group ->
            DecisionTree(group, onOpenHistory)
        }

        state.loading -> item { Loading() }

        state.loadError == null -> item {
            Text(
                "아직 확정된 결정이 없습니다.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "확인" 칸: 확정된 것을 볼 때 함께 알아야 하는 사항. */
@Composable
private fun NoticeBox(notices: List<Notice>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("확인", style = MaterialTheme.typography.titleMedium)
            notices.forEach { notice ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (notice.count > 1) "${notice.kind} ${notice.count}건" else notice.kind,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    notice.text?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private val Gutter = 28.dp
private val Trunk = 12.dp
private val Elbow = 40.dp

/**
 * 안건 하나를 나무 그림으로: 위에 안건, 거기서 선이 내려와 결정마다 가지를 친다.
 * 결정 칸을 누르면 그 결정이 어떻게 바뀌어 왔는지(이력)를 연다.
 */
@Composable
private fun DecisionTree(group: TopicGroup, onOpenHistory: (String) -> Unit) {
    val line = MaterialTheme.colorScheme.outline
    Column {
        Surface(color = MaterialTheme.colorScheme.secondary, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Text(
                group.topic?.label ?: "안건 없음",
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        group.rows.forEachIndexed { index, row ->
            val last = index == group.rows.lastIndex
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val x = Trunk.toPx()
                        val y = Elbow.toPx()
                        val stroke = 2.dp.toPx()
                        // 줄기는 마지막 가지에서 끝난다.
                        drawLine(line, Offset(x, 0f), Offset(x, if (last) y else size.height), stroke)
                        drawLine(line, Offset(x, y), Offset(Gutter.toPx(), y), stroke)
                    }
                    .padding(start = Gutter, top = 12.dp),
            ) {
                DecisionNode(row, onClick = { onOpenHistory(row.item.node.id) })
            }
        }
    }
}

/** 결정 하나: 무엇을 정했나(항목), 어떻게 정했나(지금 유효한 결정), 언제. */
@Composable
private fun DecisionNode(row: DecisionRow, onClick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                row.item.label,
                style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.primary,
            )
            row.current?.let { current ->
                Text(
                    current.label,
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.fillMaxWidth(),
                )
                current.states["occurredAt"]?.takeIf { it.isNotEmpty() }?.let {
                    Text(formatDay(it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun LazyListScope.tasks(state: ContextUiState, viewModel: ContextViewModel, onOpenTrace: (NodeRef) -> Unit) {
    item {
        Chips {
            Choice("전체", selected = !state.mine) { viewModel.showMine(false) }
            Choice("내 할 일", selected = state.mine) { viewModel.showMine(true) }
        }
    }
    when {
        state.tasks.isNotEmpty() -> items(state.tasks, key = { "task:${it.task.node.id}" }) { row ->
            TaskCard(
                row = row,
                busy = state.busyId == row.task.node.id,
                enabled = state.busyId == null,
                canAccept = state.mine,
                onChange = { viewModel.changeStatus(row, it) },
                onAccept = { viewModel.accept(row, it) },
                onOpenTrace = { onOpenTrace(row.task.node) },
            )
        }

        state.loading -> item { Loading() }

        state.loadError == null -> item {
            Text(
                if (state.mine) "내게 맡겨진 할 일이 없습니다." else "아직 확인된 할 일이 없습니다.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 할 일 한 장: 무엇을, 누가, 언제까지. 카드를 누르면 근거를 따라가 본다.
 *
 * @param canAccept 내 할 일을 보고 있을 때만 수락·거절을 보여 준다(server도 담당자 본인만 받아 준다)
 */
@Composable
private fun TaskCard(
    row: TaskRow,
    busy: Boolean,
    enabled: Boolean,
    canAccept: Boolean,
    onChange: (TaskStatus) -> Unit,
    onAccept: (Boolean) -> Unit,
    onOpenTrace: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenTrace)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                row.task.label,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyValue("담당", row.assignees.joinToString(", ").ifEmpty { "미정" })
                KeyValue("기한", row.dueAt?.takeIf { it.isNotEmpty() }?.let(::formatDay) ?: "미정")
                row.acceptance?.let { KeyValue("수락", stateLabel("acceptance", it)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    var open by remember { mutableStateOf(false) }
                    val status = row.task.states["workStatus"]?.let { stateLabel("workStatus", it) } ?: "상태"
                    SecondaryButton(if (busy) "바꾸는 중…" else "$status ▾", onClick = { open = true }, enabled = enabled)
                    DropdownMenu(
                        expanded = open,
                        onDismissRequest = { open = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        nextStatuses(row.workStatus).forEach { next ->
                            DropdownMenuItem(
                                text = { Text(stateLabel("workStatus", next.name), style = MaterialTheme.typography.bodyLarge) },
                                onClick = {
                                    open = false
                                    onChange(next)
                                },
                            )
                        }
                    }
                }
                if (canAccept && row.awaitingAcceptance) {
                    PrimaryButton("수락", onClick = { onAccept(true) }, enabled = enabled)
                    SecondaryButton("거절", onClick = { onAccept(false) }, enabled = enabled)
                }
            }
        }
    }
}

@Composable
fun DecisionStateTag(value: String) {
    val decided = value == "DECIDED"
    Tag(
        stateLabel("decisionState", value),
        color = if (decided) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        container = if (decided) MaterialTheme.colorScheme.primaryContainer else null,
    )
}

/** 확인된 카드에는 붙이지 않는다. 후보·보류처럼 아직 믿으면 안 되는 카드만 표시한다. */
@Composable
fun ReviewStatusTag(fact: Fact) {
    if (fact.status != ReviewStatus.CONFIRMED) Tag(statusLabel(fact.status))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    ChoiceChip(label, selected = selected, onClick = onClick)
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
