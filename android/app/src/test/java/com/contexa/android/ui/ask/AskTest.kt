package com.contexa.android.ui.ask

import com.contexa.android.api.ArtifactStatus
import com.contexa.android.api.ArtifactSummary
import com.contexa.android.api.ArtifactView
import com.contexa.android.api.AskRequest
import com.contexa.android.api.GenKind
import com.contexa.android.api.GenerationApi
import com.contexa.android.api.GenerationRequested
import com.contexa.android.api.Source
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

@OptIn(ExperimentalCoroutinesApi::class)
class AskTest {

    private val api = FakeGenerationApi()

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
    fun `질문을 다듬어 언어를 정하지 않고 보내고, 대화 끝에 붙인다`() = runTest {
        val viewModel = AskViewModel(api, "p1")

        viewModel.editInput("  When do we print?  ")
        viewModel.send()
        viewModel.send() // 두 번 눌러도 한 번만 보낸다
        advanceUntilIdle()

        assertEquals(listOf(AskRequest(question = "When do we print?")), api.asked.toList())
        assertEquals("", viewModel.state.value.input)
        assertEquals(ChatTurn("a1", "When do we print?", null), viewModel.state.value.turns.last())
    }

    @Test
    fun `브리핑은 비워 둬도 보내고, 비우면 정해 둔 요청을 보낸다`() {
        assertTrue(canSend("", briefing = true))
        assertFalse(canSend("", briefing = false))
        assertFalse(canSend("가".repeat(MAX_QUESTION + 1), briefing = true))
        assertEquals(AskRequest(kind = GenKind.BRIEFING, question = DEFAULT_BRIEFING), askRequest("  ", briefing = true))
        assertEquals(AskRequest(kind = GenKind.BRIEFING, question = "예산"), askRequest(" 예산 ", briefing = true))
        assertEquals(AskRequest(question = "예산"), askRequest(" 예산 ", briefing = false))
    }

    @Test
    fun `브리핑을 켜고 보내면 BRIEFING으로 보내고, 보낸 뒤에는 질문으로 돌아간다`() = runTest {
        val viewModel = AskViewModel(api, "p1")

        viewModel.toggleBriefing()
        assertTrue(viewModel.state.value.briefing)
        viewModel.send()
        advanceUntilIdle()

        assertEquals(listOf(AskRequest(kind = GenKind.BRIEFING, question = DEFAULT_BRIEFING)), api.asked.toList())
        assertFalse(viewModel.state.value.briefing)
        assertEquals(GenKind.BRIEFING, viewModel.state.value.turns.last().kind)
    }

    @Test
    fun `답을 다 쓸 때까지만 다시 열어 본다`() = runTest {
        api.views = listOf(artifact(ArtifactStatus.PENDING), artifact(ArtifactStatus.PENDING), artifact(ArtifactStatus.PUBLISHED))
        val viewModel = AskViewModel(api, "p1")

        backgroundScope.launch { viewModel.watch() }
        advanceTimeBy(POLL_MS * 10)
        runCurrent()

        assertEquals(3, api.gets)
        assertEquals(ArtifactStatus.PUBLISHED, viewModel.state.value.turns.single().view?.status)
        assertFalse(viewModel.state.value.gaveUp)

        // 다 쓴 답은 다시 불러와도 또 열지 않는다.
        viewModel.reload()
        runCurrent()
        assertEquals(3, api.gets)
    }

    @Test
    fun `실패한 답은 다시 열어 보지 않는다`() = runTest {
        api.views = listOf(artifact(ArtifactStatus.FAILED, failure = "근거를 찾지 못했다"))
        val viewModel = AskViewModel(api, "p1")

        backgroundScope.launch { viewModel.watch() }
        advanceTimeBy(POLL_MS * 10)
        runCurrent()

        assertEquals(1, api.gets)
        assertEquals("근거를 찾지 못했다", viewModel.state.value.turns.single().view?.failure)
    }

    @Test
    fun `3분이 지나도 답이 없으면 멈추고, 다시 확인하면 이어 간다`() = runTest {
        api.views = listOf(artifact(ArtifactStatus.PENDING))
        val viewModel = AskViewModel(api, "p1")

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
