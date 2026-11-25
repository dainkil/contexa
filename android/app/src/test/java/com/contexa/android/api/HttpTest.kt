package com.contexa.android.api

import java.io.IOException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class HttpTest {

    private lateinit var server: MockWebServer
    private var token: String? = null
    private var unauthorized = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api(): AuthApi =
        buildRetrofit(
            server.url("/api/v1/").toString(),
            buildHttpClient(token = { token }, onUnauthorized = { unauthorized++ }),
        ).create(AuthApi::class.java)

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `로그인 - 토큰 없이 보내고 응답의 토큰을 받는다`() = runBlocking {
        server.enqueue(json(200, """{"token":"abc"}"""))

        val result = api().login(LoginRequest("kim@example.com", "pw"))

        assertEquals("abc", result.token)
        val request = server.takeRequest()
        assertEquals("/api/v1/auth/login", request.url.encodedPath)
        assertNull(request.headers["Authorization"])
        assertEquals("""{"email":"kim@example.com","password":"pw"}""", request.body?.utf8())
    }

    @Test
    fun `토큰이 있으면 Bearer로 붙이고 프로젝트 목록을 푼다`() = runBlocking {
        token = "abc"
        server.enqueue(
            json(200, """[{"id":"p1","title":"행사 준비팀","role":"OWNER","extra":1},{"id":"p2","title":"مشروع","role":"MEMBER"}]""")
        )

        val projects = api().listProjects()

        assertEquals(listOf(ProjectSummary("p1", "행사 준비팀", Role.OWNER), ProjectSummary("p2", "مشروع", Role.MEMBER)), projects)
        assertEquals("Bearer abc", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `토큰을 보냈는데 401이면 로그아웃을 알린다`() = runBlocking {
        token = "expired"
        server.enqueue(json(401, """{"error":"UNAUTHENTICATED","message":"로그인이 필요하다"}"""))

        try {
            api().listProjects()
            fail("401이면 예외가 나야 한다")
        } catch (e: HttpException) {
            assertEquals(401, e.code())
        }
        assertEquals(1, unauthorized)
    }

    @Test
    fun `토큰 없이 받은 401은 로그아웃으로 보지 않고 사용자 문장으로 보여 준다`() = runBlocking {
        server.enqueue(json(401, """{"error":"UNAUTHENTICATED","message":"이메일 또는 비밀번호가 맞지 않는다"}"""))

        try {
            api().login(LoginRequest("kim@example.com", "wrong"))
            fail("401이면 예외가 나야 한다")
        } catch (e: HttpException) {
            assertEquals("이메일 또는 비밀번호가 맞지 않습니다.", userMessage(e))
            assertFalse(isStale(e))
        }
        assertEquals(0, unauthorized)
    }

    @Test
    fun `409 STALE은 정해 둔 문장으로 바꾼다`() = runBlocking {
        token = "abc"
        server.enqueue(json(409, """{"error":"STALE","message":"review 123 version 4 != 3"}"""))

        try {
            api().createProject(CreateProjectRequest("x"))
            fail("409면 예외가 나야 한다")
        } catch (e: HttpException) {
            assertEquals(STALE_MESSAGE, userMessage(e))
            assertTrue(isStale(e))
        }
    }

    @Test
    fun `server 형식이 아닌 오류와 연결 실패는 일반 문장으로 보여 준다`() {
        assertEquals(GENERIC_ERROR_MESSAGE, messageForErrorBody("<html>502</html>"))
        assertEquals(GENERIC_ERROR_MESSAGE, messageForErrorBody(null))
        assertEquals("입력한 내용을 다시 확인해 주세요.", messageForErrorBody("""{"error":"BAD_REQUEST","message":""}"""))
        assertEquals(NETWORK_ERROR_MESSAGE, userMessage(IOException("connection refused")))
        assertEquals(GENERIC_ERROR_MESSAGE, userMessage(IllegalStateException()))
    }
}
