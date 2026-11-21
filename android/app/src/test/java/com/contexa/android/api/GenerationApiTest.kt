package com.contexa.android.api

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GenerationApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GenerationApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = buildRetrofit(
            server.url("/api/v1/").toString(),
            buildHttpClient(token = { "abc" }, onUnauthorized = {}),
        ).create(GenerationApi::class.java)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(body: String) =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `질문 - 종류를 정하지 않으면 빼고 보낸다`() = runBlocking {
        server.enqueue(json("""{"artifactId":"a1","jobId":"j1"}"""))

        val requested = api.ask("p1", AskRequest(question = "인쇄는 언제 하나요?", lang = "ar"))

        assertEquals("a1", requested.artifactId)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/projects/p1/generations", request.url.encodedPath)
        assertEquals("""{"question":"인쇄는 언제 하나요?","lang":"ar"}""", request.body?.utf8())
    }

    @Test
    fun `지난 질문 목록을 푼다`() = runBlocking {
        server.enqueue(
            json("""[{"id":"a1","kind":"ANSWER","question":"متى الطباعة؟","lang":"ar","published":false,"createdAt":"2026-11-19T10:00:00Z"}]"""),
        )

        val row = api.list("p1").single()

        assertEquals("متى الطباعة؟", row.question)
        assertFalse(row.published)
        assertEquals("/api/v1/projects/p1/generations", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `글 - 쓰는 중이면 본문이 없고, 다 쓰면 인용과 바뀐 근거가 온다`() = runBlocking {
        server.enqueue(
            json("""{"id":"a1","kind":"ANSWER","question":"q","lang":"ko","status":"PENDING","content":null,"citations":[],"stale":[],"failure":null,"createdAt":"2026-11-19T10:00:00Z","publishedAt":null}"""),
        )
        server.enqueue(
            json(
                """{"id":"a1","kind":"ANSWER","question":"q","lang":"ko","status":"PUBLISHED","content":"예산 승인 뒤입니다 [1].",
                "citations":[{"span":{"type":"SPAN","id":"s1","revision":1},"documentId":"d1","seq":1,"speaker":"민지","text":"보류입니다."}],
                "stale":[{"node":{"type":"TASK","id":"t1","revision":1},"edge":null,"currentVersion":2}],
                "failure":null,"createdAt":"2026-11-19T10:00:00Z","publishedAt":"2026-11-19T10:00:09Z"}""",
            ),
        )

        val pending = api.get("p1", "a1")
        val published = api.get("p1", "a1")

        assertEquals(ArtifactStatus.PENDING, pending.status)
        assertNull(pending.content)
        assertEquals("s1", published.citations.single().span.id)
        assertEquals(2, published.stale.single().currentVersion)
        assertEquals("/api/v1/projects/p1/generations/a1", server.takeRequest().url.encodedPath)
    }
}
