package com.contexa.android.ui.reviews

import com.contexa.android.api.Impact
import com.contexa.android.api.NodeKey
import com.contexa.android.api.NodeType
import com.contexa.android.api.RelType
import com.contexa.android.api.ReviewState
import com.contexa.android.api.ReviewStatus
import com.contexa.android.api.Verdict
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewActionTest {

    @Test
    fun `카드 판단만 보내고 줄은 server가 따라 처리하게 둔다`() {
        val action = buildAction(printTask(), Verdict.CONFIRM, emptyMap())!!

        assertEquals(Verdict.CONFIRM, action.card)
        assertEquals(3, action.expectedVersion)
        assertNull(action.edges)
        assertNull(action.corrections)
    }

    @Test
    fun `따로 판단한 줄을 덧붙인다`() {
        val action = buildAction(printTask(), Verdict.CONFIRM, mapOf("e-hidden" to Verdict.REJECT))

        assertEquals(mapOf("e-hidden" to Verdict.REJECT), action?.edges)
    }

    @Test
    fun `판단 없이 고친 내용만 저장한다`() {
        val action = buildAction(printTask(), null, emptyMap(), mapOf("title" to JsonPrimitive("포스터 인쇄")))!!

        assertEquals(mapOf("title" to JsonPrimitive("포스터 인쇄")), action.corrections)
        assertNull(action.card)
    }

    @Test
    fun `보낼 것이 없으면 null`() {
        assertNull(buildAction(printTask(), null, emptyMap()))
    }

    @Test
    fun `카드가 없는 묶음은 판단이 적용되는 줄마다 적어 보낸다`() {
        val span = ref(NodeType.SPAN, "s1")
        val item = printTask(
            owner = span,
            card = null,
            edges = listOf(
                edge("a", RelType.SPOKEN_BY, span, ref(NodeType.ENTITY, "p1")),
                edge("b", RelType.MENTIONS, span, ref(NodeType.ENTITY, "p2"), ReviewStatus.CONFIRMED),
                edge("c", RelType.MENTIONS, span, ref(NodeType.ENTITY, "p3"), ReviewStatus.HELD),
            ),
        )

        assertEquals(mapOf("a" to Verdict.CONFIRM, "c" to Verdict.CONFIRM), buildAction(item, Verdict.CONFIRM, emptyMap())?.edges)
        assertEquals(mapOf("b" to Verdict.RETRACT), buildAction(item, Verdict.RETRACT, emptyMap())?.edges)
        assertEquals(
            mapOf("a" to Verdict.CONFIRM, "c" to Verdict.REJECT),
            buildAction(item, Verdict.REJECT, mapOf("a" to Verdict.CONFIRM))?.edges,
        )
        assertNull(buildAction(item, Verdict.CONFIRM, emptyMap())?.card)
    }

    @Test
    fun `같은 요청은 같은 키가 되어 두 번 눌러도 한 번만 처리된다`() {
        fun key(verdict: Verdict, version: Int = 3) =
            buildAction(printTask(version = version), verdict, emptyMap())?.idempotencyKey

        assertEquals(key(Verdict.CONFIRM), key(Verdict.CONFIRM))
        assertNotEquals(key(Verdict.CONFIRM), key(Verdict.HOLD))
        assertNotEquals(key(Verdict.CONFIRM), key(Verdict.CONFIRM, 4))
        assertEquals(true, key(Verdict.CONFIRM)?.startsWith("r1:3:"))
    }

    @Test
    fun `묶음 버튼은 카드 상태를 따른다`() {
        fun withCard(status: ReviewStatus, state: ReviewState = ReviewState.OPEN) =
            printTask(card = view(NodeType.TASK, "t1", "인쇄 발주", status), state = state)

        assertEquals(listOf(Verdict.CONFIRM, Verdict.HOLD, Verdict.REJECT), bundleVerdicts(withCard(ReviewStatus.CANDIDATE)))
        assertEquals(listOf(Verdict.CONFIRM, Verdict.REJECT), bundleVerdicts(withCard(ReviewStatus.HELD)))
        assertEquals(emptyList<Verdict>(), bundleVerdicts(withCard(ReviewStatus.REJECTED)))
        // 확인된 카드의 묶음이 열려 있으면 새로 들어온 줄을 확인할 수 있다.
        assertEquals(listOf(Verdict.CONFIRM, Verdict.RETRACT), bundleVerdicts(withCard(ReviewStatus.CONFIRMED)))
        assertEquals(listOf(Verdict.RETRACT), bundleVerdicts(withCard(ReviewStatus.CONFIRMED, ReviewState.DONE)))
    }

    @Test
    fun `카드가 없으면 줄 상태를 보고 버튼을 정한다`() {
        val span = ref(NodeType.SPAN, "s1")
        val item = printTask(
            owner = span,
            card = null,
            edges = listOf(edge("b", RelType.MENTIONS, span, ref(NodeType.ENTITY, "p2"), ReviewStatus.CONFIRMED)),
        )

        assertEquals(listOf(Verdict.RETRACT), bundleVerdicts(item))
    }

    @Test
    fun `줄에는 server가 허용하는 판단만 내놓는다`() {
        val t = ref(NodeType.TASK, "t1")
        val c = ref(NodeType.CONDITION, "c1")
        fun choices(status: ReviewStatus) = edgeChoices(edge("e", RelType.REQUIRES, t, c, status))

        assertEquals(listOf(null, Verdict.CONFIRM, Verdict.HOLD, Verdict.REJECT), choices(ReviewStatus.CANDIDATE))
        assertEquals(listOf(null, Verdict.CONFIRM, Verdict.REJECT), choices(ReviewStatus.HELD))
        assertEquals(listOf(null, Verdict.RETRACT), choices(ReviewStatus.CONFIRMED))
        assertFalse(canApply(Verdict.CONFIRM, ReviewStatus.REJECTED))
    }

    @Test
    fun `알림은 다시 확인할 카드 수를 알려 준다`() {
        fun impact(affected: String) = Impact(NodeKey(NodeType.TASK, "t1"), NodeKey(NodeType.TASK, affected), 1)

        assertEquals(
            "확인했습니다. 이 변경에 기대는 카드 2개는 다시 확인이 필요합니다.",
            resultMessage(Verdict.CONFIRM, false, listOf(impact("a"), impact("a"), impact("b"))),
        )
        assertEquals("고쳐서 확인했습니다.", resultMessage(Verdict.CONFIRM, true, emptyList()))
        assertEquals("고친 내용을 저장했습니다.", resultMessage(null, true, emptyList()))
        assertEquals("줄 판단을 반영했습니다.", resultMessage(null, false, emptyList()))
        assertEquals("거절했습니다.", resultMessage(Verdict.REJECT, false, emptyList()))
    }
}
