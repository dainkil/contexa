package com.contexa.server.contract;

import java.util.Objects;
import java.util.UUID;

/** 줄 하나를 가리키는 참조. version은 "이 참조를 만들 때 본 버전"이다. */
public record EdgeRef(UUID id, long version) {

	public EdgeRef {
		Objects.requireNonNull(id, "id");
		if (version < 1) {
			throw new IllegalArgumentException("version은 1 이상이어야 한다: " + version);
		}
	}
}
