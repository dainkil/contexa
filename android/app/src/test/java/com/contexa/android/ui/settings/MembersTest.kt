package com.contexa.android.ui.settings

import com.contexa.android.api.Member
import com.contexa.android.api.Role
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private val ME = Member("u1", "김민지", "minji@example.com", Role.OWNER, me = true)
private val AHMED = Member("u2", "أحمد", "Ahmed@Example.com", Role.REVIEWER)
private val HIDDEN = Member("u3", "Sara", null, Role.MEMBER)

class MembersTest {

    @Test
    fun `목록에서 내 역할을 찾고, 못 읽었으면 null이다`() {
        assertEquals(Role.OWNER, myRole(listOf(AHMED, ME)))
        assertEquals(Role.REVIEWER, myRole(listOf(AHMED.copy(me = true))))
        assertNull(myRole(emptyList()))
        assertNull(myRole(listOf(AHMED, HIDDEN)))
    }

    @Test
    fun `검토는 소유자·검토자, 구성원 관리는 소유자만`() {
        assertTrue(canReview(Role.OWNER))
        assertTrue(canReview(Role.REVIEWER))
        assertFalse(canReview(Role.MEMBER))
        assertFalse(canReview(null))

        assertTrue(canManageMembers(Role.OWNER))
        assertFalse(canManageMembers(Role.REVIEWER))
        assertFalse(canManageMembers(null))
    }

    @Test
    fun `넣기 전에 이메일 칸을 검사한다`() {
        assertEquals("넣을 사람의 이메일을 입력해 주세요.", inviteError("   "))
        assertEquals("이메일 형식이 올바르지 않습니다.", inviteError("ahmed"))
        assertEquals("이메일 형식이 올바르지 않습니다.", inviteError("a b@example.com"))
        assertNull(inviteError(" ahmed@example.com "))
    }

    @Test
    fun `이미 구성원인 이메일은 대소문자와 앞뒤 공백을 가리지 않고 찾는다`() {
        val members = listOf(ME, AHMED, HIDDEN)
        assertSame(AHMED, memberByEmail(members, " ahmed@example.COM "))
        assertNull(memberByEmail(members, "sara@example.com"))
        assertNull(memberByEmail(members, ""))
    }

    @Test
    fun `역할 이름 뒤에 받침에 맞는 조사를 붙인다`() {
        assertEquals("검토자로", withRo("검토자"))
        assertEquals("구성원으로", withRo("구성원"))
        assertEquals("서울로", withRo("서울"))
        assertEquals("OWNER(으)로", withRo("OWNER"))
    }

    @Test
    fun `넣거나 바꾼 뒤 알림`() {
        assertEquals("أحمد 님의 역할을 소유자로 바꿨습니다.", memberNotice("أحمد", Role.OWNER, existed = true))
        assertEquals("sara@example.com 님을 구성원으로 넣었습니다.", memberNotice("sara@example.com", Role.MEMBER, existed = false))
    }

    @Test
    fun `소유자가 한 명뿐이면 그 역할은 바꿀 수 없다`() {
        val members = listOf(ME, AHMED, HIDDEN)
        assertFalse(canChangeRole(members, ME))
        assertTrue(canChangeRole(members, AHMED))
        // 이메일이 없으면 소유자 화면이 아니다
        assertFalse(canChangeRole(members, HIDDEN))

        val twoOwners = listOf(ME, AHMED.copy(role = Role.OWNER))
        assertTrue(canChangeRole(twoOwners, ME))
        assertTrue(canChangeRole(twoOwners, twoOwners[1]))
    }

    @Test
    fun `내 소유자 권한을 내려놓을 때만 한 번 더 묻는다`() {
        assertTrue(losesOwnership(ME, Role.REVIEWER))
        assertFalse(losesOwnership(ME, Role.OWNER))
        assertFalse(losesOwnership(ME.copy(me = false), Role.MEMBER))
        assertFalse(losesOwnership(AHMED.copy(me = true), Role.MEMBER))
    }

    @Test
    fun `로그아웃 - server에 폐기를 못 해도 이 기기의 로그인은 지운다`() = runBlocking {
        var cleared = 0
        revokeThenClear(revoke = { throw IOException("connection refused") }, clear = { cleared++ })
        assertEquals(1, cleared)

        var revoked = 0
        revokeThenClear(revoke = { revoked++ }, clear = { cleared++ })
        assertEquals(1, revoked)
        assertEquals(2, cleared)
    }

    @Test
    fun `로그아웃 - 취소돼도 지우고 취소는 그대로 올린다`() = runBlocking {
        var cleared = 0
        try {
            revokeThenClear(revoke = { throw CancellationException("화면을 떠남") }, clear = { cleared++ })
            fail("취소는 삼키지 않는다")
        } catch (e: CancellationException) {
            assertNotNull(e.message)
        }
        assertEquals(1, cleared)
    }
}
