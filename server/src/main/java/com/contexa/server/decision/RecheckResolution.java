package com.contexa.server.decision;

/** 다시 확인할 카드를 어떻게 닫았나. */
public enum RecheckResolution {
	/** 다시 봤고 바뀐 것이 없다. */
	KEPT,
	/** 그 카드의 검토 묶음을 다시 처리했다. */
	REVIEWED
}
