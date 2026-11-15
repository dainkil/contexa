package com.contexa.server.generation;

/** 산출물의 종류. */
public enum GenKind {
	/** 질문에 대한 답변. */
	ANSWER,
	/** 현재 상황을 정리한 브리핑. */
	BRIEFING,
	/** 메일·공지 같은 글의 초안. */
	DRAFT
}
