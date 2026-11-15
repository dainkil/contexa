package com.contexa.server.contract;

/** 카드와 줄이 함께 쓰는 확인 상태. CANDIDATE는 AI 후보(연필), CONFIRMED는 사람이 확인한 것(도장). */
public enum ReviewStatus {
	CANDIDATE,
	CONFIRMED,
	HELD,
	REJECTED,
	RETRACTED;

	/**
	 * 허용되는 상태 변화: 후보·보류 → 확인·보류·거절, 확인 → 철회.
	 * 거절·철회는 되돌리지 않는다(다시 제안하면 새 카드·새 줄이 된다). 같은 상태로는 갈 수 없다.
	 */
	public boolean canMoveTo(ReviewStatus next) {
		if (next == null || next == this) {
			return false;
		}
		return switch (this) {
			case CANDIDATE, HELD -> next == CONFIRMED || next == HELD || next == REJECTED;
			case CONFIRMED -> next == RETRACTED;
			case REJECTED, RETRACTED -> false;
		};
	}
}
