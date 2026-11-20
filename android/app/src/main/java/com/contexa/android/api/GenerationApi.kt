package com.contexa.android.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** 질문하고 답변(글)을 읽는다. 기준: server api/GenerationController.java */
interface GenerationApi {

    /** 질문한다. 바로 돌아오고, 답변은 AI 일꾼이 쓴 뒤에 생긴다. */
    @POST("projects/{projectId}/generations")
    suspend fun ask(@Path("projectId") projectId: String, @Body request: AskRequest): GenerationRequested

    /** 내가 요청한 글. 최근 것부터. 다른 사람의 글은 오지 않는다. */
    @GET("projects/{projectId}/generations")
    suspend fun list(@Path("projectId") projectId: String): List<ArtifactSummary>

    /** 글 하나를 연다. 참고한 카드가 그사이 바뀌었으면 stale에 담겨 나온다. */
    @GET("projects/{projectId}/generations/{artifactId}")
    suspend fun get(@Path("projectId") projectId: String, @Path("artifactId") artifactId: String): ArtifactView
}

/** kind를 빼면 server가 ANSWER로, lang을 빼면 ko로 본다. */
@Serializable
data class AskRequest(val kind: GenKind? = null, val question: String, val lang: String? = null)
