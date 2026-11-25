package com.contexa.server.decision;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.NodeKey;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.ReviewStatus;

/**
 * 다시 확인할 카드의 장부(결정·업무 관리 소속).
 *
 * <ul>
 * <li>확인·보류된 카드가 기대는 카드나 발언이 바뀌면 결재 창구(review-impact)가 {@link #mark}로 남긴다.
 * 남기고 닫는 함수는 결재 창구만 부른다.</li>
 * <li>바뀐 것을 근거로 쓰는 카드(결정 사건, 조건, 할 일)만 남는다. 안건과 결정 항목은 사건을 담을 뿐이고
 * 결정 항목의 현재 결정은 이 모듈이 다시 계산하므로(경합하면 CONTESTED), 결정이 바뀔 때마다 남기면 소음이 된다.
 * 후보 카드는 이미 검토함에 있고, 거절·철회된 카드는 다시 볼 일이 없다.</li>
 * <li>한 카드에 이유가 여럿 쌓일 수 있다. 같은 카드·같은 원인으로는 열린 것이 하나뿐이다.</li>
 * <li>닫으면 그 카드의 열린 이유가 모두 닫힌다. 닫은 기록은 지우지 않는다.</li>
 * </ul>
 */
@Service
public class Rechecks {

	private static final Set<ReviewStatus> MARKED = EnumSet.of(ReviewStatus.CONFIRMED, ReviewStatus.HELD);

	/** 다시 확인으로 남기는 카드. 안건·결정 항목은 담는 카드라 빠진다. */
	private static final Set<NodeType> MARKABLE =
			EnumSet.of(NodeType.DECISION_EVENT, NodeType.CONDITION, NodeType.TASK);

	private static final String CAUSE_COLUMNS =
			"node_type, node_id, cause_type, cause_id, reason, distance, actor_id, created_at";

	private record CauseRow(NodeKey node, RecheckCause cause) {
	}

	private final JdbcClient jdbc;
	private final DecisionTask decision;

	public Rechecks(JdbcClient jdbc, DecisionTask decision) {
		this.jdbc = jdbc;
		this.decision = decision;
	}

	/**
	 * 바뀐 것에 기대는 카드를 다시 확인할 카드로 남긴다.
	 *
	 * @param actorId 바꾼 사람
	 * @return 새로 남긴 이유의 수. 이미 열려 있던 것은 세지 않는다
	 */
	@Transactional
	public int mark(UUID projectId, Collection<RecheckMark> marks, RecheckReason reason, UUID actorId) {
		Objects.requireNonNull(projectId, "projectId");
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(actorId, "actorId");
		List<RecheckMark> owned = marks.stream().filter(mark -> MARKABLE.contains(mark.node().type())).toList();
		if (owned.isEmpty()) {
			return 0;
		}
		Set<NodeKey> marked = decision.getNodes(projectId, owned.stream()
				.map(mark -> new NodeRef(mark.node().type(), mark.node().id(), 1)).toList())
				.stream()
				.filter(view -> MARKED.contains(view.status()))
				.map(view -> view.ref().key())
				.collect(Collectors.toSet());
		int added = 0;
		for (RecheckMark mark : owned) {
			if (!marked.contains(mark.node())) {
				continue;
			}
			added += jdbc.sql("""
					insert into recheck (id, project_id, node_type, node_id, cause_type, cause_id, reason, distance,
					                     actor_id, created_at)
					values (:id, :projectId, :nodeType, :nodeId, :causeType, :causeId, :reason, :distance,
					        :actorId, clock_timestamp())
					on conflict (project_id, node_type, node_id, cause_type, cause_id) where resolved_at is null
					    do nothing
					""")
					.param("id", UUID.randomUUID())
					.param("projectId", projectId)
					.param("nodeType", mark.node().type().name())
					.param("nodeId", mark.node().id())
					.param("causeType", mark.cause().type().name())
					.param("causeId", mark.cause().id())
					.param("reason", reason.name())
					.param("distance", mark.distance())
					.param("actorId", actorId)
					.update();
		}
		return added;
	}

	/** 이 카드들 가운데 다시 확인할 것과 그 이유. 이유는 먼저 열린 것부터, 카드는 요청한 순서로 돌려준다. */
	@Transactional(readOnly = true)
	public Map<NodeKey, List<RecheckCause>> openOf(UUID projectId, Collection<NodeKey> nodes) {
		Objects.requireNonNull(projectId, "projectId");
		Set<NodeKey> wanted = nodes.stream().filter(key -> DecisionTask.owns(key.type()))
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<NodeKey, List<RecheckCause>> found = new LinkedHashMap<>();
		if (wanted.isEmpty()) {
			return found;
		}
		Map<NodeKey, List<RecheckCause>> byNode = new LinkedHashMap<>();
		jdbc.sql("select " + CAUSE_COLUMNS + " from recheck"
				+ " where project_id = :projectId and resolved_at is null and node_id in (:ids)"
				+ " order by created_at, id")
				.param("projectId", projectId)
				.param("ids", wanted.stream().map(NodeKey::id).distinct().toList())
				.query(Rechecks::mapCause)
				.list()
				.forEach(row -> byNode.computeIfAbsent(row.node(), key -> new ArrayList<>()).add(row.cause()));
		for (NodeKey key : wanted) {
			List<RecheckCause> causes = byNode.get(key);
			if (causes != null) {
				found.put(key, List.copyOf(causes));
			}
		}
		return found;
	}

	/**
	 * 다시 확인할 카드 한 쪽. 가장 먼저 열린 이유의 시각 순서다.
	 *
	 * @param afterSince 앞 쪽 마지막 카드의 since. 첫 쪽이면 null
	 * @param afterId    앞 쪽 마지막 카드의 id
	 */
	@Transactional(readOnly = true)
	public List<OpenRecheck> listOpen(UUID projectId, int limit, OffsetDateTime afterSince, UUID afterId) {
		Objects.requireNonNull(projectId, "projectId");
		if (limit < 1) {
			throw new IllegalArgumentException("limit은 1 이상이어야 한다: " + limit);
		}
		JdbcClient.StatementSpec spec = jdbc.sql("""
				select node_type, node_id, min(created_at) as since from recheck
				 where project_id = :projectId and resolved_at is null
				 group by node_type, node_id
				""" + (afterSince == null ? "" : " having (min(created_at), node_id) > (:afterAt, :afterId)")
				+ " order by since, node_id limit :limit")
				.param("projectId", projectId)
				.param("limit", limit);
		if (afterSince != null) {
			spec = spec.param("afterAt", afterSince).param("afterId", Objects.requireNonNull(afterId, "afterId"));
		}
		Map<NodeKey, OffsetDateTime> since = new LinkedHashMap<>();
		spec.query((rs, n) -> Map.entry(new NodeKey(NodeType.valueOf(rs.getString("node_type")),
				rs.getObject("node_id", UUID.class)), rs.getObject("since", OffsetDateTime.class)))
				.list()
				.forEach(entry -> since.put(entry.getKey(), entry.getValue()));
		Map<NodeKey, List<RecheckCause>> causes = openOf(projectId, since.keySet());
		List<OpenRecheck> page = new ArrayList<>();
		since.forEach((key, at) -> page.add(new OpenRecheck(key, at, causes.getOrDefault(key, List.of()))));
		return page;
	}

	/**
	 * 이 카드의 열린 이유를 모두 닫는다.
	 *
	 * @return 닫은 이유의 수. 열린 것이 없었으면 0
	 */
	@Transactional
	public int resolve(UUID projectId, NodeKey node, UUID resolvedBy, RecheckResolution resolution) {
		Objects.requireNonNull(projectId, "projectId");
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(resolvedBy, "resolvedBy");
		Objects.requireNonNull(resolution, "resolution");
		return jdbc.sql("""
				update recheck set resolved_at = clock_timestamp(), resolved_by = :by, resolution = :resolution
				 where project_id = :projectId and node_type = :nodeType and node_id = :nodeId and resolved_at is null
				""")
				.param("by", resolvedBy)
				.param("resolution", resolution.name())
				.param("projectId", projectId)
				.param("nodeType", node.type().name())
				.param("nodeId", node.id())
				.update();
	}

	private static CauseRow mapCause(ResultSet rs, int rowNum) throws SQLException {
		NodeKey node = new NodeKey(NodeType.valueOf(rs.getString("node_type")), rs.getObject("node_id", UUID.class));
		NodeKey changed = new NodeKey(NodeType.valueOf(rs.getString("cause_type")),
				rs.getObject("cause_id", UUID.class));
		return new CauseRow(node, new RecheckCause(changed, RecheckReason.valueOf(rs.getString("reason")),
				rs.getInt("distance"), rs.getObject("actor_id", UUID.class),
				rs.getObject("created_at", OffsetDateTime.class)));
	}
}
