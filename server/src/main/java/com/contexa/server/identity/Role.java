package com.contexa.server.identity;

/** 프로젝트 안에서의 역할. */
public enum Role {
	/** 구성원을 관리하고 검토도 한다. */
	OWNER,
	/** 후보를 확인·정정·보류·거절한다. */
	REVIEWER,
	/** 읽고, 자기 업무 상태를 바꾼다. */
	MEMBER;

	public boolean canReview() {
		return this == OWNER || this == REVIEWER;
	}
}
