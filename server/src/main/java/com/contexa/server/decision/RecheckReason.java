package com.contexa.server.decision;

/** 카드를 다시 확인해야 하게 만든 일. 결재 창구의 어느 쓰기에서 나왔나. */
public enum RecheckReason {
	/** 검토 묶음 처리(확인·보류·거절·철회·정정). */
	REVIEW,
	/** 인물·기관의 표기 확인·거절. */
	NAME,
	/** 인물과 계정의 연결. */
	ACCOUNT,
	/** 조건 충족 확인. */
	CONDITION,
	/** 발언 정정. */
	SPAN_REVISION
}
