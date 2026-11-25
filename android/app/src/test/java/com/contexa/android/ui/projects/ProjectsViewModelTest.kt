package com.contexa.android.ui.projects

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

private val EVENT = ProjectSummary("p1", "국제교류의 밤 준비", Role.OWNER)
private val SHARED = ProjectSummary("p2", "مشروع التبادل", Role.MEMBER)

/** 프로젝트 목록·만들기만 받는 가짜. gate가 있으면 목록 응답을 그때까지 붙잡는다. */
private class FakeProjectsApi(var projects: List<ProjectSummary> = emptyList()) : AuthApi {
    var listCalls = 0
    var listError: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    var nextId = "p-new"

    override suspend fun listProjects(): List<ProjectSummary> {
        listCalls++
        val snapshot = projects
        gate?.await()
        listError?.let { throw it }
        return snapshot
    }

    override suspend fun createProject(request: CreateProjectRequest): IdResponse {
        projects = projects + ProjectSummary(nextId, request.title, Role.OWNER)
        return IdResponse(nextId)
    }

    override suspend fun signUp(request: SignUpRequest): UserIdResponse = error("쓰지 않는다")
    override suspend fun login(request: LoginRequest): TokenResponse = error("쓰지 않는다")
    override suspend fun logout(): Response<Unit> = error("쓰지 않는다")
    override suspend fun me(): Profile = error("쓰지 않는다")
    override suspend fun listMembers(projectId: String): List<Member> = error("쓰지 않는다")
    override suspend fun setMember(projectId: String, request: MemberRequest): UserIdResponse = error("쓰지 않는다")
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `불러온 목록에 없는 새 프로젝트만 뒤에 붙인다`() {
        val created = ProjectSummary("p-new", "새 프로젝트", Role.OWNER)
        assertEquals(listOf(EVENT, created), mergeProjects(listOf(EVENT), listOf(created)))
        assertEquals(listOf(EVENT, created), mergeProjects(listOf(EVENT, created), listOf(created)))
        assertEquals(listOf(EVENT), mergeProjects(listOf(EVENT), emptyList()))
    }

    @Test
    fun `화면이 다시 보이면 새로 읽고, 목록이 있으면 돌아가는 표시 없이 바꾼다`() = runTest {
        val api = FakeProjectsApi(listOf(EVENT))
        val viewModel = ProjectsViewModel(api)
        // 화면이 load를 부르기 전에는 읽지 않는다
        runCurrent()
        assertEquals(0, api.listCalls)

        viewModel.load()
        advanceUntilIdle()
        assertEquals(listOf(EVENT), viewModel.state.value.projects)

        // 다른 사람이 나를 넣은 뒤 목록으로 돌아왔다
        api.projects = listOf(EVENT, SHARED)
        viewModel.load()
        assertFalse(viewModel.state.value.loading)
        advanceUntilIdle()
        assertEquals(listOf(EVENT, SHARED), viewModel.state.value.projects)
        assertEquals(2, api.listCalls)
    }

    @Test
    fun `읽는 중에 또 부르면 한 번만 읽는다`() = runTest {
        val api = FakeProjectsApi(listOf(EVENT)).apply { gate = CompletableDeferred() }
        val viewModel = ProjectsViewModel(api)

        viewModel.load()
        runCurrent()
        viewModel.load()
        runCurrent()
        assertTrue(viewModel.state.value.loading)

        api.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, api.listCalls)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun `만들기 전에 시작한 불러오기가 늦게 끝나도 새 프로젝트를 잃지 않는다`() = runTest {
        val api = FakeProjectsApi(listOf(EVENT)).apply { gate = CompletableDeferred() }
        val viewModel = ProjectsViewModel(api)
        viewModel.load()
        runCurrent()

        var opened: ProjectSummary? = null
        viewModel.create("  새 프로젝트 ") { opened = it }
        advanceUntilIdle()
        assertEquals(ProjectSummary("p-new", "새 프로젝트", Role.OWNER), opened)

        // 만들기 전 목록(EVENT만)이 이제야 도착한다
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(EVENT, opened), viewModel.state.value.projects)

        // 다음에 읽은 목록에는 server가 넣어 준 것이 있으니 두 번 붙지 않는다
        api.gate = null
        viewModel.load()
        advanceUntilIdle()
        assertEquals(listOf(EVENT, opened), viewModel.state.value.projects)
    }

    @Test
    fun `불러오기에 실패해도 받은 목록은 남고, 만들기에 성공하면 오류를 지운다`() = runTest {
        val api = FakeProjectsApi(listOf(EVENT))
        val viewModel = ProjectsViewModel(api)
        viewModel.load()
        advanceUntilIdle()

        api.listError = IOException("connection refused")
        viewModel.load()
        advanceUntilIdle()
        assertEquals(NETWORK_ERROR_MESSAGE, viewModel.state.value.loadError)
        assertEquals(listOf(EVENT), viewModel.state.value.projects)

        viewModel.create("새 프로젝트") {}
        advanceUntilIdle()
        assertNull(viewModel.state.value.loadError)
        assertNull(viewModel.state.value.createError)
        assertEquals(2, viewModel.state.value.projects.size)
    }

    @Test
    fun `빈 이름은 보내지 않는다`() = runTest {
        val api = FakeProjectsApi()
        val viewModel = ProjectsViewModel(api)

        var opened = false
        viewModel.create("   ") { opened = true }
        advanceUntilIdle()

        assertFalse(opened)
        assertFalse(viewModel.state.value.creating)
        assertEquals(emptyList<ProjectSummary>(), api.projects)
    }
}
