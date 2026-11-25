package com.contexa.android.ui.context

import com.contexa.android.api.AcceptanceRequest
import com.contexa.android.api.AccountRequest
import com.contexa.android.api.Changed
import com.contexa.android.api.ContextApi
import com.contexa.android.api.ContextPacket
import com.contexa.android.api.Direction
import com.contexa.android.api.DocumentApi
import com.contexa.android.api.DocumentInput
import com.contexa.android.api.DocumentStatus
import com.contexa.android.api.DocumentView
import com.contexa.android.api.Fact
import com.contexa.android.api.Impact
import com.contexa.android.api.IngestStarted
import com.contexa.android.api.JobBrief
import com.contexa.android.api.NameCard
import com.contexa.android.api.NameForm
import com.contexa.android.api.NameRequest
import com.contexa.android.api.NewVersionRequest
import com.contexa.android.api.NodeKey
import com.contexa.android.api.NodeRef
import com.contexa.android.api.NodeType
import com.contexa.android.api.NodeView
import com.contexa.android.api.OpenIssue
import com.contexa.android.api.OpenIssueKind
import com.contexa.android.api.ReviewStatus
import com.contexa.android.api.ReviseSpanRequest
import com.contexa.android.api.Scope
import com.contexa.android.api.ScopeView
import com.contexa.android.api.Source
import com.contexa.android.api.TaskStatusRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun ref(type: NodeType, id: String) = NodeRef(type, id, 1)

private fun entity(id: String, label: String, states: Map<String, String> = mapOf("kind" to "PERSON"), status: ReviewStatus = ReviewStatus.CONFIRMED) =
    Fact(node = ref(NodeType.ENTITY, id), status = status, label = label, states = states)

private fun packet(facts: List<Fact>, open: List<OpenIssue> = emptyList()) =
    ContextPacket("2026-11-21T00:00:00Z", Scope("p1", ScopeView.RESOLVE_ENTITY), facts = facts, open = open)

private fun source(id: String, text: String) = Source(ref(NodeType.SPAN, id), "d1", 1, "민지", text)

private val arabic = NameForm("ar", "أحمد", "CANDIDATE", evidenceSpanId = "s1")
private val english = NameForm("en", "Ahmed", "CONFIRMED")
private val wrong = NameForm("en", "Ahmad", "REJECTED")
private val korean = NameForm("ko", "아흐마드", "CANDIDATE")

private val ahmad = NameCard(
    entity = NodeView(ref(NodeType.ENTITY, "n1"), "p1", ReviewStatus.CONFIRMED, "아흐마드", mapOf("kind" to "PERSON")),
    names = listOf(wrong, arabic, english, korean),
    sources = listOf(
        source("s1", "أَحْمَد سيرسل الملف"),
        source("s2", "Ahmed will send the file tomorrow."),
        source("s3", "견적은 다음 주에 다시 봅니다."),
    ),
)

class PeopleTest {

    @Test
    fun `사람 목록에는 확인된 인물만, 사람 먼저 기관은 뒤에, 대표 표기와 같은 표기는 빼고 보인다`() {
        val rows = personRows(
            packet(
                listOf(
                    entity("o1", "한빛인쇄", mapOf("kind" to "ORGANIZATION")),
                    entity("n1", "아흐마드", mapOf("kind" to "PERSON", "name.ko" to "아흐마드", "name.en" to "Ahmed", "name.ar" to "أحمد")),
                    entity("n2", "지훈", status = ReviewStatus.CANDIDATE),
                    Fact(ref(NodeType.TASK, "k1"), ReviewStatus.CONFIRMED, "견적 받기"),
                ),
            ),
        )

        assertEquals(listOf("n1", "o1"), rows.map { it.entity.node.id })
        assertEquals(listOf("Ahmed", "أحمد"), rows[0].confirmedNames)
        assertTrue(rows[1].organization)
    }

    @Test
    fun `표기는 언어 순서대로 묶고, 한 언어 안에서는 확인 - 제안 - 거절 순`() {
        val groups = nameGroups(ahmad.names + NameForm("fr", "Ahmed", "CANDIDATE"))

        assertEquals(listOf("ko", "en", "ar", "fr"), groups.map { it.lang })
        assertEquals(listOf("한국어", "영어", "아랍어", "fr"), groups.map { it.label })
        assertEquals(listOf(english, wrong), groups[1].forms)
    }

    @Test
    fun `제안은 확인과 거절, 확인된 것은 거절, 거절된 것은 다시 확인할 수 있다`() {
        assertEquals(listOf(NameAction.CONFIRM, NameAction.REJECT), nameActions("CANDIDATE"))
        assertEquals(listOf(NameAction.REJECT), nameActions("CONFIRMED"))
        assertEquals(listOf(NameAction.CONFIRM), nameActions("REJECTED"))
        assertEquals(emptyList<NameAction>(), nameActions("UNKNOWN"))
    }

    @Test
    fun `매칭 정규화 - 대소문자와 공백, 아랍어 모음 기호와 타트윌, 알리프 변형`() {
        assertEquals("ahmed ali", normalizeForMatch("  AHMED \n Ali "))
        assertEquals("احمد", normalizeForMatch("أَحْمَد"))
        assertEquals("احمد", normalizeForMatch("إحـمد"))
        assertEquals("امين", normalizeForMatch("آمين"))
        // 음역은 같은 표기로 치지 않는다.
        assertFalse(normalizeForMatch("Ahmad") == normalizeForMatch("Ahmed"))
    }

    @Test
    fun `표기가 나온 발언 - 적힌 근거가 먼저, 없으면 글자 그대로 들어 있는 발언`() {
        assertEquals(listOf("s1"), sourcesFor(arabic, ahmad.sources).map { it.span.id })
        assertEquals(listOf("s2"), sourcesFor(english, ahmad.sources).map { it.span.id })
        // 근거 발언을 읽을 수 없으면(목록에 없으면) 글자로 찾는다. 모음 기호가 달라도 같은 표기다.
        assertEquals(listOf("s1"), sourcesFor(arabic.copy(evidenceSpanId = "hidden"), ahmad.sources).map { it.span.id })
        assertEquals(emptyList<Source>(), sourcesFor(wrong, ahmad.sources))
    }

    @Test
    fun `원문 배치 - 표기마다 첫 발언, 어느 표기에도 걸리지 않은 언급은 따로`() {
        val evidence = nameEvidence(ahmad)

        assertEquals("s1", evidence.byForm[arabic]?.span?.id)
        assertEquals("s2", evidence.byForm[english]?.span?.id)
        assertNull(evidence.byForm[korean])
        assertEquals(listOf("s3"), evidence.others.map { it.span.id })
    }

    @Test
    fun `겹치는 인물은 대표 표기와 거절되지 않은 표기로 찾고, 같은 글자는 한 번만`() {
        assertEquals(listOf("아흐마드", "أحمد", "Ahmed"), searchSurfaces(ahmad))
    }

    @Test
    fun `겹치는 인물 - 나는 빼고, 한 번씩만, 불분명한 이름이면 표시한다`() {
        val other = entity("n2", "Ahmed Ali")
        val third = entity("n3", "أحمد حسن")
        val similar = similarPeople(
            "n1",
            listOf(
                "Ahmed" to packet(
                    listOf(entity("n1", "아흐마드"), other),
                    listOf(OpenIssue(OpenIssueKind.AMBIGUOUS_NAME, other.node)),
                ),
                "أحمد" to packet(listOf(other, third, Fact(ref(NodeType.TASK, "k1"), ReviewStatus.CONFIRMED, "할 일"))),
            ),
        )

        assertEquals(listOf("n2", "n3"), similar.map { it.entity.node.id })
        assertEquals(listOf("Ahmed", "أحمد"), similar.map { it.via })
        assertEquals(listOf(true, false), similar.map { it.ambiguous })
    }

    @Test
    fun `표기를 바꾸면 기대는 카드 수를 알린다`() {
        assertEquals("표기를 확인했습니다.", nameChangedMessage(NameAction.CONFIRM, 0))
        assertEquals("표기를 거절했습니다. 이 인물에 기대는 카드 2개가 영향을 받습니다.", nameChangedMessage(NameAction.REJECT, 2))
    }
}

private class FakePeopleApi : ContextApi {
    val named = mutableListOf<NameRequest>()
    val searched = mutableListOf<String>()
    var failSearch: Exception? = null

    override suspend fun nameCard(projectId: String, entityId: String): NameCard = ahmad

    override suspend fun resolveEntity(projectId: String, surface: String, lang: String?): ContextPacket {
        failSearch?.let { throw it }
        searched += surface
        val other = entity("n2", "Ahmed Ali")
        return if (surface == "Ahmed") packet(listOf(entity("n1", "아흐마드"), other)) else packet(emptyList())
    }

    override suspend fun name(projectId: String, entityId: String, request: NameRequest): Changed {
        named += request
        val key = NodeKey(NodeType.ENTITY, entityId)
        return Changed(ref(NodeType.ENTITY, entityId), listOf(Impact(key, NodeKey(NodeType.TASK, "k1"), 1)))
    }

    override suspend fun state(projectId: String, topicId: String?): ContextPacket = error("쓰지 않는다")
    override suspend fun history(projectId: String, decisionItemId: String): ContextPacket = error("쓰지 않는다")
    override suspend fun trace(projectId: String, type: NodeType, id: String, direction: Direction, depth: Int): ContextPacket =
        error("쓰지 않는다")
    override suspend fun tasks(projectId: String, mine: Boolean): ContextPacket = error("쓰지 않는다")
    override suspend fun taskStatus(projectId: String, taskId: String, request: TaskStatusRequest): Changed = error("쓰지 않는다")
    override suspend fun acceptance(projectId: String, taskId: String, request: AcceptanceRequest): Changed = error("쓰지 않는다")
    override suspend fun linkAccount(projectId: String, entityId: String, request: AccountRequest): Changed = error("쓰지 않는다")
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
    override suspend fun retryJob(projectId: String, jobId: String): JobBrief = error("쓰지 않는다")
}

@OptIn(ExperimentalCoroutinesApi::class)
class PersonViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `이름 카드를 읽고 표기마다 이름 찾기를 불러 나 아닌 인물을 모은다`() = runTest {
        val api = FakePeopleApi()
        val viewModel = PersonViewModel(api, NoDocuments, "p1", "n1")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertEquals("아흐마드", state.card?.entity?.label)
        assertEquals(listOf("아흐마드", "أحمد", "Ahmed"), api.searched)
        assertEquals(listOf("n2"), state.similar?.map { it.entity.node.id })
    }

    @Test
    fun `이름 찾기가 실패해도 이름 카드는 보이고 이유만 적는다`() = runTest {
        val api = FakePeopleApi().apply { failSearch = java.io.IOException("끊김") }
        val viewModel = PersonViewModel(api, NoDocuments, "p1", "n1")
        advanceUntilIdle()

        assertEquals("아흐마드", viewModel.state.value.card?.entity?.label)
        assertNull(viewModel.state.value.similar)
        assertTrue(viewModel.state.value.similarError != null)
    }

    @Test
    fun `확인할 때는 그 표기가 나온 발언을 근거로 보내고, 거절할 때는 근거 없이 보낸다`() = runTest {
        val api = FakePeopleApi()
        val viewModel = PersonViewModel(api, NoDocuments, "p1", "n1")
        advanceUntilIdle()

        viewModel.act(english, NameAction.CONFIRM)
        advanceUntilIdle()
        viewModel.act(arabic, NameAction.REJECT)
        advanceUntilIdle()

        assertEquals(
            listOf(NameRequest("en", "Ahmed", confirmed = true, evidenceSpanId = "s2"), NameRequest("ar", "أحمد", confirmed = false)),
            api.named,
        )
        assertNull(viewModel.state.value.busy)
        assertEquals("표기를 거절했습니다. 이 인물에 기대는 카드 1개가 영향을 받습니다.", viewModel.state.value.notice)
    }
}
