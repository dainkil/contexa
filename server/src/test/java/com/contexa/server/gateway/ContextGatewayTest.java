package com.contexa.server.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Caller;
import com.contexa.server.contract.ContextPacket;
import com.contexa.server.contract.Direction;
import com.contexa.server.contract.Edge;
import com.contexa.server.contract.EdgeRef;
import com.contexa.server.contract.EvidenceRef;
import com.contexa.server.contract.Fact;
import com.contexa.server.contract.NodeDraft;
import com.contexa.server.contract.NodeKey;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.Ontology;
import com.contexa.server.contract.OpenIssue;
import com.contexa.server.contract.RelType;
import com.contexa.server.contract.ReviewStatus;
import com.contexa.server.contract.Scope;
import com.contexa.server.contract.StaleRef;
import com.contexa.server.decision.DecisionTask;
import com.contexa.server.decision.NodeNotFoundException;
import com.contexa.server.decision.TaskStatus;
import com.contexa.server.Fixtures;
import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.entity.EntityReview;
import com.contexa.server.graph.DecisionGraph;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.Role;
import com.contexa.server.identity.Visibility;

/**
 * "인쇄 진행해도 돼?"와 "지금 어디까지 결정됐어?" 장면으로 안내 데스크를 확인한다.
 *
 * <pre>
 *   안건 "국제교류 행사" ─ 항목 "개최 방식" ─ 제안 "온라인", 확정 "대면", 변경 "하이브리드"
 *   답변 ─참고했다→ 할 일 "인쇄 발주" ─필요로 한다→ 조건 "예산 승인" ─근거다→ 22번 발언
 *                         └─근거다→ 23번 발언
 * </pre>
 *
 * 처음에는 모든 카드와 줄이 AI 후보(연필)다. 테스트마다 트랜잭션을 되돌린다.
 */
@SpringBootTest
@Transactional
class ContextGatewayTest {

	@Autowired
	ContextGateway gateway;

	@Autowired
	DecisionTask decision;

	@Autowired
	DecisionGraph graph;

	@Autowired
	IdentityAccess identity;

	@Autowired
	DocumentEvidence document;

	@Autowired
	EntityReview entity;

	UUID projectId;
	UUID reviewer;
	Caller caller;

	NodeRef span22;
	NodeRef span23;
	NodeRef answer;

	NodeRef topic;
	NodeRef item;
	NodeRef online;
	NodeRef inPerson;
	NodeRef hybrid;
	NodeRef printTask;
	NodeRef budget;

	UUID requiresEdge;
	UUID budgetEvidenceEdge;
	UUID taskEvidenceEdge;

	@BeforeEach
	void 장면을_후보로_넣는다() {
		caller = Fixtures.newOwner(identity);
		projectId = caller.projectId();
		reviewer = caller.id();
		List<NodeRef> spans = Fixtures.transcript(document, caller,
				"예산 승인 전까지 인쇄는 보류입니다.", "인쇄 발주는 다음 주에 진행하죠.");
		span22 = spans.get(0);
		span23 = spans.get(1);
		answer = new NodeRef(NodeType.ARTIFACT, UUID.randomUUID(), 1);

		Map<String, NodeRef> saved = decision.saveCandidates(projectId, List.of(
				new NodeDraft("topic", NodeType.TOPIC, Map.of("title", "국제교류 행사")),
				new NodeDraft("item", NodeType.DECISION_ITEM, Map.of("title", "개최 방식")),
				event("online", "PROPOSAL", "온라인으로 하자", "2026-10-06T10:00:00+09:00"),
				event("inPerson", "DECISION", "대면 개최", "2026-10-20T10:00:00+09:00"),
				event("hybrid", "CHANGE", "하이브리드로 변경", "2026-11-03T10:00:00+09:00"),
				new NodeDraft("print", NodeType.TASK, Map.of("title", "인쇄 발주")),
				new NodeDraft("budget", NodeType.CONDITION, Map.of("description", "예산 승인"))));
		topic = saved.get("topic");
		item = saved.get("item");
		online = saved.get("online");
		inPerson = saved.get("inPerson");
		hybrid = saved.get("hybrid");
		printTask = saved.get("print");
		budget = saved.get("budget");

		requiresEdge = link(RelType.REQUIRES, printTask, budget);
		budgetEvidenceEdge = link(RelType.EVIDENCED_BY, budget, span22);
		taskEvidenceEdge = link(RelType.EVIDENCED_BY, printTask, span23);
	}

	// ---- 따라가기 ----

	@Test
	void 인쇄_발주가_무엇에_달려_있는지_물으면_조건과_근거가_담긴_답변서가_나온다() {
		ContextPacket packet = gateway.trace(caller, printTask, Direction.BASIS, 2);

		assertThat(packet.scope().view()).isEqualTo(Scope.View.TRACE);
		assertThat(packet.asOf()).isBeforeOrEqualTo(Instant.now());
		assertThat(packet.facts()).extracting(Fact::label).containsExactly("인쇄 발주", "예산 승인");
		assertThat(fact(packet, budget).states()).containsEntry("state", "UNMET");
		assertThat(fact(packet, budget).evidence()).extracting(e -> e.span().id()).containsExactly(span22.id());
		assertThat(fact(packet, printTask).evidence()).extracting(e -> e.span().id()).containsExactly(span23.id());
		assertThat(packet.relations()).extracting(Edge::id)
				.containsExactlyInAnyOrder(requiresEdge, budgetEvidenceEdge, taskEvidenceEdge);
		assertThat(packet.open()).extracting(OpenIssue::kind).containsExactly(OpenIssue.Kind.UNMET_CONDITION);
		assertThat(packet.open().get(0).node().id()).isEqualTo(budget.id());
	}

	@Test
	void 확인_전에는_모든_것이_AI_후보로_표시되고_확인하면_표시가_바뀐다() {
		ContextPacket before = gateway.trace(caller, printTask, Direction.BASIS, 2);
		assertThat(before.facts()).extracting(Fact::status).containsOnly(ReviewStatus.CANDIDATE);
		assertThat(before.relations()).extracting(Edge::status).containsOnly(ReviewStatus.CANDIDATE);
		assertThat(fact(before, budget).evidence()).extracting(EvidenceRef::status)
				.containsExactly(ReviewStatus.CANDIDATE);

		confirm(printTask);
		confirmEdge(budgetEvidenceEdge);

		ContextPacket after = gateway.trace(caller, printTask, Direction.BASIS, 2);
		assertThat(fact(after, printTask).status()).isEqualTo(ReviewStatus.CONFIRMED);
		assertThat(fact(after, printTask).confirmedBy()).isEqualTo(reviewer);
		assertThat(fact(after, budget).status()).isEqualTo(ReviewStatus.CANDIDATE);
		assertThat(fact(after, budget).evidence()).extracting(EvidenceRef::status)
				.containsExactly(ReviewStatus.CONFIRMED);
	}

	@Test
	void 조건이_충족되면_남은_것_목록에서_빠진다() {
		confirmEdge(link(RelType.SATISFIED_BY, budget, span22));

		ContextPacket packet = gateway.trace(caller, printTask, Direction.BASIS, 2);

		assertThat(fact(packet, budget).states()).containsEntry("state", "MET");
		assertThat(packet.open()).isEmpty();
	}

	@Test
	void 한_칸만_따라가도_담긴_카드의_근거는_붙는다() {
		ContextPacket packet = gateway.trace(caller, printTask, Direction.BASIS, 1);

		assertThat(packet.facts()).extracting(Fact::label).containsExactly("인쇄 발주", "예산 승인");
		assertThat(packet.relations()).extracting(Edge::id).containsExactlyInAnyOrder(requiresEdge, taskEvidenceEdge);
		assertThat(fact(packet, budget).evidence()).hasSize(1);
	}

	@Test
	void 발언을_고치면_흔들리는_것을_영향_쪽으로_따라간다() {
		graph.recordSystemEdge(new Edge(UUID.randomUUID(), projectId, RelType.USES, answer, printTask,
				ReviewStatus.CONFIRMED, List.of(), null, Instant.now(), Ontology.VERSION, 1));

		ContextPacket packet = gateway.trace(caller, span22, Direction.IMPACT, 3);

		assertThat(packet.facts()).extracting(Fact::label).containsExactly("예산 승인", "인쇄 발주");
		assertThat(packet.relations()).extracting(e -> e.src().key())
				.contains(new NodeKey(NodeType.ARTIFACT, answer.id()));
	}

	@Test
	void 보류된_줄은_따라가지_않고_남은_것_목록에_넣는다() {
		graph.setEdgeStatus(requiresEdge, ReviewStatus.HELD, reviewer, null, 1);

		ContextPacket packet = gateway.trace(caller, printTask, Direction.BASIS, 2);

		assertThat(packet.facts()).extracting(Fact::label).containsExactly("인쇄 발주");
		assertThat(packet.relations()).extracting(Edge::id).containsExactly(taskEvidenceEdge);
		assertThat(packet.open()).containsExactly(OpenIssue.ofEdge(requiresEdge));
	}

	@Test
	void 보류된_카드는_사실로_쓰지_않고_그_너머로도_가지_않는다() {
		decision.setNodeStatus(budget, ReviewStatus.HELD, reviewer, 1);

		ContextPacket packet = gateway.trace(caller, printTask, Direction.BASIS, 2);

		assertThat(packet.facts()).extracting(Fact::label).containsExactly("인쇄 발주");
		assertThat(packet.relations()).extracting(Edge::id).containsExactly(taskEvidenceEdge);
		assertThat(packet.open()).extracting(OpenIssue::kind).containsExactly(OpenIssue.Kind.HELD_NODE);
		assertThat(packet.open().get(0).node().id()).isEqualTo(budget.id());
	}

	@Test
	void 거절된_카드와_거절된_줄은_답변서에_나오지_않는다() {
		decision.setNodeStatus(budget, ReviewStatus.REJECTED, reviewer, 1);
		graph.setEdgeStatus(taskEvidenceEdge, ReviewStatus.REJECTED, reviewer, null, 1);

		ContextPacket packet = gateway.trace(caller, printTask, Direction.BASIS, 2);

		assertThat(packet.facts()).extracting(Fact::label).containsExactly("인쇄 발주");
		assertThat(fact(packet, printTask).evidence()).isEmpty();
		assertThat(packet.relations()).isEmpty();
		assertThat(packet.open()).isEmpty();
	}

	@Test
	void 다른_프로젝트에서는_이_카드가_없는_것으로_보인다() {
		Caller outsider = Fixtures.newOwner(identity);

		assertThatThrownBy(() -> gateway.trace(outsider, printTask, Direction.BASIS, 2))
				.isInstanceOf(NodeNotFoundException.class);
		assertThatThrownBy(() -> gateway.decisionHistory(outsider, item.id()))
				.isInstanceOf(NodeNotFoundException.class);
		assertThat(gateway.currentState(outsider, null).facts()).isEmpty();
	}

	@Test
	void 따라갈_칸_수가_범위를_벗어나면_거절한다() {
		assertThatThrownBy(() -> gateway.trace(caller, printTask, Direction.BASIS, 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> gateway.trace(caller, printTask, Direction.BASIS, 11))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ---- 현재 상태 ----

	@Test
	void 현재_상태에는_지금_유효한_결정만_담기고_지난_제안과_대체된_결정은_빠진다() {
		decideHybridOverInPerson();

		ContextPacket packet = gateway.currentState(caller, null);

		assertThat(packet.facts()).extracting(Fact::label)
				.contains("국제교류 행사", "개최 방식", "하이브리드로 변경", "인쇄 발주", "예산 승인")
				.doesNotContain("온라인으로 하자", "대면 개최");
		assertThat(fact(packet, item).states()).containsEntry("decisionState", "DECIDED")
				.containsEntry("currentEventId", hybrid.id().toString());
		assertThat(packet.relations()).extracting(Edge::rel)
				.contains(RelType.HAS_ITEM, RelType.HAS_EVENT, RelType.REQUIRES)
				.doesNotContain(RelType.SUPERSEDES);
		assertThat(packet.open()).extracting(OpenIssue::kind).containsExactly(OpenIssue.Kind.UNMET_CONDITION);
	}

	@Test
	void 확정된_결정이_겹치면_둘_다_담고_경합으로_알린다() {
		confirm(item);
		confirmEventOfItem(inPerson);
		confirmEventOfItem(hybrid);

		ContextPacket packet = gateway.currentState(caller, null);

		assertThat(packet.facts()).extracting(Fact::label).contains("대면 개최", "하이브리드로 변경");
		assertThat(packet.open()).extracting(OpenIssue::kind)
				.containsExactlyInAnyOrder(OpenIssue.Kind.CONTESTED_DECISION, OpenIssue.Kind.UNMET_CONDITION);
	}

	@Test
	void 결정이_없는_항목은_미확정으로_담긴다() {
		confirmEventOfItem(online);

		ContextPacket packet = gateway.currentState(caller, null);

		assertThat(fact(packet, item).states()).containsEntry("decisionState", "UNDECIDED");
		assertThat(packet.facts()).extracting(Fact::label).doesNotContain("온라인으로 하자");
	}

	@Test
	void 안건_하나만_보면_그_안건에_딸린_것만_담긴다() {
		decideHybridOverInPerson();
		NodeRef derived = decision.saveCandidates(projectId, List.of(
				new NodeDraft("venue", NodeType.TASK, Map.of("title", "장소 예약")))).get("venue");
		link(RelType.DERIVED_FROM, derived, item);
		NodeRef person = entity.saveCandidates(projectId, List.of(
				new NodeDraft("minji", NodeType.ENTITY, Map.of("name", "민지", "kind", "PERSON")))).get("minji");
		UUID assigned = link(RelType.ASSIGNED_TO, derived, person);
		// 없는 인물을 가리키는 줄은 답변서에 나오지 않는다
		UUID dangling = link(RelType.ASSIGNED_TO, derived, new NodeRef(NodeType.ENTITY, UUID.randomUUID(), 1));

		ContextPacket packet = gateway.currentState(caller, topic.id());

		assertThat(packet.scope().focus().id()).isEqualTo(topic.id());
		assertThat(packet.facts()).extracting(Fact::label)
				.containsExactlyInAnyOrder("국제교류 행사", "개최 방식", "하이브리드로 변경", "장소 예약", "민지");
		assertThat(packet.relations()).extracting(Edge::id).contains(assigned).doesNotContain(dangling);
	}

	@Test
	void 먼저_끝나야_하는_할_일도_현재_상태에_이어진다() {
		NodeRef translate = decision.saveCandidates(projectId, List.of(
				new NodeDraft("translate", NodeType.TASK, Map.of("title", "아랍어 문구 번역")))).get("translate");
		UUID prerequisite = link(RelType.REQUIRES, printTask, translate);

		ContextPacket packet = gateway.currentState(caller, null);

		assertThat(packet.relations()).extracting(Edge::id).contains(prerequisite, requiresEdge);
		assertThat(fact(packet, translate).states()).containsEntry("workStatus", "PLANNED");
	}

	// ---- 결정 이력 ----

	@Test
	void 결정_이력에는_제안과_대체된_결정까지_일어난_순서로_담긴다() {
		decideHybridOverInPerson();
		confirmEventOfItem(online);

		ContextPacket packet = gateway.decisionHistory(caller, item.id());

		assertThat(packet.facts()).extracting(Fact::label)
				.containsExactly("개최 방식", "온라인으로 하자", "대면 개최", "하이브리드로 변경");
		assertThat(fact(packet, inPerson).states()).containsEntry("standing", "SUPERSEDED");
		assertThat(fact(packet, online).states()).containsEntry("kind", "PROPOSAL");
		assertThat(packet.relations()).extracting(Edge::rel).contains(RelType.HAS_EVENT, RelType.SUPERSEDES);
	}

	// ---- 그사이 바뀌었는지 ----

	@Test
	void 참고한_카드와_줄이_그대로면_바뀐_것이_없다고_답한다() {
		ContextPacket packet = gateway.trace(caller, printTask, Direction.BASIS, 2);

		assertThat(gateway.verifyCurrent(caller, nodesOf(packet), edgesOf(packet))).isEmpty();
	}

	@Test
	void 참고한_뒤에_바뀐_카드와_줄을_찾아낸다() {
		ContextPacket packet = gateway.trace(caller, printTask, Direction.BASIS, 2);

		NodeRef confirmedTask = confirm(printTask);
		decision.changeTaskStatus(printTask.id(), TaskStatus.ON_HOLD, reviewer, confirmedTask.revision());
		graph.setEdgeStatus(requiresEdge, ReviewStatus.HELD, reviewer, null, 1);

		List<StaleRef> stale = gateway.verifyCurrent(caller, nodesOf(packet), edgesOf(packet));

		assertThat(stale).hasSize(2);
		assertThat(stale).filteredOn(s -> s.node() != null).singleElement().satisfies(s -> {
			assertThat(s.node().id()).isEqualTo(printTask.id());
			assertThat(s.currentVersion()).isEqualTo(3);
		});
		assertThat(stale).filteredOn(s -> s.edge() != null).singleElement().satisfies(s -> {
			assertThat(s.edge().id()).isEqualTo(requiresEdge);
			assertThat(s.currentVersion()).isEqualTo(2);
		});
	}

	@Test
	void 충족_줄이_새로_확인되어_조건이_풀린_것도_바뀐_것으로_잡힌다() {
		ContextPacket packet = gateway.trace(caller, printTask, Direction.BASIS, 2);

		confirmEdge(link(RelType.SATISFIED_BY, budget, span22));

		assertThat(gateway.verifyCurrent(caller, nodesOf(packet), edgesOf(packet)))
				.singleElement().satisfies(s -> assertThat(s.node().id()).isEqualTo(budget.id()));
	}

	@Test
	void 찾을_수_없는_카드와_줄은_바뀐_것으로_본다() {
		NodeRef ghost = new NodeRef(NodeType.TASK, UUID.randomUUID(), 1);
		EdgeRef ghostEdge = new EdgeRef(UUID.randomUUID(), 1);
		NodeRef ghostSpan = new NodeRef(NodeType.SPAN, UUID.randomUUID(), 1);
		Caller outsider = Fixtures.newOwner(identity);

		assertThat(gateway.verifyCurrent(caller, List.of(ghost, span22, ghostSpan), List.of(ghostEdge)))
				.extracting(StaleRef::currentVersion).containsExactly(null, null, null);
		assertThat(gateway.verifyCurrent(outsider, List.of(printTask), List.of(new EdgeRef(requiresEdge, 1))))
				.hasSize(2);
	}

	@Test
	void AI_작업은_요청한_사용자가_있어야_한다() {
		assertThatThrownBy(() -> new Caller(Caller.Kind.JOB, UUID.randomUUID(), projectId, null,
				Caller.Audience.INTERNAL)).isInstanceOf(IllegalArgumentException.class);

		UUID requester = UUID.randomUUID();
		Caller job = new Caller(Caller.Kind.JOB, UUID.randomUUID(), projectId, requester, Caller.Audience.INTERNAL);
		assertThat(job.actingUser()).isEqualTo(requester);
		assertThat(gateway.trace(job, printTask, Direction.BASIS, 2).facts()).hasSize(2);
	}

	// ---- 근거를 읽을 수 있는 사람에게만 ----

	@Test
	void 답변서에는_근거_발언의_원문이_실린다() {
		ContextPacket packet = gateway.trace(caller, printTask, Direction.BASIS, 2);

		assertThat(packet.sources()).extracting(source -> source.text())
				.containsExactlyInAnyOrder("예산 승인 전까지 인쇄는 보류입니다.", "인쇄 발주는 다음 주에 진행하죠.");
		assertThat(packet.sources()).filteredOn(source -> source.span().id().equals(span22.id()))
				.singleElement().satisfies(source -> assertThat(source.seq()).isEqualTo(1));
	}

	@Test
	void 개인_메모에서_나온_카드와_줄은_메모_주인에게만_보인다() {
		Caller member = Fixtures.newMember(identity, caller, Role.MEMBER);
		NodeRef memo = Fixtures.document(document, member, Visibility.PRIVATE, false,
				"(개인 메모) 인쇄소 견적을 따로 받아 보자.").get(0);
		NodeRef quote = decision.saveCandidates(projectId, List.of(
				new NodeDraft("quote", NodeType.TASK, Map.of("title", "인쇄소 견적 받기")))).get("quote");
		graph.addCandidateEdge(new Edge(UUID.randomUUID(), projectId, RelType.EVIDENCED_BY, quote, memo,
				ReviewStatus.CANDIDATE, List.of(memo.id()), null, null, Ontology.VERSION, 1));
		UUID prerequisite = graph.addCandidateEdge(new Edge(UUID.randomUUID(), projectId, RelType.REQUIRES,
				printTask, quote, ReviewStatus.CANDIDATE, List.of(memo.id()), null, null, Ontology.VERSION, 1));

		// 메모 주인에게는 보인다
		ContextPacket mine = gateway.trace(member, printTask, Direction.BASIS, 2);
		assertThat(mine.facts()).extracting(Fact::label).contains("인쇄소 견적 받기");
		assertThat(mine.relations()).extracting(Edge::id).contains(prerequisite);
		assertThat(mine.sources()).extracting(source -> source.span().id()).contains(memo.id());

		// 다른 구성원에게는 카드도, 줄도, 원문도 보이지 않는다
		ContextPacket theirs = gateway.trace(caller, printTask, Direction.BASIS, 2);
		assertThat(theirs.facts()).extracting(Fact::label).doesNotContain("인쇄소 견적 받기");
		assertThat(theirs.relations()).extracting(Edge::id).doesNotContain(prerequisite);
		assertThat(theirs.sources()).extracting(source -> source.span().id()).doesNotContain(memo.id());
		assertThat(gateway.currentState(caller, null).facts()).extracting(Fact::label)
				.doesNotContain("인쇄소 견적 받기");
		assertThatThrownBy(() -> gateway.trace(caller, quote, Direction.BASIS, 1))
				.isInstanceOf(NodeNotFoundException.class);
		assertThatThrownBy(() -> gateway.trace(caller, memo, Direction.IMPACT, 1))
				.isInstanceOf(NodeNotFoundException.class);

		// AI 작업은 작업을 요청한 사용자가 볼 수 있는 만큼만 본다
		Caller jobForOwner = new Caller(Caller.Kind.JOB, UUID.randomUUID(), projectId, reviewer,
				Caller.Audience.INTERNAL);
		Caller jobForMember = new Caller(Caller.Kind.JOB, UUID.randomUUID(), projectId, member.id(),
				Caller.Audience.INTERNAL);
		assertThat(gateway.trace(jobForOwner, printTask, Direction.BASIS, 2).facts()).extracting(Fact::label)
				.doesNotContain("인쇄소 견적 받기");
		assertThat(gateway.trace(jobForMember, printTask, Direction.BASIS, 2).facts()).extracting(Fact::label)
				.contains("인쇄소 견적 받기");
	}

	@Test
	void 외부_에이전트에게는_외부_공유가_허용된_문서의_내용만_보인다() {
		Caller agent = identity.authenticate(identity.issueAgentToken(projectId, reviewer), projectId);

		// 회의 전사문은 외부 공유가 허용되지 않았다 → 근거가 있는 카드는 보이지 않는다
		assertThatThrownBy(() -> gateway.trace(agent, printTask, Direction.BASIS, 2))
				.isInstanceOf(NodeNotFoundException.class);

		NodeRef notice = Fixtures.document(document, caller, Visibility.PROJECT, true,
				"행사 안내문: 포스터는 11월 둘째 주에 배포합니다.").get(0);
		NodeRef poster = decision.saveCandidates(projectId, List.of(
				new NodeDraft("poster", NodeType.TASK, Map.of("title", "포스터 배포")))).get("poster");
		graph.addCandidateEdge(new Edge(UUID.randomUUID(), projectId, RelType.EVIDENCED_BY, poster, notice,
				ReviewStatus.CANDIDATE, List.of(notice.id()), null, null, Ontology.VERSION, 1));

		ContextPacket packet = gateway.currentState(agent, null);
		assertThat(packet.facts()).extracting(Fact::label).contains("포스터 배포")
				.doesNotContain("인쇄 발주", "예산 승인");
		assertThat(packet.sources()).extracting(source -> source.span().id()).containsExactly(notice.id());
	}

	@Test
	void 발언이_정정되면_그_발언을_참고한_답변은_낡은_것으로_잡힌다() {
		ContextPacket packet = gateway.trace(caller, printTask, Direction.BASIS, 2);
		List<NodeRef> used = packet.sources().stream().map(source -> source.span()).toList();
		assertThat(gateway.verifyCurrent(caller, used, List.of())).isEmpty();

		document.reviseSpan(projectId, span22.id(), 1, "예산 승인 후에 인쇄를 진행합니다.", reviewer);

		assertThat(gateway.verifyCurrent(caller, used, List.of())).singleElement().satisfies(stale -> {
			assertThat(stale.node().id()).isEqualTo(span22.id());
			assertThat(stale.currentVersion()).isEqualTo(2);
		});
	}

	// ---- 도우미 ----

	private static NodeDraft event(String key, String kind, String statement, String occurredAt) {
		Map<String, Object> fields = new HashMap<>();
		fields.put("statement", statement);
		fields.put("kind", kind);
		fields.put("occurredAt", occurredAt);
		return new NodeDraft(key, NodeType.DECISION_EVENT, fields);
	}

	private static Fact fact(ContextPacket packet, NodeRef node) {
		return packet.facts().stream()
				.filter(f -> f.node().key().equals(node.key()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("답변서에 카드가 없다: " + node));
	}

	private static List<NodeRef> nodesOf(ContextPacket packet) {
		return packet.facts().stream().map(Fact::node).toList();
	}

	private static List<EdgeRef> edgesOf(ContextPacket packet) {
		return packet.relations().stream().map(e -> new EdgeRef(e.id(), e.version())).toList();
	}

	private long revision(NodeRef ref) {
		return decision.currentRevisions(projectId, List.of(ref)).get(ref.key());
	}

	private NodeRef confirm(NodeRef ref) {
		return decision.setNodeStatus(ref, ReviewStatus.CONFIRMED, reviewer, revision(ref));
	}

	private void confirmEventOfItem(NodeRef eventRef) {
		confirm(eventRef);
		confirmEdge(link(RelType.HAS_EVENT, item, eventRef));
	}

	/** 대면 확정 뒤 하이브리드로 변경된 상태를 확인까지 마친다. */
	private void decideHybridOverInPerson() {
		confirm(topic);
		confirm(item);
		confirmEdge(link(RelType.HAS_ITEM, topic, item));
		confirmEventOfItem(inPerson);
		confirmEventOfItem(hybrid);
		confirmEdge(link(RelType.SUPERSEDES, hybrid, inPerson));
	}

	private UUID link(RelType rel, NodeRef src, NodeRef dst) {
		return graph.addCandidateEdge(new Edge(UUID.randomUUID(), projectId, rel, src, dst,
				ReviewStatus.CANDIDATE, List.of(span22.id()), null, null, Ontology.VERSION, 1));
	}

	/** 줄을 확인하고, 결재 창구가 하듯이 계산 결과를 따라오게 한다. */
	private Edge confirmEdge(UUID edgeId) {
		Edge confirmed = graph.setEdgeStatus(edgeId, ReviewStatus.CONFIRMED, reviewer, null, 1);
		decision.onEdgeChanged(confirmed);
		return confirmed;
	}
}
