package com.contexa.server.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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

import com.contexa.server.Fixtures;
import com.contexa.server.contract.Caller;
import com.contexa.server.contract.ContextPacket;
import com.contexa.server.contract.Direction;
import com.contexa.server.contract.EdgeDraft;
import com.contexa.server.contract.GraphCandidates;
import com.contexa.server.contract.NodeDraft;
import com.contexa.server.contract.NodeKey;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.OpenIssue;
import com.contexa.server.contract.RelType;
import com.contexa.server.contract.ReviewStatus;
import com.contexa.server.decision.RecheckReason;
import com.contexa.server.decision.TaskStatus;
import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.gateway.ContextGateway;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.NotAllowedException;
import com.contexa.server.identity.Role;
import com.contexa.server.identity.Visibility;

/**
 * "다시 확인": 확인된 카드가 기대는 발언·카드가 바뀌면 남고, 사람이 다시 보면 닫힌다.
 *
 * <pre>
 *   할 일 "인쇄 발주" ─필요로 한다→ 조건 "예산 승인" ─근거다→ 1번 발언
 *        └─근거다→ 2번 발언
 *   제안 "온라인으로 하자"(후보로 남김) ─근거다→ 1번 발언
 * </pre>
 */
@SpringBootTest
@Transactional
class RecheckTest {

	@Autowired
	ReviewImpact review;

	@Autowired
	ContextGateway gateway;

	@Autowired
	IdentityAccess identity;

	@Autowired
	DocumentEvidence document;

	@Autowired
	JdbcClient jdbc;

	Caller owner;
	Caller aiJob;
	NodeRef span1;
	NodeRef span2;
	Map<String, ReviewItem> items;

	@BeforeEach
	void 조건과_할_일을_확인해_둔다() {
		owner = Fixtures.newOwner(identity);
		aiJob = new Caller(Caller.Kind.JOB, UUID.randomUUID(), owner.projectId(), owner.id(), Caller.Audience.INTERNAL);
		List<NodeRef> spans = Fixtures.transcript(document, owner,
				"예산 승인 전까지 인쇄는 보류입니다.", "인쇄 발주는 다음 주에 진행하죠.");
		span1 = spans.get(0);
		span2 = spans.get(1);
		List<UUID> ev1 = List.of(span1.id());
		Map<String, Object> proposal = new HashMap<>();
		proposal.put("statement", "온라인으로 하자");
		proposal.put("kind", "PROPOSAL");
		review.submitCandidates(aiJob, new GraphCandidates(List.of(
				new NodeDraft("print", NodeType.TASK, Map.of("title", "인쇄 발주")),
				new NodeDraft("budget", NodeType.CONDITION, Map.of("description", "예산 승인")),
				new NodeDraft("online", NodeType.DECISION_EVENT, proposal)),
				List.of(
						EdgeDraft.between(RelType.REQUIRES, "print", "budget", ev1),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "print", span2, List.of(span2.id())),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "budget", span1, ev1),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "online", span1, ev1))));
		confirm("인쇄 발주");
		confirm("예산 승인");
	}

	@Test
	void 발언을_정정하면_그_발언에_기댄_확인된_카드가_다시_확인으로_남고_답변서에도_표시된다() {
		review.reviseSpan(owner, span1.id(), 1, "예산 승인 후에 인쇄를 진행합니다.");

		RecheckPage page = review.listRechecks(owner, null, null);
		assertThat(page.items()).extracting(item -> item.review().card().label())
				.containsExactly("예산 승인", "인쇄 발주");
		assertThat(page.items().get(0).causes()).extracting(RecheckCauseView::changed, RecheckCauseView::label,
				RecheckCauseView::reason, RecheckCauseView::distance, RecheckCauseView::actorId)
				.containsExactly(tuple(span1.key(), "예산 승인 후에 인쇄를 진행합니다.", RecheckReason.SPAN_REVISION, 1,
						owner.id()));
		assertThat(page.items().get(1).causes()).extracting(RecheckCauseView::distance).containsExactly(2);
		// 아직 후보인 제안은 검토함에 이미 있으므로 남기지 않는다
		assertThat(openRows()).isEqualTo(2);

		ContextPacket packet = gateway.trace(owner, item("예산 승인").owner(), Direction.IMPACT, 1);
		assertThat(packet.open()).filteredOn(issue -> issue.kind() == OpenIssue.Kind.NEEDS_RECHECK)
				.extracting(issue -> issue.node().id())
				.containsExactlyInAnyOrder(item("예산 승인").owner().id(), item("인쇄 발주").owner().id());

		// 같은 발언을 또 고쳐도 열린 이유는 늘지 않는다
		review.reviseSpan(owner, span1.id(), 2, "예산 승인 뒤에 인쇄를 진행합니다.");
		assertThat(openRows()).isEqualTo(2);
	}

	@Test
	void 바뀐_것_없음으로_닫으면_목록과_답변서에서_빠지고_기록은_남는다() {
		review.reviseSpan(owner, span1.id(), 1, "예산 승인 후에 인쇄를 진행합니다.");
		NodeKey budget = item("예산 승인").owner().key();

		RecheckResolved resolved = review.resolveRecheck(owner, budget);

		assertThat(resolved.resolved()).isEqualTo(1);
		assertThat(review.listRechecks(owner, null, null).items()).extracting(item -> item.review().card().label())
				.containsExactly("인쇄 발주");
		assertThat(gateway.trace(owner, item("예산 승인").owner(), Direction.IMPACT, 1).open())
				.filteredOn(issue -> issue.kind() == OpenIssue.Kind.NEEDS_RECHECK)
				.extracting(issue -> issue.node().id())
				.containsExactly(item("인쇄 발주").owner().id());
		assertThat(jdbc.sql("select resolution from recheck where node_id = :id").param("id", budget.id())
				.query(String.class).single()).isEqualTo("KEPT");
		assertThatThrownBy(() -> review.resolveRecheck(owner, budget)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 안건과_결정_항목은_담은_사건이_바뀌어도_다시_확인으로_남기지_않는다() {
		List<UUID> ev2 = List.of(span2.id());
		Map<String, Object> decided = new HashMap<>();
		decided.put("statement", "다음 주에 발주한다");
		decided.put("kind", "DECISION");
		decided.put("occurredAt", "2026-11-10T10:00:00+09:00");
		review.submitCandidates(aiJob, new GraphCandidates(List.of(
				new NodeDraft("topic", NodeType.TOPIC, Map.of("title", "인쇄")),
				new NodeDraft("item", NodeType.DECISION_ITEM, Map.of("title", "발주 시기")),
				new NodeDraft("when", NodeType.DECISION_EVENT, decided)),
				List.of(
						EdgeDraft.between(RelType.HAS_ITEM, "topic", "item", ev2),
						EdgeDraft.between(RelType.HAS_EVENT, "item", "when", ev2),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "when", span2, ev2))));
		confirm("인쇄");
		confirm("발주 시기");
		confirm("다음 주에 발주한다");

		review.reviseSpan(owner, span2.id(), 1, "인쇄 발주는 다다음 주에 진행하죠.");

		// 사건과 할 일은 그 발언에 기대므로 남는다. 안건·결정 항목은 사건을 담을 뿐이고, 현재 결정은 server가 다시 계산한다
		assertThat(review.listRechecks(owner, null, null).items()).extracting(item -> item.review().card().label())
				.containsExactlyInAnyOrder("다음 주에 발주한다", "인쇄 발주");
	}

	@Test
	void 후보를_처음_확인하는_것은_다시_확인으로_남기지_않는다() {
		// 준비 단계에서 할 일을 먼저 확인하고 그 할 일이 기대는 조건을 나중에 확인했다
		assertThat(openRows()).isZero();
	}

	@Test
	void 그_카드의_묶음을_다시_처리하면_닫히고_그_처리로_흔들린_카드가_새로_남는다() {
		review.reviseSpan(owner, span1.id(), 1, "예산 승인 후에 인쇄를 진행합니다.");
		ReviewItem budget = item("예산 승인");

		// 다시 보고 내용을 정정한다
		review.applyAction(owner, budget.id(), new ReviewAction(null, Map.of("description", "예산 승인(이사회)"), null,
				null), budget.version(), "fix-1");

		List<RecheckItem> open = review.listRechecks(owner, null, null).items();
		assertThat(open).extracting(item -> item.review().card().label()).containsExactly("인쇄 발주");
		assertThat(open.get(0).causes()).extracting(RecheckCauseView::changed, RecheckCauseView::label,
				RecheckCauseView::reason)
				.containsExactly(
						tuple(span1.key(), "예산 승인 후에 인쇄를 진행합니다.", RecheckReason.SPAN_REVISION),
						tuple(budget.owner().key(), "예산 승인(이사회)", RecheckReason.REVIEW));
		assertThat(jdbc.sql("select resolution from recheck where node_id = :id")
				.param("id", budget.owner().id()).query(String.class).single()).isEqualTo("REVIEWED");
	}

	@Test
	void 할_일의_진행_상태와_수락은_다시_확인으로_남기지_않는다() {
		NodeRef print = item("인쇄 발주").owner();

		review.changeTaskStatus(owner, print.id(), TaskStatus.IN_PROGRESS, print.revision());

		assertThat(openRows()).isZero();
	}

	@Test
	void 구성원은_다시_확인할_것을_볼_수만_있고_닫는_것은_검토자만_한다() {
		review.reviseSpan(owner, span1.id(), 1, "예산 승인 후에 인쇄를 진행합니다.");
		Caller member = Fixtures.newMember(identity, owner, Role.MEMBER);
		NodeKey budget = item("예산 승인").owner().key();

		assertThat(review.listRechecks(member, null, null).items()).hasSize(2);
		assertThatThrownBy(() -> review.resolveRecheck(member, budget)).isInstanceOf(NotAllowedException.class);
		assertThat(review.listRechecks(Fixtures.newOwner(identity), null, null).items()).isEmpty();
	}

	@Test
	void 볼_수_없는_발언이_바뀐_것이면_무엇이_바뀌었는지는_가린다() {
		Caller member = Fixtures.newMember(identity, owner, Role.REVIEWER);
		NodeRef memo = Fixtures.document(document, member, Visibility.PRIVATE, false,
				"(개인 메모) 인쇄 예산은 300만 원까지.").get(0);
		Caller jobForMember = new Caller(Caller.Kind.JOB, UUID.randomUUID(), owner.projectId(), member.id(),
				Caller.Audience.INTERNAL);
		review.submitCandidates(jobForMember, new GraphCandidates(List.of(), List.of(
				EdgeDraft.existing(RelType.EVIDENCED_BY, item("예산 승인").owner(), memo, List.of(memo.id())))));
		ReviewItem budget = item("예산 승인");
		review.applyAction(member, budget.id(), ReviewAction.ofCard(Verdict.CONFIRM), budget.version(), "memo-1");

		review.reviseSpan(member, memo.id(), 1, "(개인 메모) 인쇄 예산은 350만 원까지.");

		RecheckCauseView seenByOwner = review.listRechecks(owner, null, null).items().get(0).causes().get(0);
		RecheckCauseView seenByMember = review.listRechecks(member, null, null).items().get(0).causes().get(0);
		assertThat(seenByOwner.changed()).isEqualTo(memo.key());
		assertThat(seenByOwner.label()).isNull();
		assertThat(seenByMember.label()).isEqualTo("(개인 메모) 인쇄 예산은 350만 원까지.");
	}

	@Test
	void 다시_확인할_것도_쪽을_나눠_읽는다() {
		review.reviseSpan(owner, span1.id(), 1, "예산 승인 후에 인쇄를 진행합니다.");

		RecheckPage first = review.listRechecks(owner, 1, null);
		RecheckPage second = review.listRechecks(owner, 1, first.next());

		assertThat(first.items()).extracting(item -> item.review().card().label()).containsExactly("예산 승인");
		assertThat(second.items()).extracting(item -> item.review().card().label()).containsExactly("인쇄 발주");
		assertThat(review.listRechecks(owner, 1, second.next()).items()).isEmpty();
		assertThatThrownBy(() -> review.listRechecks(owner, 500, null)).isInstanceOf(IllegalArgumentException.class);
	}

	// ---- 도우미 ----

	private long openRows() {
		return jdbc.sql("select count(*) from recheck where project_id = :projectId and resolved_at is null")
				.param("projectId", owner.projectId()).query(Long.class).single();
	}

	private ReviewItem item(String label) {
		items = new HashMap<>();
		for (ReviewItem.State state : ReviewItem.State.values()) {
			review.listReviews(owner, new ReviewFilter(state, null)).items()
					.forEach(item -> items.put(item.card().label(), item));
		}
		return items.get(label);
	}

	private void confirm(String label) {
		ReviewItem item = item(label);
		review.applyAction(owner, item.id(), ReviewAction.ofCard(Verdict.CONFIRM), item.version(),
				"confirm-" + UUID.randomUUID());
		assertThat(item(label).card().status()).isEqualTo(ReviewStatus.CONFIRMED);
	}
}
