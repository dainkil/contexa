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
import com.contexa.android.ui.reviews.stateLabel

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

/** 확정 화면에 그릴 것만: 확인된 결정 항목 가운데 지금 유효한 결정도 확인된 것. 그런 항목이 없는 안건은 뺀다. */
fun confirmedBoard(packet: ContextPacket): List<TopicGroup> =
    decisionBoard(packet)
        .map { group -> group.copy(rows = group.rows.filter { it.item.status == ReviewStatus.CONFIRMED && it.current?.status == ReviewStatus.CONFIRMED }) }
        .filter { it.rows.isNotEmpty() }

fun openLabel(kind: OpenIssueKind): String = when (kind) {
    OpenIssueKind.HELD_NODE -> "보류 중"
    OpenIssueKind.HELD_EDGE -> "보류 중인 연결"
    OpenIssueKind.CONTESTED_DECISION -> "확정이 둘 겹침"
    OpenIssueKind.UNMET_CONDITION -> "조건 충족 전"
    OpenIssueKind.AMBIGUOUS_NAME -> "누구인지 불분명한 이름"
    OpenIssueKind.NEEDS_RECHECK -> "다시 확인 필요"
}

/** "확인" 칸의 한 줄. kind = 무엇이 문제인가, text = 걸린 것의 내용(연결에 걸렸거나 읽을 수 없으면 null), count = 같은 줄이 몇 건인가. */
data class Notice(val kind: String, val text: String?, val count: Int = 1)

private val UNSETTLED = setOf("UNDECIDED", "WITHDRAWN")

/**
 * 알아야 하는 사항: server가 알려 준 남은 문제와, 확인됐지만 아직 유효한 결정이 없는 항목.
 * 아직 사람이 확인하지 않은 AI 후보에 걸린 것은 뺀다(검토함에서 다룬다). 같은 줄은 하나로 합쳐 건수를 센다.
 */
fun notices(packet: ContextPacket): List<Notice> {
    val byId = packet.facts.associateBy { it.node.id }
    val issues = packet.open.mapNotNull { issue ->
        val fact = issue.node?.let { byId[it.id] }
        if (fact?.status == ReviewStatus.CANDIDATE) null else openLabel(issue.kind) to fact?.label
    }
    val unsettled = packet.facts
        .filter { it.node.type == NodeType.DECISION_ITEM && it.status == ReviewStatus.CONFIRMED }
        .mapNotNull { item -> item.states["decisionState"]?.takeIf { it in UNSETTLED }?.let { stateLabel("decisionState", it) to item.label } }
    return (issues + unsettled).groupingBy { it }.eachCount().map { (row, count) -> Notice(row.first, row.second, count) }
}

/**
 * 할 일 한 줄. assignees = 담당으로 이어진 인물·기관 이름.
 * needsRecheck = 기대는 카드나 발언이 바뀌어 다시 봐야 한다(server가 open에 NEEDS_RECHECK로 알린다).
 */
data class TaskRow(val task: Fact, val assignees: List<String>, val needsRecheck: Boolean = false) {
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
    val recheck = packet.open.filter { it.kind == OpenIssueKind.NEEDS_RECHECK }.mapNotNull { it.node?.id }.toSet()
    return packet.facts.filter { it.node.type == NodeType.TASK }.map { task ->
        TaskRow(task, assigned[task.node.id].orEmpty().mapNotNull { byId[it]?.label }.distinct(), task.node.id in recheck)
    }
}

/** 이 문서의 발언을 근거로 한 할 일. 원문 뷰어가 "누가 무엇을 맡았나"를 보여 줄 때 쓴다. */
fun tasksOfDocument(packet: ContextPacket, documentId: String): List<TaskRow> =
    taskRows(packet).filter { row -> sourcesOf(row.task, packet).any { it.documentId == documentId } }

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
