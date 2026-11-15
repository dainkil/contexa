package com.contexa.server.gateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Caller;
import com.contexa.server.contract.ContextPacket;
import com.contexa.server.contract.Direction;
import com.contexa.server.contract.Edge;
import com.contexa.server.contract.EdgeRef;
import com.contexa.server.contract.EvidenceRef;
import com.contexa.server.contract.Fact;
import com.contexa.server.contract.NodeKey;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.OpenIssue;
import com.contexa.server.contract.RelType;
import com.contexa.server.contract.ReviewStatus;
import com.contexa.server.contract.Scope;
import com.contexa.server.contract.Source;
import com.contexa.server.contract.StaleRef;
import com.contexa.server.decision.DecisionStatus;
import com.contexa.server.decision.DecisionTask;
import com.contexa.server.decision.NodeNotFoundException;
import com.contexa.server.contract.NodeView;
import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.document.Span;
import com.contexa.server.entity.EntityCandidate;
import com.contexa.server.entity.EntityReview;
import com.contexa.server.entity.NameCard;
import com.contexa.server.graph.DecisionGraph;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.retrieval.Retrieval;
import com.contexa.server.retrieval.SpanHit;

/**
 * 안내 데스크(맥락 게이트웨이): 화면, Q&A, 외부 AI가 결정 정보를 읽는 유일한 출입구.
 * 읽기만 하며, 누가 묻든 같은 양식({@link ContextPacket})과 같은 규칙으로 답한다.
 *
 * <ul>
 * <li>확인된 것(CONFIRMED)과 AI 후보(CANDIDATE)를 상태 표시와 함께 돌려준다.</li>
 * <li>보류(HELD)된 카드와 줄은 사실로 쓰지 않고 open에 넣는다. 보류된 줄 너머로는 따라가지 않는다.</li>
 * <li>거절·철회된 카드와 줄은 돌려주지 않는다. 그런 카드 너머로도 따라가지 않는다.</li>
 * <li>호출자의 프로젝트 밖은 보이지 않는다.</li>
 * <li>근거를 볼 수 없으면 그 카드와 줄도 보이지 않는다. 근거 발언이 있는 카드는 그중 하나라도 읽을 수 있어야
 * 보이고, 줄은 근거로 적힌 발언 중 하나라도 읽을 수 있어야 보인다. 개인 메모에서 나온 내용은 메모 주인에게만,
 * 외부로 나가는 요청에는 외부 공유가 허용된 문서의 내용만 보인다.</li>
 * <li>근거로 나온 발언의 원문은 sources에 싣는다. 읽을 수 없는 발언은 싣지 않는다.</li>
 * </ul>
 *
 * 요청자({@link Caller})는 출입 관리가 토큰이나 작업 기록에서 만든 것을 그대로 믿는다.
 * 근거 줄이 없는 카드(안건, 결정 항목, 인물·기관)는 프로젝트 안에서 모두에게 보인다.
 * 산출물(답변) 카드의 내용은 아직 없어서, 줄의 끝점으로만 나온다.
 */
@Service
public class ContextGateway {

	private static final Logger log = LoggerFactory.getLogger(ContextGateway.class);

	/** 읽어 오는 상태. 보류는 open에 알리기 위해 함께 읽는다. */
	private static final Set<ReviewStatus> READABLE =
			EnumSet.of(ReviewStatus.CANDIDATE, ReviewStatus.CONFIRMED, ReviewStatus.HELD);

	/** 사실과 관계로 돌려주는 상태. */
	private static final Set<ReviewStatus> USABLE = EnumSet.of(ReviewStatus.CANDIDATE, ReviewStatus.CONFIRMED);

	/** 내용을 읽어 사실로 돌려줄 수 있는 카드 종류. 결정·업무 관리의 다섯 종류와 인물·기관. */
	private static final Set<NodeType> CARDS = EnumSet.of(NodeType.TOPIC, NodeType.DECISION_ITEM,
			NodeType.DECISION_EVENT, NodeType.CONDITION, NodeType.TASK, NodeType.ENTITY);

	private static final Set<RelType> DEPENDENCIES = EnumSet.allOf(RelType.class).stream()
			.filter(RelType::isDependency)
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(RelType.class)));

	/** 근거 줄을 가질 수 있는 카드. 근거가 있는데 하나도 읽을 수 없으면 그 카드는 보이지 않는다. */
	private static final Set<NodeType> EVIDENCED = EnumSet.of(NodeType.DECISION_EVENT, NodeType.CONDITION,
			NodeType.TASK);

	private final DecisionGraph graph;
	private final DecisionTask decision;
	private final DocumentEvidence document;
	private final IdentityAccess identity;
	private final EntityReview entity;
	private final Retrieval retrieval;

	public ContextGateway(DecisionGraph graph, DecisionTask decision, DocumentEvidence document,
			IdentityAccess identity, EntityReview entity, Retrieval retrieval) {
		this.entity = entity;
		this.retrieval = retrieval;
		this.graph = graph;
		this.decision = decision;
		this.document = document;
		this.identity = identity;
	}

	/**
	 * 한 카드에서 줄을 따라간다.
	 * BASIS면 "이것이 무엇에 달려 있나"(조건, 근거 발언), IMPACT면 "이것을 고치면 무엇이 흔들리나".
	 *
	 * @param depth 따라갈 칸 수. 1~10
	 */
	@Transactional(readOnly = true)
	public ContextPacket trace(Caller caller, NodeRef node, Direction direction, int depth) {
		authorize(caller);
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(direction, "direction");
		if (depth < 1 || depth > DecisionGraph.MAX_DEPTH) {
			throw new IllegalArgumentException("depth는 1~" + DecisionGraph.MAX_DEPTH + " 사이여야 한다: " + depth);
		}

		Collector found = new Collector(caller);
		Set<NodeKey> frontier = found.start(node);
		Set<NodeKey> visited = new HashSet<>(frontier);
		for (int step = 1; step <= depth && !frontier.isEmpty(); step++) {
			Set<NodeKey> reached = found.follow(frontier, direction, DEPENDENCIES, key -> true);
			reached.removeAll(visited);
			visited.addAll(reached);
			frontier = reached;
		}
		return found.toPacket(new Scope(caller.projectId(), Scope.View.TRACE, found.current(node), direction, depth),
				caller);
	}

	/**
	 * 지금 무엇이 결정됐고 무엇이 남았나.
	 * 항목마다 지금 유효한 결정(경합이면 겹치는 결정들)만 담고, 지난 제안과 대체된 결정은 담지 않는다.
	 * 항목에서 나온 할 일, 항목과 할 일에 걸린 조건, 담당 줄을 함께 담는다.
	 *
	 * @param topicId 안건 하나만 볼 때. null이면 프로젝트 전체
	 */
	@Transactional(readOnly = true)
	public ContextPacket currentState(Caller caller, UUID topicId) {
		authorize(caller);
		UUID projectId = caller.projectId();
		Collector found = new Collector(caller);

		NodeRef focus = null;
		Set<NodeKey> items;
		Set<NodeKey> work = new LinkedHashSet<>();
		if (topicId != null) {
			NodeRef topic = new NodeRef(NodeType.TOPIC, topicId, 1);
			items = found.follow(found.start(topic), Direction.BASIS, EnumSet.of(RelType.HAS_ITEM), key -> true);
			focus = found.current(topic);
		} else {
			Set<NodeKey> topics = found.admit(decision.listNodes(projectId, NodeType.TOPIC, READABLE));
			items = found.admit(decision.listNodes(projectId, NodeType.DECISION_ITEM, READABLE));
			found.follow(topics, Direction.BASIS, EnumSet.of(RelType.HAS_ITEM), key -> true);
			// 어느 항목에서도 나오지 않은 할 일과 조건도 프로젝트 현황에는 들어간다
			work.addAll(found.admit(decision.listNodes(projectId, NodeType.TASK, READABLE)));
			work.addAll(found.admit(decision.listNodes(projectId, NodeType.CONDITION, READABLE)));
			// 인물·기관도 프로젝트 현황에 싣는다. AI가 새 후보를 이미 있는 사람에게 잇는 데 쓴다
			found.admit(entity.listNodes(projectId, READABLE));
		}

		Set<NodeKey> currentEvents = currentEventsOf(found, items);
		found.follow(items, Direction.BASIS, EnumSet.of(RelType.HAS_EVENT), currentEvents::contains);

		work.addAll(items);
		work.addAll(found.follow(items, Direction.IMPACT, EnumSet.of(RelType.DERIVED_FROM), key -> true));

		// 걸린 조건, 먼저 끝나야 하는 할 일, 조건을 충족시키는 것을 끝까지 따라간다
		Set<NodeKey> frontier = new LinkedHashSet<>(work);
		Set<NodeKey> visited = new HashSet<>(work);
		for (int step = 1; step <= DecisionGraph.MAX_DEPTH && !frontier.isEmpty(); step++) {
			Set<NodeKey> reached = found.follow(frontier, Direction.BASIS,
					EnumSet.of(RelType.REQUIRES, RelType.SATISFIED_BY), key -> true);
			reached.removeAll(visited);
			visited.addAll(reached);
			frontier = reached;
		}

		Set<NodeKey> tasks = visited.stream()
				.filter(key -> key.type() == NodeType.TASK)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		found.follow(tasks, Direction.BASIS, EnumSet.of(RelType.ASSIGNED_TO), key -> true);

		return found.toPacket(new Scope(projectId, Scope.View.CURRENT_STATE, focus, null, null), caller);
	}

	/**
	 * 이 결정이 언제, 어떻게 바뀌었나.
	 * 항목에 속한 사건을 제안·대체된 것까지 모두, 실제로 일어난 순서로 담는다.
	 */
	@Transactional(readOnly = true)
	public ContextPacket decisionHistory(Caller caller, UUID decisionItemId) {
		authorize(caller);
		Objects.requireNonNull(decisionItemId, "decisionItemId");
		Collector found = new Collector(caller);

		NodeRef item = new NodeRef(NodeType.DECISION_ITEM, decisionItemId, 1);
		Set<NodeKey> events = found.follow(found.start(item), Direction.BASIS, EnumSet.of(RelType.HAS_EVENT),
				key -> true);
		found.follow(events, Direction.BASIS,
				EnumSet.of(RelType.SUPERSEDES, RelType.WITHDRAWS, RelType.CONFLICTS_WITH), key -> true);

		found.sortEventsByOccurrence();
		return found.toPacket(
				new Scope(caller.projectId(), Scope.View.DECISION_HISTORY, found.current(item), null, null), caller);
	}

	/**
	 * 이 내용이 어느 발언에 있나. 질문과 가까운 발언을 찾고, 그 발언에 기대고 있는 카드
	 * (그 발언을 근거로 하는 결정·조건·할 일, 그 발언으로 충족된 조건)와 발언이 가리키는 인물을 함께 담는다.
	 * 요청자가 읽을 수 있는 문서 안에서만 찾는다. 원문(sources)은 질문과 가까운 순서다.
	 *
	 * @param k 찾을 발언 수. 1~20
	 */
	@Transactional(readOnly = true)
	public ContextPacket searchEvidence(Caller caller, String query, int k) {
		authorize(caller);
		if (query == null || query.isBlank()) {
			throw new IllegalArgumentException("찾을 내용이 비어 있다");
		}
		Collector found = new Collector(caller);
		List<SpanHit> hits = retrieval.search(caller.projectId(), query, found.readableDocuments,
				Math.max(1, Math.min(k, 20)));
		Set<NodeKey> spans = found.admitRefs(hits.stream().map(hit -> hit.span().ref()).toList());
		found.follow(spans, Direction.IMPACT,
				EnumSet.of(RelType.EVIDENCED_BY, RelType.SATISFIED_BY, RelType.CONFLICTS_WITH), key -> true);
		found.follow(spans, Direction.BASIS, EnumSet.of(RelType.MENTIONS, RelType.SPOKEN_BY), key -> true);
		found.keepSourceOrder();
		return found.toPacket(new Scope(caller.projectId(), Scope.View.SEARCH_EVIDENCE, null, null, null), caller);
	}

	/**
	 * 누가 무엇을 해야 하나. 할 일과 그 담당 줄, 걸린 조건, 먼저 끝나야 하는 할 일을 담는다.
	 *
	 * @param filter null이면 모든 할 일
	 */
	@Transactional(readOnly = true)
	public ContextPacket tasks(Caller caller, TaskFilter filter) {
		authorize(caller);
		UUID projectId = caller.projectId();
		TaskFilter f = filter == null ? TaskFilter.all() : filter;
		Collector found = new Collector(caller);
		Scope scope = new Scope(projectId, Scope.View.TASKS, null, null, null);

		UUID assignee = f.assigneeEntityId();
		if (f.mine()) {
			// 내 계정에 연결된 인물 카드가 없으면 "내 할 일"은 비어 있다
			Optional<UUID> me = entity.entityOfAccount(projectId, caller.actingUser());
			if (me.isEmpty()) {
				return found.toPacket(scope, caller);
			}
			assignee = me.get();
		}
		List<NodeView> tasks = decision.listNodes(projectId, NodeType.TASK, READABLE).stream()
				.filter(task -> f.workStatus() == null || f.workStatus().equals(task.states().get("workStatus")))
				.toList();
		if (assignee != null && !tasks.isEmpty()) {
			UUID wanted = assignee;
			Set<NodeKey> assigned = graph.edgesOfMany(projectId, tasks.stream().map(t -> t.ref().key()).toList(),
					Direction.BASIS, EnumSet.of(RelType.ASSIGNED_TO), USABLE).stream()
					.filter(edge -> edge.dst().id().equals(wanted))
					.map(edge -> edge.src().key())
					.collect(Collectors.toSet());
			tasks = tasks.stream().filter(task -> assigned.contains(task.ref().key())).toList();
		}
		Set<NodeKey> keys = found.admit(tasks);
		found.follow(keys, Direction.BASIS,
				EnumSet.of(RelType.ASSIGNED_TO, RelType.REQUIRES, RelType.DERIVED_FROM), key -> true);
		return found.toPacket(scope, caller);
	}

	/**
	 * 이 이름 표기가 누구(어느 기관)인가. 해당할 만한 인물·기관을 모두 돌려주고, 여럿이면 open에 알린다.
	 * 여기서 하나로 고르지 않는다. 표기 전체가 같은 후보가 앞에 온다.
	 *
	 * @param lang 이 언어의 표기만 볼 때. null이면 모든 언어
	 */
	@Transactional(readOnly = true)
	public ContextPacket resolveEntity(Caller caller, String surface, String lang) {
		authorize(caller);
		Collector found = new Collector(caller);
		List<NodeRef> refs = entity.findCandidates(caller.projectId(), surface, lang).stream()
				.map(EntityCandidate::entity).distinct().toList();
		Set<NodeKey> usable = found.admit(entity.getNodes(caller.projectId(), refs));
		if (usable.size() > 1) {
			usable.forEach(key -> found.flag(OpenIssue.Kind.AMBIGUOUS_NAME, key));
		}
		return found.toPacket(new Scope(caller.projectId(), Scope.View.RESOLVE_ENTITY, null, null, null), caller);
	}

	/** 이름 카드: 인물·기관 한 명과 언어별 표기 전부(제안 표기 포함). */
	@Transactional(readOnly = true)
	public NameCard nameCard(Caller caller, UUID entityId) {
		authorize(caller);
		return entity.getNameCard(caller.projectId(), entityId);
	}

	/**
	 * 답변이 참고했던 카드와 줄이 그사이 바뀌었는지 확인한다.
	 * 바뀌었거나 더 이상 찾을 수 없는 것만 돌려준다. 비어 있으면 모두 그대로다.
	 * 산출물(답변) 카드는 아직 확인할 수 없어 건너뛴다.
	 */
	@Transactional(readOnly = true)
	public List<StaleRef> verifyCurrent(Caller caller, List<NodeRef> nodes, List<EdgeRef> edges) {
		authorize(caller);
		List<NodeRef> usedNodes = nodes == null ? List.of() : nodes;
		List<EdgeRef> usedEdges = edges == null ? List.of() : edges;
		List<StaleRef> stale = new ArrayList<>();

		Map<NodeKey, Long> revisions = decision.currentRevisions(caller.projectId(), usedNodes);
		revisions.putAll(document.currentRevisions(caller.projectId(), usedNodes));
		revisions.putAll(entity.currentRevisions(caller.projectId(), usedNodes));
		for (NodeRef node : usedNodes) {
			if (!CARDS.contains(node.type()) && node.type() != NodeType.SPAN) {
				continue;
			}
			Long current = revisions.get(node.key());
			if (current == null || current != node.revision()) {
				stale.add(new StaleRef(node, null, current));
			}
		}

		Map<UUID, Long> versions = graph.currentVersions(caller.projectId(),
				usedEdges.stream().map(EdgeRef::id).toList());
		for (EdgeRef edge : usedEdges) {
			Long current = versions.get(edge.id());
			if (current == null || current != edge.version()) {
				stale.add(new StaleRef(null, edge, current));
			}
		}
		return stale;
	}

	// ---- 내부 ----

	private static void authorize(Caller caller) {
		Objects.requireNonNull(caller, "caller");
	}

	/** 항목마다 지금 유효한 사건. 결정됨이면 하나, 경합이면 겹치는 사건들이다. */
	private Set<NodeKey> currentEventsOf(Collector found, Set<NodeKey> items) {
		Set<NodeKey> events = new HashSet<>();
		for (NodeKey item : items) {
			Map<String, String> states = found.card(item).states();
			String currentEventId = states.get("currentEventId");
			if (currentEventId != null) {
				events.add(new NodeKey(NodeType.DECISION_EVENT, UUID.fromString(currentEventId)));
			} else if (DecisionStatus.CONTESTED.name().equals(states.get("decisionState"))) {
				decision.currentDecision(item.id()).contenders().forEach(ref -> events.add(ref.key()));
			}
		}
		return events;
	}

	/** 답변서 하나를 만드는 동안 찾은 카드, 줄, 정리되지 않은 것을 모은다. */
	private final class Collector {

		private final UUID projectId;
		/** 이 요청자가 읽을 수 있는 문서. */
		private final Set<UUID> readableDocuments;
		/** 지금까지 읽어 본 발언. 이 프로젝트에 없는 발언은 null로 기억한다. */
		private final Map<UUID, Span> spans = new HashMap<>();
		/** 답변서에 원문을 실을 발언. */
		private final Set<UUID> cited = new LinkedHashSet<>();
		/** 원문을 찾은 순서 그대로 실을지. 검색 결과는 질문과 가까운 순서를 지켜야 한다. */
		private boolean sourceOrderKept;
		/** 카드마다 읽을 수 있는 근거. */
		private final Map<NodeKey, List<EvidenceRef>> evidence = new HashMap<>();
		/** 사실로 돌려줄 카드. 찾은 순서를 유지한다. */
		private final Map<NodeKey, NodeView> cards = new LinkedHashMap<>();
		/** 내용은 아직 못 읽지만 줄의 끝점으로는 인정하는 카드(발언, 인물, 산출물). */
		private final Set<NodeKey> external = new HashSet<>();
		private final Set<NodeKey> held = new HashSet<>();
		private final Set<NodeKey> hidden = new HashSet<>();
		private final Map<UUID, Edge> relations = new LinkedHashMap<>();
		private final Set<OpenIssue> open = new LinkedHashSet<>();

		Collector(Caller caller) {
			this.projectId = caller.projectId();
			this.readableDocuments = identity.readableDocumentIds(caller);
		}

		/** 주어진 발언 중 이 요청자가 읽을 수 있는 것. 처음 보는 발언은 자료실에서 한 번에 읽어 온다. */
		private Set<UUID> readable(Collection<UUID> spanIds) {
			List<UUID> unknown = spanIds.stream().filter(id -> !spans.containsKey(id)).distinct().toList();
			if (!unknown.isEmpty()) {
				unknown.forEach(id -> spans.put(id, null));
				document.getSpans(projectId, unknown).forEach(span -> spans.put(span.ref().id(), span));
			}
			Set<UUID> readable = new HashSet<>();
			for (UUID id : spanIds) {
				Span span = spans.get(id);
				if (span != null && readableDocuments.contains(span.documentId())) {
					readable.add(id);
				}
			}
			return readable;
		}

		/** 근거로 적힌 발언이 없거나, 그중 하나라도 읽을 수 있으면 보이는 줄이다. */
		private boolean visible(Edge edge) {
			return edge.evidenceSpanIds().isEmpty() || !readable(edge.evidenceSpanIds()).isEmpty();
		}

		/**
		 * 카드들의 근거 줄을 읽어, 읽을 수 있는 근거를 기억하고 보이지 않는 카드를 골라낸다.
		 * 근거 줄이 있는데 읽을 수 있는 것이 하나도 없으면 보이지 않는 카드다.
		 */
		private Set<NodeKey> unseeable(List<NodeView> views) {
			List<NodeKey> keys = views.stream().map(view -> view.ref().key())
					.filter(key -> EVIDENCED.contains(key.type()) && !evidence.containsKey(key))
					.toList();
			Set<NodeKey> unseeable = new HashSet<>();
			if (keys.isEmpty()) {
				return unseeable;
			}
			List<Edge> edges = graph.edgesOfMany(projectId, keys, Direction.BASIS,
					EnumSet.of(RelType.EVIDENCED_BY), USABLE);
			Set<UUID> readable = readable(edges.stream().map(edge -> edge.dst().id()).toList());
			Set<NodeKey> evidenced = new HashSet<>();
			keys.forEach(key -> evidence.put(key, new ArrayList<>()));
			for (Edge edge : edges) {
				evidenced.add(edge.src().key());
				if (readable.contains(edge.dst().id())) {
					Span span = spans.get(edge.dst().id());
					evidence.get(edge.src().key()).add(new EvidenceRef(span.ref(), edge.id(), edge.status()));
				}
			}
			for (NodeKey key : keys) {
				if (evidenced.contains(key) && evidence.get(key).isEmpty()) {
					unseeable.add(key);
				}
			}
			return unseeable;
		}

		/** 출발 카드를 받아들인다. 이 프로젝트에서 볼 수 없는 카드면 없다고 알린다. */
		Set<NodeKey> start(NodeRef node) {
			Set<NodeKey> usable = admitRefs(List.of(node));
			if (usable.isEmpty() && !held.contains(node.key())) {
				throw new NodeNotFoundException(node.type(), node.id());
			}
			return usable;
		}

		/** 이미 읽은 카드들을 받아들인다. 사실로 쓸 수 있는 것만 돌려준다. */
		Set<NodeKey> admit(List<NodeView> views) {
			Set<NodeKey> usable = new LinkedHashSet<>();
			Set<NodeKey> unseeable = unseeable(views);
			for (NodeView view : views) {
				NodeKey key = view.ref().key();
				if (unseeable.contains(key)) {
					hidden.add(key);
				} else if (USABLE.contains(view.status())) {
					cards.putIfAbsent(key, view);
					usable.add(key);
				} else if (view.status() == ReviewStatus.HELD) {
					held.add(key);
					open.add(OpenIssue.ofNode(OpenIssue.Kind.HELD_NODE, view.ref()));
				} else {
					hidden.add(key);
				}
			}
			return usable;
		}

		/** 참조만 아는 카드들을 읽어서 받아들인다. 사실로 쓰거나 줄의 끝점으로 인정할 수 있는 것만 돌려준다. */
		Set<NodeKey> admitRefs(Collection<NodeRef> refs) {
			List<NodeRef> unknown = new ArrayList<>();
			Set<UUID> readableSpans = readable(refs.stream()
					.filter(ref -> ref.type() == NodeType.SPAN).map(NodeRef::id).toList());
			for (NodeRef ref : refs) {
				NodeKey key = ref.key();
				if (ref.type() == NodeType.SPAN) {
					// 발언은 읽을 수 있을 때만 줄의 끝점으로 인정하고, 원문을 답변서에 싣는다
					if (readableSpans.contains(ref.id())) {
						external.add(key);
						cited.add(ref.id());
					}
				} else if (!CARDS.contains(ref.type())) {
					external.add(key);
				} else if (!cards.containsKey(key) && !held.contains(key) && !hidden.contains(key)) {
					unknown.add(ref);
				}
			}
			if (!unknown.isEmpty()) {
				List<NodeView> read = new ArrayList<>(decision.getNodes(projectId, unknown));
				read.addAll(entity.getNodes(projectId, unknown));
				admit(read);
				// 읽어 봐도 나오지 않은 카드는 이 프로젝트에 없는 것이다
				unknown.stream().map(NodeRef::key)
						.filter(key -> !cards.containsKey(key) && !held.contains(key))
						.forEach(hidden::add);
			}
			return refs.stream().map(NodeRef::key)
					.filter(key -> cards.containsKey(key) || external.contains(key))
					.collect(Collectors.toCollection(LinkedHashSet::new));
		}

		/**
		 * 주어진 카드들에서 줄을 한 칸 따라간다.
		 * 보류된 줄은 open에 넣고 따라가지 않는다. 건너편 카드를 쓸 수 없으면 그 줄도 담지 않는다.
		 *
		 * @param farFilter 건너편 카드 중 따라갈 것
		 * @return 건너가서 닿은 카드
		 */
		Set<NodeKey> follow(Collection<NodeKey> from, Direction direction, Set<RelType> rels,
				Predicate<NodeKey> farFilter) {
			if (from.isEmpty()) {
				return new LinkedHashSet<>();
			}
			List<Edge> candidates = new ArrayList<>();
			for (Edge edge : graph.edgesOfMany(projectId, from, direction, rels, READABLE)) {
				if (!farFilter.test(far(edge, direction).key()) || !visible(edge)) {
					continue;
				}
				if (edge.status() == ReviewStatus.HELD) {
					open.add(OpenIssue.ofEdge(edge.id()));
				} else {
					candidates.add(edge);
				}
			}
			Set<NodeKey> usable = admitRefs(candidates.stream().map(e -> far(e, direction)).toList());
			Set<NodeKey> reached = new LinkedHashSet<>();
			for (Edge edge : candidates) {
				NodeKey farKey = far(edge, direction).key();
				if (usable.contains(farKey)) {
					relations.putIfAbsent(edge.id(), edge);
					reached.add(farKey);
				}
			}
			return reached;
		}

		NodeView card(NodeKey key) {
			return cards.get(key);
		}

		void keepSourceOrder() {
			sourceOrderKept = true;
		}

		/** 담긴 카드 하나를 "아직 정리되지 않은 것"으로 알린다. */
		void flag(OpenIssue.Kind kind, NodeKey key) {
			open.add(OpenIssue.ofNode(kind, cards.get(key).ref()));
		}

		/** 같은 카드를 현재 버전으로 가리키는 참조. 내용을 읽지 못한 카드는 받은 그대로 돌려준다. */
		NodeRef current(NodeRef node) {
			NodeView view = cards.get(node.key());
			if (view != null) {
				return view.ref();
			}
			Span span = node.type() == NodeType.SPAN ? spans.get(node.id()) : null;
			return span == null ? node : span.ref();
		}

		/** 결정 사건을 실제로 일어난 순서로 놓는다. 일어난 시각이 없는 사건은 뒤로 간다. 다른 카드의 순서는 그대로다. */
		void sortEventsByOccurrence() {
			List<NodeView> events = cards.values().stream()
					.filter(view -> view.ref().type() == NodeType.DECISION_EVENT)
					.sorted(Comparator.comparing(Collector::occurredAt,
							Comparator.nullsLast(Comparator.naturalOrder())))
					.toList();
			List<NodeView> others = cards.values().stream()
					.filter(view -> view.ref().type() != NodeType.DECISION_EVENT)
					.toList();
			cards.clear();
			others.forEach(view -> cards.put(view.ref().key(), view));
			events.forEach(view -> cards.put(view.ref().key(), view));
		}

		private static Instant occurredAt(NodeView view) {
			String value = view.states().get("occurredAt");
			return value == null ? null : Instant.parse(value);
		}

		ContextPacket toPacket(Scope scope, Caller caller) {
			List<Fact> facts = new ArrayList<>();
			for (NodeView view : cards.values()) {
				facts.add(new Fact(view.ref(), view.status(), view.label(), view.states(), view.confirmedBy(),
						view.confirmedAt(), evidence.getOrDefault(view.ref().key(), List.of())));
				if (view.ref().type() == NodeType.CONDITION && "UNMET".equals(view.states().get("state"))) {
					open.add(OpenIssue.ofNode(OpenIssue.Kind.UNMET_CONDITION, view.ref()));
				}
				if (view.ref().type() == NodeType.DECISION_ITEM
						&& DecisionStatus.CONTESTED.name().equals(view.states().get("decisionState"))) {
					open.add(OpenIssue.ofNode(OpenIssue.Kind.CONTESTED_DECISION, view.ref()));
				}
			}
			log.debug("context {} project={} caller={}:{} facts={} relations={} open={}", scope.view(), projectId,
					caller.kind(), caller.id(), facts.size(), relations.size(), open.size());
			facts.forEach(fact -> fact.evidence().forEach(ref -> cited.add(ref.span().id())));
			// 원문은 문서별로, 문서 안에서는 발언 순서대로 싣는다. 검색 결과는 찾은 순서를 지킨다
			Comparator<Span> order = sourceOrderKept ? (a, b) -> 0
					: Comparator.comparing((Span span) -> span.documentId().toString())
							.thenComparing(span -> span.sourceVersionId().toString())
							.thenComparingInt(Span::seq);
			List<Source> sources = cited.stream().map(spans::get)
					.sorted(order)
					.map(span -> new Source(span.ref(), span.documentId(), span.seq(), span.speakerLabel(),
							span.text()))
					.toList();
			return new ContextPacket(Instant.now(), scope, facts, new ArrayList<>(relations.values()),
					new ArrayList<>(open), sources);
		}

		private static NodeRef far(Edge edge, Direction direction) {
			return direction == Direction.BASIS ? edge.dst() : edge.src();
		}
	}
}
