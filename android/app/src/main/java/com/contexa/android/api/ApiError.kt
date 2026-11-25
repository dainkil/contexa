package com.contexa.android.api

import java.io.IOException
import kotlinx.serialization.Serializable
import retrofit2.HttpException

const val GENERIC_ERROR_MESSAGE = "요청을 처리하지 못했습니다. 잠시 뒤에 다시 해 주세요."
const val NETWORK_ERROR_MESSAGE = "연결할 수 없습니다. 인터넷 연결을 확인하고 다시 해 주세요."

/** 409 STALE: 내가 본 뒤에 다른 사람이 먼저 바꿨다. server 문장에는 id·버전 번호가 들어 있어 대신 쓴다. */
const val STALE_MESSAGE = "그사이 다른 사람이 먼저 바꿨습니다. 새로 불러온 내용을 보고 다시 해 주세요."

const val QUOTA_MESSAGE = "오늘 맡길 수 있는 AI 작업을 모두 썼습니다. 내일 다시 해 주세요."
const val SERVER_TROUBLE_MESSAGE = "잠시 문제가 생겼습니다. 조금 뒤에 다시 해 주세요."

/**
 * server의 오류 응답 본문(ApiExceptionHandler).
 * error는 NOT_FOUND, BAD_REQUEST, UNAUTHENTICATED, FORBIDDEN, STALE, NOT_POSSIBLE,
 * TOO_MANY_ATTEMPTS(로그인을 너무 많이 틀림, 429), QUOTA_EXCEEDED(하루 AI 작업 수를 다 씀, 429), INTERNAL(500) 중 하나다.
 * message는 개발자용 문장이라(끝에 id가 붙기도 한다) 그대로 보여 주지 않고 아래 표로 바꾼다.
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

/**
 * server 문장 → 사용자에게 보여 줄 문장. 앞부분이 같으면 같은 뜻이다(뒤에 ": id"가 붙는 문장이 있다).
 * 표에 없는 문장은 오류 코드로 정한 문장을 쓴다.
 */
private val KNOWN_MESSAGES: List<Pair<String, String>> = listOf(
    "이메일 또는 비밀번호가 맞지 않는다" to "이메일 또는 비밀번호가 맞지 않습니다.",
    "초대된 이메일만 가입할 수 있다" to "초대받은 이메일로만 가입할 수 있습니다.",
    "이미 가입된 이메일이다" to "이미 가입된 이메일입니다. 로그인해 주세요.",
    "이메일 형식이 올바르지 않다" to "이메일 형식이 올바르지 않습니다.",
    "이름이 비어 있다" to "이름을 입력해 주세요.",
    "프로젝트 제목이 비어 있다" to "프로젝트 이름을 입력해 주세요.",
    "비밀번호가 맞지 않는다" to "비밀번호가 맞지 않습니다.",
    "가입하지 않은 이메일이다" to "아직 가입하지 않은 이메일입니다. 먼저 가입하도록 알려 주세요.",
    "프로젝트의 OWNER만 할 수 있다" to "프로젝트 소유자만 할 수 있습니다.",
    "확인·정정은 프로젝트의 OWNER나 REVIEWER만 할 수 있다" to "확인·정정은 소유자나 검토자만 할 수 있습니다.",
    "이 프로젝트의 구성원이 아니다" to "이 프로젝트의 구성원이 아닙니다.",
    "프로젝트 구성원만" to "이 프로젝트의 구성원만 할 수 있습니다.",
    "프로젝트에는 OWNER가 한 명 이상 있어야 한다" to "프로젝트에는 소유자가 한 명 이상 있어야 합니다.",
    "카드가 확인되지 않았으면 딸린 줄도 확인할 수 없다" to "카드를 확인하지 않으면 딸린 줄도 확인할 수 없습니다.",
    "확인된 근거 줄이 하나도 없으면 카드를 확인 상태로 둘 수 없다" to "근거 줄을 하나 이상 확인해야 카드를 확인할 수 있습니다.",
    "건너편 카드가 없거나 거절·철회됐다" to "이 줄이 가리키는 카드가 거절되거나 철회되어 확인할 수 없습니다.",
    "거절되거나 철회된 카드는 정정할 수 없다" to "거절되거나 철회된 카드는 고칠 수 없습니다.",
    "거절되거나 철회된 카드는 바꿀 수 없다" to "거절되거나 철회된 카드는 바꿀 수 없습니다.",
    "근거 없이는 줄을 확인할 수 없다" to "근거 발언이 없는 줄은 확인할 수 없습니다.",
    "정정할 값이 없다" to "바뀐 내용이 없습니다.",
    "이미 같은 내용의 판이 있다" to "같은 내용의 판이 이미 있습니다.",
    "실패로 끝난 작업만 다시 맡길 수 있다" to "실패한 작업만 다시 시도할 수 있습니다.",
    "작업을 시킨 사람이나 프로젝트 OWNER만 다시 맡길 수 있다" to "작업을 맡긴 사람이나 프로젝트 소유자만 다시 시도할 수 있습니다.",
    "이 할 일의 담당자로 확인된 사람이 아니다" to "이 할 일의 담당자로 확인된 사람만 할 수 있습니다.",
    "이 계정은 확인된 인물 카드에 연결되어 있지 않다" to "내 계정이 확인된 인물 카드에 연결되어 있지 않습니다.",
    "이 계정은 이미 다른 인물 카드에 연결되어 있다" to "내 계정이 이미 다른 인물 카드에 연결되어 있습니다.",
    "확인되지 않은 할 일은 진행 상태나 수락을 바꿀 수 없다" to "확인된 할 일만 상태를 바꾸거나 수락할 수 있습니다.",
    "확인되지 않은 조건은 충족으로 바꿀 수 없다" to "확인된 조건만 충족으로 바꿀 수 있습니다.",
    "이미 그 상태다" to "이미 그 상태입니다.",
    "이 카드에는 다시 확인할 것이 남아 있지 않다" to "이 카드에는 다시 확인할 것이 남아 있지 않습니다.",
    "문서 제목이 비어 있다" to "문서 제목을 입력해 주세요.",
    "문서 내용이 비어 있다" to "문서 내용이 비어 있습니다.",
    "발언 내용이 비어 있다" to "발언 내용을 입력해 주세요.",
    "기타 이유로 신고할 때는 내용을 적어 주세요" to "기타 이유로 신고할 때는 내용을 적어 주세요.",
)

/** 숫자가 들어 있는 server 문장. 숫자는 그대로 옮긴다. */
private val NUMBERED_MESSAGES: List<Pair<Regex, (String) -> String>> = listOf(
    Regex("^비밀번호는 (\\d+)자 이상이어야 한다") to { n -> "비밀번호는 ${n}자 이상이어야 합니다." },
    Regex("^질문은 1~(\\d+)자여야 한다") to { n -> "질문은 ${n}자까지 쓸 수 있습니다." },
    Regex("^신고 내용은 (\\d+)자까지 쓸 수 있다") to { n -> "신고 내용은 ${n}자까지 쓸 수 있습니다." },
)

private fun knownMessage(message: String?): String? {
    if (message.isNullOrBlank()) return null
    KNOWN_MESSAGES.firstOrNull { message.startsWith(it.first) }?.let { return it.second }
    for ((pattern, sentence) in NUMBERED_MESSAGES) {
        pattern.find(message)?.let { return sentence(it.groupValues[1]) }
    }
    return null
}

/** 로그인 제한이 풀리기까지 남은 시간. 분 단위로 올려 적는다. */
private fun tooManyAttempts(retryAfterSeconds: Long?): String {
    val wait = retryAfterSeconds?.takeIf { it > 0 }?.let { "${maxOf(1, (it + 59) / 60)}분 뒤에" } ?: "잠시 뒤에"
    return "로그인을 여러 번 틀렸습니다. $wait 다시 해 주세요."
}

/**
 * 오류 응답을 사용자에게 보여 줄 문장으로 바꾼다. 화면과 떼어 두고 테스트한다.
 *
 * @param status            HTTP 상태 코드. 모르면 null
 * @param body              응답 본문
 * @param retryAfterSeconds Retry-After 헤더(초). 로그인 제한(429)에서만 쓴다
 */
fun errorMessage(status: Int?, body: String?, retryAfterSeconds: Long? = null): String {
    val error = parseApiError(body)
    when (error?.error) {
        "STALE" -> return STALE_MESSAGE
        "TOO_MANY_ATTEMPTS" -> return tooManyAttempts(retryAfterSeconds)
        "QUOTA_EXCEEDED" -> return QUOTA_MESSAGE
        "INTERNAL" -> return SERVER_TROUBLE_MESSAGE
    }
    knownMessage(error?.message)?.let { return it }
    return when (error?.error) {
        "UNAUTHENTICATED" -> "다시 로그인해 주세요."
        "FORBIDDEN" -> "이 일을 할 권한이 없습니다."
        "NOT_FOUND" -> "찾을 수 없습니다. 지워졌거나 볼 수 없는 내용입니다."
        "BAD_REQUEST" -> "입력한 내용을 다시 확인해 주세요."
        "NOT_POSSIBLE" -> "지금 상태에서는 할 수 없습니다."
        else -> when {
            status == null -> GENERIC_ERROR_MESSAGE
            status >= 500 -> SERVER_TROUBLE_MESSAGE
            status == 429 -> "요청이 너무 많습니다. 잠시 뒤에 다시 해 주세요."
            status == 401 -> "다시 로그인해 주세요."
            status == 403 -> "이 일을 할 권한이 없습니다."
            status == 404 -> "찾을 수 없습니다. 지워졌거나 볼 수 없는 내용입니다."
            else -> GENERIC_ERROR_MESSAGE
        }
    }
}

/** 상태 코드 없이 본문만으로 사용자에게 보여 줄 문장을 정한다. */
fun messageForErrorBody(body: String?): String = errorMessage(null, body)

/** server 오류를 사용자에게 보여 줄 문장으로 바꾼다. */
fun userMessage(error: Throwable): String = when (error) {
    is HttpException -> errorMessage(
        error.code(),
        errorBody(error),
        error.response()?.headers()?.get("Retry-After")?.trim()?.toLongOrNull(),
    )
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
