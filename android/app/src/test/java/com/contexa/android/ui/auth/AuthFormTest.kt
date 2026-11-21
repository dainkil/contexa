package com.contexa.android.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthFormTest {

    @Test
    fun `로그인 - 빈 칸이면 칸마다 안내한다`() {
        val errors = validateSignIn("", "")
        assertEquals("이메일을 입력하세요.", errors.email)
        assertEquals("비밀번호를 입력하세요.", errors.password)
        assertFalse(errors.ok)
    }

    @Test
    fun `로그인 - 이메일 형식이 아니면 막는다`() {
        assertEquals("이메일 형식이 아닙니다.", validateSignIn("abc", "pw").email)
        assertEquals("이메일 형식이 아닙니다.", validateSignIn("a b@c.com", "pw").email)
        assertEquals("이메일 형식이 아닙니다.", validateSignIn("a@b", "pw").email)
    }

    @Test
    fun `로그인 - 바르게 쓰면 통과한다`() {
        assertTrue(validateSignIn("kim@example.com", "x").ok)
    }

    @Test
    fun `가입 - 빈 칸이면 칸마다 안내한다`() {
        val errors = validateSignUp("  ", "", "", "")
        assertEquals("이름을 입력하세요.", errors.displayName)
        assertEquals("이메일을 입력하세요.", errors.email)
        assertEquals("비밀번호를 입력하세요.", errors.password)
        assertEquals("비밀번호를 한 번 더 입력하세요.", errors.confirmPassword)
    }

    @Test
    fun `가입 - 비밀번호가 8자보다 짧으면 막는다`() {
        val errors = validateSignUp("김", "kim@example.com", "1234567", "1234567")
        assertEquals("비밀번호는 8자 이상이어야 합니다.", errors.password)
        assertNull(errors.confirmPassword)
    }

    @Test
    fun `가입 - 비밀번호 확인이 다르면 막는다`() {
        val errors = validateSignUp("김", "kim@example.com", "12345678", "12345679")
        assertNull(errors.password)
        assertEquals("비밀번호가 서로 다릅니다.", errors.confirmPassword)
    }

    @Test
    fun `가입 - 바르게 쓰면 통과한다`() {
        assertTrue(validateSignUp("김", "kim@example.com", "12345678", "12345678").ok)
    }
}
