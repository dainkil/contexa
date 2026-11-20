package com.contexa.android.ui.reviews

import com.contexa.android.api.Edge
import com.contexa.android.api.NodeRef
import com.contexa.android.api.NodeType
import com.contexa.android.api.NodeView
import com.contexa.android.api.RelType
import com.contexa.android.api.ReviewItem
import com.contexa.android.api.ReviewState
import com.contexa.android.api.ReviewStatus
import com.contexa.android.api.Source

/* 검토 화면 테스트용 묶음 만들기. */

fun ref(type: NodeType, id: String, revision: Int = 1) = NodeRef(type, id, revision)

fun view(
    type: NodeType,
    id: String,
    label: String,
    status: ReviewStatus = ReviewStatus.CANDIDATE,
    states: Map<String, String> = emptyMap(),
) = NodeView(ref(type, id), "p1", status, label, states)

fun edge(
    id: String,
    rel: RelType,
    src: NodeRef,
    dst: NodeRef,
    status: ReviewStatus = ReviewStatus.CANDIDATE,
    evidenceSpanIds: List<String> = emptyList(),
) = Edge(id, "p1", rel, src, dst, status, evidenceSpanIds, ontologyVersion = "1", version = 1)

fun source(id: String, seq: Int, text: String, documentId: String = "doc-1", speaker: String? = "민지") =
    Source(ref(NodeType.SPAN, id), documentId, seq, speaker, text)

/**
 * 할 일 "인쇄 발주" 묶음: 22번 발언이 근거이고, 조건 "예산 승인"을 먼저 필요로 하며,
 * 남의 개인 메모에서 나온 조건 하나에도 기댄다(그 카드는 볼 수 없다).
 */
fun printTask(
    id: String = "r1",
    version: Int = 3,
    state: ReviewState = ReviewState.OPEN,
    card: NodeView? = view(
        NodeType.TASK, "t1", "인쇄 발주", ReviewStatus.CANDIDATE,
        mapOf("workStatus" to "PLANNED", "acceptance" to "PENDING"),
    ),
    owner: NodeRef = card?.ref ?: ref(NodeType.TASK, "t1"),
    edges: List<Edge> = listOf(
        edge("e-ev", RelType.EVIDENCED_BY, owner, ref(NodeType.SPAN, "s22"), evidenceSpanIds = listOf("s22")),
        edge("e-req", RelType.REQUIRES, owner, ref(NodeType.CONDITION, "c1"), evidenceSpanIds = listOf("s22")),
        edge("e-hidden", RelType.REQUIRES, owner, ref(NodeType.CONDITION, "c-memo"), evidenceSpanIds = listOf("s-memo")),
    ),
) = ReviewItem(
    id = id,
    version = version,
    state = state,
    owner = owner,
    card = card,
    edges = edges,
    related = listOf(view(NodeType.CONDITION, "c1", "예산 승인")),
    sources = listOf(source("s22", 22, "예산 승인 전까지 인쇄는 보류입니다.")),
)
