package com.contexa.server.decision;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Direction;
import com.contexa.server.contract.Edge;
import com.contexa.server.contract.NodeDraft;
import com.contexa.server.contract.NodeKey;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.NodeView;
import com.contexa.server.contract.RelType;
import com.contexa.server.contract.ReviewStatus;
import com.contexa.server.graph.DecisionGraph;

/**
 * 결정·업무 관리: 안건·결정 항목·결정 사건·조건·할 일 카드를 보관하고,
 * 줄과 카드 상태로부터 "지금 유효한 결정"과 "조건 충족 여부"를 계산한다.
 *
 * <ul>
 * <li>카드 사이의 연결은 여기에 저장하지 않는다. 항상 게시판 관리(graph)에 물어본다.</li>
 * <li>계산 결과(항목의 현재 결정, 사건의 지위, 조건 상태)는 카드에 적어 두고, 바뀌면 카드 버전을 올린다.
 * 그래야 그 카드를 참고한 답변이 "내가 본 뒤에 바뀌었다"는 것을 알 수 있다.</li>
 * <li>행이 바뀌면 항상 revision을 1 올린다.</li>
 * <li>상태를 바꾸는 함수는 결재 창구(review-impact)만 부른다. 줄의 상태를 바꾼 뒤에는
 * {@link #onEdgeChanged(Edge)}를 불러야 계산 결과가 따라온다.</li>
 * </ul>
 */
@Service
public class DecisionTask {

	private static final Set<ReviewStatus> CONFIRMED_ONLY = EnumSet.of(ReviewStatus.CONFIRMED);

	/** 이 모듈이 소유하는 카드 종류와 테이블. label은 사람이 읽는 한 줄이 들어 있는 컬럼이다. */
	private enum Table {
		TOPIC(NodeType.TOPIC, "topic", "title"),
		ITEM(NodeType.DECISION_ITEM, "decision_item", "title"),
		EVENT(NodeType.DECISION_EVENT, "decision_event", "statement"),
		CONDITION(NodeType.CONDITION, "decision_condition", "description"),
		TASK(NodeType.TASK, "task", "title");

		final NodeType type;
		final String name;
		final String label;

		Table(NodeType type, String name, String label) {
			this.type = type;
			this.name = name;
			this.label = label;
		}

		static Optional<Table> find(NodeType type) {
			for (Table t : values()) {
				if (t.type == type) {
					return Optional.of(t);
				}
			}
			return Optional.empty();
		}

		static Table of(NodeType type) {
			return find(type).orElseThrow(
					() -> new IllegalArgumentException("결정·업무 관리가 소유하지 않는 카드 종류다: " + type));
		}
	}

	/** 모든 카드에 공통인 값. */
	private record Row(UUID id, UUID projectId, ReviewStatus status, long revision) {
	}

	private record ItemRow(Row row, DecisionStatus state, UUID currentEventId) {
	}

	private record EventRow(Row row, EventKind kind, EventStanding standing) {
	}

	/** 검사를 마치고 저장만 남은 후보. */
	private record Prepared(String key, Table table, UUID id, Map<String, Object> values) {
	}

	private final JdbcClient jdbc;
	private final DecisionGraph graph;

	public DecisionTask(JdbcClient jdbc, DecisionGraph graph) {
		this.jdbc = jdbc;
		this.graph = graph;
	}

	/** 이 모듈이 내용을 갖고 있는 카드 종류인가(안건, 결정 항목, 결정 사건, 조건, 할 일). */
	public static boolean owns(NodeType type) {
		return Table.find(type).isPresent();
	}

	// ---- 저장 ----

	/**
	 * AI나 외부 에이전트가 낸 카드 후보를 저장한다. 모두 CANDIDATE, revision 1로 들어간다.
	 * 하나라도 틀리면 아무것도 저장하지 않는다.
	 *
	 * <p>카드별 내용: TOPIC·DECISION_ITEM·TASK는 title, DECISION_EVENT는 statement와 kind,
	 * CONDITION은 description이 필수다. DECISION_EVENT의 occurredAt과 TASK의 dueAt은 선택이다.
	 *
	 * @return 후보의 key → 저장된 카드
	 */
	@Transactional
	public Map<String, NodeRef> saveCandidates(UUID projectId, List<NodeDraft> drafts) {
		Objects.requireNonNull(projectId, "projectId");
		Objects.requireNonNull(drafts, "drafts");

		List<Prepared> prepared = new ArrayList<>();
		Set<String> keys = new HashSet<>();
		for (NodeDraft draft : drafts) {
			if (!keys.add(draft.key())) {
				throw new IllegalArgumentException("같은 key가 두 번 나왔다: " + draft.key());
			}
			prepared.add(prepare(draft));
		}

		Map<String, NodeRef> saved = new LinkedHashMap<>();
		for (Prepared p : prepared) {
			insert(projectId, p);
			saved.put(p.key(), new NodeRef(p.table().type, p.id(), 1));
		}
		return saved;
	}

	// ---- 조회 ----

	/**
	 * 카드 내용과 상태를 읽는다. 결과는 요청한 순서를 따른다.
	 * 이 프로젝트에 없는 카드와, 이 모듈이 소유하지 않는 종류(발언, 인물 등)는 결과에서 빠진다.
	 */
	@Transactional(readOnly = true)
	public List<NodeView> getNodes(UUID projectId, Collection<NodeRef> refs) {
		Objects.requireNonNull(projectId, "projectId");
		Map<NodeKey, NodeView> found = new HashMap<>();
		for (Map.Entry<Table, List<UUID>> entry : groupByTable(refs).entrySet()) {
			Table table = entry.getKey();
			jdbc.sql("select * from " + table.name + " where project_id = :projectId and id in (:ids)")
					.param("projectId", projectId)
					.param("ids", entry.getValue())
					.query((rs, n) -> view(table, rs))
					.list()
					.forEach(v -> found.put(v.ref().key(), v));
		}
		List<NodeView> ordered = new ArrayList<>();
		Set<NodeKey> seen = new HashSet<>();
		for (NodeRef ref : refs) {
			NodeView v = found.get(ref.key());
			if (v != null && seen.add(ref.key())) {
				ordered.add(v);
			}
		}
		return ordered;
	}

	/**
	 * 이 프로젝트에 있는 한 종류의 카드 전부. 만들어진 순서로 돌려준다.
	 *
	 * @param statuses 보려는 확인 상태. 비워 둘 수 없다
	 */
	@Transactional(readOnly = true)
	public List<NodeView> listNodes(UUID projectId, NodeType type, Set<ReviewStatus> statuses) {
		Objects.requireNonNull(projectId, "projectId");
		if (statuses == null || statuses.isEmpty()) {
			throw new IllegalArgumentException("보려는 확인 상태를 하나 이상 지정해야 한다");
		}
		Table table = Table.of(type);
		return jdbc.sql("select * from " + table.name
				+ " where project_id = :projectId and status in (:statuses) order by created_at, id")
				.param("projectId", projectId)
				.param("statuses", statuses.stream().map(Enum::name).toList())
				.query((rs, n) -> view(table, rs))
				.list();
	}

	/** 카드마다 현재 버전. 없는 카드는 결과에서 빠진다. */
	@Transactional(readOnly = true)
	public Map<NodeKey, Long> currentRevisions(UUID projectId, Collection<NodeRef> refs) {
		Objects.requireNonNull(projectId, "projectId");
		Map<NodeKey, Long> revisions = new HashMap<>();
		for (Map.Entry<Table, List<UUID>> entry : groupByTable(refs).entrySet()) {
			Table table = entry.getKey();
			jdbc.sql("select id, revision from " + table.name + " where project_id = :projectId and id in (:ids)")
					.param("projectId", projectId)
					.param("ids", entry.getValue())
					.query((rs, n) -> Map.entry(new NodeKey(table.type, rs.getObject("id", UUID.class)),
							rs.getLong("revision")))
					.list()
					.forEach(e -> revisions.put(e.getKey(), e.getValue()));
		}
		return revisions;
	}

	/**
	 * 이 항목에서 지금 유효한 결정. 카드에 적어 둔 값을 믿지 않고 줄에서 다시 계산한다(읽기만 한다).
	 */
	@Transactional(readOnly = true)
	public DecisionState currentDecision(UUID decisionItemId) {
		return project(decisionItemId, false);
	}

	// ---- 상태 변경 (결재 창구만 호출) ----

	/**
	 * 카드의 확인 상태를 바꾼다. 줄과 같은 전이 규칙을 쓴다.
	 * 이 카드에 걸린 계산 결과(현재 결정, 조건 상태)도 함께 다시 계산한다.
	 *
	 * @param expectedRevision 검토자가 화면에서 본 카드 버전
	 * @return 카드와 바뀐 뒤의 버전
	 */
	@Transactional
	public NodeRef setNodeStatus(NodeRef node, ReviewStatus next, UUID reviewer, long expectedRevision) {
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(next, "status");
		Objects.requireNonNull(reviewer, "reviewer");
		Table table = Table.of(node.type());

		Row current = lock(table, node.id());
		requireRevision(table, current, expectedRevision);
		if (!current.status().canMoveTo(next)) {
			throw new IllegalStateException("허용되지 않은 상태 변화: " + current.status() + " → " + next);
		}

		boolean confirming = next == ReviewStatus.CONFIRMED;
		update(table, current, """
				status = :status,
				confirmed_by = case when :confirming then :reviewer else confirmed_by end,
				confirmed_at = case when :confirming then now() else confirmed_at end
				""", Map.of("status", next.name(), "confirming", confirming, "reviewer", reviewer));

		switch (table) {
			case EVENT -> recomputeAroundEvents(current.projectId(), eventsTouchedBy(current));
			case TASK -> recomputeConditionsLeaningOn(current);
			default -> {
				// 안건·항목·조건의 확인 상태는 다른 카드의 계산 결과에 영향을 주지 않는다.
			}
		}
		return ref(table, node.id());
	}

	/**
	 * 카드 내용을 정정한다. 준 값만 바꾸고 나머지는 그대로 둔다. 거절·철회된 카드는 고칠 수 없다.
	 * 쓸 수 있는 값은 {@link #saveCandidates}와 같다. occurredAt과 dueAt은 null을 주면 비운다.
	 * 사건의 유형이 바뀌면 그 사건이 속한 항목의 현재 결정도 다시 계산한다.
	 */
	@Transactional
	public NodeRef updateFields(NodeRef node, Map<String, Object> fields, long expectedRevision) {
		Objects.requireNonNull(node, "node");
		if (fields == null || fields.isEmpty()) {
			throw new IllegalArgumentException("정정할 값이 없다");
		}
		Table table = Table.of(node.type());
		Row current = lock(table, node.id());
		requireRevision(table, current, expectedRevision);
		if (current.status() == ReviewStatus.REJECTED || current.status() == ReviewStatus.RETRACTED) {
			throw new IllegalStateException("거절되거나 철회된 카드는 정정할 수 없다: " + current.status());
		}

		NodeDraft draft = new NodeDraft("정정", node.type(), fields);
		List<String> assignments = new ArrayList<>();
		Map<String, Object> params = new HashMap<>();
		for (String name : fields.keySet()) {
			String column = switch (table) {
				case TOPIC, ITEM, TASK -> name.equals("title") ? "title"
						: table == Table.TASK && name.equals("dueAt") ? "due_at" : null;
				case EVENT -> switch (name) {
					case "statement" -> "statement";
					case "kind" -> "kind";
					case "occurredAt" -> "occurred_at";
					default -> null;
				};
				case CONDITION -> name.equals("description") ? "description" : null;
			};
			if (column == null) {
				throw new IllegalArgumentException(node.type() + " 카드에 쓸 수 없는 값이다: " + name);
			}
			Object value = switch (name) {
				case "kind" -> requireKind(draft).name();
				case "occurredAt", "dueAt" -> optionalTime(draft, name);
				default -> requireText(draft, name);
			};
			assignments.add(column + " = :" + name);
			params.put(name, value);
		}
		update(table, current, String.join(", ", assignments), params);

		if (table == Table.EVENT && fields.containsKey("kind")) {
			recomputeAroundEvents(current.projectId(), List.of(node.id()));
		}
		return ref(table, node.id());
	}

	/**
	 * 항목의 현재 결정을 다시 계산해 카드에 적는다. 그 항목에 속한 사건의 지위도 함께 갱신한다.
	 * 바뀐 것이 없으면 버전은 그대로다.
	 */
	@Transactional
	public DecisionState recomputeProjection(UUID decisionItemId) {
		return project(decisionItemId, true);
	}

	/**
	 * 조건 충족 여부를 다시 계산해 카드에 적는다.
	 * 확인된 충족 줄이 발언을 가리키거나, 확인되고 완료된 할 일을 가리키면 충족이다.
	 */
	@Transactional
	public ConditionState recomputeCondition(UUID conditionId) {
		Row condition = lock(Table.CONDITION, conditionId);
		NodeRef self = new NodeRef(NodeType.CONDITION, conditionId, condition.revision());
		List<Edge> satisfiedBy = graph.edgesOf(condition.projectId(), self, Direction.BASIS,
				EnumSet.of(RelType.SATISFIED_BY), CONFIRMED_ONLY);

		boolean bySpan = satisfiedBy.stream().anyMatch(e -> e.dst().type() == NodeType.SPAN);
		List<UUID> taskIds = satisfiedBy.stream()
				.filter(e -> e.dst().type() == NodeType.TASK)
				.map(e -> e.dst().id())
				.toList();
		ConditionState next = bySpan || anyTaskDone(condition.projectId(), taskIds)
				? ConditionState.MET : ConditionState.UNMET;

		ConditionState stored = ConditionState.valueOf(jdbc.sql("select state from decision_condition where id = :id")
				.param("id", conditionId)
				.query(String.class)
				.single());
		if (stored != next) {
			update(Table.CONDITION, condition, "state = :state", Map.of("state", next.name()));
		}
		return next;
	}

	/**
	 * 줄의 상태가 바뀐 뒤에 부른다. 그 줄에 걸린 계산 결과를 다시 계산한다.
	 * 항목–사건, 대체, 철회 줄이면 현재 결정을, 충족 줄이면 조건 상태를 다시 계산한다.
	 * 그 밖의 줄은 계산 결과에 영향을 주지 않는다.
	 */
	@Transactional
	public void onEdgeChanged(Edge edge) {
		Objects.requireNonNull(edge, "edge");
		switch (edge.rel()) {
			case HAS_EVENT -> project(edge.src().id(), true);
			case SUPERSEDES, WITHDRAWS ->
				recomputeAroundEvents(edge.projectId(), List.of(edge.src().id(), edge.dst().id()));
			case SATISFIED_BY -> recomputeCondition(edge.src().id());
			default -> {
				// 계산 결과와 무관한 줄
			}
		}
	}

	/**
	 * 할 일의 진행 상태를 바꾼다. 확인된 할 일만 바꿀 수 있다.
	 * 이 할 일의 완료에 기대고 있는 조건도 함께 다시 계산한다.
	 */
	@Transactional
	public NodeRef changeTaskStatus(UUID taskId, TaskStatus next, UUID actor, long expectedRevision) {
		Objects.requireNonNull(next, "status");
		Objects.requireNonNull(actor, "actor");
		Row task = lock(Table.TASK, taskId);
		requireRevision(Table.TASK, task, expectedRevision);
		requireConfirmedTask(task);

		TaskStatus current = TaskStatus.valueOf(jdbc.sql("select work_status from task where id = :id")
				.param("id", taskId)
				.query(String.class)
				.single());
		if (current == next) {
			throw new IllegalStateException("이미 그 상태다: " + next);
		}
		update(Table.TASK, task, """
				work_status = :workStatus,
				work_status_changed_by = :actor,
				work_status_changed_at = now()
				""", Map.of("workStatus", next.name(), "actor", actor));

		recomputeConditionsLeaningOn(task);
		return ref(Table.TASK, taskId);
	}

	/**
	 * 담당자 본인의 수락·거절을 기록한다. 확인된 할 일만 가능하다.
	 * 이 계정이 정말 담당자인지는 결재 창구가 확인한 뒤 호출한다.
	 */
	@Transactional
	public NodeRef recordAcceptance(UUID taskId, UUID accountId, boolean accepted) {
		Objects.requireNonNull(accountId, "accountId");
		Row task = lock(Table.TASK, taskId);
		requireConfirmedTask(task);
		update(Table.TASK, task, """
				acceptance = :acceptance,
				acceptance_by = :accountId,
				acceptance_at = now()
				""", Map.of("acceptance", (accepted ? Acceptance.ACCEPTED : Acceptance.DECLINED).name(),
				"accountId", accountId));
		return ref(Table.TASK, taskId);
	}

	// ---- 현재 결정 계산 ----

	/**
	 * 항목의 현재 결정을 계산한다.
	 *
	 * <ol>
	 * <li>확인된 항목–사건 줄로 이어진, 확인된 사건 카드만 본다.</li>
	 * <li>사건마다 지위를 구한다: 확인된 철회 줄을 받았으면 WITHDRAWN, 대체 줄을 받았으면 SUPERSEDED.</li>
	 * <li>확정·변경 사건 중 살아 있는 것의 개수로 항목 상태를 정한다. 최신 사건이 저절로 이기지 않는다.</li>
	 * </ol>
	 *
	 * @param write true면 계산 결과를 카드에 적는다
	 */
	private DecisionState project(UUID itemId, boolean write) {
		Objects.requireNonNull(itemId, "decisionItemId");
		ItemRow item = loadItem(itemId, write);
		UUID projectId = item.row().projectId();
		NodeRef self = new NodeRef(NodeType.DECISION_ITEM, itemId, item.row().revision());

		List<UUID> eventIds = graph.edgesOf(projectId, self, Direction.BASIS,
				EnumSet.of(RelType.HAS_EVENT), CONFIRMED_ONLY).stream()
				.map(e -> e.dst().id())
				.toList();

		boolean decidedOnce = false;
		List<NodeRef> alive = new ArrayList<>();
		for (EventRow event : loadConfirmedEvents(projectId, eventIds, write)) {
			EventStanding standing = computeStanding(projectId, event.row());
			long revision = event.row().revision();
			if (write && standing != event.standing()) {
				revision = update(Table.EVENT, event.row(), "standing = :standing",
						Map.of("standing", standing.name()));
			}
			if (event.kind().decides()) {
				decidedOnce = true;
				if (standing == EventStanding.ACTIVE) {
					alive.add(new NodeRef(NodeType.DECISION_EVENT, event.row().id(), revision));
				}
			}
		}

		DecisionStatus status;
		if (!decidedOnce) {
			status = DecisionStatus.UNDECIDED;
		} else if (alive.isEmpty()) {
			status = DecisionStatus.WITHDRAWN;
		} else if (alive.size() == 1) {
			status = DecisionStatus.DECIDED;
		} else {
			status = DecisionStatus.CONTESTED;
		}
		NodeRef current = status == DecisionStatus.DECIDED ? alive.get(0) : null;
		UUID currentId = current == null ? null : current.id();

		long itemRevision = item.row().revision();
		if (write && (status != item.state() || !Objects.equals(currentId, item.currentEventId()))) {
			Map<String, Object> params = new HashMap<>();
			params.put("state", status.name());
			params.put("currentEventId", currentId);
			itemRevision = update(Table.ITEM, item.row(),
					"decision_state = :state, current_event_id = :currentEventId", params);
		}
		return new DecisionState(new NodeRef(NodeType.DECISION_ITEM, itemId, itemRevision), status, current,
				status == DecisionStatus.CONTESTED ? alive : List.of());
	}

	/** 사건이 받은 확인된 대체·철회 줄로 지위를 구한다. 줄을 그은 쪽 사건 카드도 확인 상태여야 센다. */
	private EventStanding computeStanding(UUID projectId, Row event) {
		NodeRef self = new NodeRef(NodeType.DECISION_EVENT, event.id(), event.revision());
		List<Edge> incoming = graph.edgesOf(projectId, self, Direction.IMPACT,
				EnumSet.of(RelType.SUPERSEDES, RelType.WITHDRAWS), CONFIRMED_ONLY);
		if (incoming.isEmpty()) {
			return EventStanding.ACTIVE;
		}
		Set<UUID> confirmedSources = confirmedIds(Table.EVENT, projectId,
				incoming.stream().map(e -> e.src().id()).toList());
		boolean withdrawn = false;
		boolean superseded = false;
		for (Edge e : incoming) {
			if (!confirmedSources.contains(e.src().id())) {
				continue;
			}
			withdrawn |= e.rel() == RelType.WITHDRAWS;
			superseded |= e.rel() == RelType.SUPERSEDES;
		}
		if (withdrawn) {
			return EventStanding.WITHDRAWN;
		}
		return superseded ? EventStanding.SUPERSEDED : EventStanding.ACTIVE;
	}

	/** 이 사건의 확인 상태가 바뀌면 영향받는 사건들: 자기 자신과, 자신이 대체·철회한 사건. */
	private List<UUID> eventsTouchedBy(Row event) {
		NodeRef self = new NodeRef(NodeType.DECISION_EVENT, event.id(), event.revision());
		List<UUID> touched = new ArrayList<>();
		touched.add(event.id());
		graph.edgesOf(event.projectId(), self, Direction.BASIS,
				EnumSet.of(RelType.SUPERSEDES, RelType.WITHDRAWS), CONFIRMED_ONLY)
				.forEach(e -> touched.add(e.dst().id()));
		return touched;
	}

	/** 주어진 사건들의 지위를 갱신하고, 그 사건들이 속한 항목의 현재 결정을 다시 계산한다. */
	private void recomputeAroundEvents(UUID projectId, Collection<UUID> eventIds) {
		Set<UUID> itemIds = new LinkedHashSet<>();
		for (UUID eventId : new LinkedHashSet<>(eventIds)) {
			Optional<EventRow> event = findEvent(eventId, true);
			if (event.isEmpty()) {
				continue;
			}
			Row row = event.get().row();
			EventStanding standing = computeStanding(projectId, row);
			if (standing != event.get().standing()) {
				update(Table.EVENT, row, "standing = :standing", Map.of("standing", standing.name()));
			}
			NodeRef self = new NodeRef(NodeType.DECISION_EVENT, eventId, row.revision());
			graph.edgesOf(projectId, self, Direction.IMPACT, EnumSet.of(RelType.HAS_EVENT), CONFIRMED_ONLY)
					.forEach(e -> itemIds.add(e.src().id()));
		}
		itemIds.forEach(itemId -> project(itemId, true));
	}

	/** 이 할 일의 완료에 기대고 있는 조건들을 다시 계산한다. */
	private void recomputeConditionsLeaningOn(Row task) {
		NodeRef self = new NodeRef(NodeType.TASK, task.id(), task.revision());
		graph.edgesOf(task.projectId(), self, Direction.IMPACT, EnumSet.of(RelType.SATISFIED_BY), CONFIRMED_ONLY)
				.stream()
				.map(e -> e.src().id())
				.distinct()
				.forEach(this::recomputeCondition);
	}

	private boolean anyTaskDone(UUID projectId, List<UUID> taskIds) {
		if (taskIds.isEmpty()) {
			return false;
		}
		return jdbc.sql("""
				select count(*) from task
				 where project_id = :projectId and id in (:ids)
				   and status = 'CONFIRMED' and work_status = 'DONE'
				""")
				.param("projectId", projectId)
				.param("ids", taskIds)
				.query(Long.class)
				.single() > 0;
	}

	// ---- 후보 검사와 저장 ----

	private static Prepared prepare(NodeDraft draft) {
		Table table = Table.of(draft.type());
		Map<String, Object> fields = draft.fields();
		Map<String, Object> values = new HashMap<>();
		Set<String> allowed;
		switch (table) {
			case TOPIC, ITEM -> {
				allowed = Set.of("title");
				values.put("title", requireText(draft, "title"));
			}
			case EVENT -> {
				allowed = Set.of("statement", "kind", "occurredAt");
				values.put("statement", requireText(draft, "statement"));
				values.put("kind", requireKind(draft).name());
				values.put("occurredAt", optionalTime(draft, "occurredAt"));
			}
			case CONDITION -> {
				allowed = Set.of("description");
				values.put("description", requireText(draft, "description"));
			}
			case TASK -> {
				allowed = Set.of("title", "dueAt");
				values.put("title", requireText(draft, "title"));
				values.put("dueAt", optionalTime(draft, "dueAt"));
			}
			default -> throw new IllegalStateException("처리하지 않은 테이블: " + table);
		}
		for (String name : fields.keySet()) {
			if (!allowed.contains(name)) {
				throw new IllegalArgumentException(
						"후보 '" + draft.key() + "'(" + draft.type() + ")에 쓸 수 없는 값이다: " + name);
			}
		}
		return new Prepared(draft.key(), table, UUID.randomUUID(), values);
	}

	/** created_at은 한 트랜잭션 안에서도 저장한 순서가 남도록 실제 시각으로 적는다. */
	private void insert(UUID projectId, Prepared p) {
		String sql = switch (p.table()) {
			case TOPIC -> "insert into topic (id, project_id, title, created_at)"
					+ " values (:id, :projectId, :title, clock_timestamp())";
			case ITEM -> "insert into decision_item (id, project_id, title, created_at)"
					+ " values (:id, :projectId, :title, clock_timestamp())";
			case EVENT -> """
					insert into decision_event (id, project_id, kind, statement, occurred_at, created_at)
					values (:id, :projectId, :kind, :statement, :occurredAt, clock_timestamp())
					""";
			case CONDITION -> """
					insert into decision_condition (id, project_id, description, created_at)
					values (:id, :projectId, :description, clock_timestamp())
					""";
			case TASK -> "insert into task (id, project_id, title, due_at, created_at)"
					+ " values (:id, :projectId, :title, :dueAt, clock_timestamp())";
		};
		JdbcClient.StatementSpec spec = jdbc.sql(sql).param("id", p.id()).param("projectId", projectId);
		for (Map.Entry<String, Object> value : p.values().entrySet()) {
			spec = spec.param(value.getKey(), value.getValue());
		}
		spec.update();
	}

	private static String requireText(NodeDraft draft, String name) {
		Object value = draft.fields().get(name);
		if (!(value instanceof String text) || text.isBlank()) {
			throw new IllegalArgumentException(
					"후보 '" + draft.key() + "'(" + draft.type() + ")에 " + name + " 값이 필요하다");
		}
		return text.strip();
	}

	private static EventKind requireKind(NodeDraft draft) {
		Object value = draft.fields().get("kind");
		if (value instanceof EventKind kind) {
			return kind;
		}
		if (value instanceof String text) {
			try {
				return EventKind.valueOf(text.strip());
			} catch (IllegalArgumentException e) {
				// 아래에서 같은 메시지로 거절한다
			}
		}
		throw new IllegalArgumentException("후보 '" + draft.key() + "'의 kind가 올바르지 않다: " + value);
	}

	/** 시각은 Instant, OffsetDateTime, 또는 "2026-11-03T10:00:00+09:00" 같은 글자로 받는다. */
	private static OffsetDateTime optionalTime(NodeDraft draft, String name) {
		Object value = draft.fields().get(name);
		if (value == null) {
			return null;
		}
		if (value instanceof Instant instant) {
			return instant.atOffset(ZoneOffset.UTC);
		}
		if (value instanceof OffsetDateTime time) {
			return time;
		}
		if (value instanceof String text) {
			try {
				return OffsetDateTime.parse(text.strip());
			} catch (DateTimeParseException e) {
				// 아래에서 같은 메시지로 거절한다
			}
		}
		throw new IllegalArgumentException(
				"후보 '" + draft.key() + "'의 " + name + "은 시간대가 포함된 시각이어야 한다: " + value);
	}

	// ---- 행 읽기와 쓰기 ----

	/** 카드를 읽으면서 다른 변경이 끼어들지 못하게 잠근다. 없으면 예외. */
	// ---- 계정 삭제 ----

	/** 카드를 지운다. 계정 삭제 전용이고, 이 카드에 닿는 줄은 그래프에서 먼저 지운다. */
	@Transactional
	public void deleteNodes(UUID projectId, Collection<NodeKey> nodes) {
		Objects.requireNonNull(projectId, "projectId");
		for (Table table : Table.values()) {
			List<UUID> ids = nodes.stream().filter(key -> key.type() == table.type).map(NodeKey::id).toList();
			if (!ids.isEmpty()) {
				jdbc.sql("delete from " + table.name + " where project_id = :projectId and id in (:ids)")
						.param("projectId", projectId)
						.param("ids", ids)
						.update();
			}
		}
	}

	/** 프로젝트의 모든 결정 항목의 현재 결정과 모든 조건의 충족 여부를 다시 계산한다. 카드나 줄을 지운 뒤에 부른다. */
	@Transactional
	public void recomputeAll(UUID projectId) {
		Objects.requireNonNull(projectId, "projectId");
		jdbc.sql("select id from decision_item where project_id = :projectId order by id")
				.param("projectId", projectId)
				.query(UUID.class)
				.list()
				.forEach(this::recomputeProjection);
		jdbc.sql("select id from decision_condition where project_id = :projectId order by id")
				.param("projectId", projectId)
				.query(UUID.class)
				.list()
				.forEach(this::recomputeCondition);
	}

	/** 프로젝트의 카드를 모두 지운다. 계정 삭제로 프로젝트를 없앨 때만 쓴다. */
	@Transactional
	public void purgeProject(UUID projectId) {
		for (Table table : Table.values()) {
			jdbc.sql("delete from " + table.name + " where project_id = :projectId").param("projectId", projectId)
					.update();
		}
	}

	private Row lock(Table table, UUID id) {
		Objects.requireNonNull(id, "id");
		return jdbc.sql("select id, project_id, status, revision from " + table.name
				+ " where id = :id for update")
				.param("id", id)
				.query(DecisionTask::mapRow)
				.optional()
				.orElseThrow(() -> new NodeNotFoundException(table.type, id));
	}

	private ItemRow loadItem(UUID itemId, boolean forUpdate) {
		return jdbc.sql("select id, project_id, status, revision, decision_state, current_event_id"
				+ " from decision_item where id = :id" + (forUpdate ? " for update" : ""))
				.param("id", itemId)
				.query((rs, n) -> new ItemRow(mapRow(rs, n), DecisionStatus.valueOf(rs.getString("decision_state")),
						rs.getObject("current_event_id", UUID.class)))
				.optional()
				.orElseThrow(() -> new NodeNotFoundException(NodeType.DECISION_ITEM, itemId));
	}

	private Optional<EventRow> findEvent(UUID eventId, boolean forUpdate) {
		return jdbc.sql("select id, project_id, status, revision, kind, standing"
				+ " from decision_event where id = :id" + (forUpdate ? " for update" : ""))
				.param("id", eventId)
				.query(DecisionTask::mapEvent)
				.optional();
	}

	/** 확인된 사건만, 일어난 순서로. 일어난 시각이 없는 사건은 뒤로 간다. */
	private List<EventRow> loadConfirmedEvents(UUID projectId, List<UUID> eventIds, boolean forUpdate) {
		if (eventIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("select id, project_id, status, revision, kind, standing from decision_event"
				+ " where project_id = :projectId and id in (:ids) and status = 'CONFIRMED'"
				+ " order by occurred_at nulls last, created_at, id" + (forUpdate ? " for update" : ""))
				.param("projectId", projectId)
				.param("ids", eventIds)
				.query(DecisionTask::mapEvent)
				.list();
	}

	private Set<UUID> confirmedIds(Table table, UUID projectId, List<UUID> ids) {
		if (ids.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(jdbc.sql("select id from " + table.name
				+ " where project_id = :projectId and id in (:ids) and status = 'CONFIRMED'")
				.param("projectId", projectId)
				.param("ids", ids)
				.query((rs, n) -> rs.getObject("id", UUID.class))
				.list());
	}

	/**
	 * 행을 고치고 버전을 1 올린다. 읽은 뒤에 버전이 바뀌었으면 고치지 않고 예외를 낸다.
	 *
	 * @return 올라간 뒤의 버전
	 */
	private long update(Table table, Row row, String assignments, Map<String, ?> params) {
		JdbcClient.StatementSpec spec = jdbc.sql("update " + table.name + " set " + assignments.strip()
				+ ", revision = revision + 1, updated_at = now() where id = :id and revision = :revision")
				.param("id", row.id())
				.param("revision", row.revision());
		for (Map.Entry<String, ?> param : params.entrySet()) {
			spec = spec.param(param.getKey(), param.getValue());
		}
		if (spec.update() == 0) {
			throw new NodeRevisionConflictException(table.type, row.id(), row.revision(),
					currentRevision(table, row.id()));
		}
		return row.revision() + 1;
	}

	private long currentRevision(Table table, UUID id) {
		return jdbc.sql("select revision from " + table.name + " where id = :id")
				.param("id", id)
				.query(Long.class)
				.optional()
				.orElseThrow(() -> new NodeNotFoundException(table.type, id));
	}

	private NodeRef ref(Table table, UUID id) {
		return new NodeRef(table.type, id, currentRevision(table, id));
	}

	private static void requireRevision(Table table, Row row, long expectedRevision) {
		if (row.revision() != expectedRevision) {
			throw new NodeRevisionConflictException(table.type, row.id(), expectedRevision, row.revision());
		}
	}

	private static void requireConfirmedTask(Row task) {
		if (task.status() != ReviewStatus.CONFIRMED) {
			throw new IllegalStateException("확인되지 않은 할 일은 진행 상태나 수락을 바꿀 수 없다: " + task.status());
		}
	}

	private static Map<Table, List<UUID>> groupByTable(Collection<NodeRef> refs) {
		Objects.requireNonNull(refs, "refs");
		Map<Table, List<UUID>> grouped = new LinkedHashMap<>();
		for (NodeRef ref : refs) {
			Table.find(ref.type()).ifPresent(
					table -> grouped.computeIfAbsent(table, t -> new ArrayList<>()).add(ref.id()));
		}
		return grouped;
	}

	private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
				ReviewStatus.valueOf(rs.getString("status")), rs.getLong("revision"));
	}

	private static EventRow mapEvent(ResultSet rs, int rowNum) throws SQLException {
		return new EventRow(mapRow(rs, rowNum), EventKind.valueOf(rs.getString("kind")),
				EventStanding.valueOf(rs.getString("standing")));
	}

	private static NodeView view(Table table, ResultSet rs) throws SQLException {
		Map<String, String> states = new LinkedHashMap<>();
		switch (table) {
			case TOPIC -> {
				// 안건에는 따로 상태가 없다
			}
			case ITEM -> {
				states.put("decisionState", rs.getString("decision_state"));
				put(states, "currentEventId", rs.getObject("current_event_id", UUID.class));
			}
			case EVENT -> {
				states.put("kind", rs.getString("kind"));
				states.put("standing", rs.getString("standing"));
				put(states, "occurredAt", instant(rs, "occurred_at"));
			}
			case CONDITION -> states.put("state", rs.getString("state"));
			case TASK -> {
				states.put("workStatus", rs.getString("work_status"));
				states.put("acceptance", rs.getString("acceptance"));
				put(states, "acceptanceBy", rs.getObject("acceptance_by", UUID.class));
				put(states, "dueAt", instant(rs, "due_at"));
			}
		}
		return new NodeView(
				new NodeRef(table.type, rs.getObject("id", UUID.class), rs.getLong("revision")),
				rs.getObject("project_id", UUID.class),
				ReviewStatus.valueOf(rs.getString("status")),
				rs.getString(table.label),
				Collections.unmodifiableMap(states),
				rs.getObject("confirmed_by", UUID.class),
				instant(rs, "confirmed_at"));
	}

	private static void put(Map<String, String> states, String name, Object value) {
		if (value != null) {
			states.put(name, value.toString());
		}
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime time = rs.getObject(column, OffsetDateTime.class);
		return time == null ? null : time.toInstant();
	}
}
