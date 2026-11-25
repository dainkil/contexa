package com.contexa.android.ui.reviews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.contexa.android.api.Edge
import com.contexa.android.api.NameForm
import com.contexa.android.api.NodeType
import com.contexa.android.api.NodeView
import com.contexa.android.api.RecheckCause
import com.contexa.android.api.RelType
import com.contexa.android.api.ReviewItem
import com.contexa.android.api.ReviewStatus
import com.contexa.android.api.Source
import com.contexa.android.api.Verdict
import com.contexa.android.ui.components.ChoiceChip
import com.contexa.android.ui.components.KeyValue
import com.contexa.android.ui.components.KoreanLocale
import com.contexa.android.ui.components.PrimaryButton
import com.contexa.android.ui.components.SecondaryButton
import com.contexa.android.ui.components.Section
import com.contexa.android.ui.components.SourceQuote
import com.contexa.android.ui.components.Tag
import com.contexa.android.ui.context.nameGroups
import com.contexa.android.ui.context.nameStatusLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonElement

/** 카드 자체의 값을 "이름 → 값"으로. 기한이 없으면 지어내지 않고 "미정"으로 둔다. */
fun cardFacts(card: NodeView): List<Pair<String, String>> {
    fun state(name: String, key: String) = card.states[name]?.takeIf { it.isNotEmpty() }?.let { key to stateLabel(name, it) }
    return when (card.ref.type) {
        NodeType.TASK -> listOfNotNull(
            state("workStatus", "상태"),
            "기한" to (card.states["dueAt"]?.takeIf { it.isNotEmpty() }?.let(::formatDay) ?: "미정"),
            // 새로 뽑힌 할 일은 늘 "수락 전"이라 알려 주는 것이 없다. 담당자가 정했을 때만 적는다
            card.states["acceptance"]?.takeIf { it != "PENDING" }?.let { "수락" to stateLabel("acceptance", it) },
        )
        NodeType.DECISION_EVENT -> listOfNotNull(
            card.states["occurredAt"]?.takeIf { it.isNotEmpty() }?.let { "일어난 날" to formatDay(it) },
            // 사건은 대체·철회됐을 때만 적는다. 후보에 "유효"라고 쓰면 확정된 것처럼 읽힌다
            card.states["standing"]?.takeIf { it != "ACTIVE" }?.let { "상태" to stateLabel("standing", it) },
        )
        NodeType.CONDITION -> listOfNotNull(state("state", "상태"))
        NodeType.DECISION_ITEM -> listOfNotNull(state("decisionState", "상태"))
        NodeType.ENTITY -> listOfNotNull(state("kind", "종류"))
        else -> emptyList()
    }
}

/** 묶음의 줄을 화면 구역으로 나눈 것. */
data class CardLinks(
    /** 내용 구역에 "이름 → 값"으로 적을 줄(담당 등). */
    val facts: List<Pair<String, String>>,
    /** 선행요건 구역: 이 카드가 먼저 필요로 하는 것. */
    val prerequisites: List<String>,
)

/** 근거 줄은 근거 구역이 원문으로 보여 주므로 뺀다. 건너편을 읽을 수 없으면 그렇다고 적는다. */
fun cardLinks(item: ReviewItem): CardLinks {
    val related = item.related.associateBy { it.ref.id }
    val facts = ArrayList<Pair<String, String>>()
    val prerequisites = ArrayList<String>()
    for (edge in item.edges) {
        val ownerIsSrc = edge.src.id == item.owner.id
        val far = if (ownerIsSrc) edge.dst else edge.src
        if (far.type == NodeType.SPAN) continue
        val name = related[far.id]?.label ?: "볼 수 없는 ${typeLabel(far.type)}"
        // 어느 안건·결정 항목에 속하는지는 카드에 적지 않는다. 맥락 화면의 그림이 보여 준다.
        if (edge.rel == RelType.HAS_ITEM || edge.rel == RelType.HAS_EVENT) continue
        if (edge.rel == RelType.REQUIRES && ownerIsSrc) prerequisites += name else facts += relationLabel(edge.rel, ownerIsSrc) to name
    }
    return CardLinks(facts.distinct(), prerequisites.distinct())
}

/**
 * 줄 하나를 사람이 읽는 말로: 관계 이름과 건너편 이름.
 * 건너편이 발언이면 "문서 제목 N번 발언", 카드이면 "종류 “이름”". 읽을 수 없으면 그렇다고 적는다.
 */
fun edgeTarget(edge: Edge, item: ReviewItem, documentTitles: Map<String, String>): Pair<String, String> {
    val ownerIsSrc = edge.src.id == item.owner.id
    val far = if (ownerIsSrc) edge.dst else edge.src
    val name = if (far.type == NodeType.SPAN) {
        item.sources.find { it.span.id == far.id }
            ?.let { "${documentTitles[it.documentId] ?: "문서"} ${it.seq}번 발언" }
            ?: "읽을 수 없는 발언"
    } else {
        item.related.find { it.ref.id == far.id }?.let { "${typeLabel(far.type)} “${it.label}”" }
            ?: "볼 수 없는 ${typeLabel(far.type)}"
    }
    return relationLabel(edge.rel, ownerIsSrc) to name
}

/**
 * 검토 묶음 한 장: 제목, 내용, 근거, 선행요건, 줄마다 판단, 고치기, 판단 버튼.
 *
 * @param draft     이 묶음에서 고르던 것(줄마다 판단, 고치는 값)
 * @param canReview 소유자·검토자만 판단할 수 있다. 구성원은 보기만 한다.
 * @param busy      이 묶음의 판단을 보내는 중
 * @param onChoose  줄 하나를 따로 판단한다(null = 따름)
 * @param onEdit    고치는 값을 바꾼다(null = 고치기를 그만둔다)
 * @param onAct     판단을 보낸다(verdict null = 고른 줄만 반영하거나 고친 내용만 저장)
 * @param onOpenPerson 인물·기관 묶음에서 이름 확인 화면을 연다(언어별 표기 확인·거절, 겹치는 인물)
 * @param recheck   다시 확인 탭이면 다시 봐야 하는 이유. 아니면 null
 * @param onKeep    다시 봤고 바뀐 것이 없다고 닫는다(검토자만)
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
    onOpenPerson: (entityId: String) -> Unit = {},
    recheck: List<RecheckCause>? = null,
    onKeep: () -> Unit = {},
) {
    val card = item.card
    val links = remember(item) { cardLinks(item) }
    val facts = (card?.let(::cardFacts).orEmpty()) + links.facts
    val verdicts = if (canReview) bundleVerdicts(item) else emptyList()
    val decidable = if (verdicts.isNotEmpty()) decidableEdges(item) else emptyList()
    val editable = canReview && canCorrect(item)
    // 줄마다 정하기는 접어 둔다. 이미 고른 줄이 있으면 펼친다.
    var showEdges by rememberSaveable(item.id) { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<Verdict?>(null) }
    // 카드가 없는 묶음은 발언 하나가 주인이다. 그 발언을 제목으로 쓴다.
    val ownerSource = if (card == null) item.sources.find { it.span.id == item.owner.id } else null

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 확인할 것은 모두 AI 후보라서 상태를 적지 않는다. 이미 처리한 것만 결과를 적는다.
                val eventKind = card?.states?.get("kind")?.takeIf { card.ref.type == NodeType.DECISION_EVENT }
                Text(
                    listOfNotNull(
                        typeLabel(item.owner.type),
                        eventKind?.let { EVENT_KIND_LABEL[it] ?: it },
                        card?.status?.takeIf { it != ReviewStatus.CANDIDATE }?.let(::statusLabel),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    card?.label ?: ownerSource?.text ?: "읽을 수 없는 발언",
                    style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            recheck?.let { causes -> RecheckReasons(causes) }

            if (facts.isNotEmpty()) {
                Section("내용") {
                    facts.forEach { (key, value) -> KeyValue(key, value) }
                }
            }

            if (item.owner.type == NodeType.ENTITY) {
                // 인물·기관 묶음: 언어별 표기. 표기마다의 확인·거절은 이름 확인 화면에서 한다
                Section("표기") {
                    nameGroups(item.names).forEach { group ->
                        group.forms.forEach { form -> NameRow(group.label, form) }
                    }
                    TextButton(onClick = { onOpenPerson(item.owner.id) }) {
                        Text(if (canReview) "표기 확인하기" else "이름 자세히 보기")
                    }
                }
            }

            if (item.sources.isNotEmpty()) {
                Section(if (item.owner.type == NodeType.ENTITY) "언급한 발언" else "근거") {
                    item.sources.forEach { source ->
                        SourceQuote(source, documentTitles[source.documentId], onOpen = { onOpenSource(source) })
                    }
                }
            }

            if (links.prerequisites.isNotEmpty()) {
                Section("선행요건") {
                    links.prerequisites.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (decidable.isNotEmpty() && (showEdges || draft.choices.isNotEmpty())) {
                Section("줄마다 정하기") {
                    val follow = followLabel(item)
                    decidable.forEach { edge ->
                        EdgeRow(
                            edge = edge,
                            target = edgeTarget(edge, item, documentTitles),
                            choices = edgeChoices(edge),
                            follow = follow,
                            choice = draft.choices[edge.id],
                            enabled = !busy,
                            onChoose = { onChoose(edge.id, it) },
                        )
                    }
                }
            }

            val values = draft.values
            if (values != null && card != null && editable) {
                CorrectionForm(
                    card = card,
                    values = values,
                    onChange = onEdit,
                    busy = busy,
                    canConfirm = isPending(card.status),
                    onSubmit = { verdict -> onAct(verdict, changedFields(card, values)) },
                    onCancel = { onEdit(null) },
                )
            } else {
                val keep = recheck != null && canReview
                if (confirmSweepsEdges(item, draft.choices) && verdicts.isNotEmpty()) {
                    Text(
                        CONFIRM_SWEEP_NOTE,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (verdicts.isNotEmpty() || keep) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (keep) PrimaryButton("바뀐 것 없음", onClick = onKeep, loading = busy, modifier = Modifier.weight(1f))
                        verdicts.forEach { verdict ->
                            val label =
                                if (verdict == Verdict.CONFIRM && card?.status == ReviewStatus.CONFIRMED) "새 연결 확인" else verdictLabel(verdict)
                            val decide = {
                                // 거절·철회는 되돌릴 수 없으므로 한 번 더 묻는다.
                                if (verdict == Verdict.REJECT || verdict == Verdict.RETRACT) confirming = verdict else onAct(verdict, emptyMap())
                            }
                            if (verdict == Verdict.CONFIRM) {
                                PrimaryButton(label, onClick = decide, loading = busy, modifier = Modifier.weight(1f))
                            } else {
                                SecondaryButton(label, onClick = decide, enabled = !busy, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (decidable.isNotEmpty() || editable) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (draft.choices.isNotEmpty()) {
                            SecondaryButton("고른 줄만 반영", onClick = { onAct(null, emptyMap()) }, enabled = !busy)
                        } else if (decidable.isNotEmpty()) {
                            TextButton(onClick = { showEdges = !showEdges }, enabled = !busy) {
                                Text(if (showEdges) "줄마다 정하기 접기" else "줄마다 정하기")
                            }
                        }
                        if (editable && card != null) {
                            TextButton(onClick = { onEdit(currentValues(card)) }, enabled = !busy) { Text("고치기") }
                        }
                    }
                }
            }
        }
    }

    confirming?.let { verdict ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    if (verdict == Verdict.RETRACT) "확인을 철회할까요?" else "거절할까요?",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    "되돌릴 수 없습니다. 따로 고르지 않은 줄도 함께 ${verdictLabel(verdict)}됩니다.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onAct(verdict, emptyMap())
                }) { Text(verdictLabel(verdict), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("취소") } },
        )
    }
}

/** 다시 봐야 하는 이유. 고치거나 철회하면(묶음 버튼) 저절로 닫히고, 그대로 두려면 "바뀐 것 없음"을 누른다. */
@Composable
private fun RecheckReasons(causes: List<RecheckCause>) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "기대는 내용이 바뀌었습니다. 다시 확인해 주세요.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            causes.forEach { cause ->
                Text(
                    recheckCauseLine(cause),
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 표기 한 줄: 언어, 표기, 상태. */
@Composable
private fun NameRow(language: String, form: NameForm) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            language,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            form.form,
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            modifier = Modifier.weight(1f),
        )
        Tag(nameStatusLabel(form.status))
    }
}

/** 줄 하나: 관계 → 건너편, 지금 상태, 고르기. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EdgeRow(
    edge: Edge,
    target: Pair<String, String>,
    choices: List<Verdict?>,
    follow: String,
    choice: Verdict?,
    enabled: Boolean,
    onChoose: (Verdict?) -> Unit,
) {
    val (relation, name) = target
    Column(modifier = Modifier.fillMaxWidth()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(relation, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(name, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
            Tag(statusLabel(edge.status))
        }
        var open by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { open = true }, enabled = enabled) {
                Text(
                    "${edgeChoiceLabel(choice, follow)} ▾",
                    color = if (choice == Verdict.REJECT || choice == Verdict.RETRACT) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                choices.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(edgeChoiceLabel(option, follow), style = MaterialTheme.typography.bodyLarge) },
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

/** 카드 값을 고치는 칸. 확인 전이면 "고쳐서 확인", 이미 확인된 카드면 "고친 내용만 저장". */
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
                FieldInput(spec, values[spec.name].orEmpty(), enabled = !busy, onChange = { onChange(values + (spec.name to it)) })
            }
            if (missing.isNotEmpty()) {
                Text(
                    "비울 수 없는 칸: ${missing.joinToString(", ")}",
                    style = MaterialTheme.typography.bodyLarge,
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

/** 칸 하나. 날짜는 고르개로 고르고 "2026-12-05" 글자로 둔다. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FieldInput(spec: FieldSpec, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    when (spec.kind) {
        FieldKind.TEXT, FieldKind.TEXTAREA -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(spec.label) },
            enabled = enabled,
            singleLine = spec.kind == FieldKind.TEXT,
            minLines = if (spec.kind == FieldKind.TEXTAREA) 2 else 1,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            modifier = Modifier.fillMaxWidth(),
        )

        FieldKind.SELECT -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(spec.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                spec.options.forEach { option ->
                    ChoiceChip(option.label, selected = value == option.value, onClick = { if (enabled) onChange(option.value) })
                }
            }
        }

        FieldKind.DATE -> {
            var picking by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(spec.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (value.isEmpty()) "정하지 않음" else value,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = { picking = true }, enabled = enabled) { Text("날짜 고르기") }
                    if (value.isNotEmpty()) TextButton(onClick = { onChange("") }, enabled = enabled) { Text("지우기") }
                }
            }
            if (picking) {
                // 기기 언어가 영어여도 다른 화면처럼 한국어로 보이게 한다.
                // 바깥은 고르개 상태(요일 이름·주 시작 요일)를, 안쪽은 대화상자 글자를 바꾼다.
                // 대화상자는 창이 따로라 바깥에서 바꾼 설정을 자기 설정으로 다시 덮는다.
                KoreanLocale {
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
                        KoreanLocale { DatePicker(state = picker) }
                    }
                }
            }
        }
    }
}
