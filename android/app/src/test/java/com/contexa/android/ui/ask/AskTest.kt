package com.contexa.android.ui.ask

import com.contexa.android.api.ArtifactStatus
import com.contexa.android.api.ArtifactSummary
import com.contexa.android.api.ArtifactView
import com.contexa.android.api.AskRequest
import com.contexa.android.api.Changed
import com.contexa.android.api.DocumentApi
import com.contexa.android.api.DocumentInfo
import com.contexa.android.api.DocumentInput
import com.contexa.android.api.DocumentKind
import com.contexa.android.api.DocumentStatus
import com.contexa.android.api.DocumentView
import com.contexa.android.api.GenKind
import com.contexa.android.api.GenerationApi
import com.contexa.android.api.GenerationRequested
import com.contexa.android.api.IngestStarted
import com.contexa.android.api.NewVersionRequest
import com.contexa.android.api.NodeRef
import com.contexa.android.api.NodeType
import com.contexa.android.api.ReviseSpanRequest
import com.contexa.android.api.Source
import com.contexa.android.api.Visibility
import com.contexa.android.ui.documents.POLL_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun artifact(status: ArtifactStatus, citations: List<Source> = emptyList(), failure: String? = null) = ArtifactView(
    id = "a1",
    kind = GenKind.ANSWER,
    question = "인쇄는 언제 하나요?",
    lang = "ko",
    status = status,
    content = if (status == ArtifactStatus.PUBLISHED) "예산 승인 뒤입니다 [1]." else null,
    citations = citations,
    failure = failure,
    createdAt = "2026-11-19T10:00:00Z",
)

private class FakeGenerationApi : GenerationApi {
    val asked = mutableListOf<AskRequest>()
    var gets = 0

    /** 차례로 돌려줄 글. 다 쓰면 마지막 것을 되풀이한다. */
    var views: List<ArtifactView> = emptyList()

    override suspend fun ask(projectId: String, request: AskRequest): GenerationRequested {
        asked += request
        return GenerationRequested("a1", "j1")
    }

    override suspend fun list(projectId: String): List<ArtifactSummary> =
        listOf(ArtifactSummary("a1", GenKind.ANSWER, "인쇄는 언제 하나요?", "ko", published = true, createdAt = "2026-11-19T10:00:00Z"))

    override suspend fun get(projectId: String, artifactId: String): ArtifactView = views[minOf(gets++, views.lastIndex)]
}

private class TitlesOnly : DocumentApi {
    var calls = 0
    override suspend fun list(projectId: String): List<DocumentStatus> {
        calls++
        return listOf(
            DocumentStatus(
                DocumentInfo("d1", "3차 회의", DocumentKind.TRANSCRIPT, "ko", Visibility.PROJECT, false, "2026-11-19T10:00:00Z"),
                versionCount = 1,
            ),
        )
    }

    override suspend fun upload(projectId: String, input: DocumentInput): IngestStarted = error("쓰지 않는다")
    override suspend fun uploadVersion(projectId: String, documentId: String, request: NewVersionRequest): IngestStarted =
        error("쓰지 않는다")
    override suspend fun open(projectId: String, documentId: String, version: Int?, span: String?): DocumentView =
        error("쓰지 않는다")
    override suspend fun reviseSpan(projectId: String, spanId: String, request: ReviseSpanRequest): Changed =
        error("쓰지 않는다")
}

@OptIn(ExperimentalCoroutinesApi::class)
class AskTest {

    private val api = FakeGenerationApi()
    private val documents = TitlesOnly()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `질문은 비어 있으면 못 보내고, 2000자를 넘으면 이유를 알려 준다`() {
        assertFalse(canAsk("  \n"))
        assertNull(questionError(""))
        assertTrue(canAsk("인쇄는 언제 하나요?"))
        // 앞뒤 공백은 세지 않는다.
        assertTrue(canAsk(" " + "가".repeat(MAX_QUESTION) + " "))
        assertFalse(canAsk("가".repeat(MAX_QUESTION + 1)))
        assertEquals("질문은 2000자까지 쓸 수 있습니다.", questionError("가".repeat(MAX_QUESTION + 1)))
    }

    @Test
    fun `질문을 다듬어 고른 언어와 함께 보내고, 새 글의 id를 알려 준다`() = runTest {
        val viewModel = AskViewModel(api, "p1")
        var opened: String? = null

        viewModel.editQuestion("  인쇄는 언제 하나요?  ")
        viewModel.pickLang("ar")
        viewModel.ask { opened = it }
        viewModel.ask { opened = "두 번 눌러도 한 번만 보낸다" }
        advanceUntilIdle()

        assertEquals(listOf(AskRequest(question = "인쇄는 언제 하나요?", lang = "ar")), api.asked.toList())
        assertEquals("a1", opened)
        assertEquals("", viewModel.state.value.question)
    }

    @Test
    fun `답을 다 쓸 때까지만 다시 열어 보고, 인용이 있으면 문서 이름을 붙인다`() = runTest {
        val source = Source(NodeRef(NodeType.SPAN, "s1", 1), "d1", 1, "민지", "보류입니다.")
        api.views = listOf(
            artifact(ArtifactStatus.PENDING),
            artifact(ArtifactStatus.PENDING),
            artifact(ArtifactStatus.PUBLISHED, listOf(source)),
        )
        val viewModel = AnswerViewModel(api, documents, "p1", "a1")

        backgroundScope.launch { viewModel.watch() }
        advanceTimeBy(POLL_MS * 10)
        runCurrent()

        assertEquals(3, api.gets)
        assertEquals(ArtifactStatus.PUBLISHED, viewModel.state.value.view?.status)
        assertEquals(mapOf("d1" to "3차 회의"), viewModel.state.value.documentTitles)
        assertFalse(viewModel.state.value.gaveUp)
    }

    @Test
    fun `실패한 글은 다시 열어 보지 않는다`() = runTest {
        api.views = listOf(artifact(ArtifactStatus.FAILED, failure = "근거를 찾지 못했다"))
        val viewModel = AnswerViewModel(api, documents, "p1", "a1")

        backgroundScope.launch { viewModel.watch() }
        advanceTimeBy(POLL_MS * 10)
        runCurrent()

        assertEquals(1, api.gets)
        assertEquals("근거를 찾지 못했다", viewModel.state.value.view?.failure)
        assertEquals(0, documents.calls)
    }

    @Test
    fun `3분이 지나도 답이 없으면 멈추고, 다시 확인하면 이어 간다`() = runTest {
        api.views = listOf(artifact(ArtifactStatus.PENDING))
        val viewModel = AnswerViewModel(api, documents, "p1", "a1")

        backgroundScope.launch { viewModel.watch() }
        advanceTimeBy(POLL_MS * (MAX_POLLS + 20))
        runCurrent()

        assertTrue(viewModel.state.value.gaveUp)
        val stoppedAt = api.gets
        assertEquals(MAX_POLLS + 1, stoppedAt)

        viewModel.reload()
        runCurrent()
        assertFalse(viewModel.state.value.gaveUp)
        assertEquals(stoppedAt + 1, api.gets)
    }
}
