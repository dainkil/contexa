package com.contexa.server.graph;

import java.util.UUID;

/** 내가 본 뒤에 다른 사람이 먼저 이 줄을 바꿨다. */
public class EdgeVersionConflictException extends RuntimeException {

	public EdgeVersionConflictException(UUID edgeId, long expected, long actual) {
		super("줄 " + edgeId + "이 이미 바뀌었다: 기대한 버전 " + expected + ", 현재 버전 " + actual);
	}
}
