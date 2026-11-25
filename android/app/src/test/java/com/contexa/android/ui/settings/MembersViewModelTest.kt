package com.contexa.android.ui.settings

import com.contexa.android.api.AuthApi
import com.contexa.android.api.CreateProjectRequest
import com.contexa.android.api.IdResponse
import com.contexa.android.api.LoginRequest
import com.contexa.android.api.Member
import com.contexa.android.api.MemberRequest
import com.contexa.android.api.NETWORK_ERROR_MESSAGE
import com.contexa.android.api.Profile
import com.contexa.android.api.ProjectSummary
import com.contexa.android.api.Role
import com.contexa.android.api.SignUpRequest
import com.contexa.android.api.TokenResponse
import com.contexa.android.api.UserIdResponse
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/** 구성원 주소만 받는 가짜. setMember는 받은 요청을 적고, 다음 목록에 반영한다. */
private class FakeMembersApi(var members: List<Member>) : AuthApi {
    var listCalls = 0
    val sets = mutableListOf<MemberRequest>()
    var setError: Exception? = null

    override suspend fun listMembers(projectId: String): List<Member> {
        listCalls++
        return members
    }

    override suspend fun setMember(projectId: String, request: MemberRequest): UserIdResponse {
        setError?.let { throw it }
        sets += request
        members = members.map { if (it.email == request.email) it.copy(role = request.role) else it }
        return UserIdResponse("u-new")
    }

    override suspend fun signUp(request: SignUpRequest): UserIdResponse = error("쓰지 않는다")
    override suspend fun login(request: LoginRequest): TokenResponse = error("쓰지 않는다")
    override suspend fun logout(): Response<Unit> = error("쓰지 않는다")
    override suspend fun me(): Profile = error("쓰지 않는다")
    override suspend fun listProjects(): List<ProjectSummary> = error("쓰지 않는다")
    override suspend fun createProject(request: CreateProjectRequest): IdResponse = error("쓰지 않는다")
}

@OptIn(ExperimentalCoroutinesApi::class)
class MembersViewModelTest {

    private val me = Member("u1", "김민지", "minji@example.com", Role.OWNER, me = true)
    private val ahmed = Member("u2", "Ahmed", "ahmed@example.com", Role.MEMBER)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `목록을 읽으면 내 역할이 server 기준으로 바뀐다`() = runTest {
        val api = FakeMembersApi(listOf(me.copy(role = Role.REVIEWER)))
        val viewModel = MembersViewModel(api, "p1")
        assertNull(viewModel.state.value.myRole)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(Role.REVIEWER, viewModel.state.value.myRole)

        // 그사이 소유자가 나를 구성원으로 내렸다
        api.members = listOf(me.copy(role = Role.MEMBER))
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(Role.MEMBER, viewModel.state.value.myRole)
        assertEquals(2, api.listCalls)
    }

    @Test
    fun `이미 있는 사람을 넣으면 역할을 바꿨다고 알리고 목록을 다시 읽는다`() = runTest {
        val api = FakeMembersApi(listOf(me, ahmed))
        val viewModel = MembersViewModel(api, "p1")
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.editEmail(" AHMED@example.com ")
        viewModel.pickRole(Role.REVIEWER)
        viewModel.invite()
        advanceUntilIdle()

        assertEquals(listOf(MemberRequest("AHMED@example.com", Role.REVIEWER)), api.sets)
        assertEquals("Ahmed 님의 역할을 검토자로 바꿨습니다.", viewModel.state.value.notice)
        assertEquals("", viewModel.state.value.email)
        assertEquals(Role.MEMBER, viewModel.state.value.role)
        assertEquals(2, api.listCalls)

        viewModel.noticeShown()
        assertNull(viewModel.state.value.notice)
    }

    @Test
    fun `이메일 형식이 틀리면 보내지 않는다`() = runTest {
        val api = FakeMembersApi(listOf(me))
        val viewModel = MembersViewModel(api, "p1")

        viewModel.editEmail("ahmed")
        viewModel.invite()
        advanceUntilIdle()

        assertEquals("이메일 형식이 올바르지 않습니다.", viewModel.state.value.inviteError)
        assertEquals(emptyList<MemberRequest>(), api.sets)
    }

    @Test
    fun `역할 바꾸기에 실패하면 오류를 보이고 다시 할 수 있다`() = runTest {
        val api = FakeMembersApi(listOf(me, ahmed)).apply { setError = IOException("connection refused") }
        val viewModel = MembersViewModel(api, "p1")
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.changeRole(ahmed, Role.REVIEWER)
        advanceUntilIdle()
        assertEquals(NETWORK_ERROR_MESSAGE, viewModel.state.value.changeError)
        assertNull(viewModel.state.value.changingId)

        api.setError = null
        viewModel.changeRole(ahmed, Role.REVIEWER)
        advanceUntilIdle()
        assertNull(viewModel.state.value.changeError)
        assertEquals(Role.REVIEWER, viewModel.state.value.members.first { it.userId == "u2" }.role)
    }
}
