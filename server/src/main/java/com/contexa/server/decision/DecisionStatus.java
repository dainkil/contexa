package com.contexa.server.decision;

/** 결정 항목의 현재 상태. 줄과 사건 카드에서 계산한다. */
public enum DecisionStatus {
	/** 확인된 확정·변경 사건이 아직 없다. 제안·논의만 있는 경우도 여기다. */
	UNDECIDED,
	/** 살아 있는 확정·변경 사건이 정확히 하나다. 그것이 현재 결정이다. */
	DECIDED,
	/** 살아 있는 확정·변경 사건이 둘 이상이다. 사람이 대체 줄을 확인해야 하나로 정해진다. */
	CONTESTED,
	/** 확정·변경 사건이 있었지만 모두 대체되거나 철회되어 지금 유효한 것이 없다. */
	WITHDRAWN
}
