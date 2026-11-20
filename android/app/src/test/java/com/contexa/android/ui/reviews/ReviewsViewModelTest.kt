package com.contexa.android.ui.reviews

import com.contexa.android.api.Changed
import com.contexa.android.api.DocumentApi
import com.contexa.android.api.DocumentInput
import com.contexa.android.api.DocumentStatus
import com.contexa.android.api.DocumentView
import com.contexa.android.api.IngestStarted
import com.contexa.android.api.NewVersionRequest
import com.contexa.android.api.NodeType
import com.contexa.android.api.ReviewActionRequest
import com.contexa.android.api.ReviewApi
import com.contexa.android.api.ReviewItem
import com.contexa.android.api.ReviewPage
import com.contexa.android.api.ReviewResult
import com.contexa.android.api.ReviewState
import com.contexa.android.api.ReviewStatus
import com.contexa.android.api.ReviseSpanRequest
import com.contexa.android.api.STALE_MESSAGE
import com.contexa.android.api.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

private class FakeReviewApi : ReviewApi {
    /** after 값 → 그 쪽 */
    var pages: Map<String?, ReviewPage> = emptyMap()
    val listed = mutableListOf<Triple<ReviewState, NodeType?, String?>>()
    val actions = mutableListOf<ReviewActionRequest>()
    var onAct: (ReviewActionRequest) -> ReviewResult = { error("정하지 않았다") }

    override suspend fun list(projectId: String, state: ReviewState, ownerType: NodeType?, limit: Int?, after: String?): ReviewPage {
        listed += Triple(state, ownerType, after)
        return pages.getValue(after)
    }

    override suspend fun act(projectId: String, reviewId: String, action: ReviewActionRequest): ReviewResult {
        actions += action
        return onAct(action)
    }
}

private object NoDocuments : DocumentApi {
    override suspend fun list(projectId: String): List<DocumentStatus> = emptyList()
    override suspend fun upload(projectId: String, input: DocumentInput): IngestStarted = error("쓰지 않는다")
    override suspend fun uploadVersion(projectId: String, documentId: String, request: NewVersionRequest): IngestStarted =
        error("쓰지 않는다")
    override suspend fun open(projectId: String, documentId: String, version: Int?, span: String?): DocumentView =
        error("쓰지 않는다")
    override suspend fun reviseSpan(projectId: String, spanId: String, request: ReviseSpanRequest): Changed =
        error("쓰지 않는다")
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewsViewModelTest {

    private val api = FakeReviewApi()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun ids(viewModel: ReviewsViewModel) = viewModel.state.value.items.map(ReviewItem::id)

    @Test
    fun `다음 쪽은 앞 쪽의 next를 보내 잇고, 끝나면 더 부르지 않는다`() = runTest {
        api.pages = mapOf(
            null to ReviewPage(listOf(printTask("r1"), printTask("r2")), next = "cursor-1"),
            "cursor-1" to ReviewPage(listOf(printTask("r2"), printTask("r3")), next = null),
        )
        val viewModel = ReviewsViewModel(api, NoDocuments, "p1")

        viewModel.reload()
        advanceUntilIdle()
        assertEquals(listOf("r1", "r2"), ids(viewModel))

        viewModel.loadMore()
        advanceUntilIdle()
        // 쪽 사이에 겹친 묶음은 한 번만 보인다.
        assertEquals(listOf("r1", "r2", "r3"), ids(viewModel))
        assertNull(viewModel.state.value.next)

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null, "cursor-1"), api.listed.map { it.third })
    }

    @Test
    fun `탭과 종류를 바꾸면 그 조건으로 첫 쪽부터 다시 읽는다`() = runTest {
        api.pages = mapOf(null to ReviewPage())
        val viewModel = ReviewsViewModel(api, NoDocuments, "p1")

        viewModel.showTab(ReviewState.DONE)
        advanceUntilIdle()
        viewModel.filterType(NodeType.TASK)
        advanceUntilIdle()

        assertEquals(
            listOf(Triple(ReviewState.DONE, null, null), Triple(ReviewState.DONE, NodeType.TASK, null)),
            api.listed.toList(),
        )
    }

    @Test
    fun `확인하면 고른 줄과 함께 보내고, 처리된 묶음은 확인할 것에서 빠진다`() = runTest {
        val item = printTask("r1")
        api.pages = mapOf(null to ReviewPage(listOf(item, printTask("r2"))))
        api.onAct = {
            ReviewResult(item.copy(version = 4, state = ReviewState.DONE, card = item.card?.copy(status = ReviewStatus.CONFIRMED)))
        }
        val viewModel = ReviewsViewModel(api, NoDocuments, "p1")
        viewModel.reload()
        advanceUntilIdle()

        viewModel.choose(item, "e-hidden", Verdict.REJECT)
        viewModel.act(item, Verdict.CONFIRM)
        advanceUntilIdle()

        assertEquals(Verdict.CONFIRM, api.actions.single().card)
        assertEquals(mapOf("e-hidden" to Verdict.REJECT), api.actions.single().edges)
        assertEquals(3, api.actions.single().expectedVersion)
        assertEquals(listOf("r2"), ids(viewModel))
        assertEquals("확인했습니다.", viewModel.state.value.notice)
        assertNull(viewModel.state.value.busyId)
    }

    @Test
    fun `그사이 묶음이 바뀌었으면 알리고 새로 읽는다`() = runTest {
        val item = printTask("r1")
        api.pages = mapOf(null to ReviewPage(listOf(item)))
        api.onAct = {
            throw HttpException(Response.error<Unit>(409, """{"error":"STALE","message":"review r1 version 4 != 3"}""".toResponseBody()))
        }
        val viewModel = ReviewsViewModel(api, NoDocuments, "p1")
        viewModel.reload()
        advanceUntilIdle()

        viewModel.act(item, Verdict.HOLD)
        advanceUntilIdle()

        assertEquals(STALE_MESSAGE, viewModel.state.value.notice)
        assertEquals(2, api.listed.size)
    }
}
