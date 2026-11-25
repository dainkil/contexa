package com.contexa.android.ui.reviews

import com.contexa.android.api.NodeType
import com.contexa.android.api.RelType
import com.contexa.android.api.ReviewStatus
import com.contexa.android.api.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeChoiceTest {

    private val task = ref(NodeType.TASK, "t1")

    @Test
    fun `판단할 수 있는 줄만 줄마다 고르기에 나온다`() {
        val item = printTask(
            edges = listOf(
                edge("e-ev", RelType.EVIDENCED_BY, task, ref(NodeType.SPAN, "s22")),
                edge("e-old", RelType.REQUIRES, task, ref(NodeType.CONDITION, "c0"), ReviewStatus.REJECTED),
            ),
        )

        assertEquals(listOf("e-ev"), decidableEdges(item).map { it.id })
    }

    @Test
    fun `줄 선택지 이름`() {
        assertEquals("카드를 따름", followLabel(printTask()))
        assertEquals("버튼대로", followLabel(printTask(card = null)))
        assertEquals("카드를 따름", edgeChoiceLabel(null, "카드를 따름"))
        assertEquals("이 줄만 거절", edgeChoiceLabel(Verdict.REJECT, "카드를 따름"))
    }

    @Test
    fun `확인하면 고르지 않은 후보 줄도 함께 확인된다는 것을 알린다`() {
        assertTrue(confirmSweepsEdges(printTask(), emptyMap()))
        assertTrue(confirmSweepsEdges(printTask(), mapOf("e-hidden" to Verdict.REJECT)))

        val allChosen = mapOf("e-ev" to Verdict.CONFIRM, "e-req" to Verdict.CONFIRM, "e-hidden" to Verdict.REJECT)
        assertFalse(confirmSweepsEdges(printTask(), allChosen))

        // 거절된 카드에는 확인 버튼이 없다
        val rejected = printTask(card = view(NodeType.TASK, "t1", "인쇄 발주", ReviewStatus.REJECTED))
        assertFalse(confirmSweepsEdges(rejected, emptyMap()))
    }

    @Test
    fun `후보나 확인된 카드만 고칠 수 있다`() {
        assertTrue(canCorrect(printTask()))
        assertTrue(canCorrect(printTask(card = view(NodeType.TASK, "t1", "인쇄 발주", ReviewStatus.CONFIRMED))))
        assertFalse(canCorrect(printTask(card = view(NodeType.TASK, "t1", "인쇄 발주", ReviewStatus.RETRACTED))))
        assertFalse(canCorrect(printTask(card = null)))
    }

    @Test
    fun `줄이 가리키는 곳을 이름으로 보여 주고, 볼 수 없으면 그렇다고 적는다`() {
        val item = printTask()
        val byId = item.edges.associateBy { it.id }

        assertEquals("근거" to "3차 회의 22번 발언", edgeTarget(byId.getValue("e-ev"), item, mapOf("doc-1" to "3차 회의")))
        assertEquals("근거" to "문서 22번 발언", edgeTarget(byId.getValue("e-ev"), item, emptyMap()))
        assertEquals(
            "먼저 필요한 것" to "${typeLabel(NodeType.CONDITION)} “예산 승인”",
            edgeTarget(byId.getValue("e-req"), item, emptyMap()),
        )
        assertEquals(
            "먼저 필요한 것" to "볼 수 없는 ${typeLabel(NodeType.CONDITION)}",
            edgeTarget(byId.getValue("e-hidden"), item, emptyMap()),
        )
    }
}
