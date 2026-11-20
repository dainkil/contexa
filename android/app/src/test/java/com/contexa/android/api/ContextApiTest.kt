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
}
