package com.contexa.android.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** 검토함 조회와 판단. 기준: server api/ReviewController.java */
interface ReviewApi {

    /**
     * 검토함 한 쪽. OPEN은 오래된 묶음부터, DONE은 최근에 바뀐 묶음부터 온다.
     *
     * @param limit 한 쪽에 훑을 묶음 수. server 기본 50, 최대 200.
     * @param after 앞 쪽의 next.
     */
    @GET("projects/{projectId}/reviews")
    suspend fun list(
        @Path("projectId") projectId: String,
        @Query("state") state: ReviewState,
        @Query("ownerType") ownerType: NodeType? = null,
        @Query("limit") limit: Int? = null,
        @Query("after") after: String? = null,
    ): ReviewPage

    /** 묶음을 확인·보류·거절·철회·정정한다. */
    @POST("projects/{projectId}/reviews/{reviewId}/actions")
    suspend fun act(
        @Path("projectId") projectId: String,
        @Path("reviewId") reviewId: String,
        @Body action: ReviewActionRequest,
    ): ReviewResult
}

@Serializable
data class ReviewActionRequest(
    /** 카드 자체에 대한 판단. 없으면 줄만 처리한다. */
    val card: Verdict? = null,
    /** 카드 내용 정정(필드 → 새 값). 지운 날짜는 JSON null이다. */
    val corrections: Map<String, JsonElement>? = null,
    /** 줄 id → 판단 */
    val edges: Map<String, Verdict>? = null,
    /** 내가 읽은 묶음 버전. 그사이 바뀌었으면 409(STALE). */
    val expectedVersion: Int,
    /** 같은 요청을 두 번 보내도 한 번만 처리되게 하는 키. */
    val idempotencyKey: String,
)
