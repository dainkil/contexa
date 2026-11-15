package com.contexa.server.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 아직 저장되지 않은 카드 후보. AI나 외부 에이전트가 제출한다.
 * key는 같은 제출 안에서 이 카드를 가리키는 임시 이름이다(저장되면 NodeRef로 바뀐다).
 */
public record NodeDraft(String key, NodeType type, Map<String, Object> fields) {

	public NodeDraft {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("key가 비어 있다");
		}
		Objects.requireNonNull(type, "type");
		fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
	}
}
