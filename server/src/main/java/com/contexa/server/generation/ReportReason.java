package com.contexa.server.generation;

/** AI가 쓴 산출물을 신고하는 이유. */
public enum ReportReason {
	/** 사실과 다르다. */
	WRONG,
	/** 근거 발언과 맞지 않거나 근거가 없다. */
	UNSUPPORTED,
	/** 부적절하거나 불쾌한 내용이다. */
	OFFENSIVE,
	/** 그 밖의 이유. note에 적는다. */
	OTHER
}
