package com.contexa.server.review;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Edge;
import com.contexa.server.contract.NodeKey;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.RelType;
import com.contexa.server.decision.DecisionTask;
import com.contexa.server.entity.EntityReview;
import com.contexa.server.graph.DecisionGraph;

/**
 * 계정을 지울 때 결정 그래프와 검토 장부에서 그 사람의 개인 자료를 지운다.
 * 카드·줄·검토 기록은 원래 지우지 않고 상태로만 바꾸지만, 계정 삭제는 개인 자료를 남기지 않는 것이 먼저다.
 *
 * <p>개인 메모의 발언을 지울 때 무엇이 함께 사라지는가:
 * <ul>
 * <li>그 발언에 닿는 줄(근거·언급·화자·충족), 근거가 모두 그 발언인 줄.</li>
 * <li>근거를 읽을 수 있어야 보이는 카드(결정 사건·조건·할 일) 중 근거가 모두 그 발언인 것.
 * 원래 메모 주인만 보던 카드다. 근거 줄만 지우고 카드를 남기면 오히려 모두에게 보이게 된다.</li>
 * <li>안건·결정 항목·인물 중 닿는 줄이 모두 위에서 사라지는 것. 메모에서만 나왔고 아무도 이어 쓰지 않은 카드다.
 * 다른 카드나 답변이 기대고 있으면 남긴다(이미 모두에게 보이던 공유 내용이다).</li>
 * </ul>
 * 근거 일부만 그 발언인 줄은 그 발언만 근거에서 뺀다. 지운 뒤 현재 결정과 조건 충족을 다시 계산한다.
 */
@Service
public class Erasure {

	/** 근거를 읽을 수 있어야 보이는 카드. 근거 줄(EVIDENCED_BY)을 가진다. */
	private static final Set<NodeType> GATED = EnumSet.of(NodeType.DECISION_EVENT, NodeType.CONDITION, NodeType.TASK);

	/** 누구에게나 보이는 카드. 메모에서만 나왔고 아무도 이어 쓰지 않았을 때만 지운다. */
	private static final Set<NodeType> SHARED = EnumSet.of(NodeType.TOPIC, NodeType.DECISION_ITEM, NodeType.ENTITY);

	private final JdbcClient jdbc;
	private final DecisionGraph graph;
	private final DecisionTask decision;
	private final EntityReview entity;

	public Erasure(JdbcClient jdbc, DecisionGraph graph, DecisionTask decision, EntityReview entity) {
		this.jdbc = jdbc;
		this.graph = graph;
		this.decision = decision;
		this.entity = entity;
	}

	/**
	 * 지울 발언(개인 메모)에만 기대던 카드와 줄을 지운다. 발언 자체는 그다음에 자료실이 지운다.
	 *
	 * @return 지운 카드
	 */
	@Transactional
	public Set<NodeKey> forgetSpans(UUID projectId, Collection<UUID> spanIds) {
		Objects.requireNonNull(projectId, "projectId");
		Set<UUID> spans = Set.copyOf(spanIds);
		if (spans.isEmpty()) {
			return Set.of();
		}
		Map<UUID, Edge> dying = new LinkedHashMap<>();
		graph.edgesTouching(projectId, spans).forEach(edge -> dying.put(edge.id(), edge));
		graph.edgesCiting(projectId, spans).stream()
				.filter(edge -> spans.containsAll(edge.evidenceSpanIds()))
				.forEach(edge -> dying.put(edge.id(), edge));

		Set<NodeKey> doomed = new HashSet<>(gatedLeaningOnlyOn(projectId, spans, dying.values()));
		while (true) {
			graph.edgesTouching(projectId, ids(doomed)).forEach(edge -> dying.put(edge.id(), edge));
			Set<NodeKey> candidates = dying.values().stream()
					.flatMap(edge -> List.of(edge.src().key(), edge.dst().key()).stream())
					.filter(key -> SHARED.contains(key.type()) && !doomed.contains(key))
					.collect(Collectors.toSet());
			Map<UUID, List<Edge>> around = graph.edgesTouching(projectId, ids(candidates)).stream()
					.flatMap(edge -> List.of(Map.entry(edge.src().id(), edge), Map.entry(edge.dst().id(), edge))
							.stream())
					.collect(Collectors.groupingBy(Map.Entry::getKey,
							Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
			Set<NodeKey> orphans = candidates.stream()
					.filter(key -> around.getOrDefault(key.id(), List.of()).stream()
							.allMatch(edge -> dying.containsKey(edge.id())))
					.collect(Collectors.toSet());
			if (orphans.isEmpty()) {
				break;
			}
			doomed.addAll(orphans);
		}

		graph.deleteEdges(projectId, dying.keySet());
		graph.removeEvidence(projectId, spans);
		Set<UUID> doomedIds = ids(doomed);
		forgetReviews(projectId, doomedIds);
		// 다시 볼 카드가 지워졌거나, 원인(바뀐 카드·발언)이 지워진 "다시 확인" 기록
		Set<UUID> gone = new HashSet<>(doomedIds);
		gone.addAll(spans);
		jdbc.sql("delete from recheck where project_id = :projectId and (node_id in (:gone) or cause_id in (:gone))")
				.param("projectId", projectId)
				.param("gone", gone)
				.update();
		decision.deleteNodes(projectId, doomed.stream().filter(key -> key.type() != NodeType.ENTITY).toList());
		entity.deleteEntities(projectId,
				doomed.stream().filter(key -> key.type() == NodeType.ENTITY).map(NodeKey::id).toList());
		entity.forgetNameEvidence(projectId, spans);
		decision.recomputeAll(projectId);
		return doomed;
	}

	/** 지워진 계정과 인물 카드의 연결을 끊는다. */
	@Transactional
	public void forgetAccount(UUID accountId) {
		entity.unlinkAccount(accountId);
	}

	/** 프로젝트의 카드·줄·검토 기록을 모두 지운다. 계정 삭제로 프로젝트를 없앨 때만 쓴다. */
	@Transactional
	public void purgeProject(UUID projectId) {
		for (String sql : List.of("delete from review_action where project_id = :projectId",
				"delete from review_item where project_id = :projectId",
				"delete from recheck where project_id = :projectId")) {
			jdbc.sql(sql).param("projectId", projectId).update();
		}
		graph.purgeProject(projectId);
		decision.purgeProject(projectId);
		entity.purgeProject(projectId);
	}

	/** 근거 줄이 있고, 그 근거가 모두 지울 발언인 결정 사건·조건·할 일. */
	private Set<NodeKey> gatedLeaningOnlyOn(UUID projectId, Set<UUID> spans, Collection<Edge> touching) {
		Set<NodeKey> leaning = touching.stream()
				.filter(edge -> edge.rel() == RelType.EVIDENCED_BY && GATED.contains(edge.src().type()))
				.map(edge -> edge.src().key())
				.collect(Collectors.toSet());
		Set<NodeKey> keepsOtherEvidence = graph.edgesTouching(projectId, ids(leaning)).stream()
				.filter(edge -> edge.rel() == RelType.EVIDENCED_BY && !spans.contains(edge.dst().id()))
				.map(edge -> edge.src().key())
				.collect(Collectors.toSet());
		leaning.removeAll(keepsOtherEvidence);
		return leaning;
	}

	private void forgetReviews(UUID projectId, Set<UUID> owners) {
		if (owners.isEmpty()) {
			return;
		}
		jdbc.sql("""
				delete from review_action
				 where review_id in (select id from review_item where project_id = :projectId and owner_id in (:owners))
				""").param("projectId", projectId).param("owners", owners).update();
		jdbc.sql("delete from review_item where project_id = :projectId and owner_id in (:owners)")
				.param("projectId", projectId).param("owners", owners).update();
	}

	private static Set<UUID> ids(Collection<NodeKey> keys) {
		return keys.stream().map(NodeKey::id).collect(Collectors.toSet());
	}
}
