package com.contexa.server.entity;

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
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.Fixtures;
import com.contexa.server.contract.Caller;
import com.contexa.server.contract.ContextPacket;
import com.contexa.server.contract.Edge;
import com.contexa.server.contract.EdgeDraft;
import com.contexa.server.contract.Fact;
import com.contexa.server.contract.GraphCandidates;
import com.contexa.server.contract.NodeDraft;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.OpenIssue;
import com.contexa.server.contract.RelType;
import com.contexa.server.contract.ReviewStatus;
import com.contexa.server.contract.Scope;
import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.gateway.ContextGateway;
import com.contexa.server.gateway.TaskFilter;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.NotAllowedException;
import com.contexa.server.identity.Role;
import com.contexa.server.review.ReviewAction;
import com.contexa.server.review.ReviewFilter;
import com.contexa.server.review.ReviewImpact;
import com.contexa.server.review.ReviewItem;
import com.contexa.server.review.Verdict;

/**
 * 이름 확인을 실제 흐름대로 확인한다.
 * "아랍어 문구는 아흐마드 씨가 봐 주세요"라는 발언에서, 해당할 만한 사람이 둘인 상황이다.
 *
 * <pre>
 *   할 일 "아랍어 문구 검토" ─맡는다→ Ahmed Hassan (후보)
 *                            ─맡는다→ Ahmad Al-Hasan (후보)
 *   발언 ─언급한다→ Ahmed Hassan / Ahmad Al-Hasan (후보)
 * </pre>
 *
 * 테스트마다 트랜잭션을 되돌린다.
 */
@SpringBootTest
@Transactional
class EntityFlowTest {

	@Autowired
	ReviewImpact review;

	@Autowired
	ContextGateway gateway;

	@Autowired
	EntityReview entity;

	@Autowired
	IdentityAccess identity;

	@Autowired
	DocumentEvidence document;

	Caller reviewer;
	Caller ahmedAccount;
	UUID projectId;
	NodeRef span;
	Map<String, NodeRef> saved = new HashMap<>();

	@BeforeEach
	void AI가_할_일과_인물_후보를_제출한다() {
		reviewer = Fixtures.newOwner(identity);
		projectId = reviewer.projectId();
		ahmedAccount = Fixtures.newMember(identity, reviewer, Role.MEMBER);
		span = Fixtures.transcript(document, reviewer, "아랍어 문구는 아흐마드 씨가 봐 주세요.").get(0);
		Caller aiJob = new Caller(Caller.Kind.JOB, UUID.randomUUID(), projectId, reviewer.id(),
				Caller.Audience.INTERNAL);
		List<UUID> ev = List.of(span.id());

		review.submitCandidates(aiJob, new GraphCandidates(List.of(
				new NodeDraft("arabic", NodeType.TASK, Map.of("title", "아랍어 문구 검토")),
				person("ahmed", "Ahmed Hassan", Map.of("ar", "أحمد حسن", "ko", "아흐마드 하산")),
				person("ahmad", "Ahmad Al-Hasan", Map.of("ko", "아흐마드 알하산")),
				new NodeDraft("nur", NodeType.ENTITY, Map.of("name", "NUR Labs", "kind", "ORGANIZATION"))),
				List.of(
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "arabic", span, ev),
						EdgeDraft.between(RelType.ASSIGNED_TO, "arabic", "ahmed", ev),
						EdgeDraft.between(RelType.ASSIGNED_TO, "arabic", "ahmad", ev),
						EdgeDraft.fromExisting(RelType.MENTIONS, span, "ahmed", ev),
						EdgeDraft.fromExisting(RelType.MENTIONS, span, "ahmad", ev))));
		for (ReviewItem item : review.listReviews(reviewer, ReviewFilter.open())) {
			if (item.card() != null) {
				saved.put(item.card().label(), item.owner());
			}
		}
	}

	// ---- 이름 찾기 ----

	@Test
	void 같은_표기에_해당하는_사람이_둘이면_둘_다_돌려주고_하나로_고르지_않는다() {
		ContextPacket packet = gateway.resolveEntity(reviewer, "아흐마드", null);

		assertThat(packet.scope().view()).isEqualTo(Scope.View.RESOLVE_ENTITY);
		assertThat(packet.facts()).extracting(Fact::label).containsExactlyInAnyOrder("Ahmed Hassan", "Ahmad Al-Hasan");
		assertThat(packet.facts()).extracting(Fact::status).containsOnly(ReviewStatus.CANDIDATE);
		assertThat(packet.open()).extracting(OpenIssue::kind)
				.containsExactly(OpenIssue.Kind.AMBIGUOUS_NAME, OpenIssue.Kind.AMBIGUOUS_NAME);
	}

	@Test
	void 표기_전체가_같은_후보가_먼저_나오고_없는_이름은_빈_답이_나온다() {
		assertThat(entity.findCandidates(projectId, " ahmed hassan ", null).get(0).name()).isEqualTo("Ahmed Hassan");
		assertThat(entity.findCandidates(projectId, "أحمد حسن", "ar")).singleElement()
				.satisfies(c -> {
					assertThat(c.exact()).isTrue();
					assertThat(c.formConfirmed()).isFalse();
				});
		assertThat(entity.findCandidates(projectId, "아흐마드", "en")).isEmpty();
		assertThat(gateway.resolveEntity(reviewer, "모르는 사람", null).facts()).isEmpty();
		assertThat(gateway.resolveEntity(Fixtures.newOwner(identity), "Ahmed Hassan", null).facts()).isEmpty();
	}

	// ---- 이름 확인은 서로 다른 사실을 따로 확인한다 ----

	@Test
	void 표기를_확인하면_이름_카드에_반영되고_나머지는_제안_표기로_남는다() {
		UUID ahmed = saved.get("Ahmed Hassan").id();

		review.confirmName(reviewer, ahmed, "ar", "أحمد حسن", span.id());

		NameCard card = gateway.nameCard(reviewer, ahmed);
		assertThat(card.entity().states()).containsEntry("name.ar", "أحمد حسن").doesNotContainKey("name.ko");
		assertThat(card.names()).filteredOn(n -> n.lang().equals("ar")).singleElement()
				.satisfies(n -> assertThat(n.status()).isEqualTo("CONFIRMED"));
		assertThat(card.names()).filteredOn(n -> n.lang().equals("ko")).singleElement()
				.satisfies(n -> assertThat(n.status()).isEqualTo("CANDIDATE"));
		// 표기를 확인해도 카드 자체의 확인 상태와 계정 연결은 그대로다
		assertThat(card.entity().status()).isEqualTo(ReviewStatus.CANDIDATE);
		assertThat(card.entity().states()).containsEntry("accountLinked", "false");

		review.rejectName(reviewer, ahmed, "ko", "아흐마드 하산");
		assertThat(entity.findCandidates(projectId, "아흐마드 하산", "ko")).isEmpty();
	}

	@Test
	void 이름과_담당이_확인되어도_본인이_수락하기_전에는_수락_대기다() {
		confirmAhmedAsAssignee();
		review.linkAccount(reviewer, saved.get("Ahmed Hassan").id(), ahmedAccount.id());

		Fact task = gateway.tasks(ahmedAccount, TaskFilter.ofMine()).facts().stream()
				.filter(f -> f.label().equals("아랍어 문구 검토")).findFirst().orElseThrow();
		assertThat(task.states()).containsEntry("acceptance", "PENDING");

		review.acceptTask(ahmedAccount, task.node().id(), true);

		assertThat(gateway.tasks(ahmedAccount, TaskFilter.ofMine()).facts())
				.filteredOn(f -> f.label().equals("아랍어 문구 검토")).singleElement()
				.satisfies(f -> assertThat(f.states()).containsEntry("acceptance", "ACCEPTED")
						.containsEntry("acceptanceBy", ahmedAccount.id().toString()));
	}

	@Test
	void 담당자_본인이_아니면_수락할_수_없다() {
		confirmAhmedAsAssignee();
		UUID task = saved.get("아랍어 문구 검토").id();

		// 계정이 인물 카드에 연결되지 않았다
		assertThatThrownBy(() -> review.acceptTask(ahmedAccount, task, true)).isInstanceOf(NotAllowedException.class);
		// 검토자여도 남의 업무를 대신 수락할 수 없다
		assertThatThrownBy(() -> review.acceptTask(reviewer, task, true)).isInstanceOf(NotAllowedException.class);
		// AI 작업은 요청한 사람이 담당자여도 수락할 수 없다
		review.linkAccount(reviewer, saved.get("Ahmed Hassan").id(), ahmedAccount.id());
		Caller job = new Caller(Caller.Kind.JOB, UUID.randomUUID(), projectId, ahmedAccount.id(),
				Caller.Audience.INTERNAL);
		assertThatThrownBy(() -> review.acceptTask(job, task, true)).isInstanceOf(NotAllowedException.class);
	}

	@Test
	void 담당_줄이_후보인_동안에는_연결된_본인도_수락할_수_없다() {
		ReviewItem person = item("Ahmed Hassan");
		review.applyAction(reviewer, person.id(), ReviewAction.ofCard(Verdict.CONFIRM), person.version(), "p");
		review.linkAccount(reviewer, person.owner().id(), ahmedAccount.id());
		ReviewItem task = item("아랍어 문구 검토");
		Map<UUID, Verdict> hold = new HashMap<>();
		task.edges().stream().filter(e -> e.rel() == RelType.ASSIGNED_TO).forEach(e -> hold.put(e.id(), Verdict.HOLD));
		review.applyAction(reviewer, task.id(), new ReviewAction(Verdict.CONFIRM, null, hold, null), task.version(), "t");

		assertThatThrownBy(() -> review.acceptTask(ahmedAccount, task.owner().id(), true))
				.isInstanceOf(NotAllowedException.class);
		assertThat(gateway.tasks(ahmedAccount, TaskFilter.ofMine()).facts()).isEmpty();
	}

	@Test
	void 계정은_확인된_인물_카드에만_구성원_계정으로_하나씩만_연결된다() {
		UUID ahmed = saved.get("Ahmed Hassan").id();
		UUID ahmad = saved.get("Ahmad Al-Hasan").id();

		assertThatThrownBy(() -> review.linkAccount(reviewer, ahmed, ahmedAccount.id()))
				.isInstanceOf(IllegalStateException.class);

		confirm("Ahmed Hassan");
		confirm("Ahmad Al-Hasan");
		confirm("NUR Labs");
		assertThatThrownBy(() -> review.linkAccount(reviewer, ahmed, UUID.randomUUID()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> review.linkAccount(reviewer, saved.get("NUR Labs").id(), ahmedAccount.id()))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> review.linkAccount(ahmedAccount, ahmed, ahmedAccount.id()))
				.isInstanceOf(NotAllowedException.class);

		review.linkAccount(reviewer, ahmed, ahmedAccount.id());
		assertThatThrownBy(() -> review.linkAccount(reviewer, ahmad, ahmedAccount.id()))
				.isInstanceOf(IllegalStateException.class);
		assertThat(entity.entityOfAccount(projectId, ahmedAccount.id())).contains(ahmed);

		review.linkAccount(reviewer, ahmed, null);
		assertThat(entity.entityOfAccount(projectId, ahmedAccount.id())).isEmpty();
	}

	// ---- 답변서 ----

	@Test
	void 답변서에는_인물_카드가_확인_상태와_함께_실리고_담당_줄의_상태도_보인다() {
		confirmAhmedAsAssignee();

		ContextPacket packet = gateway.tasks(reviewer, TaskFilter.all());

		assertThat(packet.scope().view()).isEqualTo(Scope.View.TASKS);
		assertThat(packet.facts()).filteredOn(f -> f.label().equals("Ahmed Hassan")).singleElement()
				.satisfies(f -> {
					assertThat(f.status()).isEqualTo(ReviewStatus.CONFIRMED);
					assertThat(f.states()).containsEntry("kind", "PERSON");
				});
		// 거절된 담당 줄과 그 건너편 후보는 나오지 않는다
		assertThat(packet.relations()).filteredOn(e -> e.rel() == RelType.ASSIGNED_TO).singleElement()
				.satisfies(e -> assertThat(e.status()).isEqualTo(ReviewStatus.CONFIRMED));
		assertThat(packet.facts()).extracting(Fact::label).doesNotContain("Ahmad Al-Hasan");
		assertThat(gateway.tasks(reviewer, new TaskFilter(false, saved.get("Ahmad Al-Hasan").id(), null)).facts())
				.isEmpty();
		assertThat(gateway.tasks(reviewer, new TaskFilter(false, null, "DONE")).facts()).isEmpty();
	}

	@Test
	void 인물_카드가_바뀌면_그_카드를_참고한_답변은_낡은_것으로_잡힌다() {
		NodeRef ahmed = saved.get("Ahmed Hassan");
		assertThat(gateway.verifyCurrent(reviewer, List.of(ahmed), List.of())).isEmpty();

		review.confirmName(reviewer, ahmed.id(), "en", "Ahmed Hassan", null);

		assertThat(gateway.verifyCurrent(reviewer, List.of(ahmed), List.of())).hasSize(1);
	}

	@Test
	void 발언의_이름이_누구를_가리키는지는_발언_묶음에서_줄마다_정한다() {
		confirm("Ahmed Hassan");
		confirm("Ahmad Al-Hasan");
		ReviewItem mentions = review.listReviews(reviewer, new ReviewFilter(ReviewItem.State.OPEN, NodeType.SPAN))
				.get(0);
		assertThat(mentions.card()).isNull();
		assertThat(mentions.edges()).extracting(Edge::rel).containsOnly(RelType.MENTIONS).hasSize(2);

		Map<UUID, Verdict> verdicts = new HashMap<>();
		for (Edge edge : mentions.edges()) {
			boolean ahmed = edge.dst().id().equals(saved.get("Ahmed Hassan").id());
			verdicts.put(edge.id(), ahmed ? Verdict.CONFIRM : Verdict.REJECT);
		}
		ReviewItem done = review.applyAction(reviewer, mentions.id(), new ReviewAction(null, null, verdicts, null),
				mentions.version(), "m").item();

		assertThat(done.state()).isEqualTo(ReviewItem.State.DONE);
		assertThat(done.edges()).singleElement()
				.satisfies(e -> assertThat(e.dst().id()).isEqualTo(saved.get("Ahmed Hassan").id()));
		// 언급 줄을 확인해도 담당 줄은 여전히 후보다
		assertThat(item("아랍어 문구 검토").edges()).filteredOn(e -> e.rel() == RelType.ASSIGNED_TO)
				.extracting(Edge::status).containsOnly(ReviewStatus.CANDIDATE);
	}

	// ---- 도우미 ----

	private static NodeDraft person(String key, String name, Map<String, String> names) {
		Map<String, Object> fields = new HashMap<>();
		fields.put("name", name);
		fields.put("kind", "PERSON");
		fields.put("names", names);
		return new NodeDraft(key, NodeType.ENTITY, fields);
	}

	private ReviewItem item(String label) {
		for (ReviewItem.State state : ReviewItem.State.values()) {
			for (ReviewItem item : review.listReviews(reviewer, new ReviewFilter(state, null))) {
				if (item.card() != null && item.card().label().equals(label)) {
					return item;
				}
			}
		}
		throw new AssertionError("검토 묶음이 없다: " + label);
	}

	private void confirm(String label) {
		ReviewItem item = item(label);
		review.applyAction(reviewer, item.id(), ReviewAction.ofCard(Verdict.CONFIRM), item.version(),
				"c-" + UUID.randomUUID());
	}

	/** Ahmed Hassan 카드를 확인하고, 할 일의 담당 줄은 Ahmed 쪽만 확인하고 다른 쪽은 거절한다. */
	private void confirmAhmedAsAssignee() {
		confirm("Ahmed Hassan");
		ReviewItem task = item("아랍어 문구 검토");
		Map<UUID, Verdict> verdicts = new HashMap<>();
		task.edges().stream()
				.filter(e -> e.rel() == RelType.ASSIGNED_TO && !e.dst().id().equals(saved.get("Ahmed Hassan").id()))
				.forEach(e -> verdicts.put(e.id(), Verdict.REJECT));
		review.applyAction(reviewer, task.id(), new ReviewAction(Verdict.CONFIRM, null, verdicts, null),
				task.version(), "assign");
	}
}
