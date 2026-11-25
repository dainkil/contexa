package com.contexa.android.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** 결정 정보 읽기. 권한과 상태 계산은 server가 한다. 기준: server api/ContextController.java */
interface ContextApi {

    /** 지금 무엇이 결정됐고 무엇이 남았나. */
    @GET("projects/{projectId}/context/state")
    suspend fun state(@Path("projectId") projectId: String, @Query("topicId") topicId: String? = null): ContextPacket

    /** 결정 항목 하나가 언제, 어떻게 바뀌었나. 사건은 일어난 순서로 온다. */
    @GET("projects/{projectId}/context/history/{decisionItemId}")
    suspend fun history(@Path("projectId") projectId: String, @Path("decisionItemId") decisionItemId: String): ContextPacket

    /** 한 카드에서 줄을 따라간다. BASIS = 근거 쪽, IMPACT = 영향 쪽. */
    @GET("projects/{projectId}/context/trace")
    suspend fun trace(
        @Path("projectId") projectId: String,
        @Query("type") type: NodeType,
        @Query("id") id: String,
        @Query("direction") direction: Direction = Direction.BASIS,
        @Query("depth") depth: Int = 2,
    ): ContextPacket

    /**
     * 이 이름 표기에 해당할 만한 인물·기관. 여럿이면 open에 AMBIGUOUS_NAME이 붙는다.
     * server는 하나로 고르지 않고, 앱도 합치지 않는다. lang을 주면 그 언어의 표기만 본다.
     */
    @GET("projects/{projectId}/context/entities")
    suspend fun resolveEntity(
        @Path("projectId") projectId: String,
        @Query("surface") surface: String,
        @Query("lang") lang: String? = null,
    ): ContextPacket

    /** 이름 카드: 언어별 표기 전부와 이 인물을 언급한 발언. */
    @GET("projects/{projectId}/context/entities/{entityId}")
    suspend fun nameCard(@Path("projectId") projectId: String, @Path("entityId") entityId: String): NameCard

    /** 누가 무엇을 해야 하나. mine=true면 내 계정에 연결된 인물에게 맡겨진 할 일만. */
    @GET("projects/{projectId}/context/tasks")
    suspend fun tasks(@Path("projectId") projectId: String, @Query("mine") mine: Boolean = false): ContextPacket

    /** 할 일의 진행 상태를 바꾼다. 구성원이면 누구나. 확인된 할 일만 된다. 기준: api/ReviewController.java */
    @POST("projects/{projectId}/tasks/{taskId}/status")
    suspend fun taskStatus(
        @Path("projectId") projectId: String,
        @Path("taskId") taskId: String,
        @Body request: TaskStatusRequest,
    ): Changed

    /** 담당자 본인이 할 일을 수락하거나 거절한다. */
    @POST("projects/{projectId}/tasks/{taskId}/acceptance")
    suspend fun acceptance(
        @Path("projectId") projectId: String,
        @Path("taskId") taskId: String,
        @Body request: AcceptanceRequest,
    ): Changed

    /**
     * 이 인물이 이 이메일의 계정이라고 확인한다. email이 null이면 연결을 끊는다. 소유자·검토자만.
     * 확인된 인물 카드에만, 이 프로젝트 구성원의 계정만 연결된다. 한 계정은 한 인물에게만.
     */
    @POST("projects/{projectId}/entities/{entityId}/account")
    suspend fun linkAccount(
        @Path("projectId") projectId: String,
        @Path("entityId") entityId: String,
        @Body request: AccountRequest,
    ): Changed

    /**
     * 이 언어로는 이렇게 적는다고 확인하거나(confirmed=true) 틀린 표기라고 거절한다. 소유자·검토자만.
     * 거절된 표기는 이름 찾기에 쓰이지 않는다. 다시 확인하면 되살아난다.
     */
    @POST("projects/{projectId}/entities/{entityId}/names")
    suspend fun name(
        @Path("projectId") projectId: String,
        @Path("entityId") entityId: String,
        @Body request: NameRequest,
    ): Changed
}

/** expectedRevision = 내가 읽은 할 일의 revision. 그사이 바뀌었으면 409(STALE). */
@Serializable
data class TaskStatusRequest(val status: TaskStatus, val expectedRevision: Int)

@Serializable
data class AcceptanceRequest(val accepted: Boolean)

/** evidenceSpanId = 이 표기가 나온 발언. 확인할 때만 보내고, 없어도 된다. */
@Serializable
data class NameRequest(val lang: String, val form: String, val confirmed: Boolean, val evidenceSpanId: String? = null)

/** email을 빼면(null) 연결을 끊는다. */
@Serializable
data class AccountRequest(val email: String? = null)
