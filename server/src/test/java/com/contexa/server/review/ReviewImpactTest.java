package com.contexa.server.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Caller;
import com.contexa.server.contract.ContextPacket;
import com.contexa.server.contract.Direction;
import com.contexa.server.contract.Edge;
import com.contexa.server.contract.EdgeDraft;
import com.contexa.server.contract.Fact;
import com.contexa.server.contract.GraphCandidates;
import com.contexa.server.contract.NodeDraft;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.NodeView;
import com.contexa.server.contract.OpenIssue;
import com.contexa.server.contract.RelType;
import com.contexa.server.contract.ReviewStatus;
import com.contexa.server.contract.Source;
import com.contexa.server.decision.DecisionStatus;
import com.contexa.server.decision.DecisionTask;
import com.contexa.server.decision.NodeNotFoundException;
import com.contexa.server.decision.TaskStatus;
import com.contexa.server.Fixtures;
import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.gateway.ContextGateway;
import com.contexa.server.identity.Visibility;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.NotAllowedException;
import com.contexa.server.identity.Role;

/**
 * 결재 창구를 실제 흐름대로 확인한다: AI가 후보를 내고, 사람이 묶음 단위로 확인한다.
 *
 * <pre>
 *   항목 "개최 방식" ─ 확정 "대면 개최", 변경 "하이브리드로 변경" ─대체한다→ 대면 개최, 제안 "온라인"
 *   할 일 "인쇄 발주" ─필요로 한다→ 조건 "예산 승인" ─근거다→ 22번 발언
 *                         └─근거다→ 23번 발언
 * </pre>
 *
 * 테스트마다 트랜잭션을 되돌린다.
 */
@SpringBootTest
@Transactional
class ReviewImpactTest {

	@Autowired
	ReviewImpact review;

	@Autowired
	DecisionTask decision;

	@Autowired
	ContextGateway gateway;

	@Autowired
	IdentityAccess identity;

	@Autowired
	DocumentEvidence document;

	@Autowired
	JdbcClient jdbc;

	UUID projectId;
	Caller reviewer;
	Caller aiJob;
	NodeRef span22;
	NodeRef span23;

	Map<String, ReviewItem> items;

	@BeforeEach
	void AI가_후보를_제출한다() {
		reviewer = newOwner();
		projectId = reviewer.projectId();
		aiJob = new Caller(Caller.Kind.JOB, UUID.randomUUID(), projectId, reviewer.id(), Caller.Audience.INTERNAL);
		List<NodeRef> spans = Fixtures.transcript(document, reviewer,
				"예산 승인 전까지 인쇄는 보류입니다.", "인쇄 발주는 다음 주에 진행하죠.");
		span22 = spans.get(0);
		span23 = spans.get(1);
		List<UUID> ev = List.of(span22.id());

		review.submitCandidates(aiJob, new GraphCandidates(List.of(
				new NodeDraft("item", NodeType.DECISION_ITEM, Map.of("title", "개최 방식")),
				event("online", "PROPOSAL", "온라인으로 하자"),
				event("inPerson", "DECISION", "대면 개최"),
				event("hybrid", "CHANGE", "하이브리드로 변경"),
				new NodeDraft("print", NodeType.TASK, Map.of("title", "인쇄 발주")),
				new NodeDraft("budget", NodeType.CONDITION, Map.of("description", "예산 승인"))),
				List.of(
						EdgeDraft.between(RelType.HAS_EVENT, "item", "online", ev),
						EdgeDraft.between(RelType.HAS_EVENT, "item", "inPerson", ev),
						EdgeDraft.between(RelType.HAS_EVENT, "item", "hybrid", ev),
						EdgeDraft.between(RelType.SUPERSEDES, "hybrid", "inPerson", ev),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "online", span22, ev),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "inPerson", span22, ev),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "hybrid", span22, ev),
						EdgeDraft.between(RelType.REQUIRES, "print", "budget", ev),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "print", span23, List.of(span23.id())),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "budget", span22, ev))));
		reload();
	}

	// ---- 후보 제출 ----

	@Test
	void 후보를_내면_카드마다_검토_묶음이_생기고_줄은_기대는_쪽_카드에_딸린다() {
		assertThat(items).hasSize(6);
		assertThat(items.values()).allSatisfy(item -> {
			assertThat(item.state()).isEqualTo(ReviewItem.State.OPEN);
			assertThat(item.card().status()).isEqualTo(ReviewStatus.CANDIDATE);
		});
		assertThat(item("인쇄 발주").edges()).extracting(Edge::rel)
				.containsExactlyInAnyOrder(RelType.REQUIRES, RelType.EVIDENCED_BY);
		assertThat(item("예산 승인").edges()).extracting(Edge::rel).containsExactly(RelType.EVIDENCED_BY);
	}

	@Test
	void 항목과_사건을_잇는_줄은_사건_쪽_묶음에_딸린다() {
		assertThat(item("개최 방식").edges()).isEmpty();
		assertThat(item("하이브리드로 변경").edges()).extracting(Edge::rel)
				.containsExactlyInAnyOrder(RelType.HAS_EVENT, RelType.SUPERSEDES, RelType.EVIDENCED_BY);
	}

	@Test
	void 근거_없는_줄이나_규칙에_없는_줄이_섞이면_아무것도_저장하지_않는다() {
		Caller otherOwner = Fixtures.newOwner(identity);
		UUID other = otherOwner.projectId();
		UUID ownSpan = Fixtures.transcript(document, otherOwner, "번역은 승인이 나면 시작합니다.").get(0).id();
		Caller job = new Caller(Caller.Kind.JOB, UUID.randomUUID(), other, otherOwner.id(),
				Caller.Audience.INTERNAL);
		List<NodeDraft> nodes = List.of(new NodeDraft("t", NodeType.TASK, Map.of("title", "번역")),
				new NodeDraft("c", NodeType.CONDITION, Map.of("description", "승인")));

		assertThatThrownBy(() -> review.submitCandidates(job, new GraphCandidates(nodes,
				List.of(EdgeDraft.between(RelType.REQUIRES, "t", "c", List.of())))))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("근거");
		assertThatThrownBy(() -> review.submitCandidates(job, new GraphCandidates(nodes,
				List.of(EdgeDraft.between(RelType.REQUIRES, "c", "t", List.of(ownSpan))))))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("허용되지 않은");
		assertThatThrownBy(() -> review.submitCandidates(job, new GraphCandidates(nodes,
				List.of(EdgeDraft.between(RelType.REQUIRES, "t", "nope", List.of(ownSpan))))))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope");
		assertThatThrownBy(() -> review.submitCandidates(job, new GraphCandidates(nodes, List.of(EdgeDraft.toExisting(
				RelType.DERIVED_FROM, "t", new NodeRef(NodeType.DECISION_ITEM, UUID.randomUUID(), 1),
				List.of(ownSpan))))))
				.isInstanceOf(NodeNotFoundException.class);
		// 자료실에 없는 발언, 다른 프로젝트의 발언은 근거로 댈 수 없다
		assertThatThrownBy(() -> review.submitCandidates(job, new GraphCandidates(nodes,
				List.of(EdgeDraft.between(RelType.REQUIRES, "t", "c", List.of(UUID.randomUUID()))))))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("자료실에 없는");
		assertThatThrownBy(() -> review.submitCandidates(job, new GraphCandidates(nodes,
				List.of(EdgeDraft.between(RelType.REQUIRES, "t", "c", List.of(span22.id()))))))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("자료실에 없는");
		assertThatThrownBy(() -> review.submitCandidates(job, new GraphCandidates(
				List.of(new NodeDraft("p", NodeType.ENTITY, Map.of("title", "Ahmed"))), List.of())))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(jdbc.sql("select count(*) from task where project_id = :p").param("p", other)
				.query(Long.class).single()).isZero();
		assertThat(review.listReviews(otherOwner, null).items()).isEmpty();
	}

	@Test
	void 확인이_끝난_묶음에_새_후보_줄이_들어오면_다시_열린다() {
		confirm("예산 승인");
		ReviewItem print = confirm("인쇄 발주").item();
		assertThat(print.state()).isEqualTo(ReviewItem.State.DONE);

		review.submitCandidates(aiJob, new GraphCandidates(
				List.of(new NodeDraft("translate", NodeType.TASK, Map.of("title", "번역"))),
				List.of(EdgeDraft.fromExisting(RelType.REQUIRES, print.owner(), "translate", List.of(span23.id())))));

		ReviewItem reopened = review.getReview(reviewer, print.id());
		assertThat(reopened.state()).isEqualTo(ReviewItem.State.OPEN);
		assertThat(reopened.version()).isGreaterThan(print.version());
		assertThat(reopened.card().status()).isEqualTo(ReviewStatus.CONFIRMED);
	}

	// ---- 확인 ----

	@Test
	void 묶음을_확인하면_카드와_딸린_줄에_한_번에_도장이_찍힌다() {
		ReviewResult result = confirm("인쇄 발주");

		assertThat(result.item().state()).isEqualTo(ReviewItem.State.DONE);
		assertThat(result.item().card().status()).isEqualTo(ReviewStatus.CONFIRMED);
		assertThat(result.item().card().confirmedBy()).isEqualTo(reviewer.id());
		assertThat(result.item().edges()).extracting(Edge::status).containsOnly(ReviewStatus.CONFIRMED);
		// 건너편 카드(예산 승인)는 따로 확인하기 전까지 AI 후보로 남는다
		assertThat(item("예산 승인").card().status()).isEqualTo(ReviewStatus.CANDIDATE);
	}

	@Test
	void AI_작업과_외부_에이전트는_도장을_찍을_수_없다() {
		Caller agent = new Caller(Caller.Kind.AGENT, UUID.randomUUID(), projectId, null, Caller.Audience.EXTERNAL);
		ReviewItem print = item("인쇄 발주");

		assertThatThrownBy(() -> review.applyAction(aiJob, print.id(), ReviewAction.ofCard(Verdict.CONFIRM),
				print.version(), "k1")).isInstanceOf(NotAllowedException.class);
		assertThatThrownBy(() -> review.applyAction(agent, print.id(), ReviewAction.ofCard(Verdict.CONFIRM),
				print.version(), "k2")).isInstanceOf(NotAllowedException.class);
		assertThatThrownBy(() -> review.changeTaskStatus(aiJob, print.owner().id(), TaskStatus.DONE, 1))
				.isInstanceOf(NotAllowedException.class);
		assertThat(item("인쇄 발주").card().status()).isEqualTo(ReviewStatus.CANDIDATE);
	}

	@Test
	void 근거_줄을_거절하면서_카드를_확인할_수는_없다() {
		ReviewItem budget = item("예산 승인");
		UUID evidenceEdge = budget.edges().get(0).id();

		assertThatThrownBy(() -> review.applyAction(reviewer, budget.id(),
				new ReviewAction(Verdict.CONFIRM, null, Map.of(evidenceEdge, Verdict.REJECT), null),
				budget.version(), "k1"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("근거");

		ReviewItem untouched = item("예산 승인");
		assertThat(untouched.card().status()).isEqualTo(ReviewStatus.CANDIDATE);
		assertThat(untouched.edges().get(0).status()).isEqualTo(ReviewStatus.CANDIDATE);
		assertThat(untouched.version()).isEqualTo(budget.version());
	}

	@Test
	void 확인된_카드의_마지막_근거_줄은_철회할_수_없다() {
		ReviewItem budget = confirm("예산 승인").item();
		UUID evidenceEdge = budget.edges().get(0).id();

		assertThatThrownBy(() -> review.applyAction(reviewer, budget.id(),
				new ReviewAction(null, null, Map.of(evidenceEdge, Verdict.RETRACT), null), budget.version(), "k1"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("근거");
	}

	@Test
	void 카드를_보류하면_딸린_줄도_보류되고_묶음은_열린_채로_남는다() {
		ReviewItem print = item("인쇄 발주");

		ReviewResult held = review.applyAction(reviewer, print.id(), ReviewAction.ofCard(Verdict.HOLD),
				print.version(), "k1");

		assertThat(held.item().state()).isEqualTo(ReviewItem.State.OPEN);
		assertThat(held.item().card().status()).isEqualTo(ReviewStatus.HELD);
		assertThat(held.item().edges()).extracting(Edge::status).containsOnly(ReviewStatus.HELD);

		ReviewResult confirmed = review.applyAction(reviewer, print.id(), ReviewAction.ofCard(Verdict.CONFIRM),
				held.item().version(), "k2");
		assertThat(confirmed.item().state()).isEqualTo(ReviewItem.State.DONE);
	}

	@Test
	void 카드를_거절하면_딸린_줄도_거절되고_답변서에서_사라진다() {
		ReviewItem budget = item("예산 승인");

		ReviewResult rejected = review.applyAction(reviewer, budget.id(), ReviewAction.ofCard(Verdict.REJECT),
				budget.version(), "k1");

		assertThat(rejected.item().state()).isEqualTo(ReviewItem.State.DONE);
		assertThat(rejected.item().edges()).isEmpty();
		ContextPacket packet = gateway.trace(reviewer, item("인쇄 발주").owner(), Direction.BASIS, 2);
		assertThat(packet.facts()).extracting(Fact::label).containsExactly("인쇄 발주");
	}

	@Test
	void 줄마다_따로_판단할_수_있다() {
		ReviewItem print = item("인쇄 발주");
		UUID requires = edge(print, RelType.REQUIRES).id();

		ReviewResult result = review.applyAction(reviewer, print.id(),
				new ReviewAction(Verdict.CONFIRM, null, Map.of(requires, Verdict.REJECT), null),
				print.version(), "k1");

		assertThat(result.item().card().status()).isEqualTo(ReviewStatus.CONFIRMED);
		assertThat(result.item().edges()).extracting(Edge::rel).containsExactly(RelType.EVIDENCED_BY);
		assertThat(result.item().state()).isEqualTo(ReviewItem.State.DONE);
	}

	@Test
	void 내가_본_묶음이_아니면_처리하지_않고_같은_요청이_두_번_오면_한_번만_처리한다() {
		ReviewItem print = item("인쇄 발주");

		assertThatThrownBy(() -> review.applyAction(reviewer, print.id(), ReviewAction.ofCard(Verdict.CONFIRM),
				print.version() + 1, "k1")).isInstanceOf(ReviewVersionConflictException.class);

		ReviewResult first = review.applyAction(reviewer, print.id(), ReviewAction.ofCard(Verdict.CONFIRM),
				print.version(), "k2");
		ReviewResult again = review.applyAction(reviewer, print.id(), ReviewAction.ofCard(Verdict.CONFIRM),
				print.version(), "k2");

		assertThat(again.item().version()).isEqualTo(first.item().version());
		assertThat(jdbc.sql("select count(*) from review_action where review_id = :id").param("id", print.id())
				.query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void 다른_프로젝트의_검토_묶음은_없는_것으로_보인다() {
		Caller outsider = newOwner();
		ReviewItem print = item("인쇄 발주");

		assertThatThrownBy(() -> review.applyAction(outsider, print.id(), ReviewAction.ofCard(Verdict.CONFIRM),
				print.version(), "k1")).isInstanceOf(ReviewNotFoundException.class);
		assertThat(review.listReviews(outsider, null).items()).isEmpty();
	}

	// ---- 결정이 바뀌는 흐름 ----

	@Test
	void 사건_묶음을_확인해_가면_현재_결정이_따라_바뀐다() {
		UUID itemId = confirm("개최 방식").item().owner().id();
		confirm("온라인으로 하자");
		assertThat(decision.currentDecision(itemId).status()).isEqualTo(DecisionStatus.UNDECIDED);

		confirm("대면 개최");
		assertThat(decision.currentDecision(itemId).current().id()).isEqualTo(item("대면 개최").owner().id());

		// 변경 사건은 확인하되, 대체 줄은 판단을 보류한다 → 확정된 결정이 둘 겹친다
		ReviewItem hybrid = item("하이브리드로 변경");
		UUID supersedes = edge(hybrid, RelType.SUPERSEDES).id();
		ReviewResult held = review.applyAction(reviewer, hybrid.id(),
				new ReviewAction(Verdict.CONFIRM, null, Map.of(supersedes, Verdict.HOLD), null),
				hybrid.version(), "k1");
		assertThat(decision.currentDecision(itemId).status()).isEqualTo(DecisionStatus.CONTESTED);
		assertThat(held.item().state()).isEqualTo(ReviewItem.State.OPEN);
		assertThat(gateway.currentState(reviewer, null).open()).extracting(OpenIssue::kind)
				.contains(OpenIssue.Kind.CONTESTED_DECISION);

		// 보류했던 대체 줄을 확인하면 하나로 정해진다
		review.applyAction(reviewer, hybrid.id(),
				new ReviewAction(null, null, Map.of(supersedes, Verdict.CONFIRM), null),
				held.item().version(), "k2");
		assertThat(decision.currentDecision(itemId).current().id()).isEqualTo(hybrid.owner().id());
	}

	@Test
	void 제안이_확정을_대체한다는_줄은_확인할_수_없다() {
		confirm("대면 개최");
		review.submitCandidates(aiJob, new GraphCandidates(List.of(), List.of(EdgeDraft.existing(
				RelType.SUPERSEDES, item("온라인으로 하자").owner(), item("대면 개최").owner(), List.of(span22.id())))));
		ReviewItem online = item("온라인으로 하자");

		assertThatThrownBy(() -> review.applyAction(reviewer, online.id(), ReviewAction.ofCard(Verdict.CONFIRM),
				online.version(), "k1"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("대체 줄");
	}

	@Test
	void 유형을_정정하면서_확인하면_정정한_유형으로_검사하고_저장한다() {
		confirm("대면 개최");
		review.submitCandidates(aiJob, new GraphCandidates(List.of(), List.of(EdgeDraft.existing(
				RelType.SUPERSEDES, item("온라인으로 하자").owner(), item("대면 개최").owner(), List.of(span22.id())))));
		ReviewItem online = item("온라인으로 하자");

		ReviewResult result = review.applyAction(reviewer, online.id(), new ReviewAction(Verdict.CONFIRM,
				Map.of("kind", "CHANGE", "statement", "온라인으로 변경"), null, null), online.version(), "k1");

		assertThat(result.item().card().label()).isEqualTo("온라인으로 변경");
		assertThat(result.item().card().states()).containsEntry("kind", "CHANGE");
	}

	@Test
	void 건너편_카드가_거절됐으면_그_줄은_확인할_수_없다() {
		ReviewItem budget = item("예산 승인");
		review.applyAction(reviewer, budget.id(), ReviewAction.ofCard(Verdict.REJECT), budget.version(), "k1");
		ReviewItem print = item("인쇄 발주");

		assertThatThrownBy(() -> review.applyAction(reviewer, print.id(), ReviewAction.ofCard(Verdict.CONFIRM),
				print.version(), "k2"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("건너편");
	}

	// ---- 개인 메모와 발언 정정 ----

	@Test
	void 개인_메모에서_나온_후보는_메모를_읽을_수_있는_검토자에게만_보인다() {
		Caller member = Fixtures.newMember(identity, reviewer, Role.REVIEWER);
		UUID memo = Fixtures.document(document, member, Visibility.PRIVATE, false,
				"(개인 메모) 인쇄소 견적을 따로 받아 보자.").get(0).id();
		Caller jobForMember = new Caller(Caller.Kind.JOB, UUID.randomUUID(), projectId, member.id(),
				Caller.Audience.INTERNAL);

		// 메모를 읽을 수 없는 사람의 AI 작업은 그 메모를 근거로 댈 수 없다
		GraphCandidates fromMemo = new GraphCandidates(
				List.of(new NodeDraft("quote", NodeType.TASK, Map.of("title", "인쇄소 견적 받기"))),
				List.of(EdgeDraft.toExisting(RelType.EVIDENCED_BY, "quote", new NodeRef(NodeType.SPAN, memo, 1),
						List.of(memo))));
		assertThatThrownBy(() -> review.submitCandidates(aiJob, fromMemo)).isInstanceOf(NotAllowedException.class);

		UUID reviewId = review.submitCandidates(jobForMember, fromMemo).get(0);

		assertThat(review.listReviews(member, null).items()).extracting(ReviewItem::id).contains(reviewId);
		assertThat(review.listReviews(reviewer, null).items()).extracting(ReviewItem::id).doesNotContain(reviewId);
		assertThatThrownBy(() -> review.getReview(reviewer, reviewId)).isInstanceOf(ReviewNotFoundException.class);
		assertThatThrownBy(() -> review.applyAction(reviewer, reviewId, ReviewAction.ofCard(Verdict.CONFIRM), 1, "x"))
				.isInstanceOf(ReviewNotFoundException.class);
		assertThat(review.applyAction(member, reviewId, ReviewAction.ofCard(Verdict.CONFIRM),
				review.getReview(member, reviewId).version(), "y").item().state()).isEqualTo(ReviewItem.State.DONE);
	}

	@Test
	void 묶음에는_건너편_카드와_근거_원문이_함께_실린다() {
		ReviewItem print = item("인쇄 발주");
		assertThat(print.related()).extracting(NodeView::label).containsExactly("예산 승인");
		assertThat(print.sources()).extracting(Source::text)
				.containsExactlyInAnyOrder("예산 승인 전까지 인쇄는 보류입니다.", "인쇄 발주는 다음 주에 진행하죠.");
		assertThat(print.sources()).allSatisfy(source -> assertThat(source.documentId()).isNotNull());

		// 사건 쪽 묶음에는 위의 항목과 대체 대상 사건이 함께 실린다. 근거 발언은 카드가 아니라 원문으로 실린다
		assertThat(item("하이브리드로 변경").related()).extracting(NodeView::label)
				.containsExactlyInAnyOrder("개최 방식", "대면 개최");
	}

	@Test
	void 남의_개인_메모에서_나온_원문과_건너편_카드는_싣지_않는다() {
		Caller member = Fixtures.newMember(identity, reviewer, Role.REVIEWER);
		NodeRef memo = Fixtures.document(document, member, Visibility.PRIVATE, false,
				"(개인 메모) 인쇄소 견적을 두 군데서 받아 보자.").get(0);
		Caller jobForMember = new Caller(Caller.Kind.JOB, UUID.randomUUID(), projectId, member.id(),
				Caller.Audience.INTERNAL);
		review.submitCandidates(jobForMember, new GraphCandidates(
				List.of(new NodeDraft("quotes", NodeType.CONDITION, Map.of("description", "견적 두 곳 비교"))),
				List.of(EdgeDraft.fromExisting(RelType.REQUIRES, item("인쇄 발주").owner(), "quotes",
						List.of(memo.id())),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "quotes", memo, List.of(memo.id())))));

		// 다른 근거는 읽을 수 있으니 묶음은 보이고, 줄도 그대로 있다. 메모 원문과 메모에서 나온 조건만 빠진다
		ReviewItem forOwner = item("인쇄 발주");
		assertThat(forOwner.edges()).filteredOn(edge -> edge.rel() == RelType.REQUIRES).hasSize(2);
		assertThat(forOwner.related()).extracting(NodeView::label).containsExactly("예산 승인");
		assertThat(forOwner.sources()).extracting(Source::text).noneMatch(text -> text.contains("개인 메모"));

		ReviewItem forMember = review.getReview(member, forOwner.id());
		assertThat(forMember.related()).extracting(NodeView::label)
				.containsExactlyInAnyOrder("예산 승인", "견적 두 곳 비교");
		assertThat(forMember.sources()).extracting(Source::text).anyMatch(text -> text.contains("개인 메모"));
	}

	@Test
	void 검토함은_쪽을_나눠_읽고_처리한_것은_최근에_바뀐_묶음부터_나온다() {
		ReviewPage first = review.listReviews(reviewer, new ReviewFilter(ReviewItem.State.OPEN, null, 4, null));
		ReviewPage second = review.listReviews(reviewer,
				new ReviewFilter(ReviewItem.State.OPEN, null, 4, first.next()));
		assertThat(first.items()).hasSize(4);
		assertThat(second.items()).hasSize(2);
		assertThat(second.next()).isNull();
		assertThat(Stream.concat(first.items().stream(), second.items().stream()).map(ReviewItem::id).distinct())
				.hasSize(6);

		confirm("예산 승인");
		confirm("인쇄 발주");
		assertThat(review.listReviews(reviewer, new ReviewFilter(ReviewItem.State.DONE, null)).items())
				.extracting(item -> item.card().label()).containsExactly("인쇄 발주", "예산 승인");

		assertThatThrownBy(() -> review.listReviews(reviewer, new ReviewFilter(null, null, 0, null)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> review.listReviews(reviewer, new ReviewFilter(null, null, null, "not-a-cursor")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 발언을_정정하면_이전_내용이_남고_그_발언에_기댄_카드가_영향_목록에_나온다() {
		Changed changed = review.reviseSpan(reviewer, span22.id(), 1, "예산 승인 후에 인쇄를 진행합니다.");

		assertThat(changed.node().revision()).isEqualTo(2);
		assertThat(document.getSpans(projectId, List.of(span22.id())).get(0).text())
				.isEqualTo("예산 승인 후에 인쇄를 진행합니다.");
		assertThat(jdbc.sql("select text from span_history where span_id = :id").param("id", span22.id())
				.query(String.class).single()).isEqualTo("예산 승인 전까지 인쇄는 보류입니다.");
		assertThat(changed.impacts()).extracting(impact -> impact.affected().id())
				.contains(item("예산 승인").owner().id(), item("인쇄 발주").owner().id());
		assertThatThrownBy(() -> review.reviseSpan(reviewer, span22.id(), 1, "다시 정정"))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> review.reviseSpan(aiJob, span22.id(), 2, "AI가 정정"))
				.isInstanceOf(NotAllowedException.class);
	}

	// ---- 영향 ----

	@Test
	void 확인된_카드를_철회하면_그_카드에_기댄_카드가_영향_목록에_나온다() {
		confirm("인쇄 발주");
		ReviewItem budget = confirm("예산 승인").item();

		ReviewResult retracted = review.applyAction(reviewer, budget.id(), ReviewAction.ofCard(Verdict.RETRACT),
				budget.version(), "k9");

		assertThat(retracted.item().card().status()).isEqualTo(ReviewStatus.RETRACTED);
		assertThat(retracted.impacts()).extracting(impact -> impact.affected().id())
				.containsExactly(item("인쇄 발주").owner().id());
		assertThat(retracted.impacts().get(0).distance()).isEqualTo(1);
	}

	@Test
	void 발언이_바뀌면_영향받는_카드를_가까운_순서로_알려준다() {
		List<Impact> impacts = review.listImpacts(projectId, span22);

		assertThat(impacts).extracting(impact -> impact.affected().type())
				.contains(NodeType.CONDITION, NodeType.TASK, NodeType.DECISION_EVENT, NodeType.DECISION_ITEM);
		assertThat(impacts).extracting(Impact::distance).isSorted();
	}

	// ---- 업무와 조건 ----

	@Test
	void 조건을_충족으로_확인하면_상태가_바뀌고_그_조건에_기댄_할_일이_영향받는다() {
		confirm("인쇄 발주");
		ReviewItem budget = confirm("예산 승인").item();

		Changed changed = review.satisfyCondition(reviewer, budget.owner().id(), span23, null);

		assertThat(item("예산 승인").card().states()).containsEntry("state", "MET");
		assertThat(changed.node().revision()).isGreaterThan(budget.owner().revision());
		assertThat(changed.impacts()).extracting(impact -> impact.affected().id())
				.containsExactly(item("인쇄 발주").owner().id());

		// 되돌릴 때는 조건 묶음에서 충족 줄을 철회한다
		ReviewItem met = item("예산 승인");
		review.applyAction(reviewer, met.id(), new ReviewAction(null, null,
				Map.of(edge(met, RelType.SATISFIED_BY).id(), Verdict.RETRACT), null), met.version(), "k9");
		assertThat(item("예산 승인").card().states()).containsEntry("state", "UNMET");
	}

	@Test
	void 확인되지_않은_조건은_충족으로_바꿀_수_없다() {
		assertThatThrownBy(() -> review.satisfyCondition(reviewer, item("예산 승인").owner().id(), span23, null))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> review.satisfyCondition(reviewer, UUID.randomUUID(), span23, null))
				.isInstanceOf(NodeNotFoundException.class);
	}

	@Test
	void 할_일의_진행_상태는_결재_창구를_거쳐_바꾼다() {
		NodeRef print = confirm("인쇄 발주").item().owner();

		Changed changed = review.changeTaskStatus(reviewer, print.id(), TaskStatus.ON_HOLD, print.revision());

		assertThat(changed.node().revision()).isEqualTo(print.revision() + 1);
		assertThat(item("인쇄 발주").card().states()).containsEntry("workStatus", "ON_HOLD");
		assertThatThrownBy(() -> review.changeTaskStatus(newOwner(), print.id(), TaskStatus.DONE,
				changed.node().revision())).isInstanceOf(NodeNotFoundException.class);
	}

	@Test
	void 일반_구성원은_도장을_찍을_수_없지만_업무_상태는_바꿀_수_있다() {
		NodeRef print = confirm("인쇄 발주").item().owner();
		UUID memberId = identity.signUp("member-" + UUID.randomUUID() + "@contexa.test", "구성원", "password-1234");
		identity.setMember(projectId, reviewer.id(), memberId, Role.MEMBER);
		Caller member = Caller.user(memberId, projectId);
		Caller stranger = Caller.user(UUID.randomUUID(), projectId);
		ReviewItem budget = item("예산 승인");

		assertThatThrownBy(() -> review.applyAction(member, budget.id(), ReviewAction.ofCard(Verdict.CONFIRM),
				budget.version(), "m1")).isInstanceOf(NotAllowedException.class);
		assertThatThrownBy(() -> review.changeTaskStatus(stranger, print.id(), TaskStatus.DONE, print.revision()))
				.isInstanceOf(NotAllowedException.class);

		review.changeTaskStatus(member, print.id(), TaskStatus.IN_PROGRESS, print.revision());
		assertThat(item("인쇄 발주").card().states()).containsEntry("workStatus", "IN_PROGRESS");

		identity.setMember(projectId, reviewer.id(), memberId, Role.REVIEWER);
		assertThat(review.applyAction(member, budget.id(), ReviewAction.ofCard(Verdict.CONFIRM),
				budget.version(), "m2").item().card().confirmedBy()).isEqualTo(memberId);
	}

	// ---- 도우미 ----

	/** 새 사용자를 가입시키고 새 프로젝트의 OWNER로 만든다. */
	private Caller newOwner() {
		return Fixtures.newOwner(identity);
	}

	private static NodeDraft event(String key, String kind, String statement) {
		Map<String, Object> fields = new HashMap<>();
		fields.put("statement", statement);
		fields.put("kind", kind);
		return new NodeDraft(key, NodeType.DECISION_EVENT, fields);
	}

	private void reload() {
		items = new HashMap<>();
		for (ReviewItem.State state : ReviewItem.State.values()) {
			review.listReviews(reviewer, new ReviewFilter(state, null)).items()
					.forEach(item -> items.put(item.card().label(), item));
		}
	}

	private ReviewItem item(String label) {
		reload();
		return items.get(label);
	}

	private static Edge edge(ReviewItem item, RelType rel) {
		return item.edges().stream().filter(e -> e.rel() == rel).findFirst().orElseThrow();
	}

	private ReviewResult confirm(String label) {
		ReviewItem item = item(label);
		return review.applyAction(reviewer, item.id(), ReviewAction.ofCard(Verdict.CONFIRM), item.version(),
				"confirm-" + UUID.randomUUID());
	}
}
