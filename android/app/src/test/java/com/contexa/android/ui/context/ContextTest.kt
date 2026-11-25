package com.contexa.android.ui.context

import com.contexa.android.api.AcceptanceRequest
import com.contexa.android.api.AccountRequest
import com.contexa.android.api.Changed
import com.contexa.android.api.ContextApi
import com.contexa.android.api.ContextPacket
import com.contexa.android.api.Direction
import com.contexa.android.api.Edge
import com.contexa.android.api.EvidenceRef
import com.contexa.android.api.NameCard
import com.contexa.android.api.NameRequest
import com.contexa.android.api.Fact
import com.contexa.android.api.NodeRef
import com.contexa.android.api.NodeType
import com.contexa.android.api.OpenIssue
import com.contexa.android.api.OpenIssueKind
import com.contexa.android.api.RelType
import com.contexa.android.api.ReviewStatus
import com.contexa.android.api.STALE_MESSAGE
import com.contexa.android.api.Scope
import com.contexa.android.api.ScopeView
import com.contexa.android.api.Source
import com.contexa.android.api.TaskStatus
import com.contexa.android.api.TaskStatusRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

private fun ref(type: NodeType, id: String, revision: Int = 1) = NodeRef(type, id, revision)

private fun fact(
    type: NodeType,
    id: String,
    label: String,
    states: Map<String, String> = emptyMap(),
    status: ReviewStatus = ReviewStatus.CONFIRMED,
    evidence: List<String> = emptyList(),
    revision: Int = 1,
) = Fact(
    node = ref(type, id, revision),
    status = status,
    label = label,
    states = states,
    evidence = evidence.map { EvidenceRef(ref(NodeType.SPAN, it), "e-$it", ReviewStatus.CONFIRMED) },
)

private fun edge(rel: RelType, src: NodeRef, dst: NodeRef) =
    Edge("${rel.name}:${src.id}:${dst.id}", "p1", rel, src, dst, ReviewStatus.CONFIRMED, ontologyVersion = "1", version = 1)

private fun packet(
    facts: List<Fact>,
    relations: List<Edge> = emptyList(),
    open: List<OpenIssue> = emptyList(),
    sources: List<Source> = emptyList(),
    focus: NodeRef? = null,
) = ContextPacket("2026-11-21T00:00:00Z", Scope("p1", ScopeView.CURRENT_STATE, focus), facts, relations, open, sources)

private val topic = fact(NodeType.TOPIC, "t1", "인쇄")
private val item = fact(NodeType.DECISION_ITEM, "i1", "인쇄 발주 시점", mapOf("decisionState" to "DECIDED", "currentEventId" to "e2"))
private val loose = fact(NodeType.DECISION_ITEM, "i2", "장소", mapOf("decisionState" to "UNDECIDED"))
private val proposal = fact(NodeType.DECISION_EVENT, "e1", "바로 발주하자", mapOf("kind" to "PROPOSAL", "standing" to "SUPERSEDED"))
private val decided = fact(NodeType.DECISION_EVENT, "e2", "예산 승인 뒤에 발주", mapOf("kind" to "DECISION"), evidence = listOf("s1", "s9"))
private val condition = fact(NodeType.CONDITION, "c1", "예산 승인", mapOf("state" to "UNMET"))
private val task = fact(
    NodeType.TASK, "k1", "초청장 문구 정리",
    mapOf("workStatus" to "PLANNED", "acceptance" to "PENDING", "dueAt" to "2026-11-27T00:00:00Z"), revision = 3,
)
private val candidateTask = fact(NodeType.TASK, "k2", "견적 받기", mapOf("workStatus" to "PLANNED"), ReviewStatus.CANDIDATE)
private val person = fact(NodeType.ENTITY, "n1", "지훈", mapOf("kind" to "PERSON"))
private val quote = Source(ref(NodeType.SPAN, "s1"), "d1", 1, "민지", "예산 승인 전까지 인쇄는 보류입니다.")

private fun stale() = HttpException(
    Response.error<Any>(409, """{"error":"STALE","message":"바뀜"}""".toResponseBody("application/json".toMediaType())),
)

private class FakeContextApi : ContextApi {
    var tasks = packet(listOf(task, candidateTask, person), listOf(edge(RelType.ASSIGNED_TO, task.node, person.node)))
    val mineAsked = mutableListOf<Boolean>()
    val statusSent = mutableListOf<TaskStatusRequest>()
    val accepted = mutableListOf<Boolean>()
    var failStatus: Exception? = null

    override suspend fun state(projectId: String, topicId: String?) = packet(listOf(topic, item, decided, person))
    override suspend fun history(projectId: String, decisionItemId: String) = error("쓰지 않는다")
    override suspend fun trace(projectId: String, type: NodeType, id: String, direction: Direction, depth: Int) =
        error("쓰지 않는다")

    override suspend fun tasks(projectId: String, mine: Boolean): ContextPacket {
        mineAsked += mine
        return tasks
    }

    override suspend fun taskStatus(projectId: String, taskId: String, request: TaskStatusRequest): Changed {
        failStatus?.let { throw it }
        statusSent += request
        return Changed(ref(NodeType.TASK, taskId, 4))
    }

    override suspend fun acceptance(projectId: String, taskId: String, request: AcceptanceRequest): Changed {
        accepted += request.accepted
        return Changed(ref(NodeType.TASK, taskId, 4))
    }

    override suspend fun linkAccount(projectId: String, entityId: String, request: AccountRequest): Changed =
        error("쓰지 않는다")
    override suspend fun resolveEntity(projectId: String, surface: String, lang: String?): ContextPacket = error("쓰지 않는다")
    override suspend fun nameCard(projectId: String, entityId: String): NameCard = error("쓰지 않는다")
    override suspend fun name(projectId: String, entityId: String, request: NameRequest): Changed = error("쓰지 않는다")
}

@OptIn(ExperimentalCoroutinesApi::class)
class ContextTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `현황을 안건별로 묶고, 항목마다 지금 유효한 결정을 붙인다`() {
        val board = decisionBoard(
            packet(listOf(topic, item, loose, proposal, decided), listOf(edge(RelType.HAS_ITEM, topic.node, item.node))),
        )

        assertEquals(listOf("인쇄", null), board.map { it.topic?.label })
        assertEquals("예산 승인 뒤에 발주", board[0].rows.single().current?.label)
        // 안건에 묶이지 않은 항목은 맨 뒤에 따로 모은다. 아직 정해지지 않았으면 유효한 결정이 없다.
        assertEquals("장소", board[1].rows.single().item.label)
        assertNull(board[1].rows.single().current)
    }

    @Test
    fun `확정에는 확인된 결정만 남기고, 그런 결정이 없는 안건은 뺀다`() {
        val pendingItem = fact(NodeType.DECISION_ITEM, "i3", "예산", mapOf("decisionState" to "DECIDED", "currentEventId" to "e3"))
        val pendingEvent = fact(NodeType.DECISION_EVENT, "e3", "500만 원", mapOf("kind" to "DECISION"), ReviewStatus.CANDIDATE)

        val board = confirmedBoard(
            packet(
                listOf(topic, item, loose, decided, pendingItem, pendingEvent),
                listOf(edge(RelType.HAS_ITEM, topic.node, item.node)),
            ),
        )

        // "장소"는 아직 정해지지 않았고, "예산"은 결정이 아직 AI 후보라서 빠진다.
        assertEquals(listOf("인쇄"), board.map { it.topic?.label })
        assertEquals("예산 승인 뒤에 발주", board.single().rows.single().current?.label)
    }

    @Test
    fun `확인 칸에는 남은 문제와 아직 정해지지 않은 결정을 적고, 같은 줄은 합친다`() {
        val twin = condition.copy(node = ref(NodeType.CONDITION, "c2"))
        val candidate = condition.copy(node = ref(NodeType.CONDITION, "c3"), status = ReviewStatus.CANDIDATE)

        val rows = notices(
            packet(
                listOf(condition, twin, candidate, loose),
                open = listOf(
                    OpenIssue(OpenIssueKind.UNMET_CONDITION, condition.node),
                    OpenIssue(OpenIssueKind.UNMET_CONDITION, twin.node),
                    // 아직 확인하지 않은 AI 후보에 걸린 것은 검토함에서 다루므로 뺀다.
                    OpenIssue(OpenIssueKind.UNMET_CONDITION, candidate.node),
                    OpenIssue(OpenIssueKind.HELD_EDGE, edgeId = "x"),
                ),
            ),
        )

        assertEquals(
            listOf(Notice("조건 충족 전", "예산 승인", 2), Notice("보류 중인 연결", null), Notice("아직 정해지지 않음", "장소")),
            rows,
        )
    }

    @Test
    fun `문서의 할 일은 그 문서의 발언을 근거로 한 것만 고른다`() {
        val here = task.copy(evidence = listOf(EvidenceRef(ref(NodeType.SPAN, "s1"), "e-s1", ReviewStatus.CONFIRMED)))
        val elsewhere = candidateTask.copy(evidence = listOf(EvidenceRef(ref(NodeType.SPAN, "s2"), "e-s2", ReviewStatus.CANDIDATE)))
        val otherQuote = Source(ref(NodeType.SPAN, "s2"), "d2", 3, null, "견적은 다음 주에 받습니다.")

        val rows = tasksOfDocument(
            packet(
                listOf(here, elsewhere, person),
                listOf(edge(RelType.ASSIGNED_TO, here.node, person.node)),
                sources = listOf(quote, otherQuote),
            ),
            documentId = "d1",
        )

        assertEquals(listOf("초청장 문구 정리"), rows.map { it.task.label })
        assertEquals(listOf("지훈"), rows.single().assignees)
    }

    @Test
    fun `할 일에 담당자를 붙이고, 확인된 할 일만 상태를 바꿀 수 있다`() {
        val rows = taskRows(packet(listOf(task, candidateTask, person), listOf(edge(RelType.ASSIGNED_TO, task.node, person.node))))

        assertEquals(listOf("지훈"), rows[0].assignees)
        assertEquals(TaskStatus.PLANNED, rows[0].workStatus)
        assertTrue(rows[0].canChange)
        assertTrue(rows[0].awaitingAcceptance)
        assertEquals(emptyList<String>(), rows[1].assignees)
        assertFalse(rows[1].canChange)
        assertEquals("PENDING", rows[0].acceptance)
        assertFalse(TaskStatus.PLANNED in nextStatuses(TaskStatus.PLANNED))
        assertEquals(TaskStatus.entries.size - 1, nextStatuses(TaskStatus.PLANNED).size)
    }

    @Test
    fun `담당이 없는 할 일은 수락 상태를 적지 않고 수락을 기다리지도 않는다`() {
        val row = taskRows(packet(listOf(task))).single()

        assertTrue(row.canChange)
        assertNull(row.acceptance)
        assertFalse(row.awaitingAcceptance)
    }

    @Test
    fun `server가 다시 확인하라고 알린 할 일에는 표시를 붙이고, 확인 칸에도 적는다`() {
        val flagged = packet(listOf(task, candidateTask), open = listOf(OpenIssue(OpenIssueKind.NEEDS_RECHECK, task.node)))

        assertEquals(listOf(true, false), taskRows(flagged).map { it.needsRecheck })
        assertEquals(listOf(Notice("다시 확인 필요", "초청장 문구 정리")), notices(flagged).filter { it.kind == "다시 확인 필요" })
    }

    @Test
    fun `이력은 사건만 받은 순서대로, 근거는 읽을 수 있는 발언만`() {
        val history = packet(listOf(item, proposal, decided), sources = listOf(quote), focus = item.node)

        assertEquals(listOf("e1", "e2"), historyEvents(history).map { it.node.id })
        // s9는 읽을 수 없는 문서의 발언이라 원문이 오지 않았다.
        assertEquals(listOf("s1"), sourcesOf(decided, history).map { it.span.id })
        assertEquals(emptyList<Source>(), sourcesOf(proposal, history))
    }

    @Test
    fun `따라가기 - 바로 이어진 카드에 줄 이름을 붙이고, 더 먼 카드는 뒤에 둔다`() {
        val span = fact(NodeType.SPAN, "s1", "발언")
        val trace = packet(
            listOf(task, condition, decided, person, span),
            listOf(
                edge(RelType.REQUIRES, task.node, condition.node),
                edge(RelType.ASSIGNED_TO, task.node, person.node),
                edge(RelType.DERIVED_FROM, condition.node, decided.node),
                edge(RelType.EVIDENCED_BY, task.node, span.node),
            ),
            focus = task.node,
        )

        val linked = linkedCards(trace)

        assertEquals(listOf("예산 승인" to "먼저 필요한 것", "지훈" to "담당", "예산 승인 뒤에 발주" to null), linked.map { it.fact.label to it.relation })
    }

    @Test
    fun `따라가기 - 영향 쪽에서는 건너편이 나에게 기대는 줄이다`() {
        val impact = packet(listOf(condition, task), listOf(edge(RelType.REQUIRES, task.node, condition.node)), focus = condition.node)

        assertEquals("초청장 문구 정리", linkedCards(impact).single().fact.label)
    }

    @Test
    fun `상태를 바꿀 때 읽은 revision을 보내고, 끝나면 새로 읽는다`() = runTest {
        val api = FakeContextApi()
        val viewModel = ContextViewModel(api, "p1")
        viewModel.reload()
        advanceUntilIdle()
        val row = viewModel.state.value.tasks.first()

        viewModel.changeStatus(row, TaskStatus.IN_PROGRESS)
        advanceUntilIdle()

        assertEquals(listOf(TaskStatusRequest(TaskStatus.IN_PROGRESS, 3)), api.statusSent.toList())
        assertEquals("상태를 바꿨습니다.", viewModel.state.value.notice)
        assertNull(viewModel.state.value.busyId)
        assertEquals(2, api.mineAsked.size)
        assertEquals("인쇄 발주 시점", viewModel.state.value.groups.last().rows.single().item.label)
    }

    @Test
    fun `그사이 바뀐 할 일이면 알리고 새로 읽는다`() = runTest {
        val api = FakeContextApi().apply { failStatus = stale() }
        val viewModel = ContextViewModel(api, "p1")
        viewModel.reload()
        advanceUntilIdle()

        viewModel.changeStatus(viewModel.state.value.tasks.first(), TaskStatus.DONE)
        advanceUntilIdle()

        assertEquals(STALE_MESSAGE, viewModel.state.value.notice)
        assertEquals(2, api.mineAsked.size)
    }

    @Test
    fun `내 할 일로 바꾸면 그것만 다시 읽고, 수락을 보낸다`() = runTest {
        val api = FakeContextApi()
        val viewModel = ContextViewModel(api, "p1")
        viewModel.reload()
        advanceUntilIdle()

        viewModel.showMine(true)
        advanceUntilIdle()
        viewModel.accept(viewModel.state.value.tasks.first(), accepted = true)
        advanceUntilIdle()

        assertEquals(listOf(false, true, true), api.mineAsked.toList())
        assertEquals(listOf(true), api.accepted.toList())
        assertEquals("수락했습니다.", viewModel.state.value.notice)
    }

    @Test
    fun `맥락에는 확정된 결정과 확인된 할 일만 싣는다`() = runTest {
        val viewModel = ContextViewModel(FakeContextApi(), "p1")
        viewModel.reload()
        advanceUntilIdle()

        assertEquals(listOf("인쇄 발주 시점"), viewModel.state.value.groups.flatMap { it.rows }.map { it.item.label })
        // AI 후보인 "견적 받기"는 검토함에서 확인한 뒤에야 나온다.
        assertEquals(listOf("초청장 문구 정리"), viewModel.state.value.tasks.map { it.task.label })
    }
}
