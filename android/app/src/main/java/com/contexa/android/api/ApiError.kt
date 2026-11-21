package com.contexa.android.api

import java.io.IOException
import kotlinx.serialization.Serializable
import retrofit2.HttpException

const val GENERIC_ERROR_MESSAGE = "요청을 처리하지 못했습니다."
const val NETWORK_ERROR_MESSAGE = "server에 연결할 수 없습니다. server가 켜져 있는지 확인하세요."

/** 409 STALE: 내가 본 뒤에 다른 사람이 먼저 바꿨다. server 문장에는 id·버전 번호가 들어 있어 대신 쓴다. */
const val STALE_MESSAGE = "그사이 다른 사람이 먼저 바꿨습니다. 새로 불러온 내용을 보고 다시 해 주세요."

/**
 * server의 오류 응답 본문(ApiExceptionHandler).
 * error는 NOT_FOUND, BAD_REQUEST, UNAUTHENTICATED, FORBIDDEN, STALE, NOT_POSSIBLE 중 하나다.
 */
@Serializable
data class ApiError(val error: String? = null, val message: String? = null)

/** 오류 응답 본문을 푼다. server의 형식이 아니면 null. */
fun parseApiError(body: String?): ApiError? {
    if (body.isNullOrBlank()) return null
    return try {
        contexaJson.decodeFromString<ApiError>(body)
    } catch (_: Exception) {
        null
    }
}

/** 상태 코드 없이 본문만으로 사용자에게 보여 줄 문장을 정한다. */
fun messageForErrorBody(body: String?): String {
    val error = parseApiError(body) ?: return GENERIC_ERROR_MESSAGE
    if (error.error == "STALE") return STALE_MESSAGE
    return error.message?.takeIf { it.isNotEmpty() } ?: GENERIC_ERROR_MESSAGE
}

/** server 오류를 사용자에게 보여 줄 문장으로 바꾼다. */
fun userMessage(error: Throwable): String = when (error) {
    is HttpException -> messageForErrorBody(errorBody(error))
    is IOException -> NETWORK_ERROR_MESSAGE
    else -> GENERIC_ERROR_MESSAGE
}

/** 다른 사람이 먼저 바꿔서 거절됐는가(409 STALE). 화면은 이때 새로 불러온다. */
fun isStale(error: Throwable): Boolean =
    error is HttpException && parseApiError(errorBody(error))?.error == "STALE"

private fun errorBody(error: HttpException): String? = try {
    // 본문은 한 번만 읽을 수 있으므로 복사본을 본다.
    error.response()?.errorBody()?.source()?.peek()?.readUtf8()
} catch (_: IOException) {
    null
}
