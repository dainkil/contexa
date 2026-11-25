package com.contexa.android.ui.reviews

import com.contexa.android.api.NodeType
import com.contexa.android.api.RelType
import com.contexa.android.api.ReviewStatus
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewFieldsTest {

    // UTC보다 앞선 시간대와 뒤진 시간대. 날짜가 하루 밀리는 실수를 잡는다.
    private val seoul = ZoneId.of("Asia/Seoul")
    private val newYork = ZoneId.of("America/New_York")
    private fun midnight(date: String) = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toString()
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
    fun `날짜만 고른 값은 UTC 0시로 저장하고 어느 시간대에서나 같은 날로 읽는다`() {
        assertEquals("2026-12-05T00:00:00Z", fromDateInput("2026-12-05"))
        for (zone in listOf(seoul, newYork, ZoneOffset.UTC)) {
            assertEquals("2026-02-28", toDateInput(fromDateInput("2026-02-28"), zone))
            assertEquals("2026. 12. 5.", formatDay(dec5, zone))
        }
        assertEquals("", toDateInput(null, seoul))
        assertEquals("", toDateInput("모름", seoul))
        assertEquals("모름", formatDay("모름", seoul))
    }

    @Test
    fun `정확한 시각은 이 기기 시간대의 날짜로 읽는다`() {
        // 서울 12월 5일 오전 8시 = UTC 12월 4일 23시
        val morning = "2026-12-05T08:00:00+09:00"
        assertEquals(LocalDate.parse("2026-12-05"), dayOf(morning, seoul))
        assertEquals(LocalDate.parse("2026-12-04"), dayOf(morning, newYork))
        // 예전에 서울 자정으로 저장한 값도 서울 기기에서는 그날이다
        assertEquals("2026-12-05", toDateInput("2026-12-04T15:00:00Z", seoul))
    }

    @Test
    fun `카드 내용과 줄 이름`() {
        // 새 할 일은 늘 "수락 전"이라 적지 않고, 담당자가 정했을 때만 적는다
        assertEquals(listOf("상태" to "예정", "기한" to "미정"), cardFacts(printTask().card!!))
        assertEquals(
            listOf("기한" to "2026. 12. 5.", "수락" to "담당자 수락"),
            cardFacts(view(NodeType.TASK, "t2", "인쇄 발주", states = mapOf("dueAt" to dec5, "acceptance" to "ACCEPTED"))),
        )
        // 후보 사건에 "유효"라고 쓰면 확정된 것처럼 읽히므로 적지 않는다.
        assertEquals(
            emptyList<Pair<String, String>>(),
            cardFacts(view(NodeType.DECISION_EVENT, "e1", "대면 개최", states = mapOf("standing" to "ACTIVE"))),
        )
        // 근거 줄은 원문으로 보여 주므로 빼고, 먼저 필요한 것은 선행요건으로 모은다. 볼 수 없는 카드는 그렇다고 적는다.
        val links = cardLinks(printTask())
        assertEquals(listOf("예산 승인", "볼 수 없는 조건"), links.prerequisites)
        assertEquals(emptyList<Pair<String, String>>(), links.facts)
        assertEquals("먼저 필요한 것", relationLabel(RelType.REQUIRES, ownerIsSrc = true))
        assertEquals("딸린 사건", relationLabel(RelType.HAS_EVENT, ownerIsSrc = true))
        assertEquals("속한 결정 항목", relationLabel(RelType.HAS_EVENT, ownerIsSrc = false))
        assertEquals("모르는값", stateLabel("workStatus", "모르는값"))
    }
}
