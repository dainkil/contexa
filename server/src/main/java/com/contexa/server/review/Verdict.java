package com.contexa.server.review;

/** 검토자가 카드나 줄에 내리는 판단. */
public enum Verdict {
	/** 확인: 후보·보류 → 확인됨 */
	CONFIRM,
	/** 보류: 후보 → 보류 */
	HOLD,
	/** 거절: 후보·보류 → 거절됨 */
	REJECT,
	/** 철회: 확인됨 → 철회됨 */
	RETRACT
}
