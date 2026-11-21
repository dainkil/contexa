package com.contexa.android.ui.reviews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.contexa.android.api.Edge
import com.contexa.android.api.NodeType
import com.contexa.android.api.NodeView
import com.contexa.android.api.ReviewItem
import com.contexa.android.api.ReviewStatus
import com.contexa.android.api.Source
import com.contexa.android.api.Verdict
import com.contexa.android.ui.components.PrimaryButton
import com.contexa.android.ui.components.SecondaryButton
import com.contexa.android.ui.components.SourceQuote
import com.contexa.android.ui.components.Tag
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonElement

/** 카드 종류마다 머리에 보여 줄 상태 값. */
private fun summaryStates(type: NodeType): List<String> = when (type) {
    NodeType.DECISION_ITEM -> listOf("decisionState")
    NodeType.DECISION_EVENT -> listOf("standing", "occurredAt")
    NodeType.CONDITION -> listOf("state")
    NodeType.TASK -> listOf("workStatus", "acceptance", "dueAt")
    NodeType.ENTITY -> listOf("kind")
    else -> emptyList()
}

private val DATE_STATES = mapOf("occurredAt" to "일어난 날", "dueAt" to "기한")

/** 카드 머리 아래 한 줄 요약. 보여 줄 것이 없으면 null. */
fun cardSummary(card: NodeView): String? {
    val shown = summaryStates(card.ref.type).mapNotNull { name ->
        val value = card.states[name]
        val dateLabel = DATE_STATES[name]
        when {
            dateLabel != null && !value.isNullOrEmpty() -> "$dateLabel ${formatDay(value)}"
            // 기한이 없으면 지어내지 않고 "미정"으로 둔다
            dateLabel != null -> if (name == "dueAt") "기한 미정" else null
            // 사건은 대체·철회됐을 때만 적는다. 후보에 "유효"라고 쓰면 확정된 것처럼 읽힌다
            name == "standing" && value == "ACTIVE" -> null
            value.isNullOrEmpty() -> null
            else -> stateLabel(name, value)
        }
    }
    return shown.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * 검토 묶음 한 장: 카드, 근거 원문, 딸린 줄, 판단 버튼.
 *
 * @param canReview 소유자·검토자만 판단할 수 있다. 구성원은 보기만 한다.
 * @param busy      이 묶음의 판단을 보내는 중
 * @param onChoose  줄 하나를 따로 판단한다(null = 따름)
 * @param onEdit    고치는 값을 바꾼다(null = 고치기를 그만둔다)
 * @param onAct     판단을 보낸다(verdict null = 고른 줄만 반영하거나 고친 내용만 저장)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewCard(
    item: ReviewItem,
    draft: ReviewDraft,
    canReview: Boolean,
    busy: Boolean,
    documentTitles: Map<String, String>,
    onChoose: (edgeId: String, verdict: Verdict?) -> Unit,
    onEdit: (Map<String, String>?) -> Unit,
    onAct: (verdict: Verdict?, corrections: Map<String, JsonElement>) -> Unit,
    onOpenSource: (Source) -> Unit,
) {
    val card = item.card
    val related = remember(item) { item.related.associateBy { it.ref.id } }
    val sources = remember(item) { item.sources.associateBy { it.span.id } }
    val verdicts = if (canReview) bundleVerdicts(item) else emptyList()
    val editable = canReview && card != null && fieldsOf(card.ref.type).isNotEmpty() &&
        (isPending(card.status) || card.status == ReviewStatus.CONFIRMED)
    var confirming by remember { mutableStateOf<Verdict?>(null) }
    val ownerSource = if (card == null) sources[item.owner.id] else null

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ── 머리: 종류·상태, 이름, 요약 ──
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(typeLabel(item.owner.type))
                    val eventKind = card?.states?.get("kind")?.takeIf { card.ref.type == NodeType.DECISION_EVENT }
                    if (eventKind != null) {
                        if (eventKind in DECIDING_KINDS) {
                            Tag(
                                EVENT_KIND_LABEL[eventKind] ?: eventKind,
                                color = MaterialTheme.colorScheme.onSecondary,
                                container = MaterialTheme.colorScheme.secondary,
                            )
                        } else {
                            Tag(EVENT_KIND_LABEL[eventKind] ?: eventKind)
                        }
                    }
                    if (card != null) StatusTag(card.status)
                }
                Text(
                    card?.label ?: ownerSource?.let { "“${it.text}”" } ?: "읽을 수 없는 발언",
                    style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.fillMaxWidth(),
                )
                val summary = if (card != null) cardSummary(card) else "이 발언에서 누가 말했는지, 누구를 언급했는지 확인합니다."
                summary?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── 근거 원문 ──
            item.sources.forEach { source ->
                SourceQuote(source, documentTitles[source.documentId], onOpen = { onOpenSource(source) })
            }

            // ── 딸린 줄 ──
            if (item.edges.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.edges.forEach { edge ->
                        EdgeRow(
                            edge = edge,
                            ownerId = item.owner.id,
                            related = related,
                            sources = sources,
                            documentTitles = documentTitles,
                            choices = if (verdicts.isNotEmpty()) edgeChoices(edge) else emptyList(),
                            followLabel = if (card != null) "카드를 따름" else "버튼대로",
                            choice = draft.choices[edge.id],
                            enabled = !busy,
                            onChoose = { onChoose(edge.id, it) },
                        )
                    }
                }
            }

            // ── 고치기 ──
            val values = draft.values
            if (values != null && card != null) {
                CorrectionForm(
                    card = card,
                    values = values,
                    onChange = onEdit,
                    busy = busy,
                    canConfirm = isPending(card.status),
                    onSubmit = { verdict -> onAct(verdict, changedFields(card, values)) },
                    onCancel = { onEdit(null) },
                )
            } else if (verdicts.isNotEmpty() || editable) {
                // ── 판단 버튼 ──
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    verdicts.forEach { verdict ->
                        val label =
                            if (verdict == Verdict.CONFIRM && card?.status == ReviewStatus.CONFIRMED) "새 줄 확인" else verdictLabel(verdict)
                        val decide = {
                            // 거절·철회는 되돌릴 수 없으므로 한 번 더 묻는다.
                            if (verdict == Verdict.REJECT || verdict == Verdict.RETRACT) confirming = verdict else onAct(verdict, emptyMap())
                        }
                        if (verdict == Verdict.CONFIRM) {
                            PrimaryButton(label, onClick = decide, loading = busy)
                        } else {
                            SecondaryButton(label, onClick = decide, enabled = !busy)
                        }
                    }
                    if (draft.choices.isNotEmpty()) {
                        SecondaryButton("고른 줄만 반영", onClick = { onAct(null, emptyMap()) }, enabled = !busy)
                    }
                    if (editable && card != null) {
                        TextButton(onClick = { onEdit(currentValues(card)) }, enabled = !busy) { Text("고치기") }
                    }
                }
            }
        }
    }

    confirming?.let { verdict ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(if (verdict == Verdict.RETRACT) "확인을 철회할까요?" else "이 묶음을 거절할까요?") },
            text = {
                Text(
                    "거절·철회는 되돌릴 수 없습니다. 같은 내용이 다시 필요하면 새 후보로 들어와야 합니다. 따로 고르지 않은 줄도 함께 처리됩니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onAct(verdict, emptyMap())
                }) { Text(verdictLabel(verdict), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("그만두기") } },
        )
    }
}

@Composable
private fun StatusTag(status: ReviewStatus) {
    val label = statusLabel(status)
    when (statusTone(status)) {
        StatusTone.CONFIRMED -> Tag(
            label,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            container = MaterialTheme.colorScheme.primaryContainer,
        )
        StatusTone.HELD -> Tag(
            label,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            container = MaterialTheme.colorScheme.secondaryContainer,
        )
        StatusTone.CANDIDATE -> Tag(label, color = MaterialTheme.colorScheme.tertiary)
        StatusTone.CLOSED -> Tag(label)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EdgeRow(
    edge: Edge,
    ownerId: String,
    related: Map<String, NodeView>,
    sources: Map<String, Source>,
    documentTitles: Map<String, String>,
    choices: List<Verdict?>,
    followLabel: String,
    choice: Verdict?,
    enabled: Boolean,
    onChoose: (Verdict?) -> Unit,
) {
    val ownerIsSrc = edge.src.id == ownerId
    val far = if (ownerIsSrc) edge.dst else edge.src
    val relation = relationLabel(edge.rel, ownerIsSrc)

    val name: String
    val readable: Boolean
    if (far.type == NodeType.SPAN) {
        val source = sources[far.id]
        readable = source != null
        name = source?.let { "${documentTitles[it.documentId] ?: "문서"} ${it.seq}번 발언" } ?: "읽을 수 없는 발언"
    } else {
        val view = related[far.id]
        readable = view != null
        // 볼 수 없는 카드: 근거가 내가 읽을 수 없는 문서에만 있다.
        name = view?.let { "${typeLabel(far.type)} “${it.label}”" } ?: "${typeLabel(far.type)} (볼 수 없는 카드)"
    }

    Column {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$relation →", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                color = if (readable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusTag(edge.status)
        }
        if (choices.size > 1) {
            var open by remember { mutableStateOf(false) }
            val label: (Verdict?) -> String = { if (it == null) followLabel else "이 줄만 ${verdictLabel(it)}" }
            Box {
                TextButton(onClick = { open = true }, enabled = enabled) {
                    Text("${label(choice)} ▾", style = MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    choices.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(label(option), style = MaterialTheme.typography.bodyLarge) },
                            onClick = {
                                open = false
                                onChoose(option)
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CorrectionForm(
    card: NodeView,
    values: Map<String, String>,
    onChange: (Map<String, String>) -> Unit,
    busy: Boolean,
    canConfirm: Boolean,
    onSubmit: (Verdict?) -> Unit,
    onCancel: () -> Unit,
) {
    val missing = missingFields(card, values)
    val changed = changedFields(card, values).isNotEmpty()

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fieldsOf(card.ref.type).forEach { spec ->
                FieldInput(spec, values[spec.name].orEmpty(), onChange = { onChange(values + (spec.name to it)) })
            }
            if (missing.isNotEmpty()) {
                Text(
                    "비울 수 없는 칸: ${missing.joinToString(", ")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (canConfirm) {
                    PrimaryButton("고쳐서 확인", onClick = { onSubmit(Verdict.CONFIRM) }, enabled = missing.isEmpty(), loading = busy)
                    SecondaryButton("고친 내용만 저장", onClick = { onSubmit(null) }, enabled = !busy && missing.isEmpty() && changed)
                } else {
                    PrimaryButton("고친 내용만 저장", onClick = { onSubmit(null) }, enabled = missing.isEmpty() && changed, loading = busy)
                }
                TextButton(onClick = onCancel, enabled = !busy) { Text("취소") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FieldInput(spec: FieldSpec, value: String, onChange: (String) -> Unit) {
    when (spec.kind) {
        FieldKind.TEXT, FieldKind.TEXTAREA -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(spec.label) },
            singleLine = spec.kind == FieldKind.TEXT,
            minLines = if (spec.kind == FieldKind.TEXTAREA) 2 else 1,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            modifier = Modifier.fillMaxWidth(),
        )

        FieldKind.SELECT -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(spec.label, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                spec.options.forEach { option ->
                    FilterChip(
                        selected = value == option.value,
                        onClick = { onChange(option.value) },
                        label = { Text(option.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        }

        FieldKind.DATE -> {
            var picking by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(spec.label, style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (value.isEmpty()) "정하지 않음" else value,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = { picking = true }) { Text("날짜 고르기") }
                    if (value.isNotEmpty()) TextButton(onClick = { onChange("") }) { Text("지우기") }
                }
            }
            if (picking) {
                // 날짜 고르개는 UTC 자정의 밀리초로 날짜를 주고받는다. 칸의 값은 "2026-12-05" 글자다.
                val picker = rememberDatePickerState(
                    initialSelectedDateMillis = value.takeIf { it.isNotEmpty() }
                        ?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() },
                )
                DatePickerDialog(
                    onDismissRequest = { picking = false },
                    confirmButton = {
                        TextButton(onClick = {
                            picker.selectedDateMillis?.let {
                                onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                            }
                            picking = false
                        }) { Text("고르기") }
                    },
                    dismissButton = { TextButton(onClick = { picking = false }) { Text("취소") } },
                ) {
                    DatePicker(state = picker)
                }
            }
        }
    }
}
