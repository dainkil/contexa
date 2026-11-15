package com.contexa.server.contract;

import java.util.Objects;
import java.util.UUID;

/** 카드 하나를 가리키는 참조. revision은 "이 참조를 만들 때 본 버전"이다. */
public record NodeRef(NodeType type, UUID id, long revision) {

	public NodeRef {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(id, "id");
		if (revision < 1) {
			throw new IllegalArgumentException("revision은 1 이상이어야 한다: " + revision);
		}
	}

	/** 버전을 뺀 카드의 정체. */
	public NodeKey key() {
		return new NodeKey(type, id);
	}

	/** 버전과 무관하게 같은 카드인가. 줄은 이 기준으로 이어진다. */
	public boolean sameNode(NodeRef other) {
		return other != null && key().equals(other.key());
	}
}
