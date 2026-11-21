package com.contexa.android.ui.reviews

import com.contexa.android.api.NodeType
import com.contexa.android.api.RelType
import com.contexa.android.api.ReviewStatus
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewFieldsTest {

    // UTC보다 앞선 시간대. 그날 0시가 UTC로는 전날이라, 날짜가 하루 밀리는 실수를 잡는다.
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun midnight(date: String) = LocalDate.parse(date).atStartOfDay(seoul).toInstant().toString()
    private val dec5 = midnight("2026-12-05")

    private val task = view(NodeType.TASK, "t1", "인쇄 발주", ReviewStatus.CANDIDATE, mapOf("dueAt" to dec5))

    @Test
    fun `카드 종류마다 이름과 날짜 칸을 읽는다`() {
        assertEquals(mapOf("title" to "인쇄 발주", "dueAt" to "2026-12-05"), currentValues(task, seoul))

        val event = view(NodeType.DECISION_EVENT, "e1", "대면 개최", ReviewStatus.CANDIDATE, mapOf("kind" to "PROPOSAL"))
        assertEquals(
            mapOf("statement" to "대면 개최", "kind" to "PROPOSAL", "occurredAt" to ""),
            currentValues(event, seoul),
        )
    }

    @Test
    fun `바뀐 것이 없으면 아무것도 보내지 않는다 - 공백만 다른 것도`() {
        assertEquals(
            emptyMap<String, JsonElement>(),
            changedFields(task, mapOf("title" to " 인쇄 발주 ", "dueAt" to "2026-12-05"), seoul),
        )
    }

    @Test
    fun `바뀐 값만 보내고, 날짜는 시각으로, 지운 날짜는 null로 보낸다`() {
        assertEquals(
            mapOf<String, JsonElement>("title" to JsonPrimitive("포스터 인쇄")),
            changedFields(task, mapOf("title" to "포스터 인쇄", "dueAt" to "2026-12-05"), seoul),
        )
        assertEquals(
            mapOf<String, JsonElement>("dueAt" to JsonPrimitive(midnight("2026-12-12"))),
            changedFields(task, mapOf("title" to "인쇄 발주", "dueAt" to "2026-12-12"), seoul),
        )
        assertEquals(
            mapOf<String, JsonElement>("dueAt" to JsonNull),
            changedFields(task, mapOf("title" to "인쇄 발주", "dueAt" to ""), seoul),
        )
    }

    @Test
    fun `제안을 확정으로 잘못 뽑은 것을 바로잡는다`() {
        val event = view(NodeType.DECISION_EVENT, "e1", "대면 개최", ReviewStatus.CANDIDATE, mapOf("kind" to "DECISION"))
        assertEquals(
            mapOf<String, JsonElement>("kind" to JsonPrimitive("PROPOSAL")),
            changedFields(event, mapOf("statement" to "대면 개최", "kind" to "PROPOSAL", "occurredAt" to ""), seoul),
        )
    }

    @Test
    fun `필수인데 빈 칸의 이름을 알려 준다`() {
        val condition = view(NodeType.CONDITION, "c1", "예산 승인")
        assertEquals(listOf("내용"), missingFields(condition, mapOf("description" to "  ")))
        assertEquals(emptyList<String>(), missingFields(condition, mapOf("description" to "예산 승인")))
    }

    @Test
    fun `날짜는 기기 시간대로 오가도 같은 날이다`() {
        assertEquals("2026-02-28", toDateInput(fromDateInput("2026-02-28", seoul), seoul))
        assertEquals("2026-12-04T15:00:00Z", dec5)
        assertEquals("", toDateInput(null, seoul))
        assertEquals("", toDateInput("모름", seoul))
    }

    @Test
    fun `카드 요약과 줄 이름`() {
        assertEquals("예정 · 담당자 수락 전 · 기한 미정", cardSummary(printTask().card!!))
        // 후보 사건에 "유효"라고 쓰면 확정된 것처럼 읽히므로 적지 않는다.
        assertNull(cardSummary(view(NodeType.DECISION_EVENT, "e1", "대면 개최", states = mapOf("standing" to "ACTIVE"))))
        assertEquals("먼저 필요한 것", relationLabel(RelType.REQUIRES, ownerIsSrc = true))
        assertEquals("딸린 사건", relationLabel(RelType.HAS_EVENT, ownerIsSrc = true))
        assertEquals("속한 결정 항목", relationLabel(RelType.HAS_EVENT, ownerIsSrc = false))
        assertEquals("모르는값", stateLabel("workStatus", "모르는값"))
    }
}
