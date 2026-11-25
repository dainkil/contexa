package com.contexa.android.ui.documents

import com.contexa.android.api.AcceptanceRequest
import com.contexa.android.api.AccountRequest
import com.contexa.android.api.Changed
import com.contexa.android.api.ContextApi
import com.contexa.android.api.ContextPacket
import com.contexa.android.api.Direction
import com.contexa.android.api.DocumentApi
import com.contexa.android.api.DocumentInfo
import com.contexa.android.api.DocumentInput
import com.contexa.android.api.DocumentKind
import com.contexa.android.api.DocumentStatus
import com.contexa.android.api.DocumentView
import com.contexa.android.api.IngestStarted
import com.contexa.android.api.JobBrief
import com.contexa.android.api.JobStatus
import com.contexa.android.api.NETWORK_ERROR_MESSAGE
import com.contexa.android.api.NameCard
import com.contexa.android.api.NameRequest
import com.contexa.android.api.NewVersionRequest
import com.contexa.android.api.NodeType
import com.contexa.android.api.OpenedVersion
import com.contexa.android.api.ReviseSpanRequest
import com.contexa.android.api.Scope
import com.contexa.android.api.ScopeView
import com.contexa.android.api.TaskStatusRequest
import com.contexa.android.api.VersionStatus
import com.contexa.android.api.Visibility
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private val INFO = DocumentInfo("d1", "3차 회의", DocumentKind.TRANSCRIPT, "ko", Visibility.PROJECT, false, "2026-11-19T10:00:00Z")

/** 정해 둔 응답을 차례로 돌려주는 가짜. 다 쓰면 마지막 응답을 되풀이한다. */
private class FakeDocumentApi(private val lists: List<() -> List<DocumentStatus>> = emptyList()) : DocumentApi {
    var listCalls = 0
    val opened = mutableListOf<Pair<Int?, String?>>()
    var views: List<DocumentView> = emptyList()
    val retried = mutableListOf<String>()

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

    override suspend fun retryJob(projectId: String, jobId: String): JobBrief {
        retried += jobId
        return JobBrief(jobId, JobStatus.QUEUED, 0, "모델 응답 시간 초과", "2026-11-19T10:00:00Z")
    }
}

/** 할 일 읽기만 받는 가짜. 뷰어는 문서 처리가 끝난 뒤에 할 일을 읽는다. */
private class FakeTasksApi : ContextApi {
    var taskCalls = 0

    override suspend fun tasks(projectId: String, mine: Boolean): ContextPacket {
        taskCalls++
        return ContextPacket("2026-11-21T00:00:00Z", Scope(projectId, ScopeView.TASKS))
    }

    override suspend fun state(projectId: String, topicId: String?): ContextPacket = error("쓰지 않는다")
    override suspend fun history(projectId: String, decisionItemId: String): ContextPacket = error("쓰지 않는다")
    override suspend fun trace(projectId: String, type: NodeType, id: String, direction: Direction, depth: Int): ContextPacket =
        error("쓰지 않는다")
    override suspend fun taskStatus(projectId: String, taskId: String, request: TaskStatusRequest): Changed = error("쓰지 않는다")
    override suspend fun acceptance(projectId: String, taskId: String, request: AcceptanceRequest): Changed = error("쓰지 않는다")
    override suspend fun linkAccount(projectId: String, entityId: String, request: AccountRequest): Changed = error("쓰지 않는다")
    override suspend fun resolveEntity(projectId: String, surface: String, lang: String?): ContextPacket = error("쓰지 않는다")
    override suspend fun nameCard(projectId: String, entityId: String): NameCard = error("쓰지 않는다")
    override suspend fun name(projectId: String, entityId: String, request: NameRequest): Changed = error("쓰지 않는다")
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

    // 다시 맡기기는 viewModelScope(Main)에서 보낸다. runTest는 Main의 시계를 함께 쓴다.
    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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
        val tasks = FakeTasksApi()
        val viewModel = DocumentViewerViewModel(api, tasks, "p1", "d1", spanId = "s9")

        backgroundScope.launch { viewModel.watch() }
        settle()
        // 처리 중이라 한 번 더 불러왔고, 끝난 뒤에는 멈췄다. 할 일은 처리가 끝난 뒤에 한 번 읽는다.
        assertEquals(listOf<Pair<Int?, String?>>(null to "s9", null to "s9"), api.opened)
        assertEquals(1, tasks.taskCalls)
        assertEquals(emptyList<Any>(), viewModel.state.value.tasks)

        viewModel.openVersion(2)
        settle()
        assertEquals(2 to null, api.opened.last())
        assertNull(viewModel.state.value.highlightId)
        assertEquals(3, api.opened.size)
    }

    @Test
    fun `뷰어 - 실패한 작업을 다시 맡기면 다시 불러오며 처리가 끝날 때까지 지켜본다`() = runTest {
        val failed = version(job(JobStatus.SUCCEEDED), job(JobStatus.FAILED, "모델 응답 시간 초과"), 3)
        val api = FakeDocumentApi().apply { views = listOf(view(failed), view(running), view(done)) }
        val viewModel = DocumentViewerViewModel(api, FakeTasksApi(), "p1", "d1", spanId = null)

        backgroundScope.launch { viewModel.watch() }
        settle()
        assertEquals(1, api.opened.size)

        viewModel.retry()
        settle()

        assertEquals(listOf(failed.extract!!.id), api.retried.toList())
        assertEquals(3, api.opened.size)
        assertEquals(done, viewModel.state.value.openedVersion)
        assertNull(viewModel.state.value.retryError)

        // 실패한 작업이 없으면 아무것도 보내지 않는다.
        viewModel.retry()
        settle()
        assertEquals(1, api.retried.size)
    }
}
