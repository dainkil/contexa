package com.contexa.android.ui.reviews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.contexa.android.api.NameForm
import com.contexa.android.api.NodeType
import com.contexa.android.api.NodeView
import com.contexa.android.api.RecheckCause
import com.contexa.android.api.RelType
import com.contexa.android.api.ReviewItem
import com.contexa.android.api.ReviewStatus
import com.contexa.android.api.Source
import com.contexa.android.api.Verdict
import com.contexa.android.ui.components.KeyValue
import com.contexa.android.ui.components.PrimaryButton
import com.contexa.android.ui.components.SecondaryButton
import com.contexa.android.ui.components.Section
import com.contexa.android.ui.components.SourceQuote
import com.contexa.android.ui.components.Tag
import com.contexa.android.ui.context.nameGroups
import com.contexa.android.ui.context.nameStatusLabel

/** 카드 자체의 값을 "이름 → 값"으로. 기한이 없으면 지어내지 않고 "미정"으로 둔다. */
fun cardFacts(card: NodeView): List<Pair<String, String>> {
    fun state(name: String, key: String) = card.states[name]?.takeIf { it.isNotEmpty() }?.let { key to stateLabel(name, it) }
    return when (card.ref.type) {
        NodeType.TASK -> listOfNotNull(
            state("workStatus", "상태"),
            "기한" to (card.states["dueAt"]?.takeIf { it.isNotEmpty() }?.let(::formatDay) ?: "미정"),
            state("acceptance", "수락"),
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
 * 검토 묶음 한 장: 제목, 내용, 근거, 선행요건, 판단 버튼.
 *
 * @param canReview 소유자·검토자만 판단할 수 있다. 구성원은 보기만 한다.
 * @param busy      이 묶음의 판단을 보내는 중
 * @param onOpenPerson 인물·기관 묶음에서 이름 확인 화면을 연다(언어별 표기 확인·거절, 겹치는 인물)
 * @param recheck   다시 확인 탭이면 다시 봐야 하는 이유. 아니면 null
 * @param onKeep    다시 봤고 바뀐 것이 없다고 닫는다(검토자만)
 */
@Composable
fun ReviewCard(
    item: ReviewItem,
    canReview: Boolean,
    busy: Boolean,
    documentTitles: Map<String, String>,
    onAct: (Verdict) -> Unit,
    onOpenSource: (Source) -> Unit,
    onOpenPerson: (entityId: String) -> Unit = {},
    recheck: List<RecheckCause>? = null,
    onKeep: () -> Unit = {},
) {
    val card = item.card
    val links = remember(item) { cardLinks(item) }
    val facts = (card?.let(::cardFacts).orEmpty()) + links.facts
    val verdicts = if (canReview) bundleVerdicts(item) else emptyList()
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

            val keep = recheck != null && canReview
            if (verdicts.isNotEmpty() || keep) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (keep) PrimaryButton("바뀐 것 없음", onClick = onKeep, loading = busy, modifier = Modifier.weight(1f))
                    verdicts.forEach { verdict ->
                        val label =
                            if (verdict == Verdict.CONFIRM && card?.status == ReviewStatus.CONFIRMED) "새 연결 확인" else verdictLabel(verdict)
                        val decide = {
                            // 거절·철회는 되돌릴 수 없으므로 한 번 더 묻는다.
                            if (verdict == Verdict.REJECT || verdict == Verdict.RETRACT) confirming = verdict else onAct(verdict)
                        }
                        if (verdict == Verdict.CONFIRM) {
                            PrimaryButton(label, onClick = decide, loading = busy, modifier = Modifier.weight(1f))
                        } else {
                            SecondaryButton(label, onClick = decide, enabled = !busy, modifier = Modifier.weight(1f))
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
            text = { Text("되돌릴 수 없습니다.", style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onAct(verdict)
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
