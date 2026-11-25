package com.contexa.android.api

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ContextApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ContextApi

    private val empty = """{"asOf":"2026-11-21T00:00:00Z","scope":{"projectId":"p1","view":"TASKS"},"facts":[],"relations":[],"open":[],"sources":[]}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = buildRetrofit(
            server.url("/api/v1/").toString(),
            buildHttpClient(token = { "abc" }, onUnauthorized = {}),
        ).create(ContextApi::class.java)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(body: String) =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `읽기 주소 - 현황, 이력, 따라가기, 할 일`() = runBlocking {
        repeat(4) { server.enqueue(json(empty)) }

        api.state("p1")
        api.history("p1", "i1")
        api.trace("p1", NodeType.TASK, "k1", Direction.IMPACT)
        api.tasks("p1", mine = true)

        assertEquals("/api/v1/projects/p1/context/state", server.takeRequest().url.toString().substringAfter(":${server.port}"))
        assertEquals("/api/v1/projects/p1/context/history/i1", server.takeRequest().url.encodedPath)
        val trace = server.takeRequest().url
        assertEquals("/api/v1/projects/p1/context/trace", trace.encodedPath)
        assertEquals("type=TASK&id=k1&direction=IMPACT&depth=2", trace.encodedQuery)
        assertEquals("mine=true", server.takeRequest().url.encodedQuery)
    }

    @Test
    fun `현황을 푼다 - 카드 상태, 줄, 남은 것`() = runBlocking {
        server.enqueue(
            json(
                """{"asOf":"2026-11-21T00:00:00Z","scope":{"projectId":"p1","view":"CURRENT_STATE","focus":null,"direction":null,"depth":null},
                "facts":[{"node":{"type":"DECISION_ITEM","id":"i1","revision":2},"status":"CONFIRMED","label":"인쇄 발주 시점",
                  "states":{"decisionState":"DECIDED","currentEventId":"e2"},"confirmedBy":"u1","confirmedAt":"2026-11-20T00:00:00Z",
                  "evidence":[{"span":{"type":"SPAN","id":"s1","revision":1},"edgeId":"x","status":"CONFIRMED"}]}],
                "relations":[{"id":"r1","projectId":"p1","rel":"HAS_ITEM","src":{"type":"TOPIC","id":"t1","revision":1},
                  "dst":{"type":"DECISION_ITEM","id":"i1","revision":2},"status":"CONFIRMED","evidenceSpanIds":[],"confirmedBy":null,
                  "confirmedAt":null,"ontologyVersion":"1","version":1}],
                "open":[{"kind":"UNMET_CONDITION","node":{"type":"CONDITION","id":"c1","revision":1},"edgeId":null}],"sources":[]}""",
            ),
        )

        val packet = api.state("p1")

        assertEquals("e2", packet.facts.single().states["currentEventId"])
        assertEquals(RelType.HAS_ITEM, packet.relations.single().rel)
        assertEquals(OpenIssueKind.UNMET_CONDITION, packet.open.single().kind)
    }

    @Test
    fun `할 일 상태와 수락을 보낸다`() = runBlocking {
        val changed = """{"node":{"type":"TASK","id":"k1","revision":4},"impacts":[]}"""
        server.enqueue(json(changed))
        server.enqueue(json(changed))

        api.taskStatus("p1", "k1", TaskStatusRequest(TaskStatus.DONE, 3))
        api.acceptance("p1", "k1", AcceptanceRequest(false))

        val status = server.takeRequest()
        assertEquals("/api/v1/projects/p1/tasks/k1/status", status.url.encodedPath)
        assertEquals("""{"status":"DONE","expectedRevision":3}""", status.body?.utf8())
        val acceptance = server.takeRequest()
        assertEquals("/api/v1/projects/p1/tasks/k1/acceptance", acceptance.url.encodedPath)
        assertEquals("""{"accepted":false}""", acceptance.body?.utf8())
    }

    @Test
    fun `계정 연결 - 끊을 때는 이메일을 빼고 보낸다`() = runBlocking {
        val changed = """{"node":{"type":"ENTITY","id":"n1","revision":2},"impacts":[]}"""
        server.enqueue(json(changed))
        server.enqueue(json(changed))

        api.linkAccount("p1", "n1", AccountRequest("jihun@example.com"))
        api.linkAccount("p1", "n1", AccountRequest(null))

        val link = server.takeRequest()
        assertEquals("/api/v1/projects/p1/entities/n1/account", link.url.encodedPath)
        assertEquals("""{"email":"jihun@example.com"}""", link.body?.utf8())
        assertEquals("{}", server.takeRequest().body?.utf8())
    }

    @Test
    fun `이름 검수 - 이름 찾기, 이름 카드, 표기 확인`() = runBlocking {
        server.enqueue(json(empty))
        server.enqueue(
            json(
                """{"entity":{"ref":{"type":"ENTITY","id":"n1","revision":3},"projectId":"p1","status":"CONFIRMED","label":"아흐마드",
                  "states":{"kind":"PERSON"},"confirmedBy":"u1","confirmedAt":"2026-11-20T00:00:00Z"},
                "names":[{"lang":"ar","form":"أحمد","status":"CANDIDATE","evidenceSpanId":"s1"},
                         {"lang":"en","form":"Ahmed","status":"CONFIRMED","evidenceSpanId":null}],
                "sources":[{"span":{"type":"SPAN","id":"s1","revision":1},"documentId":"d1","seq":4,"speaker":"민지","text":"أحمد سيرسل الملف"}]}""",
            ),
        )
        server.enqueue(json("""{"node":{"type":"ENTITY","id":"n1","revision":4},"impacts":[]}"""))

        api.resolveEntity("p1", "Ahmed Ali")
        val card = api.nameCard("p1", "n1")
        api.name("p1", "n1", NameRequest("ar", "أحمد", confirmed = true, evidenceSpanId = "s1"))

        val resolve = server.takeRequest().url
        assertEquals("/api/v1/projects/p1/context/entities", resolve.encodedPath)
        assertEquals("Ahmed Ali", resolve.queryParameter("surface"))
        assertEquals(null, resolve.queryParameter("lang"))
        assertEquals("/api/v1/projects/p1/context/entities/n1", server.takeRequest().url.encodedPath)
        assertEquals("s1", card.names.first().evidenceSpanId)
        assertEquals("أحمد سيرسل الملف", card.sources.single().text)
        val name = server.takeRequest()
        assertEquals("/api/v1/projects/p1/entities/n1/names", name.url.encodedPath)
        assertEquals("""{"lang":"ar","form":"أحمد","confirmed":true,"evidenceSpanId":"s1"}""", name.body?.utf8())
    }
}
