package com.contexa.server.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EdgeTest {

	private final UUID projectId = UUID.randomUUID();
	private final UUID spanId = UUID.randomUUID();
	private final NodeRef task = new NodeRef(NodeType.TASK, UUID.randomUUID(), 1);
	private final NodeRef condition = new NodeRef(NodeType.CONDITION, UUID.randomUUID(), 1);
	private final NodeRef span = new NodeRef(NodeType.SPAN, spanId, 1);
	private final NodeRef artifact = new NodeRef(NodeType.ARTIFACT, UUID.randomUUID(), 1);

	private Edge edge(RelType rel, NodeRef src, NodeRef dst, ReviewStatus status, UUID by, Instant at) {
		return new Edge(UUID.randomUUID(), projectId, rel, src, dst, status,
				List.of(spanId), by, at, Ontology.VERSION, 1);
	}

	@Test
	void 인쇄_발주가_예산_승인을_필요로_한다는_후보_줄을_만든다() {
		Edge e = edge(RelType.REQUIRES, task, condition, ReviewStatus.CANDIDATE, null, null);

		assertThat(e.src()).isEqualTo(task);
		assertThat(e.dst()).isEqualTo(condition);
		assertThat(e.evidenceSpanIds()).containsExactly(spanId);
	}

	@Test
	void 온톨로지에_없는_줄은_만들_수_없다() {
		assertThatThrownBy(() -> edge(RelType.REQUIRES, span, condition, ReviewStatus.CANDIDATE, null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("허용되지 않은 줄");
	}

	@Test
	void 자기_자신에게는_줄을_그을_수_없다() {
		NodeRef event = new NodeRef(NodeType.DECISION_EVENT, UUID.randomUUID(), 1);
		NodeRef sameEventNewerRevision = new NodeRef(NodeType.DECISION_EVENT, event.id(), 2);

		assertThatThrownBy(() -> edge(RelType.SUPERSEDES, event, sameEventNewerRevision,
				ReviewStatus.CANDIDATE, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 확인된_줄에는_확인자와_시각이_있어야_한다() {
		assertThatThrownBy(() -> edge(RelType.REQUIRES, task, condition, ReviewStatus.CONFIRMED, null, Instant.now()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("확인자");
		assertThatThrownBy(() -> edge(RelType.REQUIRES, task, condition, ReviewStatus.CONFIRMED,
				UUID.randomUUID(), null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("확인 시각");

		Edge confirmed = edge(RelType.REQUIRES, task, condition, ReviewStatus.CONFIRMED,
				UUID.randomUUID(), Instant.now());
		assertThat(confirmed.status()).isEqualTo(ReviewStatus.CONFIRMED);
	}

	@Test
	void 참고했다_줄은_확인자_없이_확정으로만_기록된다() {
		Edge uses = edge(RelType.USES, artifact, task, ReviewStatus.CONFIRMED, null, Instant.now());
		assertThat(uses.confirmedBy()).isNull();

		assertThatThrownBy(() -> edge(RelType.USES, artifact, task, ReviewStatus.CANDIDATE, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 근거_목록은_밖에서_바꿀_수_없다() {
		List<UUID> evidence = new ArrayList<>(List.of(spanId));
		Edge e = new Edge(UUID.randomUUID(), projectId, RelType.REQUIRES, task, condition,
				ReviewStatus.CANDIDATE, evidence, null, null, Ontology.VERSION, 1);

		evidence.add(UUID.randomUUID());

		assertThat(e.evidenceSpanIds()).containsExactly(spanId);
		assertThatThrownBy(() -> e.evidenceSpanIds().add(UUID.randomUUID()))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void 같은_카드는_버전이_달라도_같은_카드로_본다() {
		NodeRef newer = new NodeRef(NodeType.TASK, task.id(), 2);

		assertThat(task.sameNode(newer)).isTrue();
		assertThat(task).isNotEqualTo(newer);
		assertThat(task.sameNode(condition)).isFalse();
	}

	@Test
	void 카드_버전은_1부터_시작한다() {
		assertThatThrownBy(() -> new NodeRef(NodeType.TASK, UUID.randomUUID(), 0))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
