package com.contexa.server.graph;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Direction;
import com.contexa.server.contract.Edge;
import com.contexa.server.contract.NodeKey;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.RelType;
import com.contexa.server.contract.ReviewStatus;

/**
 * 게시판 관리: 줄을 긋고, 상태를 바꾸고, 따라간다.
 * 줄의 상태는 여기(graph_edge)에만 저장된다.
 * 양끝 카드가 실제로 있는지, 같은 프로젝트인지는 카드를 소유한 위층 모듈이 확인한 뒤 호출한다.
 */
@Service
public class DecisionGraph {

	/** 한 번의 탐색에서 따라갈 수 있는 최대 칸 수. */
	public static final int MAX_DEPTH = 10;

	private static final Set<ReviewStatus> ACTIVE =
			EnumSet.of(ReviewStatus.CANDIDATE, ReviewStatus.CONFIRMED, ReviewStatus.HELD);

	private static final String COLUMNS = """
			id, project_id, rel_type, src_type, src_id, src_rev, dst_type, dst_id, dst_rev,
			status, evidence_span_ids, confirmed_by, confirmed_at, ontology_version, version
			""";

	private final JdbcClient jdbc;

	public DecisionGraph(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 후보(연필) 줄을 긋는다. 같은 두 카드 사이에 같은 종류의 살아 있는 줄이 이미 있으면
	 * 새로 긋지 않고 그 줄의 id를 돌려준다.
	 */
	@Transactional
	public UUID addCandidateEdge(Edge e) {
		Objects.requireNonNull(e, "edge");
		if (e.status() != ReviewStatus.CANDIDATE) {
			throw new IllegalArgumentException("후보 줄은 CANDIDATE 상태여야 한다: " + e.status());
		}
		return insert(e);
	}

	/** 시스템이 기록하는 줄(USES)을 긋는다. */
	@Transactional
	public UUID recordSystemEdge(Edge e) {
		Objects.requireNonNull(e, "edge");
		if (!e.rel().isSystemRecorded()) {
			throw new IllegalArgumentException("시스템 기록 줄이 아니다: " + e.rel());
		}
		return insert(e);
	}

	/**
	 * 줄의 상태를 바꾼다. 결재 창구(review-impact)만 호출한다.
	 *
	 * @param evidence        null이면 기존 근거를 유지한다
	 * @param expectedVersion 검토자가 화면에서 본 줄의 버전
	 */
	@Transactional
	public Edge setEdgeStatus(UUID edgeId, ReviewStatus next, UUID reviewer, List<UUID> evidence,
			long expectedVersion) {
		Objects.requireNonNull(edgeId, "edgeId");
		Objects.requireNonNull(next, "status");
		Objects.requireNonNull(reviewer, "reviewer");

		Edge current = find(edgeId).orElseThrow(() -> new EdgeNotFoundException(edgeId));
		if (current.version() != expectedVersion) {
			throw new EdgeVersionConflictException(edgeId, expectedVersion, current.version());
		}
		if (current.rel().isSystemRecorded()) {
			throw new IllegalStateException("시스템 기록 줄은 검토로 바꿀 수 없다: " + current.rel());
		}
		requireTransition(current.status(), next);

		List<UUID> finalEvidence = evidence == null ? current.evidenceSpanIds() : List.copyOf(evidence);
		if (next == ReviewStatus.CONFIRMED && finalEvidence.isEmpty()) {
			throw new IllegalArgumentException("근거 없이는 줄을 확인할 수 없다");
		}

		boolean confirming = next == ReviewStatus.CONFIRMED;
		int updated = jdbc.sql("""
				update graph_edge
				   set status = :status,
				       evidence_span_ids = cast(:evidence as uuid[]),
				       confirmed_by = case when :confirming then :reviewer else confirmed_by end,
				       confirmed_at = case when :confirming then now() else confirmed_at end,
				       version = version + 1,
				       updated_at = now()
				 where id = :id and version = :expectedVersion
				""")
				.param("status", next.name())
				.param("evidence", toArrayLiteral(finalEvidence))
				.param("confirming", confirming)
				.param("reviewer", reviewer)
				.param("id", edgeId)
				.param("expectedVersion", expectedVersion)
				.update();
		if (updated == 0) {
			long actual = find(edgeId).map(Edge::version).orElseThrow(() -> new EdgeNotFoundException(edgeId));
			throw new EdgeVersionConflictException(edgeId, expectedVersion, actual);
		}
		return find(edgeId).orElseThrow(() -> new EdgeNotFoundException(edgeId));
	}

	@Transactional(readOnly = true)
	public Optional<Edge> find(UUID edgeId) {
		return jdbc.sql("select " + COLUMNS + " from graph_edge where id = :id")
				.param("id", edgeId)
				.query(DecisionGraph::mapEdge)
				.optional();
	}

	/**
	 * 한 카드에 바로 붙은 줄.
	 *
	 * @param rels     null이나 빈 값이면 모든 종류
	 * @param statuses 보려는 확인 상태. 비워 둘 수 없다
	 */
	@Transactional(readOnly = true)
	public List<Edge> edgesOf(UUID projectId, NodeRef node, Direction direction, Set<RelType> rels,
			Set<ReviewStatus> statuses) {
		Objects.requireNonNull(node, "node");
		Set<RelType> relFilter = rels == null || rels.isEmpty() ? EnumSet.allOf(RelType.class) : rels;
		NodeKey key = node.key();
		return fetch(projectId, List.of(key.id()), direction, relFilter, requireStatuses(statuses)).stream()
				.filter(e -> near(e, direction).equals(key))
				.toList();
	}

	/**
	 * 여러 카드에 바로 붙은 줄을 한 번에 가져온다. 안내 데스크가 한 칸씩 넓혀 갈 때 쓴다.
	 *
	 * @param rels     null이나 빈 값이면 모든 종류
	 * @param statuses 보려는 확인 상태. 비워 둘 수 없다
	 */
	@Transactional(readOnly = true)
	public List<Edge> edgesOfMany(UUID projectId, Collection<NodeKey> nodes, Direction direction,
			Set<RelType> rels, Set<ReviewStatus> statuses) {
		Objects.requireNonNull(nodes, "nodes");
		Set<RelType> relFilter = rels == null || rels.isEmpty() ? EnumSet.allOf(RelType.class) : rels;
		Set<NodeKey> keys = new LinkedHashSet<>(nodes);
		List<UUID> ids = keys.stream().map(NodeKey::id).distinct().toList();
		return fetch(projectId, ids, direction, relFilter, requireStatuses(statuses)).stream()
				.filter(e -> keys.contains(near(e, direction)))
				.toList();
	}

	/**
	 * 한 카드에서 줄을 따라간다. BASIS는 근거 쪽(src에서 dst), IMPACT는 영향 쪽(dst에서 src).
	 * 같은 카드는 한 번만 지나므로 줄이 돌아오는 모양이어도 끝난다.
	 * 충돌 표시(CONFLICTS_WITH)는 따라가지 않는다.
	 *
	 * @param rels null이나 빈 값이면 따라갈 수 있는 모든 종류
	 */
	@Transactional(readOnly = true)
	public Subgraph traverse(UUID projectId, NodeRef start, Direction direction, Set<RelType> rels,
			int maxDepth, Set<ReviewStatus> statuses) {
		Objects.requireNonNull(start, "start");
		if (maxDepth < 1 || maxDepth > MAX_DEPTH) {
			throw new IllegalArgumentException("maxDepth는 1~" + MAX_DEPTH + " 사이여야 한다: " + maxDepth);
		}
		Set<ReviewStatus> statusFilter = requireStatuses(statuses);
		Set<RelType> relFilter = (rels == null || rels.isEmpty() ? EnumSet.allOf(RelType.class) : rels).stream()
				.filter(RelType::isDependency)
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(RelType.class)));

		NodeKey startKey = start.key();
		Map<NodeKey, Integer> depths = new LinkedHashMap<>();
		depths.put(startKey, 0);
		Map<UUID, Edge> edges = new LinkedHashMap<>();
		Set<NodeKey> frontier = Set.of(startKey);

		for (int depth = 1; depth <= maxDepth && !frontier.isEmpty() && !relFilter.isEmpty(); depth++) {
			List<UUID> frontierIds = frontier.stream().map(NodeKey::id).toList();
			Set<NodeKey> next = new LinkedHashSet<>();
			for (Edge e : fetch(projectId, frontierIds, direction, relFilter, statusFilter)) {
				if (!frontier.contains(near(e, direction))) {
					continue;
				}
				edges.putIfAbsent(e.id(), e);
				NodeKey reached = far(e, direction);
				if (depths.putIfAbsent(reached, depth) == null) {
					next.add(reached);
				}
			}
			frontier = next;
		}
		return new Subgraph(startKey, new ArrayList<>(edges.values()), depths);
	}

	/** 줄마다 현재 버전. 이 프로젝트에 없는 줄은 결과에서 빠진다. */
	@Transactional(readOnly = true)
	public Map<UUID, Long> currentVersions(UUID projectId, Collection<UUID> edgeIds) {
		Objects.requireNonNull(projectId, "projectId");
		Map<UUID, Long> versions = new HashMap<>();
		if (edgeIds == null || edgeIds.isEmpty()) {
			return versions;
		}
		jdbc.sql("select id, version from graph_edge where project_id = :projectId and id in (:ids)")
				.param("projectId", projectId)
				.param("ids", edgeIds)
				.query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getLong("version")))
				.list()
				.forEach(entry -> versions.put(entry.getKey(), entry.getValue()));
		return versions;
	}

	// ---- 내부 ----

	private UUID insert(Edge e) {
		OffsetDateTime confirmedAt = e.confirmedAt() == null ? null
				: OffsetDateTime.ofInstant(e.confirmedAt(), ZoneOffset.UTC);
		Optional<UUID> inserted = jdbc.sql("""
				insert into graph_edge (
				    id, project_id, rel_type, src_type, src_id, src_rev, dst_type, dst_id, dst_rev,
				    status, evidence_span_ids, confirmed_by, confirmed_at, ontology_version, version, created_at)
				values (
				    :id, :projectId, :rel, :srcType, :srcId, :srcRev, :dstType, :dstId, :dstRev,
				    :status, cast(:evidence as uuid[]), :confirmedBy, :confirmedAt, :ontologyVersion, :version,
				    clock_timestamp())
				on conflict (project_id, rel_type, src_type, src_id, dst_type, dst_id)
				    where status in ('CANDIDATE', 'CONFIRMED', 'HELD')
				    do nothing
				returning id
				""")
				.param("id", e.id())
				.param("projectId", e.projectId())
				.param("rel", e.rel().name())
				.param("srcType", e.src().type().name())
				.param("srcId", e.src().id())
				.param("srcRev", e.src().revision())
				.param("dstType", e.dst().type().name())
				.param("dstId", e.dst().id())
				.param("dstRev", e.dst().revision())
				.param("status", e.status().name())
				.param("evidence", toArrayLiteral(e.evidenceSpanIds()))
				.param("confirmedBy", e.confirmedBy())
				.param("confirmedAt", confirmedAt)
				.param("ontologyVersion", e.ontologyVersion())
				.param("version", e.version())
				.query((rs, n) -> rs.getObject("id", UUID.class))
				.optional();
		if (inserted.isPresent()) {
			return inserted.get();
		}
		return jdbc.sql("""
				select id from graph_edge
				 where project_id = :projectId and rel_type = :rel
				   and src_type = :srcType and src_id = :srcId
				   and dst_type = :dstType and dst_id = :dstId
				   and status in (:active)
				""")
				.param("projectId", e.projectId())
				.param("rel", e.rel().name())
				.param("srcType", e.src().type().name())
				.param("srcId", e.src().id())
				.param("dstType", e.dst().type().name())
				.param("dstId", e.dst().id())
				.param("active", names(ACTIVE))
				.query((rs, n) -> rs.getObject("id", UUID.class))
				.single();
	}

	private List<Edge> fetch(UUID projectId, Collection<UUID> nodeIds, Direction direction,
			Set<RelType> rels, Set<ReviewStatus> statuses) {
		Objects.requireNonNull(projectId, "projectId");
		Objects.requireNonNull(direction, "direction");
		if (nodeIds.isEmpty() || rels.isEmpty()) {
			return List.of();
		}
		String nearColumn = direction == Direction.BASIS ? "src_id" : "dst_id";
		return jdbc.sql("select " + COLUMNS + " from graph_edge"
				+ " where project_id = :projectId and " + nearColumn + " in (:nodeIds)"
				+ " and rel_type in (:rels) and status in (:statuses)"
				+ " order by created_at, id")
				.param("projectId", projectId)
				.param("nodeIds", nodeIds)
				.param("rels", names(rels))
				.param("statuses", names(statuses))
				.query(DecisionGraph::mapEdge)
				.list();
	}

	/** 탐색 방향에서 "지금 서 있는 쪽" 카드. */
	private static NodeKey near(Edge e, Direction direction) {
		return direction == Direction.BASIS ? e.src().key() : e.dst().key();
	}

	/** 탐색 방향에서 "건너가서 닿는 쪽" 카드. */
	private static NodeKey far(Edge e, Direction direction) {
		return direction == Direction.BASIS ? e.dst().key() : e.src().key();
	}

	private static Set<ReviewStatus> requireStatuses(Set<ReviewStatus> statuses) {
		if (statuses == null || statuses.isEmpty()) {
			throw new IllegalArgumentException("보려는 확인 상태를 하나 이상 지정해야 한다");
		}
		return statuses;
	}

	/** 허용되는 상태 변화는 카드와 줄이 같은 규칙을 쓴다({@link ReviewStatus#canMoveTo}). */
	private static void requireTransition(ReviewStatus from, ReviewStatus to) {
		if (!from.canMoveTo(to)) {
			throw new IllegalStateException("허용되지 않은 상태 변화: " + from + " → " + to);
		}
	}

	private static List<String> names(Collection<? extends Enum<?>> values) {
		return values.stream().map(Enum::name).toList();
	}

	private static String toArrayLiteral(List<UUID> ids) {
		return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
	}

	private static Edge mapEdge(ResultSet rs, int rowNum) throws SQLException {
		UUID[] evidence = (UUID[]) rs.getArray("evidence_span_ids").getArray();
		OffsetDateTime confirmedAt = rs.getObject("confirmed_at", OffsetDateTime.class);
		Instant confirmedInstant = confirmedAt == null ? null : confirmedAt.toInstant();
		return new Edge(
				rs.getObject("id", UUID.class),
				rs.getObject("project_id", UUID.class),
				RelType.valueOf(rs.getString("rel_type")),
				new NodeRef(NodeType.valueOf(rs.getString("src_type")), rs.getObject("src_id", UUID.class),
						rs.getLong("src_rev")),
				new NodeRef(NodeType.valueOf(rs.getString("dst_type")), rs.getObject("dst_id", UUID.class),
						rs.getLong("dst_rev")),
				ReviewStatus.valueOf(rs.getString("status")),
				List.of(evidence),
				rs.getObject("confirmed_by", UUID.class),
				confirmedInstant,
				rs.getString("ontology_version"),
				rs.getLong("version"));
	}
}
