package com.contexa.android.ui.reviews

import com.contexa.android.api.NodeType
import com.contexa.android.api.RelType
import com.contexa.android.api.ReviewStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** 카드 종류. */
fun typeLabel(type: NodeType): String = when (type) {
    NodeType.TOPIC -> "안건"
    NodeType.DECISION_ITEM -> "결정 항목"
    NodeType.DECISION_EVENT -> "결정 사건"
    NodeType.CONDITION -> "조건"
    NodeType.TASK -> "할 일"
    NodeType.ENTITY -> "인물·기관"
    NodeType.SPAN -> "발언"
    NodeType.ARTIFACT -> "답변·초안"
}

/** 검토함에서 거를 수 있는 종류. 자주 보는 것부터. */
val FILTER_TYPES = listOf(
    NodeType.DECISION_EVENT,
    NodeType.TASK,
    NodeType.CONDITION,
    NodeType.DECISION_ITEM,
    NodeType.TOPIC,
    NodeType.ENTITY,
    NodeType.SPAN,
)

enum class StatusTone { CANDIDATE, HELD, CONFIRMED, CLOSED }

/** 카드·줄의 확인 상태. */
fun statusLabel(status: ReviewStatus): String = when (status) {
    ReviewStatus.CANDIDATE -> "AI 후보"
    ReviewStatus.HELD -> "보류"
    ReviewStatus.CONFIRMED -> "확인됨"
    ReviewStatus.REJECTED -> "거절됨"
    ReviewStatus.RETRACTED -> "철회됨"
}

fun statusTone(status: ReviewStatus): StatusTone = when (status) {
    ReviewStatus.CANDIDATE -> StatusTone.CANDIDATE
    ReviewStatus.HELD -> StatusTone.HELD
    ReviewStatus.CONFIRMED -> StatusTone.CONFIRMED
    ReviewStatus.REJECTED, ReviewStatus.RETRACTED -> StatusTone.CLOSED
}

/** 결정 사건의 유형. 확정·변경만 현재 결정이 될 수 있다. */
val EVENT_KIND_LABEL: Map<String, String> = linkedMapOf(
    "PROPOSAL" to "제안",
    "DISCUSSION" to "논의",
    "DEFERRAL" to "보류(미룸)",
    "DECISION" to "확정",
    "CHANGE" to "변경",
    "WITHDRAWAL" to "철회",
)

val DECIDING_KINDS = setOf("DECISION", "CHANGE")

val ENTITY_KIND_LABEL: Map<String, String> = linkedMapOf("PERSON" to "사람", "ORGANIZATION" to "기관")

private val STATE_LABEL: Map<String, Map<String, String>> = mapOf(
    "decisionState" to mapOf(
        "UNDECIDED" to "아직 정해지지 않음",
        "DECIDED" to "결정됨",
        "CONTESTED" to "확정이 둘 겹침",
        "WITHDRAWN" to "유효한 결정 없음",
    ),
    "standing" to mapOf("ACTIVE" to "유효", "SUPERSEDED" to "다른 결정으로 대체됨", "WITHDRAWN" to "철회됨"),
    "state" to mapOf("UNMET" to "아직 충족 안 됨", "MET" to "충족됨"),
    "workStatus" to mapOf(
        "PLANNED" to "예정",
        "IN_PROGRESS" to "진행 중",
        "ON_HOLD" to "멈춤",
        "DONE" to "끝남",
        "CANCELLED" to "취소",
    ),
    "acceptance" to mapOf("PENDING" to "담당자 수락 전", "ACCEPTED" to "담당자 수락", "DECLINED" to "담당자 거절"),
    "kind" to EVENT_KIND_LABEL + ENTITY_KIND_LABEL,
)

/** 카드 상태 값 하나를 읽을 수 있는 말로. 모르는 값은 그대로 둔다. */
fun stateLabel(name: String, value: String): String = STATE_LABEL[name]?.get(value) ?: value

/**
 * 묶음 주인 쪽에서 본 줄 이름. 줄은 기대는 쪽(src) 카드에 딸리므로 대부분 "나 → 건너편"이다.
 * 안건–항목, 항목–사건 줄만 아래쪽 카드(dst)에 딸려 "건너편 → 나"로 읽는다.
 */
fun relationLabel(rel: RelType, ownerIsSrc: Boolean): String {
    if (!ownerIsSrc) {
        if (rel == RelType.HAS_ITEM) return "속한 안건"
        if (rel == RelType.HAS_EVENT) return "속한 결정 항목"
    }
    return when (rel) {
        RelType.HAS_ITEM -> "딸린 결정 항목"
        RelType.HAS_EVENT -> "딸린 사건"
        RelType.SUPERSEDES -> "대체하는 결정"
        RelType.WITHDRAWS -> "철회하는 결정"
        RelType.REQUIRES -> "먼저 필요한 것"
        RelType.SATISFIED_BY -> "충족시킨 것"
        RelType.DERIVED_FROM -> "나온 곳"
        RelType.ASSIGNED_TO -> "담당"
        RelType.EVIDENCED_BY -> "근거"
        RelType.SPOKEN_BY -> "말한 사람"
        RelType.MENTIONS -> "언급한 대상"
        RelType.USES -> "참고한 것"
        RelType.CONFLICTS_WITH -> "충돌하는 것"
    }
}

private val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.KOREA)

/** 저장된 시각(ISO)을 날짜만 지역화해 보여 준다. 날짜만 적은 값(UTC 0시)은 어느 시간대에서도 같은 날이다(dayOf). */
fun formatDay(iso: String, zone: ZoneId = ZoneId.systemDefault()): String = dayOf(iso, zone)?.let(dayFormat::format) ?: iso
