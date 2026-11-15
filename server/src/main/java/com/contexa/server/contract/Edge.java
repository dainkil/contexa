package com.contexa.server.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 줄 하나. src가 dst에 기댄다.
 * 양끝은 카드의 type과 id로 이어지고, 각 NodeRef의 revision은 줄을 긋거나 확인할 때 본 버전이다.
 * version은 이 줄 자체가 바뀐 횟수로, 동시 수정을 막는 데 쓴다.
 */
public record Edge(
		UUID id,
		UUID projectId,
		RelType rel,
		NodeRef src,
		NodeRef dst,
		ReviewStatus status,
		List<UUID> evidenceSpanIds,
		UUID confirmedBy,
		Instant confirmedAt,
		String ontologyVersion,
		long version) {

	public Edge {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(projectId, "projectId");
		Objects.requireNonNull(rel, "rel");
		Objects.requireNonNull(src, "src");
		Objects.requireNonNull(dst, "dst");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(ontologyVersion, "ontologyVersion");
		evidenceSpanIds = evidenceSpanIds == null ? List.of() : List.copyOf(evidenceSpanIds);

		if (!Ontology.isAllowed(rel, src.type(), dst.type())) {
			throw new IllegalArgumentException(
					"허용되지 않은 줄: " + src.type() + " -" + rel + "-> " + dst.type());
		}
		if (src.sameNode(dst)) {
			throw new IllegalArgumentException("카드는 자기 자신에게 줄을 그을 수 없다: " + rel);
		}
		if (version < 1) {
			throw new IllegalArgumentException("version은 1 이상이어야 한다: " + version);
		}
		if (rel.isSystemRecorded() && status != ReviewStatus.CONFIRMED) {
			throw new IllegalArgumentException(rel + " 줄은 시스템 기록이므로 CONFIRMED여야 한다");
		}
		if (status == ReviewStatus.CONFIRMED) {
			if (confirmedAt == null) {
				throw new IllegalArgumentException("확인된 줄에는 확인 시각이 있어야 한다");
			}
			if (confirmedBy == null && !rel.isSystemRecorded()) {
				throw new IllegalArgumentException("확인된 줄에는 확인자가 있어야 한다");
			}
		}
	}
}
