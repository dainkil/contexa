package com.contexa.android.ui.reviews

import com.contexa.android.api.NodeKey
import com.contexa.android.api.NodeType
import com.contexa.android.api.RecheckCause
import com.contexa.android.api.RecheckReason
import org.junit.Assert.assertEquals
import org.junit.Test

class RecheckTextTest {

    private fun cause(type: NodeType, label: String?, reason: RecheckReason) =
        RecheckCause(NodeKey(type, "x1"), label, reason, 1, "u1", "2026-11-24T01:00:00Z")

    @Test
    fun `이유 한 줄 - 바뀐 것의 내용과 어떻게 바뀌었나`() {
        assertEquals(
            "‘예산 승인(이사회)’ — 검토에서 바뀜",
            recheckCauseText(cause(NodeType.CONDITION, "예산 승인(이사회)", RecheckReason.REVIEW)),
        )
        assertEquals(
            "‘" + "가".repeat(40) + "…’ — 발언이 정정됨",
            recheckCauseText(cause(NodeType.SPAN, "가".repeat(60), RecheckReason.SPAN_REVISION)),
        )
    }

    @Test
    fun `볼 수 없는 것이면 종류만 적는다`() {
        assertEquals("볼 수 없는 발언 — 발언이 정정됨", recheckCauseText(cause(NodeType.SPAN, null, RecheckReason.SPAN_REVISION)))
        assertEquals("볼 수 없는 조건 — 검토에서 바뀜", recheckCauseText(cause(NodeType.CONDITION, null, RecheckReason.REVIEW)))
    }
}
