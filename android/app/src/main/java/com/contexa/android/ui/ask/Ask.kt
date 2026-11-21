package com.contexa.android.ui.ask

import com.contexa.android.api.ArtifactStatus
import com.contexa.android.api.ArtifactView

/* 질문·답변 화면의 규칙. 화면과 떼어 두고 테스트한다. */

/** server가 받는 가장 긴 질문(generation/GenerationWiki.MAX_QUESTION). */
const val MAX_QUESTION = 2000

/** 답변을 기다리며 다시 열어 보는 횟수의 상한. 2초 간격이라 3분이다. */
const val MAX_POLLS = 90

/** 브리핑을 받을 때 보내는 질문. server는 브리핑에도 질문 글을 요구한다. */
const val BRIEFING_QUESTION = "지금까지 결정된 것, 남은 것, 할 일을 정리해 주세요."

const val STILL_WAITING_MESSAGE = "아직 답이 없습니다. AI 일꾼이 켜져 있는지 확인하세요."

/** 보낼 수 없는 질문이면 그 이유. 보낼 수 있으면 null. 빈 칸은 이유 없이 버튼만 막는다. */
fun questionError(text: String): String? =
    if (text.trim().length > MAX_QUESTION) "질문은 ${MAX_QUESTION}자까지 쓸 수 있습니다." else null

fun canAsk(text: String): Boolean = text.isNotBlank() && questionError(text) == null

/** 답변이 아직 없어서 다시 열어 봐야 하나. */
fun isWaiting(view: ArtifactView?): Boolean = view?.status == ArtifactStatus.PENDING
