package com.contexa.android.ui.documents

import com.contexa.android.api.Impact
import com.contexa.android.api.JobBrief
import com.contexa.android.api.JobStatus
import com.contexa.android.api.NodeKey
import com.contexa.android.api.NodeType
import com.contexa.android.api.VersionStatus
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CREATED = "2026-11-19T10:00:00Z"

fun job(status: JobStatus, lastError: String? = null) = JobBrief(
    id = UUID.randomUUID().toString(),
    status = status,
    attempts = if (status == JobStatus.QUEUED) 0 else 1,
    lastError = lastError,
    createdAt = CREATED,
)

fun version(parse: JobBrief?, extract: JobBrief? = null, spanCount: Int = 0) = VersionStatus(
    sourceVersionId = UUID.randomUUID().toString(),
    versionNo = 1,
    createdAt = CREATED,
    spanCount = spanCount,
    parse = parse,
    extract = extract,
)

class ProgressTest {

    @Test
    fun `실패로 끝난 작업만 다시 맡길 수 있다`() {
        val failedParse = job(JobStatus.FAILED, "빈 문서")
        assertSame(failedParse, retryableJob(progressOf(version(failedParse))))
        val failedExtract = job(JobStatus.FAILED)
        assertSame(failedExtract, retryableJob(progressOf(version(job(JobStatus.SUCCEEDED), failedExtract, 3))))
        assertEquals(null, retryableJob(progressOf(version(job(JobStatus.CANCELLED)))))
        assertEquals(null, retryableJob(progressOf(version(job(JobStatus.QUEUED)))))
        assertEquals(null, retryableJob(progressOf(version(job(JobStatus.SUCCEEDED), job(JobStatus.SUCCEEDED), 3))))
    }

    @Test
    fun `자르기 다음에 뽑기 순서로 진행한다`() {
        progressOf(version(job(JobStatus.QUEUED))).let {
            assertEquals(Stage.QUEUED, it.stage)
            assertEquals("처리 대기", it.label)
            assertTrue(it.active)
        }
        progressOf(version(job(JobStatus.RUNNING))).let {
            assertEquals(Stage.PARSING, it.stage)
            assertTrue(it.active)
        }
        progressOf(version(job(JobStatus.SUCCEEDED), null, 3)).let {
            assertEquals(Stage.EXTRACTING, it.stage)
            assertEquals("후보 뽑기 대기", it.label)
            assertTrue(it.active)
        }
        progressOf(version(job(JobStatus.SUCCEEDED), job(JobStatus.RUNNING), 3)).let {
            assertEquals(Stage.EXTRACTING, it.stage)
            assertEquals("후보 뽑는 중", it.label)
        }
        progressOf(version(job(JobStatus.SUCCEEDED), job(JobStatus.SUCCEEDED), 3)).let {
            assertEquals(Stage.READY, it.stage)
            assertEquals("처리 완료", it.label)
            assertFalse(it.active)
        }
    }

    @Test
    fun `어느 단계에서 실패했는지와 실패한 작업을 알려 준다`() {
        val failedParse = job(JobStatus.FAILED, "자를 수 없는 문서")
        progressOf(version(failedParse)).let {
            assertEquals(Stage.FAILED, it.stage)
            assertEquals("자르기 실패", it.label)
            assertEquals(Tone.ERROR, it.tone)
            assertFalse(it.active)
            assertSame(failedParse, it.job)
            assertEquals(FailedAt.PARSE, it.failedAt)
        }
        progressOf(version(job(JobStatus.SUCCEEDED), job(JobStatus.FAILED, "rate limit"), 2)).let {
            assertEquals(Stage.FAILED, it.stage)
            assertEquals("후보 뽑기 실패", it.label)
            assertEquals(FailedAt.EXTRACT, it.failedAt)
        }
    }

    @Test
    fun `취소된 작업은 다시 불러오지 않는다`() {
        assertFalse(isActive(version(job(JobStatus.CANCELLED))))
        assertFalse(isActive(version(job(JobStatus.SUCCEEDED), job(JobStatus.CANCELLED), 1)))
    }

    @Test
    fun `작업 없이 발언만 있으면 완료로 본다`() {
        progressOf(version(null, null, 4)).let {
            assertEquals(Stage.READY, it.stage)
            assertFalse(it.active)
        }
        assertEquals(Stage.UNKNOWN, progressOf(version(null, null, 0)).stage)
        assertEquals(Stage.UNKNOWN, progressOf(null).stage)
    }

    @Test
    fun `아무도 가져가지 않은 대기 작업을 알린다`() {
        val created = Instant.parse(CREATED).toEpochMilli()
        val progress = progressOf(version(job(JobStatus.QUEUED)))
        assertFalse(waitingTooLong(progress, created + STALE_QUEUE_MS - 1))
        assertTrue(waitingTooLong(progress, created + STALE_QUEUE_MS + 1))
    }

    @Test
    fun `실행 중이거나 끝난 작업은 오래 기다린 것으로 보지 않는다`() {
        val later = Instant.parse(CREATED).toEpochMilli() + STALE_QUEUE_MS * 10
        assertFalse(waitingTooLong(progressOf(version(job(JobStatus.RUNNING))), later))
        assertFalse(waitingTooLong(progressOf(version(job(JobStatus.SUCCEEDED), job(JobStatus.SUCCEEDED), 1)), later))
    }

    @Test
    fun `언어 이름 - 아는 코드, 섞인 문서, 모르는 코드`() {
        assertEquals("아랍어", languageLabel("ar"))
        assertEquals("여러 언어", languageLabel(null))
        assertEquals("fr", languageLabel("fr"))
    }

    @Test
    fun `정정 알림은 영향받는 카드를 한 번씩만 센다`() {
        fun impact(id: String) = Impact(NodeKey(NodeType.SPAN, "s1"), NodeKey(NodeType.TASK, id), 1)
        assertEquals(
            "발언을 정정했습니다. 이 발언에 기댄 카드 2개는 다시 확인이 필요합니다.",
            revisedMessage(listOf(impact("t1"), impact("t1"), impact("t2"))),
        )
        assertEquals("발언을 정정했습니다.", revisedMessage(emptyList()))
    }

    @Test
    fun `시각은 기기 시간대로 보여 주고, 풀 수 없으면 그대로 둔다`() {
        val seoul = formatInstant("2026-11-19T10:00:00.123456Z", ZoneId.of("Asia/Seoul"))
        assertTrue(seoul, seoul.contains("2026") && seoul.contains("19") && seoul.contains("7:00"))
        assertEquals("어제", formatInstant("어제"))
    }
}
