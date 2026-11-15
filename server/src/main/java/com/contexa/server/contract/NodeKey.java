package com.contexa.server.contract;

import java.util.Objects;
import java.util.UUID;

/** 버전을 뺀 카드의 정체. 줄은 이 기준으로 이어진다. */
public record NodeKey(NodeType type, UUID id) {

	public NodeKey {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(id, "id");
	}
}
