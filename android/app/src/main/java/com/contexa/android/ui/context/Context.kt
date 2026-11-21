package com.contexa.android.ui.context

import com.contexa.android.api.ContextPacket
import com.contexa.android.api.Fact
import com.contexa.android.api.NodeRef
import com.contexa.android.api.NodeType
import com.contexa.android.api.OpenIssueKind
import com.contexa.android.api.RelType
import com.contexa.android.api.ReviewStatus
import com.contexa.android.api.Source
import com.contexa.android.api.TaskStatus
import com.contexa.android.ui.reviews.relationLabel

/* 맥락 화면의 규칙: server가 준 ContextPacket(카드·줄·원문)을 화면에 그릴 모양으로 엮는다. 화면과 떼어 두고 테스트한다. */

/** 결정 항목 하나와 지금 유효한 결정(없으면 null). */
data class DecisionRow(val item: Fact, val current: Fact?)

/** 안건 하나와 그 아래 결정 항목. topic이 null이면 안건에 묶이지 않은 항목들이다. */
data class TopicGroup(val topic: Fact?, val rows: List<DecisionRow>)

/** 현황을 안건별로 묶는다. 안건은 server가 준 순서, 안건 없는 항목은 맨 뒤. 항목이 없는 안건도 보여 준다. */
fun decisionBoard(packet: ContextPacket): List<TopicGroup> {
    val byId = packet.facts.associateBy { it.node.id }
    val topicOf = packet.relations.filter { it.rel == RelType.HAS_ITEM }.associate { it.dst.id to it.src.id }
    val rows = packet.facts.filter { it.node.type == NodeType.DECISION_ITEM }
        .map { item -> DecisionRow(item, item.states["currentEventId"]?.let(byId::get)) }
    val groups = packet.facts.filter { it.node.type == NodeType.TOPIC }
        .map { topic -> TopicGroup(topic, rows.filter { topicOf[it.item.node.id] == topic.node.id }) }
    val known = groups.map { it.topic?.node?.id }.toSet()
    val loose = rows.filter { topicOf[it.item.node.id] !in known }
    return if (loose.isEmpty()) groups else groups + TopicGroup(null, loose)
}

fun openLabel(kind: OpenIssueKind): String = when (kind) {
    OpenIssueKind.HELD_NODE -> "보류된 카드"
    OpenIssueKind.HELD_EDGE -> "보류된 연결"
    OpenIssueKind.CONTESTED_DECISION -> "확정이 둘 겹친 결정"
    OpenIssueKind.UNMET_CONDITION -> "아직 충족 안 된 조건"
    OpenIssueKind.AMBIGUOUS_NAME -> "누구인지 여럿인 이름"
}

/** 남은 것 한 줄. label은 걸린 카드의 내용. 줄에 걸렸거나 읽을 수 없는 카드면 null. */
data class OpenRow(val kind: OpenIssueKind, val label: String?, val node: NodeRef?)

fun openRows(packet: ContextPacket): List<OpenRow> {
    val byId = packet.facts.associateBy { it.node.id }
    return packet.open.map { issue -> OpenRow(issue.kind, issue.node?.let { byId[it.id]?.label }, issue.node) }
}

/** 할 일 한 줄. assignees = 담당으로 이어진 인물·기관 이름. */
data class TaskRow(val task: Fact, val assignees: List<String>) {
    val workStatus: TaskStatus? get() = task.states["workStatus"]?.let { name -> TaskStatus.entries.find { it.name == name } }
    val acceptance: String? get() = task.states["acceptance"]
    val dueAt: String? get() = task.states["dueAt"]

    /** server는 확인된 할 일만 상태를 바꿔 준다. */
    val canChange: Boolean get() = task.status == ReviewStatus.CONFIRMED

    val awaitingAcceptance: Boolean get() = canChange && acceptance == "PENDING"
}

fun taskRows(packet: ContextPacket): List<TaskRow> {
    val byId = packet.facts.associateBy { it.node.id }
    val assigned = packet.relations.filter { it.rel == RelType.ASSIGNED_TO }.groupBy({ it.src.id }, { it.dst.id })
    return packet.facts.filter { it.node.type == NodeType.TASK }.map { task ->
        TaskRow(task, assigned[task.node.id].orEmpty().mapNotNull { byId[it]?.label }.distinct())
    }
}

/** 인물 한 명. linked = 이 프로젝트의 계정에 연결돼 있다(누구의 계정인지는 server가 알려 주지 않는다). */
data class PersonRow(val person: Fact) {
    val linked: Boolean get() = person.states["accountLinked"] == "true"

    /** server는 확인된 인물 카드에만 계정을 연결해 준다. 끊는 것은 언제나 된다. */
    val canLink: Boolean get() = person.status == ReviewStatus.CONFIRMED
}

/** 현황에 실려 온 인물·기관 가운데 사람만. 기관에는 계정을 연결할 수 없다. */
fun personRows(packet: ContextPacket): List<PersonRow> =
    packet.facts.filter { it.node.type == NodeType.ENTITY && it.states["kind"] == "PERSON" }.map(::PersonRow)

/** 연결할 이메일로 보낼 수 있나. 형식은 server가 가입 여부로 가린다. */
fun canSubmitEmail(text: String): Boolean = text.trim().let { it.length >= 3 && '@' in it && ' ' !in it }

/** 지금 상태에서 고를 수 있는 다른 상태. */
fun nextStatuses(current: TaskStatus?): List<TaskStatus> = TaskStatus.entries.filter { it != current }

/** 카드의 근거 발언 원문. 읽을 수 없는 문서의 발언은 packet에 없으므로 빠진다. */
fun sourcesOf(fact: Fact, packet: ContextPacket): List<Source> {
    val byId = packet.sources.associateBy { it.span.id }
    return fact.evidence.mapNotNull { byId[it.span.id] }.distinctBy { it.span.id }
}

/** 결정 이력: 사건을 server가 준 순서(일어난 순서)대로. */
fun historyEvents(packet: ContextPacket): List<Fact> = packet.facts.filter { it.node.type == NodeType.DECISION_EVENT }

/** 따라가서 만난 카드 하나. relation = 출발 카드에서 본 줄 이름. 한 다리 건너 만난 카드면 null. */
data class Linked(val relation: String?, val fact: Fact)

/** 출발 카드에 바로 이어진 카드를 먼저, 그다음 더 멀리서 만난 카드. 발언은 원문 목록으로 따로 보여 주므로 뺀다. */
fun linkedCards(packet: ContextPacket): List<Linked> {
    val focus = packet.scope.focus?.id
    val cards = packet.facts.filter { it.node.id != focus && it.node.type != NodeType.SPAN }
    val direct = LinkedHashMap<String, String>()
    packet.relations.forEach { edge ->
        when (focus) {
            edge.src.id -> direct.putIfAbsent(edge.dst.id, relationLabel(edge.rel, ownerIsSrc = true))
            edge.dst.id -> direct.putIfAbsent(edge.src.id, relationLabel(edge.rel, ownerIsSrc = false))
        }
    }
    val (near, far) = cards.partition { it.node.id in direct }
    return near.map { Linked(direct[it.node.id], it) } + far.map { Linked(null, it) }
}

fun statusChangedMessage(next: TaskStatus, impacts: Int): String {
    val base = "상태를 바꿨습니다."
    return if (impacts > 0) "$base 여기에 기대는 카드 ${impacts}개가 영향을 받습니다." else base
}
