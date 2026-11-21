package com.contexa.android.ui.context

import com.contexa.android.api.AcceptanceRequest
import com.contexa.android.api.AccountRequest
import com.contexa.android.api.Changed
import com.contexa.android.api.ContextApi
import com.contexa.android.api.ContextPacket
import com.contexa.android.api.Direction
import com.contexa.android.api.Edge
import com.contexa.android.api.EvidenceRef
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
    val linked = mutableListOf<Pair<String, String?>>()
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

    override suspend fun linkAccount(projectId: String, entityId: String, request: AccountRequest): Changed {
        linked += entityId to request.email
        return Changed(ref(NodeType.ENTITY, entityId, 2))
    }
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
    fun `남은 것에는 걸린 카드의 내용을 붙이고, 줄에 걸린 것은 종류만 알린다`() {
        val rows = openRows(
            packet(
                listOf(condition),
                open = listOf(
                    OpenIssue(OpenIssueKind.UNMET_CONDITION, condition.node),
                    OpenIssue(OpenIssueKind.HELD_EDGE, edgeId = "x"),
                ),
            ),
        )

        assertEquals(listOf("예산 승인", null), rows.map { it.label })
        assertEquals("아직 충족 안 된 조건", openLabel(rows[0].kind))
    }

    @Test
    fun `남은 것에 걸린 카드가 AI 후보면 그렇다고 적는다`() {
        val candidate = condition.copy(status = ReviewStatus.CANDIDATE)
        val confirmed = openRows(packet(listOf(condition), open = listOf(OpenIssue(OpenIssueKind.UNMET_CONDITION, condition.node))))
        val pending = openRows(packet(listOf(candidate), open = listOf(OpenIssue(OpenIssueKind.UNMET_CONDITION, candidate.node))))

        assertEquals("아직 충족 안 된 조건 · 조건", openCaption(confirmed[0]))
        assertEquals("아직 충족 안 된 조건 · 조건 · AI 후보", openCaption(pending[0]))
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
        assertFalse(TaskStatus.PLANNED in nextStatuses(TaskStatus.PLANNED))
        assertEquals(TaskStatus.entries.size - 1, nextStatuses(TaskStatus.PLANNED).size)
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
    fun `사람만 고르고, 확인된 사람에게만 계정을 연결할 수 있다`() {
        val org = fact(NodeType.ENTITY, "n2", "NUR Labs", mapOf("kind" to "ORGANIZATION"))
        val linked = fact(NodeType.ENTITY, "n3", "민지", mapOf("kind" to "PERSON", "accountLinked" to "true"))
        val candidate = fact(NodeType.ENTITY, "n4", "Ahmed", mapOf("kind" to "PERSON", "accountLinked" to "false"), ReviewStatus.CANDIDATE)

        val rows = personRows(packet(listOf(person, org, linked, candidate)))

        assertEquals(listOf("지훈", "민지", "Ahmed"), rows.map { it.person.label })
        assertEquals(listOf(false, true, false), rows.map { it.linked })
        assertEquals(listOf(true, true, false), rows.map { it.canLink })
        assertTrue(canSubmitEmail(" a@b.kr "))
        assertFalse(canSubmitEmail("지훈"))
        assertFalse(canSubmitEmail("a b@c"))
    }

    @Test
    fun `계정을 연결할 때 이메일을 다듬어 보내고, 끊을 때는 이메일 없이 보낸다`() = runTest {
        val api = FakeContextApi()
        val viewModel = ContextViewModel(api, "p1")
        viewModel.reload()
        advanceUntilIdle()
        val row = viewModel.state.value.people.single()

        viewModel.linkAccount(row, " jihun@example.com ")
        advanceUntilIdle()
        viewModel.unlinkAccount(row)
        advanceUntilIdle()

        assertEquals(listOf<Pair<String, String?>>("n1" to "jihun@example.com", "n1" to null), api.linked.toList())
        assertEquals("지훈의 계정 연결을 끊었습니다.", viewModel.state.value.notice)
        assertEquals(3, api.mineAsked.size)
    }
}
