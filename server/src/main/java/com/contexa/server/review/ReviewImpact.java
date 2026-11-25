package com.contexa.server.review;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
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

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Caller;
import com.contexa.server.contract.Direction;
import com.contexa.server.contract.Edge;
import com.contexa.server.contract.EdgeDraft;
import com.contexa.server.contract.GraphCandidates;
import com.contexa.server.contract.NodeDraft;
import com.contexa.server.contract.NodeKey;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.Ontology;
import com.contexa.server.contract.RelType;
import com.contexa.server.contract.ReviewStatus;
import com.contexa.server.contract.Source;
import com.contexa.server.decision.DecisionTask;
import com.contexa.server.decision.NodeNotFoundException;
import com.contexa.server.decision.OpenRecheck;
import com.contexa.server.decision.RecheckMark;
import com.contexa.server.decision.RecheckReason;
import com.contexa.server.decision.RecheckResolution;
import com.contexa.server.decision.Rechecks;
import com.contexa.server.contract.NodeView;
import com.contexa.server.decision.TaskStatus;
import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.document.Span;
import com.contexa.server.entity.EntityReview;
import com.contexa.server.entity.NameForm;
import com.contexa.server.graph.DecisionGraph;
import com.contexa.server.graph.Subgraph;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.NotAllowedException;

/**
 * 결재 창구: 결정 정보를 바꾸는 유일한 곳.
 *
 * <ul>
 * <li>AI와 외부 에이전트는 {@link #submitCandidates}로 후보(연필)만 낼 수 있다.</li>
 * <li>확인·보류·거절·철회·정정(도장)은 사람만, 그중에서도 프로젝트의 OWNER와 REVIEWER만 할 수 있다.</li>
 * <li>검토는 카드 한 장과 그 카드에 딸린 줄을 한 묶음으로 한다. 줄은 "기대는 쪽" 카드에 딸린다.
 * 다만 안건–항목, 항목–사건 줄은 아래쪽 카드(항목, 사건)에 딸린다. 새 사건을 확인할 때
 * "어느 항목의 사건인지"도 함께 확인해야 하기 때문이다.</li>
 * <li>바꾼 뒤에는 걸린 계산 결과를 다시 계산하게 하고, 영향받는 카드 목록을 돌려준다.</li>
 * <li>확인된 것이 바뀌면(카드 정정·철회, 확인된 줄의 철회, 확정의 대체·철회, 확인된 표기의 거절, 계정 연결 변경,
 * 조건 충족, 발언 정정) 거기에 기대는 확인·보류된 결정·업무 카드를 "다시 확인"으로 남긴다. 후보를 처음 확인하는
 * 일과 할 일의 진행 상태·수락은 남기지 않는다.</li>
 * <li>근거로 대는 발언은 자료실에 실제로 있어야 하고, 대는 사람이 읽을 수 있는 문서의 것이어야 한다.</li>
 * <li>묶음의 근거를 하나도 읽을 수 없는 사람에게는 그 묶음이 보이지 않는다(개인 메모에서 나온 후보).</li>
 * </ul>
 *
 * 확인할 때 지키는 것:
 * <ul>
 * <li>결정 사건·조건·할 일 카드는 확인된 근거 줄이 하나 이상 있어야 확인 상태일 수 있다.</li>
 * <li>카드가 확인되지 않았으면 그 카드에 딸린 줄도 확인할 수 없다.</li>
 * <li>건너편 카드가 거절·철회됐거나 없으면 그 줄은 확인할 수 없다. 건너편이 아직 후보인 것은 괜찮다.</li>
 * <li>대체 줄은 확정·변경 사건끼리만, 철회 줄은 철회 사건에서 확정·변경 사건으로만 확인할 수 있다.</li>
 * </ul>
 */
@Service
public class ReviewImpact {

	private static final Set<ReviewStatus> ACTIVE =
			EnumSet.of(ReviewStatus.CANDIDATE, ReviewStatus.CONFIRMED, ReviewStatus.HELD);

	/** 카드를 볼 수 있는지 따질 때 세는 근거 줄. 안내 데스크와 같은 기준이다. */
	private static final Set<ReviewStatus> USABLE = EnumSet.of(ReviewStatus.CANDIDATE, ReviewStatus.CONFIRMED);

	/** "다시 확인"으로 보여 주는 카드 상태. 그사이 거절·철회된 카드는 빠진다. */
	private static final Set<ReviewStatus> MARKED = EnumSet.of(ReviewStatus.CONFIRMED, ReviewStatus.HELD);

	/** 다른 사건을 대체하거나 철회하는 줄. */
	private static final Set<RelType> REPLACES = EnumSet.of(RelType.SUPERSEDES, RelType.WITHDRAWS);

	/** 아래쪽 카드에 딸리는 줄. */
	private static final Set<RelType> OWNED_BY_DST = EnumSet.of(RelType.HAS_ITEM, RelType.HAS_EVENT);

	/** 기대는 쪽 카드에 딸리는 줄. 시스템이 기록하는 줄은 검토 대상이 아니다. */
	private static final Set<RelType> OWNED_BY_SRC = ownedBySrc();

	/** 근거 줄이 있어야 확인 상태일 수 있는 카드. */
	private static final Set<NodeType> NEEDS_EVIDENCE =
			EnumSet.of(NodeType.DECISION_EVENT, NodeType.CONDITION, NodeType.TASK);

	private record ItemRow(UUID id, UUID projectId, NodeKey owner, ReviewItem.State state, long version,
			OffsetDateTime createdAt, OffsetDateTime updatedAt) {
	}

	private static final String ITEM_COLUMNS =
			"id, project_id, owner_type, owner_id, state, version, created_at, updated_at";

	/** 검토함 쪽 나누기 위치: 앞 쪽 마지막 묶음의 정렬 시각과 id. 화면에는 알아볼 수 없는 글자로 건넨다. */
	private record Cursor(OffsetDateTime at, UUID id) {

		String encode() {
			String raw = at.toInstant() + "|" + id;
			return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
		}

		static Cursor parse(String value) {
			try {
				String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
				int bar = raw.indexOf('|');
				return new Cursor(OffsetDateTime.ofInstant(Instant.parse(raw.substring(0, bar)), ZoneOffset.UTC),
						UUID.fromString(raw.substring(bar + 1)));
			} catch (RuntimeException e) {
				throw new IllegalArgumentException("after 값을 읽을 수 없다: " + value);
			}
		}
	}

	private record Bundle(NodeView card, List<Edge> edges) {
	}

	private final JdbcClient jdbc;
	private final DecisionGraph graph;
	private final DecisionTask decision;
	private final IdentityAccess identity;
	private final DocumentEvidence document;
	private final EntityReview entity;
	private final Rechecks rechecks;

	public ReviewImpact(JdbcClient jdbc, DecisionGraph graph, DecisionTask decision, IdentityAccess identity,
			DocumentEvidence document, EntityReview entity, Rechecks rechecks) {
		this.entity = entity;
		this.rechecks = rechecks;
		this.jdbc = jdbc;
		this.graph = graph;
		this.decision = decision;
		this.identity = identity;
		this.document = document;
	}

	// ---- 후보 제출 ----

	/**
	 * 카드 후보와 줄 후보를 받아 연필로 저장하고, 검토 묶음을 만든다. 누구나(사람, AI 작업, 외부 에이전트) 낼 수 있다.
	 * 하나라도 틀리면 아무것도 저장하지 않는다.
	 *
	 * <p>모든 줄 후보에는 근거 발언이 하나 이상 있어야 한다. 이미 있는 카드를 가리킬 때는 그 카드가
	 * 이 프로젝트에 살아 있어야 한다.
	 *
	 * @return 새로 생기거나 다시 열린 검토 묶음의 id
	 */
	@Transactional
	public List<UUID> submitCandidates(Caller source, GraphCandidates candidates) {
		Objects.requireNonNull(source, "caller");
		Objects.requireNonNull(candidates, "candidates");
		UUID projectId = source.projectId();

		Map<String, NodeType> draftTypes = new HashMap<>();
		for (NodeDraft draft : candidates.nodes()) {
			if (!isCard(draft.type())) {
				throw new IllegalArgumentException("후보로 낼 수 없는 카드 종류다: " + draft.type());
			}
			if (draftTypes.put(draft.key(), draft.type()) != null) {
				throw new IllegalArgumentException("같은 key가 두 번 나왔다: " + draft.key());
			}
		}
		List<NodeRef> existing = new ArrayList<>();
		Set<UUID> citedSpans = new LinkedHashSet<>();
		for (EdgeDraft edge : candidates.edges()) {
			NodeType srcType = endType(edge.srcKey(), edge.srcRef(), draftTypes);
			NodeType dstType = endType(edge.dstKey(), edge.dstRef(), draftTypes);
			if (edge.rel().isSystemRecorded()) {
				throw new IllegalArgumentException(edge.rel() + " 줄은 시스템이 기록한다. 후보로 낼 수 없다");
			}
			if (!Ontology.isAllowed(edge.rel(), srcType, dstType)) {
				throw new IllegalArgumentException("허용되지 않은 줄: " + srcType + " -" + edge.rel() + "-> " + dstType);
			}
			if (edge.evidenceSpanIds().isEmpty()) {
				throw new IllegalArgumentException("근거 발언이 없는 줄 후보다: " + srcType + " -" + edge.rel() + "-> " + dstType);
			}
			citedSpans.addAll(edge.evidenceSpanIds());
			for (NodeRef ref : new NodeRef[] { edge.srcRef(), edge.dstRef() }) {
				if (ref == null) {
					continue;
				}
				existing.add(ref);
				if (ref.type() == NodeType.SPAN) {
					citedSpans.add(ref.id());
				}
			}
		}
		Map<UUID, Span> spans = requireReadableSpans(source, citedSpans);
		Map<NodeKey, NodeView> alive = aliveCards(projectId, existing);
		for (NodeRef ref : existing) {
			if (isCard(ref.type()) && !alive.containsKey(ref.key())) {
				throw new NodeNotFoundException(ref.type(), ref.id());
			}
		}

		// 카드 후보는 종류에 따라 담당 부서에 맡긴다. 낸 순서는 유지한다
		Map<String, NodeRef> byDecision = decision.saveCandidates(projectId,
				candidates.nodes().stream().filter(draft -> draft.type() != NodeType.ENTITY).toList());
		Map<String, NodeRef> byEntity = entity.saveCandidates(projectId,
				candidates.nodes().stream().filter(draft -> draft.type() == NodeType.ENTITY).toList());
		Map<String, NodeRef> saved = new LinkedHashMap<>();
		candidates.nodes().forEach(draft -> saved.put(draft.key(),
				draft.type() == NodeType.ENTITY ? byEntity.get(draft.key()) : byDecision.get(draft.key())));
		Set<NodeKey> owners = new LinkedHashSet<>();
		saved.values().forEach(ref -> owners.add(ref.key()));
		for (EdgeDraft draft : candidates.edges()) {
			NodeRef src = draft.srcKey() != null ? saved.get(draft.srcKey()) : current(alive, spans, draft.srcRef());
			NodeRef dst = draft.dstKey() != null ? saved.get(draft.dstKey()) : current(alive, spans, draft.dstRef());
			Edge edge = new Edge(UUID.randomUUID(), projectId, draft.rel(), src, dst, ReviewStatus.CANDIDATE,
					draft.evidenceSpanIds(), null, null, Ontology.VERSION, 1);
			graph.addCandidateEdge(edge);
			owners.add(ownerOf(edge).key());
		}

		List<UUID> reviewIds = new ArrayList<>();
		for (NodeKey owner : owners) {
			reviewIds.add(touch(projectId, owner));
		}
		return reviewIds;
	}

	// ---- 검토 ----

	/**
	 * 검토함 한 쪽. 확인할 것(OPEN)은 오래된 묶음부터, 처리한 것(DONE)은 최근에 바뀐 묶음부터 돌려준다.
	 * 묶음이 몇 개든 DB는 정해진 횟수만 읽는다.
	 */
	@Transactional(readOnly = true)
	public ReviewPage listReviews(Caller caller, ReviewFilter filter) {
		Objects.requireNonNull(caller, "caller");
		ReviewFilter f = filter == null ? ReviewFilter.open() : filter;
		ReviewItem.State state = f.state() == null ? ReviewItem.State.OPEN : f.state();
		int limit = f.limit() == null ? ReviewFilter.DEFAULT_LIMIT : f.limit();
		if (limit < 1 || limit > ReviewFilter.MAX_LIMIT) {
			throw new IllegalArgumentException("limit은 1~" + ReviewFilter.MAX_LIMIT + " 사이여야 한다: " + limit);
		}
		boolean newestFirst = state == ReviewItem.State.DONE;
		String sortColumn = newestFirst ? "updated_at" : "created_at";
		Cursor after = f.after() == null ? null : Cursor.parse(f.after());
		String sql = "select " + ITEM_COLUMNS + " from review_item"
				+ " where project_id = :projectId and state = :state"
				+ (f.ownerType() == null ? "" : " and owner_type = :ownerType")
				+ (after == null ? ""
						: " and (" + sortColumn + ", id) " + (newestFirst ? "<" : ">") + " (:afterAt, :afterId)")
				+ " order by " + sortColumn + (newestFirst ? " desc, id desc" : ", id")
				+ " limit :limit";
		JdbcClient.StatementSpec spec = jdbc.sql(sql).param("projectId", caller.projectId())
				.param("state", state.name())
				.param("limit", limit);
		if (f.ownerType() != null) {
			spec = spec.param("ownerType", f.ownerType().name());
		}
		if (after != null) {
			spec = spec.param("afterAt", after.at()).param("afterId", after.id());
		}
		List<ItemRow> rows = spec.query(ReviewImpact::mapItem).list();
		String next = null;
		if (rows.size() == limit) {
			ItemRow last = rows.get(rows.size() - 1);
			next = new Cursor(newestFirst ? last.updatedAt() : last.createdAt(), last.id()).encode();
		}
		return new ReviewPage(views(caller, rows, true), next);
	}

	@Transactional(readOnly = true)
	public ReviewItem getReview(Caller caller, UUID reviewId) {
		Objects.requireNonNull(caller, "caller");
		ItemRow item = findItem(caller.projectId(), reviewId, false);
		return views(caller, List.of(item), true).stream().findFirst()
				.orElseThrow(() -> new ReviewNotFoundException(reviewId));
	}

	/**
	 * 검토 묶음을 처리한다: 카드와 딸린 줄을 확인·보류·거절·철회하고, 필요하면 카드 내용을 정정한다.
	 * 처리할 수 없는 것이 하나라도 있으면 아무것도 바꾸지 않는다.
	 *
	 * @param expectedVersion 검토자가 화면에서 본 묶음 버전
	 * @param idempotencyKey  이 요청을 가리키는 값. 같은 값으로 다시 오면 두 번 처리하지 않고 현재 묶음을 돌려준다
	 */
	@Transactional
	public ReviewResult applyAction(Caller caller, UUID reviewId, ReviewAction action, long expectedVersion,
			String idempotencyKey) {
		requireReviewer(caller);
		Objects.requireNonNull(action, "action");
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("idempotencyKey가 비어 있다");
		}
		UUID projectId = caller.projectId();
		ItemRow item = findItem(projectId, reviewId, true);
		if (alreadyApplied(projectId, idempotencyKey)) {
			return new ReviewResult(view(caller, item), List.of());
		}
		if (item.version() != expectedVersion) {
			throw new ReviewVersionConflictException(reviewId, expectedVersion, item.version());
		}
		Bundle bundle = bundle(item);
		requireVisible(caller, item, bundle);
		List<UUID> newEvidence = new ArrayList<>();
		action.edgeEvidence().values().forEach(newEvidence::addAll);
		requireReadableSpans(caller, newEvidence);
		NodeView card = bundle.card();

		// 1. 무엇을 어떻게 바꿀지 먼저 정한다
		if (card == null && (action.card() != null || !action.corrections().isEmpty())) {
			throw new IllegalArgumentException("이 묶음에는 판단하거나 정정할 카드 내용이 없다");
		}
		ReviewStatus cardFinal = card == null ? null
				: action.card() == null ? card.status() : target(action.card(), card.status(), "카드");
		Map<UUID, Edge> owned = new LinkedHashMap<>();
		bundle.edges().forEach(edge -> owned.put(edge.id(), edge));
		for (UUID edgeId : action.edges().keySet()) {
			if (!owned.containsKey(edgeId)) {
				throw new IllegalArgumentException("이 묶음에 딸린 줄이 아니다: " + edgeId);
			}
		}
		Map<UUID, ReviewStatus> edgeFinal = new LinkedHashMap<>();
		for (Edge edge : bundle.edges()) {
			Verdict verdict = action.edges().containsKey(edge.id()) ? action.edges().get(edge.id())
					: followCard(action.card(), edge.status());
			edgeFinal.put(edge.id(), verdict == null ? edge.status() : target(verdict, edge.status(), "줄"));
		}

		// 2. 바꾸기 전에 전부 검사한다
		validate(projectId, item.owner(), card, action, cardFinal, bundle.edges(), edgeFinal);

		// 3. 바꾼다. changed는 영향 목록(응답)용, shaken은 "다시 확인"용이다: 확인된 것이 바뀐 경우만 담는다.
		// 후보를 처음 확인하는 것은 새 정보일 뿐 누가 기대던 것을 바꾸지 않으므로 다시 확인으로 남기지 않는다
		Set<NodeRef> changed = new LinkedHashSet<>();
		Set<NodeRef> shaken = new LinkedHashSet<>();
		NodeRef cardRef = card == null ? null : card.ref();
		boolean person = card != null && card.ref().type() == NodeType.ENTITY;
		boolean wasConfirmed = card != null && card.status() == ReviewStatus.CONFIRMED;
		if (!action.corrections().isEmpty()) {
			cardRef = person ? entity.updateFields(cardRef, action.corrections(), cardRef.revision())
					: decision.updateFields(cardRef, action.corrections(), cardRef.revision());
			changed.add(cardRef);
		}
		if (card != null && cardFinal != card.status()) {
			cardRef = person ? entity.setNodeStatus(cardRef, cardFinal, caller.id(), cardRef.revision())
					: decision.setNodeStatus(cardRef, cardFinal, caller.id(), cardRef.revision());
			changed.add(cardRef);
		}
		if (wasConfirmed && (!action.corrections().isEmpty() || cardFinal != ReviewStatus.CONFIRMED)) {
			shaken.add(cardRef);
		}
		for (Edge edge : bundle.edges()) {
			ReviewStatus next = edgeFinal.get(edge.id());
			if (next == edge.status()) {
				continue;
			}
			List<UUID> evidence = next == ReviewStatus.CONFIRMED ? action.edgeEvidence().get(edge.id()) : null;
			Edge updated = graph.setEdgeStatus(edge.id(), next, caller.id(), evidence, edge.version());
			decision.onEdgeChanged(updated);
			changed.add(updated.src());
			if (edge.status() == ReviewStatus.CONFIRMED) {
				shaken.add(updated.src());
			} else if (next == ReviewStatus.CONFIRMED && REPLACES.contains(edge.rel())) {
				// 확정을 대체·철회하는 줄이 확인되면, 대체된 쪽에 기대던 카드가 흔들린다
				shaken.add(updated.dst());
			}
		}

		// 4. 기록하고, 묶음 상태를 맞춘다
		jdbc.sql("""
				insert into review_action (id, project_id, review_id, actor_id, idempotency_key, summary)
				values (:id, :projectId, :reviewId, :actor, :key, :summary)
				""")
				.param("id", UUID.randomUUID())
				.param("projectId", projectId)
				.param("reviewId", reviewId)
				.param("actor", caller.id())
				.param("key", idempotencyKey)
				.param("summary", summarize(action, card, cardFinal, bundle.edges(), edgeFinal))
				.update();
		touch(projectId, item.owner());
		remember(caller, impactsOf(projectId, shaken), RecheckReason.REVIEW);
		// 이 묶음을 다시 처리했으니 이 카드는 다시 본 것이다
		rechecks.resolve(projectId, item.owner(), caller.id(), RecheckResolution.REVIEWED);
		return new ReviewResult(view(caller, findItem(projectId, reviewId, false)), impactsOf(projectId, changed));
	}

	// ---- 업무와 조건 ----

	/** 할 일의 진행 상태를 바꾼다. 프로젝트 구성원인 사람이면 할 수 있다. */
	@Transactional
	public Changed changeTaskStatus(Caller caller, UUID taskId, TaskStatus status, long expectedRevision) {
		Objects.requireNonNull(caller, "caller");
		if (!identity.isMember(caller)) {
			throw new NotAllowedException("업무 상태는 프로젝트 구성원만 바꿀 수 있다");
		}
		requireCard(caller.projectId(), new NodeRef(NodeType.TASK, taskId, 1));
		NodeRef task = decision.changeTaskStatus(taskId, status, caller.id(), expectedRevision);
		return new Changed(task, impactsOf(caller.projectId(), List.of(task)));
	}

	/**
	 * 조건이 충족됐다고 확인한다. 충족시킨 것(발언, 또는 할 일)으로 가는 충족 줄을 확인한다.
	 * 할 일로 충족되는 조건은 그 할 일이 완료되어야 실제로 충족 상태가 된다.
	 * 되돌리려면 조건의 검토 묶음에서 그 줄을 철회한다.
	 *
	 * @param evidence 근거 발언. 비어 있으면 발언으로 충족될 때는 그 발언을, 이미 있는 줄이면 기존 근거를 쓴다
	 */
	@Transactional
	public Changed satisfyCondition(Caller caller, UUID conditionId, NodeRef by, List<UUID> evidence) {
		requireReviewer(caller);
		Objects.requireNonNull(by, "by");
		UUID projectId = caller.projectId();
		NodeView condition = requireCard(projectId, new NodeRef(NodeType.CONDITION, conditionId, 1));
		if (condition.status() != ReviewStatus.CONFIRMED) {
			throw new IllegalStateException("확인되지 않은 조건은 충족으로 바꿀 수 없다: " + condition.status());
		}
		if (!Ontology.isAllowed(RelType.SATISFIED_BY, NodeType.CONDITION, by.type())) {
			throw new IllegalArgumentException("조건은 발언이나 할 일로만 충족된다: " + by.type());
		}
		List<UUID> given = evidence == null ? List.of() : evidence;
		List<UUID> cited = new ArrayList<>(given);
		if (by.type() == NodeType.SPAN) {
			cited.add(by.id());
		}
		Map<UUID, Span> spans = requireReadableSpans(caller, cited);
		NodeRef target = DecisionTask.owns(by.type()) ? requireCard(projectId, by).ref() : spans.get(by.id()).ref();

		Optional<Edge> existing = graph.edgesOf(projectId, condition.ref(), Direction.BASIS,
				EnumSet.of(RelType.SATISFIED_BY), ACTIVE).stream()
				.filter(edge -> edge.dst().sameNode(target))
				.findFirst();
		Edge edge;
		if (existing.isPresent()) {
			edge = existing.get();
		} else {
			List<UUID> initial = !given.isEmpty() ? given : by.type() == NodeType.SPAN ? List.of(by.id()) : List.of();
			if (initial.isEmpty()) {
				throw new IllegalArgumentException("할 일로 충족되는 조건에는 근거 발언이 필요하다");
			}
			UUID id = graph.addCandidateEdge(new Edge(UUID.randomUUID(), projectId, RelType.SATISFIED_BY,
					condition.ref(), target, ReviewStatus.CANDIDATE, initial, null, null, Ontology.VERSION, 1));
			edge = graph.find(id).orElseThrow();
		}
		if (edge.status() != ReviewStatus.CONFIRMED) {
			Edge confirmed = graph.setEdgeStatus(edge.id(), ReviewStatus.CONFIRMED, caller.id(),
					given.isEmpty() ? null : given, edge.version());
			decision.onEdgeChanged(confirmed);
		}
		touch(projectId, condition.ref().key());
		NodeRef now = requireCard(projectId, condition.ref()).ref();
		return new Changed(now, remember(caller, impactsOf(projectId, List.of(now)), RecheckReason.CONDITION));
	}

	/**
	 * 담당자 본인이 업무를 수락하거나 거절한다. 다른 사람이나 AI가 대신할 수 없다.
	 * 요청한 계정이 확인된 인물 카드에 연결되어 있고, 그 인물이 이 할 일을 맡는다는 줄이 확인되어 있어야 한다.
	 * 이름이 확인됐다고 해서 수락된 것으로 치지 않는다.
	 */
	@Transactional
	public Changed acceptTask(Caller caller, UUID taskId, boolean accepted) {
		Objects.requireNonNull(caller, "caller");
		if (!identity.isMember(caller)) {
			throw new NotAllowedException("업무 수락은 프로젝트 구성원 본인만 할 수 있다");
		}
		UUID projectId = caller.projectId();
		NodeView task = requireCard(projectId, new NodeRef(NodeType.TASK, taskId, 1));
		UUID me = entity.entityOfAccount(projectId, caller.id())
				.orElseThrow(() -> new NotAllowedException("이 계정은 확인된 인물 카드에 연결되어 있지 않다"));
		boolean assigned = graph.edgesOf(projectId, task.ref(), Direction.BASIS, EnumSet.of(RelType.ASSIGNED_TO),
				EnumSet.of(ReviewStatus.CONFIRMED)).stream().anyMatch(edge -> edge.dst().id().equals(me));
		if (!assigned) {
			throw new NotAllowedException("이 할 일의 담당자로 확인된 사람이 아니다");
		}
		NodeRef now = decision.recordAcceptance(taskId, caller.id(), accepted);
		return new Changed(now, impactsOf(projectId, List.of(now)));
	}

	/** 이 인물·기관을 이 언어로는 이렇게 적는다고 확인한다. 검토자만 할 수 있다. 근거 발언은 없어도 된다. */
	@Transactional
	public Changed confirmName(Caller caller, UUID entityId, String lang, String form, UUID evidenceSpanId) {
		requireReviewer(caller);
		if (evidenceSpanId != null) {
			requireReadableSpans(caller, List.of(evidenceSpanId));
		}
		NodeRef now = entity.confirmName(caller.projectId(), entityId, lang, form, caller.id(), evidenceSpanId);
		// 표기를 새로 확인하는 것은 새 정보다. 기대던 것이 바뀌지 않았으므로 다시 확인으로 남기지 않는다
		return new Changed(now, impactsOf(caller.projectId(), List.of(now)));
	}

	/** 이 표기는 틀렸다고 거절한다. 검토자만 할 수 있다. */
	@Transactional
	public Changed rejectName(Caller caller, UUID entityId, String lang, String form) {
		requireReviewer(caller);
		boolean wasConfirmed = entity.namesOf(caller.projectId(), List.of(entityId))
				.getOrDefault(entityId, List.of()).stream()
				.anyMatch(name -> name.lang().equals(lang) && name.form().equals(form)
						&& "CONFIRMED".equals(name.status()));
		NodeRef now = entity.rejectName(caller.projectId(), entityId, lang, form);
		List<Impact> impacts = impactsOf(caller.projectId(), List.of(now));
		// 확인돼 있던 표기를 거절했을 때만 다시 확인으로 남긴다. 제안 표기를 거절하는 것은 바뀐 것이 아니다
		return new Changed(now, wasConfirmed ? remember(caller, impacts, RecheckReason.NAME) : impacts);
	}

	/**
	 * 이 인물이 이 계정이라고 확인한다(accountId가 null이면 연결을 끊는다). 검토자만 할 수 있고,
	 * 그 계정은 이 프로젝트의 구성원이어야 한다.
	 */
	@Transactional
	public Changed linkAccount(Caller caller, UUID entityId, UUID accountId) {
		requireReviewer(caller);
		if (accountId != null && identity.roleOf(caller.projectId(), accountId).isEmpty()) {
			throw new IllegalArgumentException("프로젝트 구성원이 아닌 계정이다");
		}
		boolean wasLinked = "true".equals(requireCard(caller.projectId(), new NodeRef(NodeType.ENTITY, entityId, 1))
				.states().get("accountLinked"));
		NodeRef now = entity.linkAccount(caller.projectId(), entityId, accountId);
		List<Impact> impacts = impactsOf(caller.projectId(), List.of(now));
		// 이미 연결돼 있던 계정을 바꾸거나 끊을 때만 다시 확인으로 남긴다(처음 연결은 새 정보다)
		return new Changed(now, wasLinked ? remember(caller, impacts, RecheckReason.ACCOUNT) : impacts);
	}

	/**
	 * 발언 내용을 정정한다(전사 오류 등). 이전 내용은 이력에 남고, 이 발언에 기댄 카드가 영향 목록으로 나온다.
	 * 검토자만 할 수 있고, 읽을 수 있는 문서의 발언이어야 한다.
	 */
	@Transactional
	public Changed reviseSpan(Caller caller, UUID spanId, long expectedRevision, String text) {
		requireReviewer(caller);
		requireReadableSpans(caller, List.of(spanId));
		NodeRef span = document.reviseSpan(caller.projectId(), spanId, expectedRevision, text, caller.id());
		return new Changed(span,
				remember(caller, impactsOf(caller.projectId(), List.of(span)), RecheckReason.SPAN_REVISION));
	}

	// ---- 다시 확인 ----

	/**
	 * 검토함 "다시 확인" 한 쪽: 기대는 카드나 발언이 바뀌어 다시 봐야 하는 카드와 그 이유.
	 * 카드는 지금 상태의 검토 묶음으로 싣고, 이 사람이 볼 수 없는 카드는 뺀다. 바뀐 것의 내용(label)도
	 * 이 사람이 볼 수 있을 때만 싣는다.
	 *
	 * @param limit 기본 50, 최대 200
	 * @param after 앞 쪽 응답의 next
	 */
	@Transactional(readOnly = true)
	public RecheckPage listRechecks(Caller caller, Integer limit, String after) {
		Objects.requireNonNull(caller, "caller");
		int size = limit == null ? ReviewFilter.DEFAULT_LIMIT : limit;
		if (size < 1 || size > ReviewFilter.MAX_LIMIT) {
			throw new IllegalArgumentException("limit은 1~" + ReviewFilter.MAX_LIMIT + " 사이여야 한다: " + size);
		}
		UUID projectId = caller.projectId();
		Cursor cursor = after == null ? null : Cursor.parse(after);
		List<OpenRecheck> open = rechecks.listOpen(projectId, size, cursor == null ? null : cursor.at(),
				cursor == null ? null : cursor.id());
		String next = null;
		if (open.size() == size) {
			OpenRecheck last = open.get(open.size() - 1);
			next = new Cursor(last.since(), last.node().id()).encode();
		}
		Map<NodeKey, ItemRow> rows = itemsOf(projectId, open.stream().map(OpenRecheck::node).toList());
		Map<NodeKey, ReviewItem> reviews = new HashMap<>();
		views(caller, open.stream().map(recheck -> rows.get(recheck.node())).filter(Objects::nonNull).toList(), true)
				.forEach(review -> reviews.put(review.owner().key(), review));
		Map<NodeKey, String> labels = causeLabels(caller,
				open.stream().flatMap(recheck -> recheck.causes().stream()).map(cause -> cause.changed()).toList());
		List<RecheckItem> items = new ArrayList<>();
		for (OpenRecheck recheck : open) {
			ReviewItem review = reviews.get(recheck.node());
			if (review == null || review.card() == null || !MARKED.contains(review.card().status())) {
				continue;
			}
			items.add(new RecheckItem(review, recheck.causes().stream()
					.map(cause -> new RecheckCauseView(cause.changed(), labels.get(cause.changed()), cause.reason(),
							cause.distance(), cause.actorId(), cause.at()))
					.toList()));
		}
		return new RecheckPage(items, next);
	}

	/**
	 * 다시 봤고 바뀐 것이 없다고 이 카드의 "다시 확인"을 닫는다. 검토자만 할 수 있다.
	 * 카드를 고치거나 철회하려면 그 카드의 검토 묶음을 처리한다(그러면 저절로 닫힌다).
	 */
	@Transactional
	public RecheckResolved resolveRecheck(Caller caller, NodeKey node) {
		requireReviewer(caller);
		Objects.requireNonNull(node, "node");
		UUID projectId = caller.projectId();
		requireCard(projectId, new NodeRef(node.type(), node.id(), 1));
		ItemRow item = itemsOf(projectId, List.of(node)).get(node);
		if (item != null) {
			requireVisible(caller, item, bundle(item));
		}
		int resolved = rechecks.resolve(projectId, node, caller.id(), RecheckResolution.KEPT);
		if (resolved == 0) {
			throw new IllegalStateException("이 카드에는 다시 확인할 것이 남아 있지 않다");
		}
		return new RecheckResolved(node, resolved);
	}

	/** 이 카드가 바뀌면 다시 확인해야 하는 카드들. 가까운 것부터 돌려준다. */
	@Transactional(readOnly = true)
	public List<Impact> listImpacts(UUID projectId, NodeRef changed) {
		return impactsOf(projectId, List.of(changed));
	}

	// ---- 내부: 검사 ----

	private void validate(UUID projectId, NodeKey owner, NodeView card, ReviewAction action,
			ReviewStatus cardFinal, List<Edge> edges, Map<UUID, ReviewStatus> edgeFinal) {
		List<Edge> confirming = edges.stream()
				.filter(edge -> edge.status() != ReviewStatus.CONFIRMED
						&& edgeFinal.get(edge.id()) == ReviewStatus.CONFIRMED)
				.toList();

		if (card != null && !confirming.isEmpty() && cardFinal != ReviewStatus.CONFIRMED) {
			throw new IllegalStateException("카드가 확인되지 않았으면 딸린 줄도 확인할 수 없다");
		}
		boolean evidenceTouched = card != null && (card.status() != ReviewStatus.CONFIRMED
				|| edges.stream().anyMatch(edge -> edge.rel() == RelType.EVIDENCED_BY
						&& edgeFinal.get(edge.id()) != edge.status()));
		if (evidenceTouched && cardFinal == ReviewStatus.CONFIRMED && NEEDS_EVIDENCE.contains(owner.type())) {
			boolean evidenced = edges.stream().anyMatch(edge -> edge.rel() == RelType.EVIDENCED_BY
					&& edgeFinal.get(edge.id()) == ReviewStatus.CONFIRMED);
			if (!evidenced) {
				throw new IllegalStateException("확인된 근거 줄이 하나도 없으면 카드를 확인 상태로 둘 수 없다");
			}
		}

		List<NodeRef> ends = new ArrayList<>();
		confirming.forEach(edge -> {
			ends.add(edge.src());
			ends.add(edge.dst());
		});
		Map<NodeKey, NodeView> alive = aliveCards(projectId, ends);
		for (Edge edge : confirming) {
			List<UUID> evidence = action.edgeEvidence().getOrDefault(edge.id(), edge.evidenceSpanIds());
			if (evidence.isEmpty()) {
				throw new IllegalArgumentException("근거 없이는 줄을 확인할 수 없다: " + edge.rel());
			}
			NodeRef far = ownerOf(edge).sameNode(edge.src()) ? edge.dst() : edge.src();
			if (isCard(far.type()) && !alive.containsKey(far.key())) {
				throw new IllegalStateException("건너편 카드가 없거나 거절·철회됐다: " + edge.rel() + " → " + far.type());
			}
			if (edge.rel() == RelType.SUPERSEDES || edge.rel() == RelType.WITHDRAWS) {
				String srcKind = kindOf(alive, edge.src(), owner, action);
				String dstKind = kindOf(alive, edge.dst(), owner, action);
				boolean srcOk = edge.rel() == RelType.SUPERSEDES ? decides(srcKind) : "WITHDRAWAL".equals(srcKind);
				if (!srcOk || !decides(dstKind)) {
					throw new IllegalStateException(edge.rel() == RelType.SUPERSEDES
							? "대체 줄은 확정·변경 사건끼리만 확인할 수 있다: " + srcKind + " → " + dstKind
							: "철회 줄은 철회 사건에서 확정·변경 사건으로만 확인할 수 있다: " + srcKind + " → " + dstKind);
				}
			}
		}
	}

	private void requireVisible(Caller caller, ItemRow item, Bundle bundle) {
		if (!visibleTo(caller, identity.readableDocumentIds(caller), bundle)) {
			throw new ReviewNotFoundException(item.id());
		}
	}

	private static boolean decides(String kind) {
		return "DECISION".equals(kind) || "CHANGE".equals(kind);
	}

	/** 사건의 유형. 이번 처리에서 유형을 정정하는 중이면 정정한 값을 쓴다. */
	private static String kindOf(Map<NodeKey, NodeView> alive, NodeRef event, NodeKey owner, ReviewAction action) {
		if (event.key().equals(owner) && action.corrections().containsKey("kind")) {
			return String.valueOf(action.corrections().get("kind"));
		}
		NodeView view = alive.get(event.key());
		return view == null ? null : view.states().get("kind");
	}

	/** 판단을 적용했을 때의 상태. 이미 그 상태면 그대로 돌려주고, 갈 수 없는 상태면 거절한다. */
	private static ReviewStatus target(Verdict verdict, ReviewStatus current, String what) {
		ReviewStatus next = switch (verdict) {
			case CONFIRM -> ReviewStatus.CONFIRMED;
			case HOLD -> ReviewStatus.HELD;
			case REJECT -> ReviewStatus.REJECTED;
			case RETRACT -> ReviewStatus.RETRACTED;
		};
		if (next == current) {
			return current;
		}
		if (!current.canMoveTo(next)) {
			throw new IllegalStateException(what + "의 상태를 바꿀 수 없다: " + current + " → " + next);
		}
		return next;
	}

	/** 따로 판단하지 않은 줄이 카드 판단을 따라갈 때의 판단. null이면 그 줄은 그대로 둔다. */
	private static Verdict followCard(Verdict card, ReviewStatus edge) {
		if (card == null) {
			return null;
		}
		boolean pending = edge == ReviewStatus.CANDIDATE || edge == ReviewStatus.HELD;
		return switch (card) {
			case CONFIRM -> pending ? Verdict.CONFIRM : null;
			case HOLD -> edge == ReviewStatus.CANDIDATE ? Verdict.HOLD : null;
			case REJECT, RETRACT -> pending ? Verdict.REJECT : Verdict.RETRACT;
		};
	}

	private void requireReviewer(Caller caller) {
		Objects.requireNonNull(caller, "caller");
		if (caller.kind() != Caller.Kind.USER) {
			throw new NotAllowedException("확인·정정은 사람만 할 수 있다: " + caller.kind());
		}
		if (!identity.canReview(caller)) {
			throw new NotAllowedException("확인·정정은 프로젝트의 OWNER나 REVIEWER만 할 수 있다");
		}
	}

	// ---- 내부: 묶음 ----

	/** 줄이 딸린 카드. */
	private static NodeRef ownerOf(Edge edge) {
		return OWNED_BY_DST.contains(edge.rel()) ? edge.dst() : edge.src();
	}

	/** 줄의 건너편: 묶음 주인이 아닌 쪽 끝. */
	private static NodeRef farEnd(Edge edge, NodeKey owner) {
		return edge.src().key().equals(owner) ? edge.dst() : edge.src();
	}

	/** 줄이 가리키거나 근거로 대는 발언. */
	private static List<UUID> spansOf(Edge edge) {
		List<UUID> ids = new ArrayList<>();
		if (edge.src().type() == NodeType.SPAN) {
			ids.add(edge.src().id());
		}
		if (edge.dst().type() == NodeType.SPAN) {
			ids.add(edge.dst().id());
		}
		ids.addAll(edge.evidenceSpanIds());
		return ids;
	}

	/** 여러 카드의 검토 묶음(카드 내용과 딸린 살아 있는 줄)을 한 번에 읽는다. 줄은 아래쪽 카드에 딸린 것부터 담는다. */
	private Map<NodeKey, Bundle> bundles(UUID projectId, Collection<NodeKey> owners) {
		Map<NodeKey, NodeView> cards = new HashMap<>();
		cards(projectId, owners.stream().filter(key -> isCard(key.type()))
				.map(key -> new NodeRef(key.type(), key.id(), 1)).toList())
				.forEach(view -> cards.put(view.ref().key(), view));
		Map<NodeKey, List<Edge>> edges = new HashMap<>();
		graph.edgesOfMany(projectId, owners, Direction.IMPACT, OWNED_BY_DST, ACTIVE)
				.forEach(edge -> edges.computeIfAbsent(edge.dst().key(), key -> new ArrayList<>()).add(edge));
		graph.edgesOfMany(projectId, owners, Direction.BASIS, OWNED_BY_SRC, ACTIVE)
				.forEach(edge -> edges.computeIfAbsent(edge.src().key(), key -> new ArrayList<>()).add(edge));
		Map<NodeKey, Bundle> bundles = new HashMap<>();
		for (NodeKey owner : owners) {
			bundles.put(owner, new Bundle(cards.get(owner), edges.getOrDefault(owner, List.of())));
		}
		return bundles;
	}

	private Bundle bundle(ItemRow item) {
		return bundles(item.projectId(), List.of(item.owner())).get(item.owner());
	}

	/**
	 * 검토 묶음을 화면에 보낼 모양으로 꾸린다. 묶음이 몇 개든 DB는 정해진 횟수만 읽는다.
	 * 건너편 카드와 원문은 이 사람이 읽을 수 있는 것만 싣는다. 기준은 안내 데스크와 같다:
	 * 근거 줄이 있는데 그중 읽을 수 있는 것이 하나도 없으면 그 카드는 보이지 않는다.
	 *
	 * @param hideUnseeable 근거를 하나도 읽을 수 없는 묶음을 뺀다. 방금 처리한 묶음을 돌려줄 때는 빼지 않는다
	 */
	private List<ReviewItem> views(Caller caller, List<ItemRow> items, boolean hideUnseeable) {
		if (items.isEmpty()) {
			return List.of();
		}
		UUID projectId = caller.projectId();
		Map<NodeKey, Bundle> bundles = bundles(projectId, items.stream().map(ItemRow::owner).distinct().toList());

		Map<NodeKey, NodeRef> farRefs = new LinkedHashMap<>();
		for (ItemRow item : items) {
			for (Edge edge : bundles.get(item.owner()).edges()) {
				NodeRef far = farEnd(edge, item.owner());
				if (isCard(far.type())) {
					farRefs.putIfAbsent(far.key(), far);
				}
			}
		}
		Map<NodeKey, NodeView> farCards = new HashMap<>();
		cards(projectId, farRefs.values()).forEach(view -> farCards.put(view.ref().key(), view));
		List<NodeKey> gated = farCards.keySet().stream().filter(key -> NEEDS_EVIDENCE.contains(key.type())).toList();
		List<Edge> farEvidence = gated.isEmpty() ? List.of()
				: graph.edgesOfMany(projectId, gated, Direction.BASIS, EnumSet.of(RelType.EVIDENCED_BY), USABLE);

		// 인물·기관 묶음에는 딸린 줄이 거의 없다(언급·담당 줄은 발언·할 일에 딸린다). 판단할 거리로 언어별 표기와,
		// 그 인물을 언급한 발언, 표기마다 적힌 근거 발언을 함께 싣는다
		List<UUID> entityIds = bundles.keySet().stream().filter(key -> key.type() == NodeType.ENTITY)
				.map(NodeKey::id).toList();
		Map<UUID, List<NameForm>> names = entity.namesOf(projectId, entityIds);
		Map<UUID, Set<UUID>> mentionedIn = new HashMap<>();
		names.forEach((id, forms) -> forms.stream().map(NameForm::evidenceSpanId).filter(Objects::nonNull)
				.forEach(span -> mentionedIn.computeIfAbsent(id, key -> new LinkedHashSet<>()).add(span)));
		if (!entityIds.isEmpty()) {
			graph.edgesOfMany(projectId, entityIds.stream().map(id -> new NodeKey(NodeType.ENTITY, id)).toList(),
					Direction.IMPACT, EnumSet.of(RelType.MENTIONS), ACTIVE)
					.forEach(edge -> mentionedIn.computeIfAbsent(edge.dst().id(), key -> new LinkedHashSet<>())
							.addAll(spansOf(edge)));
		}

		Set<UUID> spanIds = new LinkedHashSet<>();
		bundles.values().forEach(bundle -> bundle.edges().forEach(edge -> spanIds.addAll(spansOf(edge))));
		farEvidence.forEach(edge -> spanIds.add(edge.dst().id()));
		mentionedIn.values().forEach(spanIds::addAll);
		Map<UUID, Span> spans = new HashMap<>();
		document.getSpans(projectId, spanIds).forEach(span -> spans.put(span.ref().id(), span));
		Set<UUID> readableDocuments = identity.readableDocumentIds(caller);
		Predicate<UUID> readable = id -> spans.containsKey(id)
				&& readableDocuments.contains(spans.get(id).documentId());

		Set<NodeKey> evidenced = new HashSet<>();
		Set<NodeKey> seeable = new HashSet<>();
		for (Edge edge : farEvidence) {
			evidenced.add(edge.src().key());
			if (readable.test(edge.dst().id())) {
				seeable.add(edge.src().key());
			}
		}

		List<ReviewItem> views = new ArrayList<>();
		for (ItemRow item : items) {
			Bundle bundle = bundles.get(item.owner());
			List<UUID> evidence = bundle.edges().stream().flatMap(edge -> edge.evidenceSpanIds().stream()).toList();
			if (hideUnseeable && !evidence.isEmpty() && evidence.stream().noneMatch(readable)) {
				continue;
			}
			Map<NodeKey, NodeView> related = new LinkedHashMap<>();
			Set<UUID> cited = new LinkedHashSet<>();
			for (Edge edge : bundle.edges()) {
				NodeKey far = farEnd(edge, item.owner()).key();
				NodeView view = farCards.get(far);
				if (view != null && (!evidenced.contains(far) || seeable.contains(far))) {
					related.putIfAbsent(far, view);
				}
				cited.addAll(spansOf(edge));
			}
			cited.addAll(mentionedIn.getOrDefault(item.owner().id(), Set.of()));
			List<Source> sources = cited.stream().filter(readable).map(spans::get)
					.map(span -> new Source(span.ref(), span.documentId(), span.seq(), span.speakerLabel(),
							span.text()))
					.toList();
			NodeRef owner = bundle.card() != null ? bundle.card().ref()
					: new NodeRef(item.owner().type(), item.owner().id(), 1);
			List<NameForm> forms = item.owner().type() == NodeType.ENTITY
					? names.getOrDefault(item.owner().id(), List.of())
					: List.of();
			views.add(new ReviewItem(item.id(), item.version(), item.state(), owner, bundle.card(), bundle.edges(),
					List.copyOf(related.values()), sources, forms));
		}
		return views;
	}

	/** 방금 처리한 묶음. 처리한 사람에게는 근거를 읽을 수 없게 됐어도 돌려준다(원문과 건너편 카드는 걸러진다). */
	private ReviewItem view(Caller caller, ItemRow item) {
		return views(caller, List.of(item), false).get(0);
	}

	/**
	 * 이 카드의 검토 묶음을 만들거나 버전을 올리고, 확인할 것이 남았는지에 따라 상태를 맞춘다.
	 *
	 * @return 검토 묶음의 id
	 */
	private UUID touch(UUID projectId, NodeKey owner) {
		UUID id = jdbc.sql("""
				insert into review_item (id, project_id, owner_type, owner_id, created_at, updated_at)
				values (:id, :projectId, :ownerType, :ownerId, clock_timestamp(), clock_timestamp())
				on conflict (project_id, owner_type, owner_id)
				    do update set version = review_item.version + 1, updated_at = clock_timestamp()
				returning id
				""")
				.param("id", UUID.randomUUID())
				.param("projectId", projectId)
				.param("ownerType", owner.type().name())
				.param("ownerId", owner.id())
				.query((rs, n) -> rs.getObject("id", UUID.class))
				.single();
		Bundle bundle = bundles(projectId, List.of(owner)).get(owner);
		boolean pending = (bundle.card() != null && isPending(bundle.card().status()))
				|| bundle.edges().stream().anyMatch(edge -> isPending(edge.status()));
		jdbc.sql("update review_item set state = :state where id = :id")
				.param("state", (pending ? ReviewItem.State.OPEN : ReviewItem.State.DONE).name())
				.param("id", id)
				.update();
		return id;
	}

	private static boolean isPending(ReviewStatus status) {
		return status == ReviewStatus.CANDIDATE || status == ReviewStatus.HELD;
	}

	private ItemRow findItem(UUID projectId, UUID reviewId, boolean forUpdate) {
		Objects.requireNonNull(reviewId, "reviewId");
		return jdbc.sql("select " + ITEM_COLUMNS + " from review_item"
				+ " where id = :id and project_id = :projectId" + (forUpdate ? " for update" : ""))
				.param("id", reviewId)
				.param("projectId", projectId)
				.query(ReviewImpact::mapItem)
				.optional()
				.orElseThrow(() -> new ReviewNotFoundException(reviewId));
	}

	private boolean alreadyApplied(UUID projectId, String idempotencyKey) {
		return jdbc.sql("select count(*) from review_action where project_id = :projectId and idempotency_key = :key")
				.param("projectId", projectId)
				.param("key", idempotencyKey)
				.query(Long.class)
				.single() > 0;
	}

	// ---- 내부: 카드와 영향 ----

	/** 내용을 읽을 수 있는 카드 종류인가(결정·업무 관리의 다섯 종류와 인물·기관). */
	private static boolean isCard(NodeType type) {
		return DecisionTask.owns(type) || type == NodeType.ENTITY;
	}

	/** 카드 내용을 담당 부서에서 읽어 온다. */
	private List<NodeView> cards(UUID projectId, Collection<NodeRef> refs) {
		List<NodeView> views = new ArrayList<>(decision.getNodes(projectId, refs));
		views.addAll(entity.getNodes(projectId, refs));
		return views;
	}

	/** 이 프로젝트에 있고 거절·철회되지 않은 카드. 내용을 읽을 수 있는 종류만 담긴다. */
	private Map<NodeKey, NodeView> aliveCards(UUID projectId, Collection<NodeRef> refs) {
		Map<NodeKey, NodeView> alive = new HashMap<>();
		if (refs.isEmpty()) {
			return alive;
		}
		for (NodeView view : cards(projectId, refs)) {
			if (ACTIVE.contains(view.status())) {
				alive.put(view.ref().key(), view);
			}
		}
		return alive;
	}

	private static NodeRef current(Map<NodeKey, NodeView> alive, Map<UUID, Span> spans, NodeRef ref) {
		if (ref.type() == NodeType.SPAN) {
			return spans.get(ref.id()).ref();
		}
		NodeView view = alive.get(ref.key());
		return view == null ? ref : view.ref();
	}

	/**
	 * 근거로 대는 발언이 자료실에 실제로 있고, 대는 사람이 읽을 수 있는 문서의 것인지 확인한다.
	 * AI 작업이면 작업을 요청한 사용자가 읽을 수 있어야 한다.
	 */
	private Map<UUID, Span> requireReadableSpans(Caller caller, Collection<UUID> spanIds) {
		Map<UUID, Span> found = new HashMap<>();
		if (spanIds.isEmpty()) {
			return found;
		}
		document.getSpans(caller.projectId(), spanIds).forEach(span -> found.put(span.ref().id(), span));
		Set<UUID> readable = identity.readableDocumentIds(caller);
		for (UUID id : spanIds) {
			Span span = found.get(id);
			if (span == null) {
				throw new IllegalArgumentException("자료실에 없는 발언을 근거로 댔다: " + id);
			}
			if (!readable.contains(span.documentId())) {
				throw new NotAllowedException("읽을 수 없는 문서의 발언을 근거로 댈 수 없다: " + id);
			}
		}
		return found;
	}

	/** 묶음의 줄에 적힌 근거 발언이 없거나, 그중 하나라도 읽을 수 있으면 이 사람에게 보이는 묶음이다. */
	private boolean visibleTo(Caller caller, Set<UUID> readableDocuments, Bundle bundle) {
		Set<UUID> spanIds = new HashSet<>();
		bundle.edges().forEach(edge -> spanIds.addAll(edge.evidenceSpanIds()));
		if (spanIds.isEmpty()) {
			return true;
		}
		return document.getSpans(caller.projectId(), spanIds).stream()
				.anyMatch(span -> readableDocuments.contains(span.documentId()));
	}

	private NodeView requireCard(UUID projectId, NodeRef ref) {
		return cards(projectId, List.of(ref)).stream().findFirst()
				.orElseThrow(() -> new NodeNotFoundException(ref.type(), ref.id()));
	}

	private List<Impact> impactsOf(UUID projectId, Collection<NodeRef> changed) {
		Map<NodeKey, Impact> nearest = new LinkedHashMap<>();
		Set<NodeKey> sources = new LinkedHashSet<>();
		changed.forEach(ref -> sources.add(ref.key()));
		for (NodeRef ref : changed) {
			Subgraph reached = graph.traverse(projectId, ref, Direction.IMPACT, null, DecisionGraph.MAX_DEPTH, ACTIVE);
			reached.depths().forEach((key, distance) -> {
				if (distance == 0 || sources.contains(key)) {
					return;
				}
				Impact known = nearest.get(key);
				if (known == null || distance < known.distance()) {
					nearest.put(key, new Impact(ref.key(), key, distance));
				}
			});
		}
		return nearest.values().stream()
				.sorted((a, b) -> Integer.compare(a.distance(), b.distance()))
				.toList();
	}

	/** 영향받는 카드를 "다시 확인"으로 남긴다. 확인·보류된 결정 사건·조건·할 일만 남는다(결정·업무 관리가 거른다). */
	private List<Impact> remember(Caller caller, List<Impact> impacts, RecheckReason reason) {
		rechecks.mark(caller.projectId(), impacts.stream()
				.map(impact -> new RecheckMark(impact.affected(), impact.changed(), impact.distance()))
				.toList(), reason, caller.id());
		return impacts;
	}

	/** 이 카드들의 검토 묶음. 묶음이 없는 카드는 빠진다. */
	private Map<NodeKey, ItemRow> itemsOf(UUID projectId, Collection<NodeKey> owners) {
		Map<NodeKey, ItemRow> found = new HashMap<>();
		if (owners.isEmpty()) {
			return found;
		}
		Set<NodeKey> wanted = new HashSet<>(owners);
		jdbc.sql("select " + ITEM_COLUMNS + " from review_item"
				+ " where project_id = :projectId and owner_id in (:ids)")
				.param("projectId", projectId)
				.param("ids", owners.stream().map(NodeKey::id).distinct().toList())
				.query(ReviewImpact::mapItem)
				.list()
				.stream()
				.filter(row -> wanted.contains(row.owner()))
				.forEach(row -> found.put(row.owner(), row));
		return found;
	}

	/**
	 * 바뀐 카드·발언의 내용 가운데 이 사람이 볼 수 있는 것. 발언은 읽을 수 있는 문서의 것만,
	 * 근거 줄이 있는 카드는 그 발언 중 하나라도 읽을 수 있을 때만 싣는다(안내 데스크와 같은 기준).
	 */
	private Map<NodeKey, String> causeLabels(Caller caller, Collection<NodeKey> keys) {
		Map<NodeKey, String> labels = new HashMap<>();
		if (keys.isEmpty()) {
			return labels;
		}
		UUID projectId = caller.projectId();
		Set<UUID> readableDocuments = identity.readableDocumentIds(caller);
		List<NodeView> views = cards(projectId, keys.stream().filter(key -> isCard(key.type())).distinct()
				.map(key -> new NodeRef(key.type(), key.id(), 1)).toList());
		List<NodeKey> gated = views.stream().map(view -> view.ref().key())
				.filter(key -> NEEDS_EVIDENCE.contains(key.type())).toList();
		Map<NodeKey, Set<UUID>> evidenceOf = new HashMap<>();
		if (!gated.isEmpty()) {
			graph.edgesOfMany(projectId, gated, Direction.BASIS, EnumSet.of(RelType.EVIDENCED_BY), USABLE)
					.forEach(edge -> evidenceOf.computeIfAbsent(edge.src().key(), key -> new HashSet<>())
							.add(edge.dst().id()));
		}
		Set<UUID> spanIds = new HashSet<>();
		keys.stream().filter(key -> key.type() == NodeType.SPAN).forEach(key -> spanIds.add(key.id()));
		evidenceOf.values().forEach(spanIds::addAll);
		Map<UUID, Span> readable = new HashMap<>();
		document.getSpans(projectId, spanIds).stream()
				.filter(span -> readableDocuments.contains(span.documentId()))
				.forEach(span -> readable.put(span.ref().id(), span));
		for (NodeKey key : keys) {
			if (key.type() == NodeType.SPAN && readable.containsKey(key.id())) {
				labels.put(key, readable.get(key.id()).text());
			}
		}
		for (NodeView view : views) {
			Set<UUID> evidence = evidenceOf.get(view.ref().key());
			if (evidence == null || evidence.stream().anyMatch(readable::containsKey)) {
				labels.put(view.ref().key(), view.label());
			}
		}
		return labels;
	}

	private static NodeType endType(String key, NodeRef ref, Map<String, NodeType> draftTypes) {
		if (ref != null) {
			return ref.type();
		}
		NodeType type = draftTypes.get(key);
		if (type == null) {
			throw new IllegalArgumentException("줄이 가리키는 카드 후보가 없다: " + key);
		}
		return type;
	}

	private static String summarize(ReviewAction action, NodeView card, ReviewStatus cardFinal, List<Edge> edges,
			Map<UUID, ReviewStatus> edgeFinal) {
		StringBuilder summary = new StringBuilder();
		if (card != null) {
			summary.append("card ").append(card.status()).append("->").append(cardFinal);
		}
		if (!action.corrections().isEmpty()) {
			summary.append(" corrected ").append(action.corrections().keySet());
		}
		for (Edge edge : edges) {
			ReviewStatus next = edgeFinal.get(edge.id());
			if (next != edge.status()) {
				summary.append(" edge ").append(edge.id()).append(' ').append(edge.status()).append("->").append(next);
			}
		}
		return summary.toString().strip();
	}

	private static Set<RelType> ownedBySrc() {
		Set<RelType> rels = EnumSet.allOf(RelType.class);
		rels.removeAll(OWNED_BY_DST);
		rels.removeIf(RelType::isSystemRecorded);
		return rels;
	}

	private static ItemRow mapItem(ResultSet rs, int rowNum) throws SQLException {
		return new ItemRow(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
				new NodeKey(NodeType.valueOf(rs.getString("owner_type")), rs.getObject("owner_id", UUID.class)),
				ReviewItem.State.valueOf(rs.getString("state")), rs.getLong("version"),
				rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
	}
}
