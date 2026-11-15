package com.contexa.server.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Direction;
import com.contexa.server.contract.Edge;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.Ontology;
import com.contexa.server.contract.RelType;
import com.contexa.server.contract.ReviewStatus;

/**
 * "인쇄 진행해도 돼?" 장면의 카드와 줄로 게시판 관리를 확인한다.
 *
 * <pre>
 *   답변 -USES-> 인쇄 발주 -REQUIRES-> 예산 승인 -EVIDENCED_BY-> 22번 발언
 *                    └-EVIDENCED_BY-> 23번 발언
 * </pre>
 *
 * 테스트마다 트랜잭션을 되돌리므로 DB에 흔적이 남지 않는다.
 */
@SpringBootTest
@Transactional
class DecisionGraphTest {

	private static final Set<ReviewStatus> ANY_ACTIVE =
			EnumSet.of(ReviewStatus.CANDIDATE, ReviewStatus.CONFIRMED, ReviewStatus.HELD);
	private static final Set<ReviewStatus> CONFIRMED_ONLY = EnumSet.of(ReviewStatus.CONFIRMED);

	@Autowired
	DecisionGraph graph;

	UUID projectId;
	UUID reviewer;
	NodeRef printTask;
	NodeRef budgetApproval;
	NodeRef span22;
	NodeRef span23;
	NodeRef answer;

	UUID requiresEdge;
	UUID conditionEvidenceEdge;
	UUID taskEvidenceEdge;

	@BeforeEach
	void 인쇄_장면을_후보_줄로_넣는다() {
		projectId = UUID.randomUUID();
		reviewer = UUID.randomUUID();
		printTask = node(NodeType.TASK);
		budgetApproval = node(NodeType.CONDITION);
		span22 = node(NodeType.SPAN);
		span23 = node(NodeType.SPAN);
		answer = node(NodeType.ARTIFACT);

		requiresEdge = graph.addCandidateEdge(candidate(RelType.REQUIRES, printTask, budgetApproval, span22));
		conditionEvidenceEdge = graph.addCandidateEdge(candidate(RelType.EVIDENCED_BY, budgetApproval, span22, span22));
		taskEvidenceEdge = graph.addCandidateEdge(candidate(RelType.EVIDENCED_BY, printTask, span23, span23));
	}

	@Test
	void 인쇄_발주에서_근거를_따라가면_두_칸_만에_22번_발언에_닿는다() {
		Subgraph basis = graph.traverse(projectId, printTask, Direction.BASIS, null, 2, ANY_ACTIVE);

		assertThat(basis.depthOf(budgetApproval.key())).isEqualTo(1);
		assertThat(basis.depthOf(span23.key())).isEqualTo(1);
		assertThat(basis.depthOf(span22.key())).isEqualTo(2);
		assertThat(basis.edges()).extracting(Edge::id)
				.containsExactlyInAnyOrder(requiresEdge, conditionEvidenceEdge, taskEvidenceEdge);
	}

	@Test
	void 한_칸만_따라가면_22번_발언에는_닿지_않는다() {
		Subgraph basis = graph.traverse(projectId, printTask, Direction.BASIS, null, 1, ANY_ACTIVE);

		assertThat(basis.reaches(budgetApproval.key())).isTrue();
		assertThat(basis.reaches(span22.key())).isFalse();
	}

	@Test
	void 발언을_고치면_영향이_조건_할_일_답변_순으로_번진다() {
		graph.recordSystemEdge(uses(answer, printTask));

		Subgraph impact = graph.traverse(projectId, span22, Direction.IMPACT, null, 5, ANY_ACTIVE);

		assertThat(impact.depthOf(budgetApproval.key())).isEqualTo(1);
		assertThat(impact.depthOf(printTask.key())).isEqualTo(2);
		assertThat(impact.depthOf(answer.key())).isEqualTo(3);
		assertThat(impact.reaches(span23.key())).isFalse();
	}

	@Test
	void 확인된_줄만_보면_후보_줄은_따라가지_않는다() {
		Subgraph before = graph.traverse(projectId, printTask, Direction.BASIS, null, 2, CONFIRMED_ONLY);
		assertThat(before.edges()).isEmpty();

		graph.setEdgeStatus(requiresEdge, ReviewStatus.CONFIRMED, reviewer, null, 1);

		Subgraph after = graph.traverse(projectId, printTask, Direction.BASIS, null, 2, CONFIRMED_ONLY);
		assertThat(after.reaches(budgetApproval.key())).isTrue();
		assertThat(after.reaches(span22.key())).isFalse();
	}

	@Test
	void 줄_종류를_고르면_그_줄만_따라간다() {
		Subgraph onlyRequires = graph.traverse(projectId, printTask, Direction.BASIS,
				EnumSet.of(RelType.REQUIRES), 3, ANY_ACTIVE);

		assertThat(onlyRequires.reaches(budgetApproval.key())).isTrue();
		assertThat(onlyRequires.reaches(span22.key())).isFalse();
		assertThat(onlyRequires.reaches(span23.key())).isFalse();
	}

	@Test
	void 줄이_돌아오는_모양이어도_탐색이_끝난다() {
		NodeRef online = node(NodeType.DECISION_EVENT);
		NodeRef offline = node(NodeType.DECISION_EVENT);
		graph.addCandidateEdge(candidate(RelType.SUPERSEDES, offline, online, span22));
		graph.addCandidateEdge(candidate(RelType.SUPERSEDES, online, offline, span22));

		Subgraph walk = graph.traverse(projectId, offline, Direction.BASIS, null, DecisionGraph.MAX_DEPTH, ANY_ACTIVE);

		assertThat(walk.depths()).hasSize(2);
		assertThat(walk.edges()).hasSize(2);
	}

	@Test
	void 충돌_표시는_따라가지_않지만_바로_붙은_줄로는_보인다() {
		NodeRef thursday = node(NodeType.DECISION_EVENT);
		NodeRef friday = node(NodeType.DECISION_EVENT);
		UUID conflict = graph.addCandidateEdge(candidate(RelType.CONFLICTS_WITH, thursday, friday, span22));

		Subgraph walk = graph.traverse(projectId, thursday, Direction.BASIS, null, 3, ANY_ACTIVE);
		assertThat(walk.reaches(friday.key())).isFalse();

		List<Edge> attached = graph.edgesOf(projectId, thursday, Direction.BASIS, null, ANY_ACTIVE);
		assertThat(attached).extracting(Edge::id).containsExactly(conflict);
	}

	@Test
	void 바로_붙은_줄을_방향별로_돌려준다() {
		assertThat(graph.edgesOf(projectId, span22, Direction.IMPACT, null, ANY_ACTIVE))
				.extracting(Edge::id).containsExactly(conditionEvidenceEdge);
		assertThat(graph.edgesOf(projectId, span22, Direction.BASIS, null, ANY_ACTIVE)).isEmpty();
		assertThat(graph.edgesOf(projectId, printTask, Direction.BASIS, EnumSet.of(RelType.REQUIRES), ANY_ACTIVE))
				.extracting(Edge::id).containsExactly(requiresEdge);
	}

	@Test
	void 다른_프로젝트의_줄은_보이지_않는다() {
		UUID otherProject = UUID.randomUUID();

		Subgraph walk = graph.traverse(otherProject, printTask, Direction.BASIS, null, 3, ANY_ACTIVE);

		assertThat(walk.edges()).isEmpty();
		assertThat(walk.depths()).containsOnlyKeys(printTask.key());
	}

	@Test
	void 같은_후보_줄을_다시_그으면_기존_줄을_돌려준다() {
		UUID again = graph.addCandidateEdge(candidate(RelType.REQUIRES, printTask, budgetApproval, span23));

		assertThat(again).isEqualTo(requiresEdge);
		assertThat(graph.find(requiresEdge).orElseThrow().evidenceSpanIds()).containsExactly(span22.id());
	}

	@Test
	void 줄을_확인하면_확인자와_시각이_남고_버전이_오른다() {
		Edge confirmed = graph.setEdgeStatus(requiresEdge, ReviewStatus.CONFIRMED, reviewer, null, 1);

		assertThat(confirmed.status()).isEqualTo(ReviewStatus.CONFIRMED);
		assertThat(confirmed.confirmedBy()).isEqualTo(reviewer);
		assertThat(confirmed.confirmedAt()).isNotNull();
		assertThat(confirmed.version()).isEqualTo(2);
		assertThat(graph.currentVersions(projectId, List.of(requiresEdge, taskEvidenceEdge)))
				.containsEntry(requiresEdge, 2L)
				.containsEntry(taskEvidenceEdge, 1L);
	}

	@Test
	void 근거_없이는_확인할_수_없다() {
		assertThatThrownBy(() -> graph.setEdgeStatus(requiresEdge, ReviewStatus.CONFIRMED, reviewer, List.of(), 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("근거");
		assertThat(graph.find(requiresEdge).orElseThrow().status()).isEqualTo(ReviewStatus.CANDIDATE);
	}

	@Test
	void 다른_사람이_먼저_바꾼_줄은_덮어쓰지_않는다() {
		graph.setEdgeStatus(requiresEdge, ReviewStatus.HELD, reviewer, null, 1);

		assertThatThrownBy(() -> graph.setEdgeStatus(requiresEdge, ReviewStatus.CONFIRMED, UUID.randomUUID(), null, 1))
				.isInstanceOf(EdgeVersionConflictException.class);
		assertThat(graph.find(requiresEdge).orElseThrow().status()).isEqualTo(ReviewStatus.HELD);
	}

	@Test
	void 확인한_줄은_철회만_할_수_있고_철회한_줄은_되살리지_않는다() {
		graph.setEdgeStatus(requiresEdge, ReviewStatus.CONFIRMED, reviewer, null, 1);

		assertThatThrownBy(() -> graph.setEdgeStatus(requiresEdge, ReviewStatus.REJECTED, reviewer, null, 2))
				.isInstanceOf(IllegalStateException.class);

		graph.setEdgeStatus(requiresEdge, ReviewStatus.RETRACTED, reviewer, null, 2);

		assertThatThrownBy(() -> graph.setEdgeStatus(requiresEdge, ReviewStatus.CONFIRMED, reviewer, null, 3))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 철회한_뒤에는_같은_줄을_새_후보로_다시_낼_수_있다() {
		graph.setEdgeStatus(requiresEdge, ReviewStatus.CONFIRMED, reviewer, null, 1);
		graph.setEdgeStatus(requiresEdge, ReviewStatus.RETRACTED, reviewer, null, 2);

		UUID fresh = graph.addCandidateEdge(candidate(RelType.REQUIRES, printTask, budgetApproval, span22));

		assertThat(fresh).isNotEqualTo(requiresEdge);
		assertThat(graph.traverse(projectId, printTask, Direction.BASIS, EnumSet.of(RelType.REQUIRES), 1, ANY_ACTIVE)
				.edges()).extracting(Edge::id).containsExactly(fresh);
	}

	@Test
	void 없는_줄은_바꿀_수_없다() {
		assertThatThrownBy(() -> graph.setEdgeStatus(UUID.randomUUID(), ReviewStatus.HELD, reviewer, null, 1))
				.isInstanceOf(EdgeNotFoundException.class);
	}

	@Test
	void 후보_창구와_시스템_창구는_서로의_줄을_받지_않는다() {
		Edge confirmedRequires = new Edge(UUID.randomUUID(), projectId, RelType.REQUIRES, printTask, budgetApproval,
				ReviewStatus.CONFIRMED, List.of(span22.id()), reviewer, Instant.now(), Ontology.VERSION, 1);

		assertThatThrownBy(() -> graph.addCandidateEdge(confirmedRequires))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> graph.addCandidateEdge(uses(answer, printTask)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> graph.recordSystemEdge(candidate(RelType.REQUIRES, printTask, budgetApproval, span22)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 참고했다_줄은_검토로_바꿀_수_없다() {
		UUID usesEdge = graph.recordSystemEdge(uses(answer, printTask));

		assertThatThrownBy(() -> graph.setEdgeStatus(usesEdge, ReviewStatus.RETRACTED, reviewer, null, 1))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 탐색_조건이_잘못되면_거절한다() {
		assertThatThrownBy(() -> graph.traverse(projectId, printTask, Direction.BASIS, null, 0, ANY_ACTIVE))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> graph.traverse(projectId, printTask, Direction.BASIS, null,
				DecisionGraph.MAX_DEPTH + 1, ANY_ACTIVE))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> graph.traverse(projectId, printTask, Direction.BASIS, null, 2, Set.of()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ---- 도우미 ----

	private static NodeRef node(NodeType type) {
		return new NodeRef(type, UUID.randomUUID(), 1);
	}

	private Edge candidate(RelType rel, NodeRef src, NodeRef dst, NodeRef evidence) {
		return new Edge(UUID.randomUUID(), projectId, rel, src, dst, ReviewStatus.CANDIDATE,
				List.of(evidence.id()), null, null, Ontology.VERSION, 1);
	}

	private Edge uses(NodeRef artifact, NodeRef used) {
		return new Edge(UUID.randomUUID(), projectId, RelType.USES, artifact, used, ReviewStatus.CONFIRMED,
				List.of(), null, Instant.now(), Ontology.VERSION, 1);
	}
}
