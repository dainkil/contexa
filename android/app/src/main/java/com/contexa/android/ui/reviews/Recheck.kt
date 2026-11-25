package com.contexa.android.ui.reviews

import com.contexa.android.api.NodeType
import com.contexa.android.api.RecheckCause
import com.contexa.android.api.RecheckReason
import com.contexa.android.ui.documents.formatInstant

/* 검토함 "다시 확인"의 문구. 화면과 떼어 두고 테스트한다. */

private const val MAX_LABEL = 40

fun recheckReasonLabel(reason: RecheckReason): String = when (reason) {
    RecheckReason.REVIEW -> "검토에서 바뀜"
    RecheckReason.NAME -> "확인된 표기가 거절됨"
    RecheckReason.ACCOUNT -> "계정 연결이 바뀜"
    RecheckReason.CONDITION -> "조건 충족이 확인됨"
    RecheckReason.SPAN_REVISION -> "발언이 정정됨"
}

/**
 * 이유 한 줄: 무엇이(바뀐 카드 제목이나 발언), 어떻게 바뀌었나. 내가 볼 수 없는 것이면 종류만 적는다.
 * 시각은 붙이지 않는다(화면에서 따로 적는다).
 */
fun recheckCauseText(cause: RecheckCause): String {
    val what = cause.label?.let { label ->
        val short = if (label.length > MAX_LABEL) label.take(MAX_LABEL).trimEnd() + "…" else label
        "‘$short’"
    } ?: if (cause.changed.type == NodeType.SPAN) "볼 수 없는 발언" else "볼 수 없는 ${typeLabel(cause.changed.type)}"
    return "$what — ${recheckReasonLabel(cause.reason)}"
}

fun recheckCauseLine(cause: RecheckCause): String = "${recheckCauseText(cause)} · ${formatInstant(cause.at)}"
