package com.contexa.server.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Edge;
import com.contexa.server.contract.NodeDraft;
import com.contexa.server.contract.NodeKey;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.NodeView;
import com.contexa.server.contract.Ontology;
import com.contexa.server.contract.RelType;
import com.contexa.server.contract.ReviewStatus;
import com.contexa.server.graph.DecisionGraph;

/**
 * "국제교류 행사" 장면으로 결정·업무 관리를 확인한다.
 *
 * <pre>
 *   안건 "국제교류 행사" ─ 항목 "개최 방식"
 *       ├ 제안 "온라인으로 하자"
 *       ├ 확정 "대면 개최"
 *       ├ 변경 "하이브리드로 변경"  ─대체한다→ 대면 개최
 *       └ 철회 "개최 자체를 철회"   ─철회한다→ 하이브리드
 *   할 일 "인쇄 발주" ─필요로 한다→ 조건 "예산 승인"
 *   조건 "장소 확보" ─충족됐다→ 할 일 "장소 예약"
 * </pre>
 *
 * 테스트마다 트랜잭션을 되돌리므로 DB에 흔적이 남지 않는다.
 */
@SpringBootTest
@Transactional
class DecisionTaskTest {

	@Autowired
	DecisionTask decision;

	@Autowired
	DecisionGraph graph;

	@Autowired
	JdbcClient jdbc;

	UUID projectId;
	UUID reviewer;
	NodeRef span;

	NodeRef topic;
	NodeRef item;
	NodeRef online;
	NodeRef inPerson;
	NodeRef hybrid;
	NodeRef cancel;
	NodeRef budget;
	NodeRef venue;
	NodeRef printTask;
	NodeRef bookVenue;

	@BeforeEach
	void 행사_장면의_카드를_후보로_넣는다() {
		projectId = UUID.randomUUID();
		reviewer = UUID.randomUUID();
		span = new NodeRef(NodeType.SPAN, UUID.randomUUID(), 1);

		Map<String, NodeRef> saved = decision.saveCandidates(projectId, List.of(
				draft("topic", NodeType.TOPIC, "title", "국제교류 행사"),
				draft("item", NodeType.DECISION_ITEM, "title", "개최 방식"),
				event("online", EventKind.PROPOSAL, "온라인으로 하자", "2026-10-06T10:00:00+09:00"),
				event("inPerson", EventKind.DECISION, "대면 개최", "2026-10-20T10:00:00+09:00"),
				event("hybrid", EventKind.CHANGE, "하이브리드로 변경", "2026-11-03T10:00:00+09:00"),
				event("cancel", EventKind.WITHDRAWAL, "개최 자체를 철회", "2026-11-10T10:00:00+09:00"),
				draft("budget", NodeType.CONDITION, "description", "예산 승인"),
				draft("venue", NodeType.CONDITION, "description", "장소 확보"),
				draft("print", NodeType.TASK, "title", "인쇄 발주"),
				draft("book", NodeType.TASK, "title", "장소 예약")));
		topic = saved.get("topic");
		item = saved.get("item");
		online = saved.get("online");
		inPerson = saved.get("inPerson");
		hybrid = saved.get("hybrid");
		cancel = saved.get("cancel");
		budget = saved.get("budget");
		venue = saved.get("venue");
		printTask = saved.get("print");
		bookVenue = saved.get("book");
	}

	// ---- 저장과 조회 ----

	@Test
	void 후보는_모두_연필_상태와_버전_1로_저장된다() {
		List<NodeView> views = decision.getNodes(projectId,
				List.of(topic, item, online, inPerson, budget, printTask));

		assertThat(views).hasSize(6);
		assertThat(views).allSatisfy(v -> {
			assertThat(v.status()).isEqualTo(ReviewStatus.CANDIDATE);
			assertThat(v.ref().revision()).isEqualTo(1);
			assertThat(v.confirmedBy()).isNull();
		});
		assertThat(views).extracting(NodeView::label)
				.containsExactly("국제교류 행사", "개최 방식", "온라인으로 하자", "대면 개최", "예산 승인", "인쇄 발주");
	}

	@Test
	void 카드_종류마다_처음_상태가_정해져_있다() {
		assertThat(view(item).states()).containsEntry("decisionState", "UNDECIDED").doesNotContainKey("currentEventId");
		assertThat(view(online).states()).containsEntry("kind", "PROPOSAL").containsEntry("standing", "ACTIVE")
				.containsEntry("occurredAt", "2026-10-06T01:00:00Z");
		assertThat(view(budget).states()).containsEntry("state", "UNMET");
		assertThat(view(printTask).states()).containsEntry("workStatus", "PLANNED")
				.containsEntry("acceptance", "PENDING").doesNotContainKey("dueAt");
	}

	@Test
	void 이_모듈이_소유하지_않는_종류는_저장하지_않는다() {
		assertThatThrownBy(() -> decision.saveCandidates(projectId,
				List.of(draft("person", NodeType.ENTITY, "title", "Ahmed Hassan"))))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 하나라도_틀리면_아무것도_저장하지_않는다() {
		UUID other = UUID.randomUUID();

		assertThatThrownBy(() -> decision.saveCandidates(other, List.of(
				draft("ok", NodeType.TOPIC, "title", "정상 안건"),
				draft("bad", NodeType.TASK, "title", " "))))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
		assertThatThrownBy(() -> decision.saveCandidates(other, List.of(
				draft("ok", NodeType.TOPIC, "title", "정상 안건"),
				draft("ok", NodeType.TOPIC, "title", "같은 key"))))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("key");
		assertThatThrownBy(() -> decision.saveCandidates(other, List.of(
				draft("ok", NodeType.TOPIC, "title", "정상 안건"),
				new NodeDraft("typo", NodeType.CONDITION, Map.of("description", "예산", "assignee", "민지")))))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("assignee");
		assertThatThrownBy(() -> decision.saveCandidates(other, List.of(
				draft("ok", NodeType.TOPIC, "title", "정상 안건"),
				new NodeDraft("ev", NodeType.DECISION_EVENT, Map.of("statement", "대면", "kind", "확정")))))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kind");

		assertThat(jdbc.sql("select count(*) from topic where project_id = :p").param("p", other)
				.query(Long.class).single()).isZero();
	}

	@Test
	void 다른_프로젝트의_카드와_없는_카드는_보이지_않는다() {
		NodeRef missing = new NodeRef(NodeType.TASK, UUID.randomUUID(), 1);

		assertThat(decision.getNodes(UUID.randomUUID(), List.of(item, printTask))).isEmpty();
		assertThat(decision.getNodes(projectId, List.of(missing, printTask, span, printTask)))
				.extracting(v -> v.ref().key()).containsExactly(printTask.key());
		assertThat(decision.currentRevisions(projectId, List.of(missing, printTask, span)))
				.containsOnly(Map.entry(printTask.key(), 1L));
	}

	// ---- 카드 확인 상태 ----

	@Test
	void 카드를_확인하면_버전이_오르고_확인자가_남는다() {
		NodeRef confirmed = decision.setNodeStatus(topic, ReviewStatus.CONFIRMED, reviewer, 1);

		assertThat(confirmed.revision()).isEqualTo(2);
		NodeView v = view(topic);
		assertThat(v.status()).isEqualTo(ReviewStatus.CONFIRMED);
		assertThat(v.confirmedBy()).isEqualTo(reviewer);
		assertThat(v.confirmedAt()).isNotNull();
	}

	@Test
	void 내가_본_버전이_아니면_카드_상태를_바꾸지_않는다() {
		decision.setNodeStatus(topic, ReviewStatus.HELD, reviewer, 1);

		assertThatThrownBy(() -> decision.setNodeStatus(topic, ReviewStatus.CONFIRMED, reviewer, 1))
				.isInstanceOf(NodeRevisionConflictException.class);
		assertThat(view(topic).status()).isEqualTo(ReviewStatus.HELD);
	}

	@Test
	void 카드도_줄과_같은_전이_규칙을_따른다() {
		assertThatThrownBy(() -> decision.setNodeStatus(topic, ReviewStatus.RETRACTED, reviewer, 1))
				.isInstanceOf(IllegalStateException.class);

		decision.setNodeStatus(topic, ReviewStatus.REJECTED, reviewer, 1);
		assertThatThrownBy(() -> decision.setNodeStatus(topic, ReviewStatus.CONFIRMED, reviewer, 2))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 없는_카드는_없다고_알려준다() {
		NodeRef missing = new NodeRef(NodeType.TOPIC, UUID.randomUUID(), 1);

		assertThatThrownBy(() -> decision.setNodeStatus(missing, ReviewStatus.CONFIRMED, reviewer, 1))
				.isInstanceOf(NodeNotFoundException.class);
		assertThatThrownBy(() -> decision.currentDecision(UUID.randomUUID()))
				.isInstanceOf(NodeNotFoundException.class);
	}

	// ---- 현재 결정 ----

	@Test
	void 제안만_확인된_항목은_미확정이다() {
		confirmEventOfItem(online);

		DecisionState state = decision.currentDecision(item.id());

		assertThat(state.status()).isEqualTo(DecisionStatus.UNDECIDED);
		assertThat(state.current()).isNull();
	}

	@Test
	void 확정_사건이_후보인_동안은_현재_결정이_아니다() {
		confirmEdge(link(RelType.HAS_EVENT, item, inPerson));

		assertThat(decision.currentDecision(item.id()).status()).isEqualTo(DecisionStatus.UNDECIDED);

		confirm(inPerson);

		DecisionState state = decision.currentDecision(item.id());
		assertThat(state.status()).isEqualTo(DecisionStatus.DECIDED);
		assertThat(state.current().id()).isEqualTo(inPerson.id());
		assertThat(view(item).states()).containsEntry("decisionState", "DECIDED")
				.containsEntry("currentEventId", inPerson.id().toString());
	}

	@Test
	void 항목과_사건을_잇는_줄이_후보면_그_사건은_세지_않는다() {
		confirm(inPerson);
		UUID hasEvent = link(RelType.HAS_EVENT, item, inPerson);

		assertThat(decision.currentDecision(item.id()).status()).isEqualTo(DecisionStatus.UNDECIDED);

		confirmEdge(hasEvent);
		assertThat(decision.currentDecision(item.id()).status()).isEqualTo(DecisionStatus.DECIDED);
	}

	@Test
	void 확정된_결정이_둘이면_최신이_이기지_않고_경합으로_남는다() {
		confirmEventOfItem(inPerson);
		confirmEventOfItem(hybrid);
		link(RelType.SUPERSEDES, hybrid, inPerson);

		DecisionState state = decision.currentDecision(item.id());

		assertThat(state.status()).isEqualTo(DecisionStatus.CONTESTED);
		assertThat(state.current()).isNull();
		assertThat(state.contenders()).extracting(NodeRef::id).containsExactly(inPerson.id(), hybrid.id());
		assertThat(view(item).states()).containsEntry("decisionState", "CONTESTED")
				.doesNotContainKey("currentEventId");
	}

	@Test
	void 대체_줄을_확인하면_현재_결정이_바뀌고_관련_카드의_버전이_오른다() {
		confirmEventOfItem(inPerson);
		confirmEventOfItem(hybrid);
		UUID supersedes = link(RelType.SUPERSEDES, hybrid, inPerson);
		long itemBefore = revision(item);
		long inPersonBefore = revision(inPerson);

		confirmEdge(supersedes);

		DecisionState state = decision.currentDecision(item.id());
		assertThat(state.status()).isEqualTo(DecisionStatus.DECIDED);
		assertThat(state.current().id()).isEqualTo(hybrid.id());
		assertThat(view(inPerson).states()).containsEntry("standing", "SUPERSEDED");
		assertThat(view(hybrid).states()).containsEntry("standing", "ACTIVE");
		assertThat(revision(item)).isGreaterThan(itemBefore);
		assertThat(revision(inPerson)).isGreaterThan(inPersonBefore);
	}

	@Test
	void 대체_줄을_철회하면_다시_경합이_된다() {
		confirmEventOfItem(inPerson);
		confirmEventOfItem(hybrid);
		Edge supersedes = confirmEdge(link(RelType.SUPERSEDES, hybrid, inPerson));

		Edge retracted = graph.setEdgeStatus(supersedes.id(), ReviewStatus.RETRACTED, reviewer, null,
				supersedes.version());
		decision.onEdgeChanged(retracted);

		assertThat(decision.currentDecision(item.id()).status()).isEqualTo(DecisionStatus.CONTESTED);
		assertThat(view(inPerson).states()).containsEntry("standing", "ACTIVE");
	}

	@Test
	void 철회_줄을_확인하면_유효한_결정이_없어진다() {
		confirmEventOfItem(inPerson);
		confirmEventOfItem(hybrid);
		confirmEdge(link(RelType.SUPERSEDES, hybrid, inPerson));
		confirmEventOfItem(cancel);

		// 철회 사건 자체는 현재 결정의 후보가 아니다
		assertThat(decision.currentDecision(item.id()).current().id()).isEqualTo(hybrid.id());

		confirmEdge(link(RelType.WITHDRAWS, cancel, hybrid));

		DecisionState state = decision.currentDecision(item.id());
		assertThat(state.status()).isEqualTo(DecisionStatus.WITHDRAWN);
		assertThat(state.current()).isNull();
		assertThat(view(hybrid).states()).containsEntry("standing", "WITHDRAWN");
		// 대면은 여전히 대체된 상태다. 철회가 이전 결정을 되살리지는 않는다
		assertThat(view(inPerson).states()).containsEntry("standing", "SUPERSEDED");
	}

	@Test
	void 잘못_뽑힌_사건_카드를_취소하면_이전_결정이_되살아난다() {
		confirmEventOfItem(inPerson);
		NodeRef confirmedHybrid = confirmEventOfItem(hybrid);
		confirmEdge(link(RelType.SUPERSEDES, hybrid, inPerson));
		assertThat(decision.currentDecision(item.id()).current().id()).isEqualTo(hybrid.id());

		decision.setNodeStatus(hybrid, ReviewStatus.RETRACTED, reviewer, revision(confirmedHybrid));

		DecisionState state = decision.currentDecision(item.id());
		assertThat(state.status()).isEqualTo(DecisionStatus.DECIDED);
		assertThat(state.current().id()).isEqualTo(inPerson.id());
		assertThat(view(inPerson).states()).containsEntry("standing", "ACTIVE");
		assertThat(view(item).states()).containsEntry("currentEventId", inPerson.id().toString());
	}

	@Test
	void 후보_사건이_그은_대체_줄은_확인되어도_효력이_없다() {
		confirmEventOfItem(inPerson);
		confirmEdge(link(RelType.HAS_EVENT, item, hybrid));
		confirmEdge(link(RelType.SUPERSEDES, hybrid, inPerson));

		DecisionState state = decision.currentDecision(item.id());

		assertThat(state.status()).isEqualTo(DecisionStatus.DECIDED);
		assertThat(state.current().id()).isEqualTo(inPerson.id());
	}

	@Test
	void 바뀐_것이_없으면_다시_계산해도_버전이_오르지_않는다() {
		confirmEventOfItem(inPerson);
		long itemBefore = revision(item);
		long eventBefore = revision(inPerson);

		DecisionState again = decision.recomputeProjection(item.id());

		assertThat(again.status()).isEqualTo(DecisionStatus.DECIDED);
		assertThat(again.item().revision()).isEqualTo(itemBefore);
		assertThat(revision(item)).isEqualTo(itemBefore);
		assertThat(revision(inPerson)).isEqualTo(eventBefore);
	}

	// ---- 조건 ----

	@Test
	void 발언으로_충족_줄을_확인하면_조건이_충족되고_줄을_철회하면_되돌아간다() {
		Edge satisfied = confirmEdge(link(RelType.SATISFIED_BY, budget, span));

		assertThat(view(budget).states()).containsEntry("state", "MET");
		assertThat(revision(budget)).isEqualTo(2);

		decision.onEdgeChanged(graph.setEdgeStatus(satisfied.id(), ReviewStatus.RETRACTED, reviewer, null,
				satisfied.version()));

		assertThat(view(budget).states()).containsEntry("state", "UNMET");
	}

	@Test
	void 충족_줄이_후보면_조건은_미충족이다() {
		link(RelType.SATISFIED_BY, budget, span);

		assertThat(decision.recomputeCondition(budget.id())).isEqualTo(ConditionState.UNMET);
		assertThat(revision(budget)).isEqualTo(1);
	}

	@Test
	void 할_일로_충족되는_조건은_그_할_일이_완료되어야_충족된다() {
		NodeRef task = confirm(bookVenue);
		confirmEdge(link(RelType.SATISFIED_BY, venue, bookVenue));
		assertThat(view(venue).states()).containsEntry("state", "UNMET");

		task = decision.changeTaskStatus(task.id(), TaskStatus.IN_PROGRESS, reviewer, task.revision());
		assertThat(view(venue).states()).containsEntry("state", "UNMET");

		task = decision.changeTaskStatus(task.id(), TaskStatus.DONE, reviewer, task.revision());
		assertThat(view(venue).states()).containsEntry("state", "MET");

		decision.changeTaskStatus(task.id(), TaskStatus.IN_PROGRESS, reviewer, task.revision());
		assertThat(view(venue).states()).containsEntry("state", "UNMET");
	}

	@Test
	void 완료된_할_일_카드가_취소되면_그것으로_충족됐던_조건도_되돌아간다() {
		NodeRef task = confirm(bookVenue);
		task = decision.changeTaskStatus(task.id(), TaskStatus.DONE, reviewer, task.revision());
		confirmEdge(link(RelType.SATISFIED_BY, venue, bookVenue));
		assertThat(view(venue).states()).containsEntry("state", "MET");

		decision.setNodeStatus(bookVenue, ReviewStatus.RETRACTED, reviewer, task.revision());

		assertThat(view(venue).states()).containsEntry("state", "UNMET");
	}

	// ---- 할 일 ----

	@Test
	void 확인되지_않은_할_일은_진행_상태와_수락을_바꿀_수_없다() {
		assertThatThrownBy(() -> decision.changeTaskStatus(printTask.id(), TaskStatus.IN_PROGRESS, reviewer, 1))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> decision.recordAcceptance(printTask.id(), reviewer, true))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 할_일의_진행_상태를_바꾸면_버전이_오르고_본_버전이_다르면_거절한다() {
		NodeRef task = confirm(printTask);

		NodeRef changed = decision.changeTaskStatus(task.id(), TaskStatus.ON_HOLD, reviewer, task.revision());

		assertThat(changed.revision()).isEqualTo(task.revision() + 1);
		assertThat(view(printTask).states()).containsEntry("workStatus", "ON_HOLD");
		assertThatThrownBy(() -> decision.changeTaskStatus(task.id(), TaskStatus.DONE, reviewer, task.revision()))
				.isInstanceOf(NodeRevisionConflictException.class);
		assertThatThrownBy(
				() -> decision.changeTaskStatus(task.id(), TaskStatus.ON_HOLD, reviewer, changed.revision()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 담당자_본인의_수락과_거절을_기록한다() {
		confirm(printTask);
		UUID account = UUID.randomUUID();

		decision.recordAcceptance(printTask.id(), account, true);
		assertThat(view(printTask).states()).containsEntry("acceptance", "ACCEPTED")
				.containsEntry("acceptanceBy", account.toString());

		decision.recordAcceptance(printTask.id(), account, false);
		assertThat(view(printTask).states()).containsEntry("acceptance", "DECLINED");
		// 수락은 진행 상태를 바꾸지 않는다
		assertThat(view(printTask).states()).containsEntry("workStatus", "PLANNED");
	}

	// ---- 도우미 ----

	private static NodeDraft draft(String key, NodeType type, String field, String value) {
		return new NodeDraft(key, type, Map.of(field, value));
	}

	private static NodeDraft event(String key, EventKind kind, String statement, String occurredAt) {
		Map<String, Object> fields = new HashMap<>();
		fields.put("statement", statement);
		fields.put("kind", kind.name());
		fields.put("occurredAt", occurredAt);
		return new NodeDraft(key, NodeType.DECISION_EVENT, fields);
	}

	private NodeView view(NodeRef ref) {
		return decision.getNodes(projectId, List.of(ref)).get(0);
	}

	private long revision(NodeRef ref) {
		return decision.currentRevisions(projectId, List.of(ref)).get(new NodeKey(ref.type(), ref.id()));
	}

	/** 카드를 지금 버전 기준으로 확인한다. */
	private NodeRef confirm(NodeRef ref) {
		return decision.setNodeStatus(ref, ReviewStatus.CONFIRMED, reviewer, revision(ref));
	}

	/** 사건 카드와, 그 사건을 "개최 방식" 항목에 잇는 줄을 함께 확인한다. */
	private NodeRef confirmEventOfItem(NodeRef eventRef) {
		NodeRef confirmed = confirm(eventRef);
		confirmEdge(link(RelType.HAS_EVENT, item, eventRef));
		return new NodeRef(confirmed.type(), confirmed.id(), revision(confirmed));
	}

	private UUID link(RelType rel, NodeRef src, NodeRef dst) {
		return graph.addCandidateEdge(new Edge(UUID.randomUUID(), projectId, rel, src, dst,
				ReviewStatus.CANDIDATE, List.of(span.id()), null, null, Ontology.VERSION, 1));
	}

	/** 줄을 확인하고, 결재 창구가 하듯이 계산 결과를 따라오게 한다. */
	private Edge confirmEdge(UUID edgeId) {
		Edge confirmed = graph.setEdgeStatus(edgeId, ReviewStatus.CONFIRMED, reviewer, null, 1);
		decision.onEdgeChanged(confirmed);
		return confirmed;
	}
}
