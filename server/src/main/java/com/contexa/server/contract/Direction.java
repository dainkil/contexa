package com.contexa.server.contract;

/** 줄을 따라가는 방향. 줄은 항상 "기대는 쪽(src) → 기댈 대상(dst)"으로 그어져 있다. */
public enum Direction {
	/** 근거 쪽: src에서 dst로. */
	BASIS,
	/** 영향 쪽: dst에서 src로. */
	IMPACT
}
