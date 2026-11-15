package com.contexa.server.contract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OntologyTest {

	@Test
	void 할_일은_조건을_필요로_할_수_있다() {
		assertThat(Ontology.isAllowed(RelType.REQUIRES, NodeType.TASK, NodeType.CONDITION)).isTrue();
	}

	@Test
	void 발언은_조건을_필요로_할_수_없다() {
		assertThat(Ontology.isAllowed(RelType.REQUIRES, NodeType.SPAN, NodeType.CONDITION)).isFalse();
	}

	@Test
	void 할_일은_다른_할_일을_필요로_할_수_있다() {
		assertThat(Ontology.isAllowed(RelType.REQUIRES, NodeType.TASK, NodeType.TASK)).isTrue();
	}

	@Test
	void 결정_항목은_조건만_필요로_할_수_있고_할_일은_안_된다() {
		assertThat(Ontology.isAllowed(RelType.REQUIRES, NodeType.DECISION_ITEM, NodeType.CONDITION)).isTrue();
		assertThat(Ontology.isAllowed(RelType.REQUIRES, NodeType.DECISION_ITEM, NodeType.TASK)).isFalse();
	}

	@Test
	void 조건은_발언이나_할_일로_충족될_수_있다() {
		assertThat(Ontology.isAllowed(RelType.SATISFIED_BY, NodeType.CONDITION, NodeType.SPAN)).isTrue();
		assertThat(Ontology.isAllowed(RelType.SATISFIED_BY, NodeType.CONDITION, NodeType.TASK)).isTrue();
		assertThat(Ontology.isAllowed(RelType.SATISFIED_BY, NodeType.CONDITION, NodeType.DECISION_EVENT)).isFalse();
		assertThat(Ontology.isAllowed(RelType.SATISFIED_BY, NodeType.TASK, NodeType.CONDITION)).isFalse();
	}

	@Test
	void 줄의_방향이_반대면_거절한다() {
		assertThat(Ontology.isAllowed(RelType.EVIDENCED_BY, NodeType.CONDITION, NodeType.SPAN)).isTrue();
		assertThat(Ontology.isAllowed(RelType.EVIDENCED_BY, NodeType.SPAN, NodeType.CONDITION)).isFalse();
	}

	@Test
	void 답변은_답변을_뺀_모든_카드를_참고할_수_있다() {
		for (NodeType target : NodeType.values()) {
			boolean expected = target != NodeType.ARTIFACT;
			assertThat(Ontology.isAllowed(RelType.USES, NodeType.ARTIFACT, target))
					.as("ARTIFACT -USES-> %s", target)
					.isEqualTo(expected);
		}
	}

	@Test
	void 답변이_아닌_카드는_참고했다_줄을_그을_수_없다() {
		assertThat(Ontology.isAllowed(RelType.USES, NodeType.TASK, NodeType.SPAN)).isFalse();
	}

	@Test
	void 모든_줄_종류에_허용_조합이_하나_이상_있다() {
		for (RelType rel : RelType.values()) {
			boolean any = false;
			for (NodeType src : NodeType.values()) {
				for (NodeType dst : NodeType.values()) {
					any |= Ontology.isAllowed(rel, src, dst);
				}
			}
			assertThat(any).as("%s의 허용 조합", rel).isTrue();
		}
	}

	@Test
	void 값이_비어_있으면_거절한다() {
		assertThat(Ontology.isAllowed(null, NodeType.TASK, NodeType.CONDITION)).isFalse();
		assertThat(Ontology.isAllowed(RelType.REQUIRES, null, NodeType.CONDITION)).isFalse();
		assertThat(Ontology.isAllowed(RelType.REQUIRES, NodeType.TASK, null)).isFalse();
	}

	@Test
	void 충돌_줄만_근거_영향_탐색에서_빠진다() {
		for (RelType rel : RelType.values()) {
			assertThat(rel.isDependency()).as("%s", rel).isEqualTo(rel != RelType.CONFLICTS_WITH);
		}
	}
}
