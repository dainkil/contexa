package com.contexa.android.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ApiErrorTest {

    private fun body(error: String, message: String = "") = """{"error":"$error","message":"$message"}"""

    @Test
    fun `코드로 정해 둔 문장은 server 문장보다 먼저 쓴다`() {
        assertEquals(STALE_MESSAGE, errorMessage(409, body("STALE", "review 12 version 4 != 3")))
        assertEquals(QUOTA_MESSAGE, errorMessage(429, body("QUOTA_EXCEEDED", "하루 AI 작업 수를 넘었다")))
        assertEquals(SERVER_TROUBLE_MESSAGE, errorMessage(500, body("INTERNAL", "server 안에서 예상하지 못한 오류가 났다")))
    }

    @Test
    fun `로그인 제한은 남은 시간을 분으로 올려 적는다`() {
        val limited = body("TOO_MANY_ATTEMPTS", "로그인 시도가 너무 많다")
        assertEquals("로그인을 여러 번 틀렸습니다. 1분 뒤에 다시 해 주세요.", errorMessage(429, limited, 1))
        assertEquals("로그인을 여러 번 틀렸습니다. 1분 뒤에 다시 해 주세요.", errorMessage(429, limited, 60))
        assertEquals("로그인을 여러 번 틀렸습니다. 2분 뒤에 다시 해 주세요.", errorMessage(429, limited, 61))
        assertEquals("로그인을 여러 번 틀렸습니다. 잠시 뒤에 다시 해 주세요.", errorMessage(429, limited, null))
        assertEquals("로그인을 여러 번 틀렸습니다. 잠시 뒤에 다시 해 주세요.", errorMessage(429, limited, 0))
    }

    @Test
    fun `아는 server 문장은 뒤에 id가 붙어도 바꾼다`() {
        assertEquals(
            "이 프로젝트의 구성원이 아닙니다.",
            errorMessage(403, body("FORBIDDEN", "이 프로젝트의 구성원이 아니다: 7f1c")),
        )
        assertEquals(
            "프로젝트에는 소유자가 한 명 이상 있어야 합니다.",
            errorMessage(409, body("NOT_POSSIBLE", "프로젝트에는 OWNER가 한 명 이상 있어야 한다")),
        )
        assertEquals(
            "아직 가입하지 않은 이메일입니다. 먼저 가입하도록 알려 주세요.",
            errorMessage(400, body("BAD_REQUEST", "가입하지 않은 이메일이다")),
        )
    }

    @Test
    fun `숫자가 든 문장은 숫자를 옮긴다`() {
        assertEquals("비밀번호는 8자 이상이어야 합니다.", errorMessage(400, body("BAD_REQUEST", "비밀번호는 8자 이상이어야 한다")))
        assertEquals("질문은 2000자까지 쓸 수 있습니다.", errorMessage(400, body("BAD_REQUEST", "질문은 1~2000자여야 한다")))
    }

    @Test
    fun `모르는 문장은 개발자용 문장을 보이지 않고 코드로 정한 문장을 쓴다`() {
        assertEquals("이 일을 할 권한이 없습니다.", errorMessage(403, body("FORBIDDEN", "Caller u1 cannot do x")))
        assertEquals("찾을 수 없습니다. 지워졌거나 볼 수 없는 내용입니다.", errorMessage(404, body("NOT_FOUND", "node 9 없음")))
        assertEquals("지금 상태에서는 할 수 없습니다.", errorMessage(409, body("NOT_POSSIBLE", "state machine says no")))
        assertEquals("다시 로그인해 주세요.", errorMessage(401, body("UNAUTHENTICATED", "토큰이 없다")))
    }

    @Test
    fun `server 형식이 아니면 상태 코드로 정한다`() {
        assertEquals(SERVER_TROUBLE_MESSAGE, errorMessage(502, "<html>Bad Gateway</html>"))
        assertEquals("요청이 너무 많습니다. 잠시 뒤에 다시 해 주세요.", errorMessage(429, null))
        assertEquals("이 일을 할 권한이 없습니다.", errorMessage(403, ""))
        assertEquals("찾을 수 없습니다. 지워졌거나 볼 수 없는 내용입니다.", errorMessage(404, null))
        // Spring이 정한 4xx(허용되지 않는 방법 등)는 일반 문장이다.
        assertEquals(GENERIC_ERROR_MESSAGE, errorMessage(405, body("METHOD_NOT_ALLOWED", "요청을 처리할 수 없다(405)")))
        assertEquals(GENERIC_ERROR_MESSAGE, errorMessage(null, null))
    }

    @Test
    fun `사용자에게 보이는 문장에는 server라는 말이 없다`() {
        val shown = listOf(
            NETWORK_ERROR_MESSAGE, GENERIC_ERROR_MESSAGE, SERVER_TROUBLE_MESSAGE, STALE_MESSAGE, QUOTA_MESSAGE,
            errorMessage(500, body("INTERNAL", "server 안에서 예상하지 못한 오류가 났다")),
        )
        shown.forEach { assertFalse(it, it.contains("server", ignoreCase = true) || it.contains("서버")) }
    }
}
