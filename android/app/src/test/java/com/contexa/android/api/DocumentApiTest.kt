package com.contexa.android.api

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DocumentApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: DocumentApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = buildRetrofit(
            server.url("/api/v1/").toString(),
            buildHttpClient(token = { "abc" }, onUnauthorized = {}),
        ).create(DocumentApi::class.java)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(body: String) =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private val started = """{"version":{"documentId":"d1","sourceVersionId":"v1","versionNo":1},"jobId":"j1"}"""
    private val info =
        """{"id":"d1","title":"محضر","kind":"TRANSCRIPT","language":null,"visibility":"PROJECT","externalOk":false,"createdAt":"2026-11-19T10:00:00Z"}"""

    @Test
    fun `올리기 - 섞인 언어는 language를 빼고 보낸다`() = runBlocking {
        server.enqueue(json(started))

        val result = api.upload("p1", DocumentInput("3차 회의", DocumentKind.TRANSCRIPT, null, Visibility.PROJECT, false, "민지: 보류"))

        assertEquals("d1", result.version.documentId)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/projects/p1/documents", request.url.encodedPath)
        assertEquals(
            """{"title":"3차 회의","kind":"TRANSCRIPT","visibility":"PROJECT","externalOk":false,"content":"민지: 보류"}""",
            request.body?.utf8(),
        )
    }

    @Test
    fun `새 판은 본문만 보낸다`() = runBlocking {
        server.enqueue(json(started))

        api.uploadVersion("p1", "d1", NewVersionRequest("고친 본문"))

        val request = server.takeRequest()
        assertEquals("/api/v1/projects/p1/documents/d1/versions", request.url.encodedPath)
        assertEquals("""{"content":"고친 본문"}""", request.body?.utf8())
    }

    @Test
    fun `목록 - 처리 상태와 함께 푼다`() = runBlocking {
        server.enqueue(
            json(
                """[{"document":$info,"versionCount":2,"latest":{"sourceVersionId":"v2","versionNo":2,"createdAt":"2026-11-19T10:00:00Z","spanCount":0,
                "parse":{"id":"j1","status":"QUEUED","attempts":0,"lastError":null,"createdAt":"2026-11-19T10:00:00Z","finishedAt":null},"extract":null}}]""",
            ),
        )

        val rows = api.list("p1")

        assertEquals("محضر", rows.single().document.title)
        assertNull(rows.single().document.language)
        assertEquals(JobStatus.QUEUED, rows.single().latest?.parse?.status)
        assertNull(rows.single().latest?.extract)
        assertEquals("/api/v1/projects/p1/documents", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `열기 - 판이나 근거 발언 중 준 것만 주소에 붙인다`() = runBlocking {
        val view =
            """{"document":$info,"versions":[],"opened":{"sourceVersionId":"v1","versionNo":1,"spans":[
            {"ref":{"type":"SPAN","id":"s1","revision":2},"projectId":"p1","documentId":"d1","sourceVersionId":"v1","seq":1,"speakerLabel":"Ahmed","text":"OK"}],"content":null}}"""
        repeat(3) { server.enqueue(json(view)) }

        val opened = api.open("p1", "d1")
        api.open("p1", "d1", version = 2)
        api.open("p1", "d1", span = "s1")

        assertEquals(2, opened.opened.spans.single().ref.revision)
        assertNull(server.takeRequest().url.encodedQuery)
        assertEquals("version=2", server.takeRequest().url.encodedQuery)
        assertEquals("span=s1", server.takeRequest().url.encodedQuery)
    }

    @Test
    fun `정정 - 내가 본 버전을 함께 보낸다`() = runBlocking {
        server.enqueue(
            json(
                """{"node":{"type":"SPAN","id":"s1","revision":3},
                "impacts":[{"changed":{"type":"SPAN","id":"s1"},"affected":{"type":"TASK","id":"t1"},"distance":1}]}""",
            ),
        )

        val changed = api.reviseSpan("p1", "s1", ReviseSpanRequest(expectedRevision = 2, text = "고친 발언"))

        assertEquals(3, changed.node.revision)
        assertEquals("t1", changed.impacts.single().affected.id)
        val request = server.takeRequest()
        assertEquals("/api/v1/projects/p1/spans/s1/revisions", request.url.encodedPath)
        assertEquals("""{"expectedRevision":2,"text":"고친 발언"}""", request.body?.utf8())
    }
}
