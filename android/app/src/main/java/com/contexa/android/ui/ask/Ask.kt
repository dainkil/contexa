package com.contexa.android.ui.ask

import com.contexa.android.api.ArtifactStatus
import com.contexa.android.api.ArtifactView
import com.contexa.android.api.AskRequest
import com.contexa.android.api.GenKind

/* 질문 화면의 규칙. 화면과 떼어 두고 테스트한다. */

/** server가 받는 가장 긴 질문(generation/GenerationWiki.MAX_QUESTION). */
const val MAX_QUESTION = 2000

/** 답변을 기다리며 다시 열어 보는 횟수의 상한. 2초 간격이라 3분이다. */
const val MAX_POLLS = 90

/** 대화에 보여 주는 지난 질문 수의 상한. 최근 것부터 센다. */
const val MAX_TURNS = 20

const val STILL_WAITING_MESSAGE = "아직 답이 없습니다. AI 일꾼이 켜져 있는지 확인하세요."

/** 보낼 수 없는 질문이면 그 이유. 보낼 수 있으면 null. 빈 칸은 이유 없이 버튼만 막는다. */
fun questionError(text: String): String? =
    if (text.trim().length > MAX_QUESTION) "질문은 ${MAX_QUESTION}자까지 쓸 수 있습니다." else null

fun canAsk(text: String): Boolean = text.isNotBlank() && questionError(text) == null

/** 브리핑을 무엇에 대해 받을지 비워 두면 대신 보내는 요청. server는 글의 종류와 이 문장을 AI 일꾼에게 넘긴다. */
const val DEFAULT_BRIEFING = "새로 합류한 사람이 알아야 할 지금의 결정, 남은 조건, 할 일과 담당자를 정리해 주세요."

/** 보낼 수 있나. 브리핑은 비워 둬도 된다(DEFAULT_BRIEFING을 보낸다). */
fun canSend(text: String, briefing: Boolean): Boolean = if (briefing) questionError(text) == null else canAsk(text)

/** 입력 칸의 글로 보낼 요청을 만든다. 질문은 kind를 빼서 server가 ANSWER로 받게 한다. 답할 언어는 정하지 않는다. */
fun askRequest(text: String, briefing: Boolean): AskRequest = if (briefing) {
    AskRequest(kind = GenKind.BRIEFING, question = text.trim().ifEmpty { DEFAULT_BRIEFING })
} else {
    AskRequest(question = text.trim())
}

/** 답변이 아직 없어서 다시 열어 봐야 하나. */
fun isWaiting(view: ArtifactView?): Boolean = view?.status == ArtifactStatus.PENDING
