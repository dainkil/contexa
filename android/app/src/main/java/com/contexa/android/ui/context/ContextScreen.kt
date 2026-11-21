package com.contexa.android.ui.context

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.contexa.android.api.Fact
import com.contexa.android.api.NodeRef
import com.contexa.android.api.ReviewStatus
import com.contexa.android.api.TaskStatus
import com.contexa.android.ui.components.ChoiceChip
import com.contexa.android.ui.components.PrimaryButton
import com.contexa.android.ui.components.SecondaryButton
import com.contexa.android.ui.components.Tag
import com.contexa.android.ui.reviews.formatDay
import com.contexa.android.ui.reviews.stateLabel
import com.contexa.android.ui.reviews.statusLabel
import com.contexa.android.ui.reviews.typeLabel

/**
 * 맥락 탭: 지금 결정된 것과 남은 것, 그리고 할 일.
 *
 * @param canReview     소유자·검토자인가. 인물에 계정을 연결하는 것은 이들만 한다
 * @param onOpenHistory 결정 항목의 이력을 연다
 * @param onOpenTrace   카드의 근거·영향을 따라가 본다
 */
@Composable
fun ContextScreen(
    viewModel: ContextViewModel,
    canReview: Boolean,
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("맥락", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "지금 무엇이 결정됐고 무엇이 남았는지, 누가 무엇을 해야 하는지 봅니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::reload) { Text("다시 불러오기") }
                }
            }
        }

        when (state.section) {
            ContextSection.DECISIONS -> decisions(state, onOpenHistory, onOpenTrace)
            ContextSection.TASKS -> tasks(state, viewModel, onOpenTrace)
            ContextSection.PEOPLE -> people(state, viewModel, canReview)
        }
    }
}

private fun LazyListScope.decisions(state: ContextUiState, onOpenHistory: (String) -> Unit, onOpenTrace: (NodeRef) -> Unit) {
    if (state.open.isNotEmpty()) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("남은 것 ${state.open.size}건", style = MaterialTheme.typography.titleMedium)
                    state.open.forEach { row ->
                        Column {
                            Text(
                                openCaption(row),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            row.label?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val empty = state.groups.all { it.rows.isEmpty() }
    when {
        state.loading && empty -> item { Loading() }

        empty && state.loadError == null -> item {
            Text(
                "아직 결정 항목이 없습니다. 검토함에서 AI 후보를 확인하면 여기에 나타납니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> state.groups.forEach { group ->
            item(key = "topic:${group.topic?.node?.id}") {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text("안건", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        group.topic?.label ?: "안건에 묶이지 않은 결정",
                        style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (group.rows.isEmpty()) {
                        Text(
                            "아직 결정 항목이 없습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(group.rows, key = { "item:${group.topic?.node?.id}:${it.item.node.id}" }) { row ->
                DecisionCard(row, onOpenHistory, onOpenTrace)
            }
        }
    }
}

@Composable
private fun DecisionCard(row: DecisionRow, onOpenHistory: (String) -> Unit, onOpenTrace: (NodeRef) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chips {
                row.item.states["decisionState"]?.let { DecisionStateTag(it) }
                ReviewStatusTag(row.item)
            }
            Text(
                row.item.label,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth(),
            )
            val current = row.current
            if (current != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            listOfNotNull("지금 유효한 결정", current.states["occurredAt"]?.let(::formatDay)).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            current.label,
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Chips {
                TextButton(onClick = { onOpenHistory(row.item.node.id) }) { Text("이력 보기") }
                TextButton(onClick = { onOpenTrace((current ?: row.item).node) }) { Text("근거 따라가기") }
            }
        }
    }
}

private fun LazyListScope.tasks(state: ContextUiState, viewModel: ContextViewModel, onOpenTrace: (NodeRef) -> Unit) {
    item {
        Chips {
            Choice("모든 할 일", selected = !state.mine) { viewModel.showMine(false) }
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
                if (state.mine) {
                    "내게 맡겨진 할 일이 없습니다. 내 계정이 인물에 연결돼 있어야 여기에 나타납니다. 연결은 소유자·검토자가 \"사람\"에서 합니다."
                } else {
                    "아직 할 일이 없습니다. 검토함에서 AI 후보를 확인하면 여기에 나타납니다."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
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
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chips {
                row.task.states["workStatus"]?.let {
                    Tag(
                        stateLabel("workStatus", it),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        container = MaterialTheme.colorScheme.primaryContainer,
                    )
                }
                ReviewStatusTag(row.task)
            }
            Text(
                row.task.label,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                listOfNotNull(
                    "담당 " + row.assignees.joinToString(", ").ifEmpty { "미정" },
                    "기한 " + (row.dueAt?.let(::formatDay) ?: "미정"),
                    row.acceptance?.let { stateLabel("acceptance", it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (canAccept && row.awaitingAcceptance) {
                Chips {
                    PrimaryButton("수락", onClick = { onAccept(true) }, enabled = enabled, loading = busy)
                    SecondaryButton("거절", onClick = { onAccept(false) }, enabled = enabled)
                }
            }
            Chips {
                if (row.canChange) {
                    Box {
                        var open by remember { mutableStateOf(false) }
                        TextButton(onClick = { open = true }, enabled = enabled) {
                            Text(if (busy) "바꾸는 중…" else "상태 바꾸기 ▾")
                        }
                        DropdownMenu(
                            expanded = open,
                            onDismissRequest = { open = false },
                            containerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            nextStatuses(row.workStatus).forEach { next ->
                                DropdownMenuItem(
                                    text = { Text(stateLabel("workStatus", next.name)) },
                                    onClick = {
                                        open = false
                                        onChange(next)
                                    },
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onOpenTrace) { Text("근거 따라가기") }
            }
        }
    }
}

private fun LazyListScope.people(state: ContextUiState, viewModel: ContextViewModel, canReview: Boolean) {
    item {
        Text(
            "회의에 나온 사람을 앱 계정에 연결합니다. 연결하면 그 사람이 맡은 일이 그 계정의 \"내 할 일\"에 나타나고, 본인이 수락·거절할 수 있습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    when {
        state.people.isNotEmpty() -> items(state.people, key = { "person:${it.person.node.id}" }) { row ->
            PersonCard(
                row = row,
                canReview = canReview,
                busy = state.busyId == row.person.node.id,
                enabled = state.busyId == null,
                onLink = { viewModel.linkAccount(row, it) },
                onUnlink = { viewModel.unlinkAccount(row) },
            )
        }

        state.loading -> item { Loading() }

        state.loadError == null -> item {
            Text(
                "아직 사람이 없습니다. 검토함에서 인물 후보를 확인하면 여기에 나타납니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PersonCard(
    row: PersonRow,
    canReview: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onLink: (email: String) -> Unit,
    onUnlink: () -> Unit,
) {
    var linking by remember { mutableStateOf(false) }
    var unlinking by remember { mutableStateOf(false) }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chips {
                if (row.linked) {
                    Tag(
                        "계정 연결됨",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        container = MaterialTheme.colorScheme.primaryContainer,
                    )
                } else {
                    Tag("계정 없음")
                }
                ReviewStatusTag(row.person)
            }
            Text(
                row.person.label,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                !canReview -> Unit
                busy -> Text("바꾸는 중…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                row.linked -> Chips {
                    if (row.canLink) TextButton(onClick = { linking = true }, enabled = enabled) { Text("다른 계정으로 바꾸기") }
                    TextButton(onClick = { unlinking = true }, enabled = enabled) { Text("연결 끊기") }
                }
                row.canLink -> SecondaryButton("계정 연결", onClick = { linking = true }, enabled = enabled)
                else -> Text(
                    "확인된 사람에게만 계정을 연결할 수 있습니다. 먼저 검토함에서 이 사람을 확인하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (linking) {
        var email by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { linking = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("계정 연결") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${row.person.label}의 가입 이메일을 적습니다. 이 프로젝트의 구성원이어야 하고, 한 계정은 한 사람에게만 연결됩니다.")
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("이메일") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        linking = false
                        onLink(email)
                    },
                    enabled = canSubmitEmail(email),
                ) { Text("연결") }
            },
            dismissButton = { TextButton(onClick = { linking = false }) { Text("취소") } },
        )
    }

    if (unlinking) {
        AlertDialog(
            onDismissRequest = { unlinking = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("연결을 끊을까요?") },
            text = { Text("${row.person.label}이(가) 맡은 일이 그 계정의 \"내 할 일\"에서 빠지고, 그 계정으로는 수락·거절을 할 수 없게 됩니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        unlinking = false
                        onUnlink()
                    },
                ) { Text("연결 끊기") }
            },
            dismissButton = { TextButton(onClick = { unlinking = false }) { Text("취소") } },
        )
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
