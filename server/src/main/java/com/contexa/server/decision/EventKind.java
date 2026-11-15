package com.contexa.server.decision;

/** 결정 사건의 내용 유형. 현재 결정이 될 수 있는 것은 확정과 변경뿐이다. */
public enum EventKind {
	PROPOSAL,
	DISCUSSION,
	DEFERRAL,
	DECISION,
	CHANGE,
	WITHDRAWAL;

	/** 이 유형의 사건이 "지금 유효한 결정"의 후보가 되는가. */
	public boolean decides() {
		return this == DECISION || this == CHANGE;
	}
}
