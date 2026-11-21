package com.contexa.android.ui.documents

import com.contexa.android.api.Changed
import com.contexa.android.api.DocumentApi
import com.contexa.android.api.DocumentInfo
import com.contexa.android.api.DocumentInput
import com.contexa.android.api.DocumentKind
import com.contexa.android.api.DocumentStatus
import com.contexa.android.api.DocumentView
import com.contexa.android.api.IngestStarted
import com.contexa.android.api.JobStatus
import com.contexa.android.api.NETWORK_ERROR_MESSAGE
import com.contexa.android.api.NewVersionRequest
import com.contexa.android.api.OpenedVersion
import com.contexa.android.api.ReviseSpanRequest
import com.contexa.android.api.VersionStatus
import com.contexa.android.api.Visibility
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

private val INFO = DocumentInfo("d1", "3차 회의", DocumentKind.TRANSCRIPT, "ko", Visibility.PROJECT, false, "2026-11-19T10:00:00Z")

/** 정해 둔 응답을 차례로 돌려주는 가짜. 다 쓰면 마지막 응답을 되풀이한다. */
private class FakeDocumentApi(private val lists: List<() -> List<DocumentStatus>> = emptyList()) : DocumentApi {
    var listCalls = 0
    val opened = mutableListOf<Pair<Int?, String?>>()
    var views: List<DocumentView> = emptyList()

    override suspend fun list(projectId: String): List<DocumentStatus> =
        lists[minOf(listCalls++, lists.lastIndex)]()

    override suspend fun open(projectId: String, documentId: String, version: Int?, span: String?): DocumentView {
        opened += version to span
        return views[minOf(opened.size - 1, views.lastIndex)]
    }

    override suspend fun upload(projectId: String, input: DocumentInput): IngestStarted = error("쓰지 않는다")
    override suspend fun uploadVersion(projectId: String, documentId: String, request: NewVersionRequest): IngestStarted =
        error("쓰지 않는다")
    override suspend fun reviseSpan(projectId: String, spanId: String, request: ReviseSpanRequest): Changed =
        error("쓰지 않는다")
}

private fun row(latest: VersionStatus?) = DocumentStatus(INFO, versionCount = 1, latest = latest)

private fun view(latest: VersionStatus) =
    DocumentView(INFO, listOf(latest), OpenedVersion(latest.sourceVersionId, latest.versionNo))

/** watch()는 backgroundScope에서 돌아 advanceUntilIdle이 기다려 주지 않는다. 폴링 몇 번 치 시간을 직접 흘린다. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() {
    advanceTimeBy(POLL_MS * 10)
    runCurrent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentsViewModelTest {

    private val running = version(job(JobStatus.RUNNING))
    private val done = version(job(JobStatus.SUCCEEDED), job(JobStatus.SUCCEEDED), 3)

    @Test
    fun `처리 중인 문서가 있는 동안만 다시 불러온다`() = runTest {
        val api = FakeDocumentApi(listOf({ listOf(row(running)) }, { listOf(row(running)) }, { listOf(row(done)) }))
        val viewModel = DocumentsViewModel(api, "p1")

        backgroundScope.launch { viewModel.watch() }
        settle()

        assertEquals(3, api.listCalls)
        assertFalse(viewModel.state.value.loading)
        assertEquals(listOf(row(done)), viewModel.state.value.documents)
    }

    @Test
    fun `실패하면 멈추고, 다시 불러오기로 이어 간다`() = runTest {
        val api = FakeDocumentApi(listOf({ throw IOException("connection refused") }, { listOf(row(done)) }))
        val viewModel = DocumentsViewModel(api, "p1")

        backgroundScope.launch { viewModel.watch() }
        settle()
        assertEquals(1, api.listCalls)
        assertEquals(NETWORK_ERROR_MESSAGE, viewModel.state.value.loadError)

        viewModel.reload()
        settle()
        assertEquals(2, api.listCalls)
        assertNull(viewModel.state.value.loadError)
    }

    @Test
    fun `뷰어 - 근거 발언으로 열고, 판을 고르면 그 판만 요청한다`() = runTest {
        val api = FakeDocumentApi().apply { views = listOf(view(running), view(done)) }
        val viewModel = DocumentViewerViewModel(api, "p1", "d1", spanId = "s9")

        backgroundScope.launch { viewModel.watch() }
        settle()
        // 처리 중이라 한 번 더 불러왔고, 끝난 뒤에는 멈췄다.
        assertEquals(listOf<Pair<Int?, String?>>(null to "s9", null to "s9"), api.opened)

        viewModel.openVersion(2)
        settle()
        assertEquals(2 to null, api.opened.last())
        assertNull(viewModel.state.value.highlightId)
        assertEquals(3, api.opened.size)
    }
}
