package com.contexa.server.dev;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Caller;
import com.contexa.server.contract.Edge;
import com.contexa.server.contract.EdgeDraft;
import com.contexa.server.contract.GraphCandidates;
import com.contexa.server.contract.NodeDraft;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.Ontology;
import com.contexa.server.contract.RelType;
import com.contexa.server.contract.ReviewStatus;
import com.contexa.server.decision.TaskStatus;
import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.document.DocumentInput;
import com.contexa.server.document.DocumentKind;
import com.contexa.server.document.SourceVersionRef;
import com.contexa.server.document.SpanDraft;
import com.contexa.server.graph.DecisionGraph;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.Visibility;
import com.contexa.server.review.ReviewAction;
import com.contexa.server.review.ReviewFilter;
import com.contexa.server.review.ReviewImpact;
import com.contexa.server.review.ReviewItem;
import com.contexa.server.review.Verdict;

/**
 * 개발용 예시 데이터. "국제교류 행사" 장면을 새 프로젝트에 넣는다.
 *
 * <p>실제 흐름과 같은 길로 넣는다: AI 작업이 후보를 제출하고(결재 창구의 submitCandidates),
 * 검토자가 묶음마다 확인한다(applyAction). 그 뒤 두 번째 회의의 후보가 더 들어와 일부는 AI 후보로 남는다.
 * "답변이 이 카드를 참고했다"는 줄만 문서 작성 부서가 하듯이 게시판에 직접 기록한다.
 *
 * <p>contexa.dev-seed.enabled가 true일 때만 켜진다. 배포 환경에서는 켜지 않는다.
 */
@Service
@ConditionalOnProperty(name = "contexa.dev-seed.enabled", havingValue = "true")
public class DevSeed {

	/**
	 * @param token 예시 프로젝트의 OWNER로 로그인한 토큰. 요청마다 "Authorization: Bearer 토큰"으로 보낸다
	 * @param nodes 화면에서 바로 써 볼 수 있도록 이름을 붙인 카드들(현재 버전)
	 */
	public record Seeded(UUID projectId, String token, Map<String, NodeRef> nodes) {
	}

	private final ReviewImpact review;
	private final DecisionGraph graph;
	private final IdentityAccess identity;
	private final DocumentEvidence document;

	public DevSeed(ReviewImpact review, DecisionGraph graph, IdentityAccess identity, DocumentEvidence document) {
		this.review = review;
		this.graph = graph;
		this.identity = identity;
		this.document = document;
	}

	@Transactional
	public Seeded seed() {
		// 예시용 사용자와 프로젝트. 누를 때마다 새로 만든다
		String email = "dev-" + UUID.randomUUID() + "@contexa.local";
		String password = UUID.randomUUID().toString();
		UUID userId = identity.signUp(email, "개발용 검토자", password);
		UUID projectId = identity.createProject(userId, "국제교류 행사 (예시)");
		String token = identity.login(email, password);
		Caller reviewer = Caller.user(userId, projectId);
		Caller aiJob = new Caller(Caller.Kind.JOB, UUID.randomUUID(), projectId, userId, Caller.Audience.INTERNAL);

		// 회의 전사문을 올리고 발언으로 자른다(실제로는 AI 인턴이 자른다)
		Map<String, NodeRef> n = new LinkedHashMap<>();
		transcript(reviewer, n, "1차 회의 전사문", List.of("span08"),
				"행사는 온라인으로 진행하는 게 어떨까요?");
		transcript(reviewer, n, "2차 회의 전사문", List.of("span15"),
				"교류 활동을 생각하면 대면이 낫습니다. 장소를 먼저 예약해 보죠.");
		transcript(reviewer, n, "3차 회의 전사문", List.of("span22", "span23", "span31"),
				"예산 승인 전까지 인쇄는 보류입니다.",
				"포스터 디자인은 Friday까지 가능하고, 인쇄 발주는 그다음입니다. 아랍어 문구는 아흐마드 씨가 봐 주세요.",
				"장소가 확보됐으니 대면 개최로 확정합니다. 날짜는 11월 28일입니다.");
		transcript(reviewer, n, "4차 회의 전사문", List.of("span40"),
				"강당 일정 때문에 12월 5일로 옮깁니다.");
		// 답변은 아직 담당 부서가 없어 id만 있다
		n.put("answer", new NodeRef(NodeType.ARTIFACT, UUID.randomUUID(), 1));

		// 1~3차 회의에서 AI가 뽑은 후보
		review.submitCandidates(aiJob, new GraphCandidates(List.of(
				card("topic", NodeType.TOPIC, "title", "국제교류 행사"),
				card("format", NodeType.DECISION_ITEM, "title", "개최 방식"),
				card("date", NodeType.DECISION_ITEM, "title", "행사 날짜"),
				event("online", "PROPOSAL", "온라인 개최 제안", "2026-10-06T10:00:00+09:00"),
				event("review", "DISCUSSION", "교류 활동을 위해 대면 전환 검토", "2026-10-13T10:00:00+09:00"),
				event("inPerson", "DECISION", "대면 개최 확정", "2026-10-20T10:00:00+09:00"),
				event("nov28", "DECISION", "11월 28일 개최", "2026-10-20T10:20:00+09:00"),
				event("dec5", "CHANGE", "12월 5일로 변경", "2026-11-03T10:00:00+09:00"),
				card("budget", NodeType.CONDITION, "description", "인쇄 예산 승인"),
				card("venue", NodeType.CONDITION, "description", "장소 확보"),
				card("print", NodeType.TASK, "title", "인쇄 발주"),
				card("poster", NodeType.TASK, "title", "포스터 디자인"),
				card("book", NodeType.TASK, "title", "장소 예약")),
				List.of(
						link(n, RelType.HAS_ITEM, "topic", "format", "span08"),
						link(n, RelType.HAS_ITEM, "topic", "date", "span31"),
						link(n, RelType.HAS_EVENT, "format", "online", "span08"),
						link(n, RelType.HAS_EVENT, "format", "review", "span15"),
						link(n, RelType.HAS_EVENT, "format", "inPerson", "span31"),
						link(n, RelType.HAS_EVENT, "date", "nov28", "span31"),
						link(n, RelType.HAS_EVENT, "date", "dec5", "span40"),
						link(n, RelType.SUPERSEDES, "dec5", "nov28", "span40"),
						link(n, RelType.EVIDENCED_BY, "online", "span08", "span08"),
						link(n, RelType.EVIDENCED_BY, "review", "span15", "span15"),
						link(n, RelType.EVIDENCED_BY, "inPerson", "span31", "span31"),
						link(n, RelType.EVIDENCED_BY, "nov28", "span31", "span31"),
						link(n, RelType.EVIDENCED_BY, "dec5", "span40", "span40"),
						link(n, RelType.EVIDENCED_BY, "budget", "span22", "span22"),
						link(n, RelType.EVIDENCED_BY, "venue", "span15", "span15"),
						link(n, RelType.EVIDENCED_BY, "print", "span23", "span23"),
						link(n, RelType.EVIDENCED_BY, "poster", "span23", "span23"),
						link(n, RelType.EVIDENCED_BY, "book", "span15", "span15"),
						// 개최 방식은 장소 확보가 필요하고, 장소 확보는 "장소 예약"이 끝나면 충족된다
						link(n, RelType.REQUIRES, "format", "venue", "span15"),
						link(n, RelType.DERIVED_FROM, "book", "format", "span15"),
						link(n, RelType.SATISFIED_BY, "venue", "book", "span15"),
						// 인쇄 발주는 예산 승인이 먼저다
						link(n, RelType.REQUIRES, "print", "budget", "span22"))));

		// 검토자가 묶음마다 확인한다. 날짜 변경이 이전 날짜를 대체한다는 줄만 판단을 보류한다
		// → "행사 날짜"는 확정된 결정이 둘 겹친 경합 상태로 남는다
		for (ReviewItem item : review.listReviews(reviewer, ReviewFilter.open())) {
			Map<UUID, Verdict> perEdge = new HashMap<>();
			item.edges().stream()
					.filter(edge -> edge.rel() == RelType.SUPERSEDES)
					.forEach(edge -> perEdge.put(edge.id(), Verdict.HOLD));
			review.applyAction(reviewer, item.id(), new ReviewAction(Verdict.CONFIRM, null, perEdge, null),
					item.version(), "seed-" + item.id());
			n.put(nameOf(item), item.owner());
		}
		progress(reviewer, n, "장소 예약", TaskStatus.DONE);
		progress(reviewer, n, "포스터 디자인", TaskStatus.IN_PROGRESS);

		// 다음 회의에서 AI가 더 뽑은 후보. 아직 아무도 확인하지 않았다
		// "아흐마드 씨"에 해당할 만한 사람이 둘이라, 담당 줄과 언급 줄이 각각 두 개씩 후보로 생긴다
		List<UUID> pending = review.submitCandidates(aiJob, new GraphCandidates(
				List.of(card("arabic", NodeType.TASK, "title", "아랍어 문구 검토"),
						person("ahmed", "Ahmed Hassan", Map.of("ar", "أحمد حسن", "ko", "아흐마드 하산")),
						person("ahmad", "Ahmad Al-Hasan", Map.of("ar", "أحمد الحسن", "ko", "아흐마드 알하산"))),
				List.of(
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "arabic", n.get("span23"), evidence(n, "span23")),
						EdgeDraft.between(RelType.ASSIGNED_TO, "arabic", "ahmed", evidence(n, "span23")),
						EdgeDraft.between(RelType.ASSIGNED_TO, "arabic", "ahmad", evidence(n, "span23")),
						EdgeDraft.fromExisting(RelType.MENTIONS, n.get("span23"), "ahmed", evidence(n, "span23")),
						EdgeDraft.fromExisting(RelType.MENTIONS, n.get("span23"), "ahmad", evidence(n, "span23")),
						EdgeDraft.fromExisting(RelType.REQUIRES, n.get("인쇄 발주"), "arabic", evidence(n, "span23")))));
		pending.forEach(id -> {
			ReviewItem item = review.getReview(reviewer, id);
			if (item.card() != null) {
				n.put(nameOf(item), item.owner());
			}
		});

		// "인쇄 진행해도 돼?"에 대한 답변이 참고한 카드
		graph.recordSystemEdge(new Edge(UUID.randomUUID(), projectId, RelType.USES, n.get("answer"),
				n.get("인쇄 발주"), ReviewStatus.CONFIRMED, List.of(), null, Instant.now(), Ontology.VERSION, 1));

		// 화면이 쓰는 짧은 이름으로 바꾸고, 모두 현재 버전을 가리키게 한다
		Map<String, NodeRef> named = new LinkedHashMap<>();
		Map<UUID, NodeRef> latest = new HashMap<>();
		for (ReviewItem.State state : ReviewItem.State.values()) {
			review.listReviews(reviewer, new ReviewFilter(state, null))
					.forEach(item -> latest.put(item.owner().id(), item.owner()));
		}
		Map<String, String> shortNames = Map.of("국제교류 행사", "topic", "개최 방식", "format", "행사 날짜", "date",
				"인쇄 예산 승인", "budget", "장소 확보", "venue", "인쇄 발주", "print", "포스터 디자인", "poster",
				"장소 예약", "book", "아랍어 문구 검토", "arabic");
		named.put("ahmed", n.get("Ahmed Hassan"));
		named.put("ahmad", n.get("Ahmad Al-Hasan"));
		n.forEach((name, ref) -> named.put(shortNames.getOrDefault(name, name), latest.getOrDefault(ref.id(), ref)));
		return new Seeded(projectId, token, named);
	}

	/** 전사문을 올리고 줄마다 발언 하나로 자른 뒤, 발언에 이름을 붙여 기억한다. */
	private void transcript(Caller uploader, Map<String, NodeRef> nodes, String title, List<String> names,
			String... lines) {
		SourceVersionRef version = document.registerDocument(uploader, new DocumentInput(title,
				DocumentKind.TRANSCRIPT, null, Visibility.PROJECT, false, String.join("\n", lines)));
		List<NodeRef> spans = document.saveSpans(version.sourceVersionId(),
				Arrays.stream(lines).map(line -> new SpanDraft(null, line)).toList());
		for (int i = 0; i < names.size(); i++) {
			nodes.put(names.get(i), spans.get(i));
		}
	}

	private static NodeDraft card(String key, NodeType type, String field, String value) {
		return new NodeDraft(key, type, Map.of(field, value));
	}

	private static NodeDraft person(String key, String name, Map<String, String> names) {
		Map<String, Object> fields = new HashMap<>();
		fields.put("name", name);
		fields.put("kind", "PERSON");
		fields.put("names", names);
		return new NodeDraft(key, NodeType.ENTITY, fields);
	}

	private static NodeDraft event(String key, String kind, String statement, String occurredAt) {
		Map<String, Object> fields = new HashMap<>();
		fields.put("statement", statement);
		fields.put("kind", kind);
		fields.put("occurredAt", occurredAt);
		return new NodeDraft(key, NodeType.DECISION_EVENT, fields);
	}

	private static List<UUID> evidence(Map<String, NodeRef> nodes, String span) {
		return List.of(nodes.get(span).id());
	}

	/** 새 카드 사이의 줄, 또는 새 카드에서 이미 id가 있는 발언으로 가는 줄. */
	private static EdgeDraft link(Map<String, NodeRef> nodes, RelType rel, String src, String dst, String span) {
		return nodes.containsKey(dst)
				? EdgeDraft.toExisting(rel, src, nodes.get(dst), evidence(nodes, span))
				: EdgeDraft.between(rel, src, dst, evidence(nodes, span));
	}

	private static String nameOf(ReviewItem item) {
		return item.card() == null ? item.owner().id().toString() : item.card().label();
	}

	private void progress(Caller reviewer, Map<String, NodeRef> nodes, String name, TaskStatus status) {
		// 확인하면서 버전이 올랐으므로 검토함에서 현재 버전을 다시 읽는다
		NodeRef task = review.listReviews(reviewer, new ReviewFilter(ReviewItem.State.DONE, NodeType.TASK)).stream()
				.map(ReviewItem::owner)
				.filter(owner -> owner.id().equals(nodes.get(name).id()))
				.findFirst()
				.orElseThrow();
		nodes.put(name, review.changeTaskStatus(reviewer, task.id(), status, task.revision()).node());
	}
}
