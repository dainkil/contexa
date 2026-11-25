package com.contexa.android.api

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** 가입·로그인·프로젝트. 기준: server api/AuthController.java */
interface AuthApi {

    @POST("auth/signup")
    suspend fun signUp(@Body request: SignUpRequest): UserIdResponse

    /** 토큰은 이때 한 번만 받는다. 이후 요청마다 Authorization: Bearer로 보낸다. */
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): TokenResponse

    /** 지금 토큰을 server에서도 폐기한다. 이미 폐기된 토큰이어도 204다. */
    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    /** 로그인한 사람의 이메일과 이름. */
    @GET("auth/me")
    suspend fun me(): Profile

    /** 내가 속한 프로젝트와 각 프로젝트에서의 역할. */
    @GET("projects")
    suspend fun listProjects(): List<ProjectSummary>

    @POST("projects")
    suspend fun createProject(@Body request: CreateProjectRequest): IdResponse

    /** 구성원 목록(소유자 → 검토자 → 구성원). 이메일은 소유자에게만, 그리고 내 것만 실린다. */
    @GET("projects/{projectId}/members")
    suspend fun listMembers(@Path("projectId") projectId: String): List<Member>

    /** 가입한 사람을 이메일로 넣거나 역할을 바꾼다. 소유자만 할 수 있다. */
    @PUT("projects/{projectId}/members")
    suspend fun setMember(@Path("projectId") projectId: String, @Body request: MemberRequest): UserIdResponse
}

/** 프로젝트 구성원 한 명(identity/IdentityAccess.Member). me는 로그인한 나. */
@Serializable
data class Member(
    val userId: String,
    val displayName: String,
    val email: String? = null,
    val role: Role,
    val me: Boolean = false,
)

@Serializable
data class MemberRequest(val email: String, val role: Role)

@Serializable
data class SignUpRequest(val email: String, val displayName: String, val password: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class CreateProjectRequest(val title: String)

@Serializable
data class UserIdResponse(val userId: String)

@Serializable
data class TokenResponse(val token: String)

@Serializable
data class Profile(val email: String, val displayName: String)

@Serializable
data class IdResponse(val id: String)
