package com.contexa.android.ui.documents

import com.contexa.android.api.DocumentInfo
import com.contexa.android.api.DocumentKind
import com.contexa.android.api.DocumentStatus
import com.contexa.android.api.JobStatus
import com.contexa.android.api.VersionStatus
import com.contexa.android.api.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun doc(id: String, latest: VersionStatus?) = DocumentStatus(
    DocumentInfo(id, "회의 $id", DocumentKind.TRANSCRIPT, "ko", Visibility.PROJECT, false, "2026-11-19T10:00:00Z"),
    versionCount = 1,
    latest = latest,
)

class AnalysisWatchTest {

    private val running = version(job(JobStatus.SUCCEEDED), job(JobStatus.RUNNING))
    private val done = version(job(JobStatus.SUCCEEDED), job(JobStatus.SUCCEEDED), 3)
    private val failed = version(job(JobStatus.SUCCEEDED), job(JobStatus.FAILED, "모델 응답 시간 초과"))

    @Test
    fun `지켜보던 문서의 분석이 끝나면 알리고 더 지켜보지 않는다`() {
        val watch = finishedAnalysis(setOf("d1"), listOf(doc("d1", done), doc("d2", running)))

        assertTrue(watch.finished)
        assertEquals(setOf("d2"), watch.watching)
    }

    @Test
    fun `처음부터 끝나 있던 문서나 실패한 문서는 알리지 않는다`() {
        assertFalse(finishedAnalysis(emptySet(), listOf(doc("d1", done))).finished)
        assertFalse(finishedAnalysis(setOf("d1"), listOf(doc("d1", failed))).finished)
    }

    @Test
    fun `방금 올려 아직 목록에 없는 문서는 계속 지켜본다`() {
        val watch = finishedAnalysis(setOf("d-new"), listOf(doc("d1", done)))

        assertFalse(watch.finished)
        assertEquals(setOf("d-new"), watch.watching)
    }

    @Test
    fun `실패 내용은 시도 횟수와 오류를 자세히 보기로만 준다`() {
        assertEquals("1번 시도함\n모델 응답 시간 초과", failureDetail(job(JobStatus.FAILED, "모델 응답 시간 초과")))
        assertEquals("1번 시도함", failureDetail(job(JobStatus.FAILED, " ")))
        assertNull(failureDetail(job(JobStatus.QUEUED)))
    }
}
