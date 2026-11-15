package com.contexa.server.decision;

import com.contexa.server.contract.NodeType;

import java.util.UUID;

/** 내가 본 뒤에 다른 사람이 먼저 이 카드를 바꿨다. */
public class NodeRevisionConflictException extends RuntimeException {

	public NodeRevisionConflictException(NodeType type, UUID id, long expected, long actual) {
		super("카드 " + type + " " + id + "이 이미 바뀌었다: 기대한 버전 " + expected + ", 현재 버전 " + actual);
	}
}
