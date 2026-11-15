package com.contexa.server.contract;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 아직 저장되지 않은 줄 후보. 양끝은 각각 둘 중 하나로 가리킨다:
 * 같은 제출에 들어 있는 새 카드는 key로, 이미 있는 카드는 NodeRef로.
 */
public record EdgeDraft(RelType rel, String srcKey, NodeRef srcRef, String dstKey, NodeRef dstRef,
		List<UUID> evidenceSpanIds) {

	public EdgeDraft {
		Objects.requireNonNull(rel, "rel");
		if ((srcKey == null) == (srcRef == null)) {
			throw new IllegalArgumentException("줄의 출발 카드는 key와 참조 중 하나로만 가리킨다: " + rel);
		}
		if ((dstKey == null) == (dstRef == null)) {
			throw new IllegalArgumentException("줄의 도착 카드는 key와 참조 중 하나로만 가리킨다: " + rel);
		}
		evidenceSpanIds = evidenceSpanIds == null ? List.of() : List.copyOf(evidenceSpanIds);
	}

	/** 새 카드에서 새 카드로. */
	public static EdgeDraft between(RelType rel, String srcKey, String dstKey, List<UUID> evidence) {
		return new EdgeDraft(rel, srcKey, null, dstKey, null, evidence);
	}

	/** 새 카드에서 이미 있는 카드로. */
	public static EdgeDraft toExisting(RelType rel, String srcKey, NodeRef dst, List<UUID> evidence) {
		return new EdgeDraft(rel, srcKey, null, null, dst, evidence);
	}

	/** 이미 있는 카드에서 새 카드로. */
	public static EdgeDraft fromExisting(RelType rel, NodeRef src, String dstKey, List<UUID> evidence) {
		return new EdgeDraft(rel, null, src, dstKey, null, evidence);
	}

	/** 이미 있는 카드 사이. */
	public static EdgeDraft existing(RelType rel, NodeRef src, NodeRef dst, List<UUID> evidence) {
		return new EdgeDraft(rel, null, src, null, dst, evidence);
	}
}
