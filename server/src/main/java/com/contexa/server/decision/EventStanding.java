package com.contexa.server.decision;

/** 결정 사건이 지금도 살아 있는지. 확인된 대체·철회 줄을 받았는지로 계산한다. */
public enum EventStanding {
	ACTIVE,
	SUPERSEDED,
	WITHDRAWN
}
