package com.contexa.android.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** 문서 올리기, 원문 읽기, 발언 정정, 실패한 작업 다시 맡기기. 기준: server api/DocumentController.java */
interface DocumentApi {

    /** 문서를 올린다. 올리면 "발언으로 자르기" 작업이 바로 맡겨진다. */
    @POST("projects/{projectId}/documents")
    suspend fun upload(@Path("projectId") projectId: String, @Body input: DocumentInput): IngestStarted

    /** 이미 있는 문서의 새 판을 올린다. 이전 판과 내용이 같으면 server가 409로 거절한다. */
    @POST("projects/{projectId}/documents/{documentId}/versions")
    suspend fun uploadVersion(
        @Path("projectId") projectId: String,
        @Path("documentId") documentId: String,
        @Body request: NewVersionRequest,
    ): IngestStarted

    /** 읽을 수 있는 문서. 최근에 올린 것부터, 가장 최근 판의 처리 상태와 함께. */
    @GET("projects/{projectId}/documents")
    suspend fun list(@Path("projectId") projectId: String): List<DocumentStatus>

    /**
     * 문서 한 건을 연다. 둘 다 null이면 가장 최근 판,
     * version을 주면 그 판, span(근거 발언 id)을 주면 그 발언이 들어 있는 판이다. 둘 중 하나만 준다.
     */
    @GET("projects/{projectId}/documents/{documentId}")
    suspend fun open(
        @Path("projectId") projectId: String,
        @Path("documentId") documentId: String,
        @Query("version") version: Int? = null,
        @Query("span") span: String? = null,
    ): DocumentView

    /** 발언 정정(전사 오류 고치기). 내가 본 버전이 지금 버전과 다르면 409 STALE. */
    @POST("projects/{projectId}/spans/{spanId}/revisions")
    suspend fun reviseSpan(
        @Path("projectId") projectId: String,
        @Path("spanId") spanId: String,
        @Body request: ReviseSpanRequest,
    ): Changed

    /**
     * 실패로 끝난 작업(자르기·뽑기)을 다시 맡긴다. 시도 횟수는 처음부터 다시 센다.
     * 그 작업을 시킨 사람이나 OWNER만 할 수 있고(아니면 403), 실패한 작업이 아니면 409다.
     * server는 작업 전체(Job)를 돌려주지만 화면에 필요한 칸만 받는다.
     */
    @POST("projects/{projectId}/jobs/{jobId}/retry")
    suspend fun retryJob(@Path("projectId") projectId: String, @Path("jobId") jobId: String): JobBrief
}

@Serializable
data class NewVersionRequest(val content: String)

@Serializable
data class ReviseSpanRequest(val expectedRevision: Int, val text: String)
