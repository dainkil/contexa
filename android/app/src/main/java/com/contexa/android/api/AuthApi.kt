package com.contexa.android.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** 가입·로그인·프로젝트. 기준: server api/AuthController.java */
interface AuthApi {

    @POST("auth/signup")
    suspend fun signUp(@Body request: SignUpRequest): UserIdResponse

    /** 토큰은 이때 한 번만 받는다. 이후 요청마다 Authorization: Bearer로 보낸다. */
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): TokenResponse

    /** 내가 속한 프로젝트와 각 프로젝트에서의 역할. */
    @GET("projects")
    suspend fun listProjects(): List<ProjectSummary>

    @POST("projects")
    suspend fun createProject(@Body request: CreateProjectRequest): IdResponse
}

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
data class IdResponse(val id: String)
