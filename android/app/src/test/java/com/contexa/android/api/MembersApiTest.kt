package com.contexa.android.api

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

/** 구성원·로그아웃 주소와 오류 문장. 기준: server api/AuthController.java */
class MembersApiTest {

    private lateinit var server: MockWebServer

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
            buildHttpClient(token = { "abc" }, onUnauthorized = {}),
        ).create(AuthApi::class.java)

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `구성원 목록 - 남의 이메일이 없어도 풀고 나를 표시한다`() = runBlocking {
        server.enqueue(
            json(
                200,
                """[{"userId":"u1","displayName":"김민지","email":"minji@example.com","role":"OWNER","me":true},""" +
                    """{"userId":"u2","displayName":"أحمد","email":null,"role":"REVIEWER","me":false},""" +
                    """{"userId":"u3","displayName":"Sara","role":"MEMBER"}]""",
            ),
        )

        val members = api().listMembers("p1")

        assertEquals(
            listOf(
                Member("u1", "김민지", "minji@example.com", Role.OWNER, me = true),
                Member("u2", "أحمد", null, Role.REVIEWER),
                Member("u3", "Sara", null, Role.MEMBER),
            ),
            members,
        )
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/projects/p1/members", request.url.encodedPath)
        assertEquals("Bearer abc", request.headers["Authorization"])
    }

    @Test
    fun `구성원 넣기 - 이메일과 역할을 PUT으로 보낸다`() = runBlocking {
        server.enqueue(json(200, """{"userId":"u2"}"""))

        val result = api().setMember("p1", MemberRequest("ahmed@example.com", Role.REVIEWER))

        assertEquals("u2", result.userId)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/projects/p1/members", request.url.encodedPath)
        assertEquals("""{"email":"ahmed@example.com","role":"REVIEWER"}""", request.body?.utf8())
    }

    @Test
    fun `마지막 소유자를 내리면 사용자 문장으로 알린다`() = runBlocking {
        server.enqueue(json(409, """{"error":"NOT_POSSIBLE","message":"프로젝트에는 OWNER가 한 명 이상 있어야 한다"}"""))

        try {
            api().setMember("p1", MemberRequest("minji@example.com", Role.MEMBER))
            fail("409면 예외가 나야 한다")
        } catch (e: HttpException) {
            assertEquals("프로젝트에는 소유자가 한 명 이상 있어야 합니다.", userMessage(e))
        }
    }

    @Test
    fun `로그아웃 - 토큰을 붙여 POST로 보내고 204를 받는다`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())

        val response = api().logout()

        assertEquals(204, response.code())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/logout", request.url.encodedPath)
        assertEquals("Bearer abc", request.headers["Authorization"])
    }

    @Test
    fun `로그인 제한은 Retry-After 헤더로 남은 분을 알린다`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(429)
                .addHeader("Content-Type", "application/json")
                .addHeader("Retry-After", "125")
                .body("""{"error":"TOO_MANY_ATTEMPTS","message":"로그인 시도가 너무 많다"}""")
                .build(),
        )

        try {
            api().login(LoginRequest("kim@example.com", "wrong"))
            fail("429면 예외가 나야 한다")
        } catch (e: HttpException) {
            assertEquals("로그인을 여러 번 틀렸습니다. 3분 뒤에 다시 해 주세요.", userMessage(e))
        }
    }
}
