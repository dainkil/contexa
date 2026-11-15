package com.contexa.server.contract;

/** 줄의 종류. 방향은 모두 "기대는 쪽 → 기댈 대상". */
public enum RelType {
	HAS_ITEM,
	HAS_EVENT,
	SUPERSEDES,
	WITHDRAWS,
	REQUIRES,
	SATISFIED_BY,
	DERIVED_FROM,
	ASSIGNED_TO,
	EVIDENCED_BY,
	SPOKEN_BY,
	MENTIONS,
	USES,
	CONFLICTS_WITH;

	/** 근거·영향 탐색에서 따라갈 수 있는 줄인가. 충돌 표시는 "기댄다"는 뜻이 없다. */
	public boolean isDependency() {
		return this != CONFLICTS_WITH;
	}

	/** 시스템이 기록하는 줄인가. 검토 없이 확정 상태로 저장된다. */
	public boolean isSystemRecorded() {
		return this == USES;
	}
}
