package com.contexa.server.dev;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Caller;
import com.contexa.server.contract.EdgeDraft;
import com.contexa.server.contract.GraphCandidates;
import com.contexa.server.contract.NodeDraft;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.RelType;
import com.contexa.server.decision.TaskStatus;
import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.document.DocumentInput;
import com.contexa.server.document.DocumentKind;
import com.contexa.server.document.SourceVersionRef;
import com.contexa.server.document.SpanDraft;
import com.contexa.server.generation.Draft;
import com.contexa.server.generation.GenKind;
import com.contexa.server.generation.GenerationRequested;
import com.contexa.server.generation.GenerationWiki;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.Visibility;
import com.contexa.server.job.JobOperation;
import com.contexa.server.review.ReviewAction;
import com.contexa.server.review.ReviewFilter;
import com.contexa.server.review.ReviewImpact;
import com.contexa.server.review.ReviewItem;
import com.contexa.server.review.Verdict;

/**
 * 시연용 데이터. 정해진 계정으로 로그인하면 앱의 모든 화면에 볼 것이 있도록 "국제교류의 밤 준비" 프로젝트를 채운다.
 *
 * <p>시연 순서와 그때 화면에 보이는 것:
 * <ol>
 * <li>자료함 — 회의 전사문 네 개(한국어·영어·아랍어가 섞여 있다). 열면 그 회의에서 나온 할 일과 담당자, 발언이 보인다.</li>
 * <li>검토함 — 4차 회의에서 AI가 뽑았고 아직 아무도 확인하지 않은 카드 네 장.</li>
 * <li>질문 — 한국어로 물으면 한국어로, 영어로 물으면 영어로 답한 대화. 근거 발언은 원문 그대로 붙는다.</li>
 * <li>맥락 — 확정된 결정 세 가지와, 알아야 할 것(인쇄 예산 승인 전, 케이터링 미정), 할 일 네 개.
 * "내 할 일"에는 시연 계정(민지)이 아직 수락하지 않은 일이 하나 있다.</li>
 * <li>설정 — 시연 계정의 이름과 이메일.</li>
 * </ol>
 *
 * <p>{@link DevSeed}와 같은 길로 넣는다: AI 작업이 후보를 내고, 검토자가 확인한다.
 * 답변만 AI 인턴 없이 직접 써서 보관한다(시연 컴퓨터에 모델 키가 없어도 대화가 보이도록).
 * 이미 만들어져 있으면 다시 만들지 않는다.
 */
@Service
@ConditionalOnProperty(name = "contexa.dev-seed.enabled", havingValue = "true")
public class DemoSeed {

	public static final String EMAIL = "demo@contexa.app";
	public static final String PASSWORD = "contexa-demo";
	public static final String PROJECT_TITLE = "국제교류의 밤 준비";

	/** @param created 이번 호출로 새로 만들었으면 true. 이미 있었으면 false */
	public record Seeded(String email, String password, UUID projectId, boolean created) {
	}

	private final ReviewImpact review;
	private final IdentityAccess identity;
	private final DocumentEvidence document;
	private final GenerationWiki generation;
	private final JobOperation jobs;

	public DemoSeed(ReviewImpact review, IdentityAccess identity, DocumentEvidence document, GenerationWiki generation,
			JobOperation jobs) {
		this.review = review;
		this.identity = identity;
		this.document = document;
		this.generation = generation;
		this.jobs = jobs;
	}

	@Transactional
	public Seeded seed() {
		UUID userId = identity.userIdByEmail(EMAIL).orElseGet(() -> identity.signUp(EMAIL, "김민지", PASSWORD));
		var existing = identity.projectsOf(userId).stream().filter(p -> p.title().equals(PROJECT_TITLE)).findFirst();
		if (existing.isPresent()) {
			return new Seeded(EMAIL, PASSWORD, existing.get().id(), false);
		}
		UUID projectId = identity.createProject(userId, PROJECT_TITLE);
		Caller minji = Caller.user(userId, projectId);
		Caller aiJob = new Caller(Caller.Kind.JOB, UUID.randomUUID(), projectId, userId, Caller.Audience.INTERNAL);

		// ---- 1. 자료함: 회의 전사문 네 개. s11 = 1차 회의의 첫 번째 발언 ----
		Map<String, NodeRef> n = new LinkedHashMap<>();
		transcript(minji, n, "1차 회의 (10월 27일)", "s1",
				"민지", "올해 국제교류의 밤은 온라인으로 진행하는 게 어떨까요?",
				"Sara", "Online is easier to run, but the exchange students want to meet each other in person.",
				"지훈", "날짜는 일단 11월 28일 금요일로 잡아 두겠습니다.");
		transcript(minji, n, "2차 회의 (11월 3일)", "s2",
				"지훈", "교류 활동을 생각하면 대면이 낫습니다. 본관 강당을 먼저 예약해 보겠습니다.",
				"민지", "초청장과 포스터 인쇄는 예산 승인 전까지 보류입니다.",
				"Sara", "I'll take the poster design. I can have it ready by next Wednesday.",
				"지훈", "케이터링은 견적을 두 군데서 받아 보고 정하죠.");
		transcript(minji, n, "3차 회의 (11월 10일)", "s3",
				"지훈", "본관 강당 예약이 끝났습니다. 대면 개최로 확정합니다.",
				"민지", "초청장 문구는 제가 정리하겠습니다. 다음 주 화요일까지 하겠습니다.",
				"지훈", "인쇄 발주는 예산 승인이 나면 바로 넣겠습니다. 담당은 그때 정하죠.");
		transcript(minji, n, "4차 회의 (11월 17일)", "s4",
				"지훈", "강당 일정이 겹쳐서 행사를 12월 5일 금요일로 옮깁니다.",
				"Ahmed", "سأراجع النص العربي للدعوة قبل الطباعة.",
				"Ahmed", "OK, then the invitations go out on Monday after I finish the Arabic review.",
				"Sara", "For catering, I'd suggest Hanbit Catering. Their quote was the lowest.");

		// ---- 2. 1~4차 회의에서 AI가 뽑았고, 검토자가 확인한 것 ----
		review.submitCandidates(aiJob, new GraphCandidates(List.of(
				card("topic", NodeType.TOPIC, "title", "국제교류의 밤"),
				card("format", NodeType.DECISION_ITEM, "title", "개최 방식"),
				card("date", NodeType.DECISION_ITEM, "title", "행사 날짜"),
				card("place", NodeType.DECISION_ITEM, "title", "장소"),
				card("catering", NodeType.DECISION_ITEM, "title", "케이터링 업체"),
				event("online", "PROPOSAL", "온라인 개최 제안", "2026-10-27T10:00:00+09:00"),
				event("review", "DISCUSSION", "교류 활동을 위해 대면 전환 검토", "2026-11-03T10:00:00+09:00"),
				event("inPerson", "DECISION", "대면 개최", "2026-11-10T10:00:00+09:00"),
				event("nov28", "DECISION", "11월 28일 금요일", "2026-10-27T10:10:00+09:00"),
				event("dec5", "CHANGE", "12월 5일 금요일로 변경", "2026-11-17T10:00:00+09:00"),
				event("hall", "DECISION", "본관 강당", "2026-11-10T10:00:00+09:00"),
				event("quotes", "DISCUSSION", "견적을 두 군데서 받아 보고 정하기로 함", "2026-11-03T10:20:00+09:00"),
				card("budget", NodeType.CONDITION, "description", "인쇄 예산 승인"),
				card("venue", NodeType.CONDITION, "description", "강당 확보"),
				task("book", "본관 강당 예약", null),
				task("poster", "포스터 디자인", "2026-11-25T18:00:00+09:00"),
				task("copy", "초청장 문구 정리", "2026-11-24T18:00:00+09:00"),
				task("print", "인쇄 발주", null),
				person("minji", "민지", Map.of("ko", "김민지", "en", "Minji Kim")),
				person("jihoon", "지훈", Map.of("ko", "박지훈", "en", "Jihoon Park")),
				person("sara", "Sara", Map.of("en", "Sara Miller", "ko", "사라 밀러")),
				person("ahmed", "Ahmed", Map.of("en", "Ahmed Hassan", "ar", "أحمد حسن", "ko", "아흐마드 하산"))),
				List.of(
						link(n, RelType.HAS_ITEM, "topic", "format", "s11"),
						link(n, RelType.HAS_ITEM, "topic", "date", "s13"),
						link(n, RelType.HAS_ITEM, "topic", "place", "s21"),
						link(n, RelType.HAS_ITEM, "topic", "catering", "s24"),
						link(n, RelType.HAS_EVENT, "format", "online", "s11"),
						link(n, RelType.HAS_EVENT, "format", "review", "s21"),
						link(n, RelType.HAS_EVENT, "format", "inPerson", "s31"),
						link(n, RelType.HAS_EVENT, "date", "nov28", "s13"),
						link(n, RelType.HAS_EVENT, "date", "dec5", "s41"),
						link(n, RelType.SUPERSEDES, "dec5", "nov28", "s41"),
						link(n, RelType.HAS_EVENT, "place", "hall", "s31"),
						link(n, RelType.HAS_EVENT, "catering", "quotes", "s24"),
						link(n, RelType.EVIDENCED_BY, "online", "s11", "s11"),
						link(n, RelType.EVIDENCED_BY, "review", "s21", "s21"),
						link(n, RelType.EVIDENCED_BY, "inPerson", "s31", "s31"),
						link(n, RelType.EVIDENCED_BY, "nov28", "s13", "s13"),
						link(n, RelType.EVIDENCED_BY, "dec5", "s41", "s41"),
						link(n, RelType.EVIDENCED_BY, "hall", "s31", "s31"),
						link(n, RelType.EVIDENCED_BY, "quotes", "s24", "s24"),
						link(n, RelType.EVIDENCED_BY, "budget", "s22", "s22"),
						link(n, RelType.EVIDENCED_BY, "venue", "s21", "s21"),
						link(n, RelType.EVIDENCED_BY, "book", "s21", "s21"),
						link(n, RelType.EVIDENCED_BY, "poster", "s23", "s23"),
						link(n, RelType.EVIDENCED_BY, "copy", "s32", "s32"),
						link(n, RelType.EVIDENCED_BY, "print", "s33", "s33"),
						// 누가 맡았나
						link(n, RelType.ASSIGNED_TO, "book", "jihoon", "s21"),
						link(n, RelType.ASSIGNED_TO, "poster", "sara", "s23"),
						link(n, RelType.ASSIGNED_TO, "copy", "minji", "s32"),
						// 대면 개최는 강당 확보가 필요하고, 강당 확보는 "본관 강당 예약"이 끝나면 충족된다
						link(n, RelType.REQUIRES, "format", "venue", "s21"),
						link(n, RelType.SATISFIED_BY, "venue", "book", "s31"),
						// 인쇄 발주는 예산 승인이 먼저다
						link(n, RelType.REQUIRES, "print", "budget", "s22"))));

		while (true) {
			List<ReviewItem> open = review.listReviews(minji,
					new ReviewFilter(ReviewItem.State.OPEN, null, ReviewFilter.MAX_LIMIT, null)).items();
			if (open.isEmpty()) {
				break;
			}
			for (ReviewItem item : open) {
				review.applyAction(minji, item.id(), ReviewAction.ofCard(Verdict.CONFIRM), item.version(),
						"demo-" + item.id());
			}
		}
		Map<String, NodeRef> cards = currentCards(minji);
		review.changeTaskStatus(minji, cards.get("본관 강당 예약").id(), TaskStatus.DONE,
				cards.get("본관 강당 예약").revision());
		review.changeTaskStatus(minji, cards.get("포스터 디자인").id(), TaskStatus.IN_PROGRESS,
				cards.get("포스터 디자인").revision());
		// 시연 계정이 인물 카드 "민지"다. 그래야 "내 할 일"에 초청장 문구 정리가 수락 전으로 나온다
		review.linkAccount(minji, cards.get("민지").id(), userId);

		// ---- 3. 검토함: 4차 회의에서 AI가 더 뽑았고 아직 아무도 확인하지 않은 것 ----
		cards = currentCards(minji);
		review.submitCandidates(aiJob, new GraphCandidates(List.of(
				task("arabic", "초청장 아랍어 문구 검토", null),
				task("send", "초청장 발송", "2026-11-23T09:00:00+09:00"),
				event("hanbit", "PROPOSAL", "한빛케이터링으로 하자는 제안", "2026-11-17T10:20:00+09:00"),
				card("review", NodeType.CONDITION, "description", "아랍어 문구 검토 완료")),
				List.of(
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "arabic", n.get("s42"), evidence(n, "s42")),
						EdgeDraft.toExisting(RelType.ASSIGNED_TO, "arabic", cards.get("Ahmed"), evidence(n, "s42")),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "send", n.get("s43"), evidence(n, "s43")),
						EdgeDraft.toExisting(RelType.ASSIGNED_TO, "send", cards.get("Ahmed"), evidence(n, "s43")),
						EdgeDraft.between(RelType.REQUIRES, "send", "review", evidence(n, "s43")),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "review", n.get("s43"), evidence(n, "s43")),
						EdgeDraft.between(RelType.SATISFIED_BY, "review", "arabic", evidence(n, "s43")),
						EdgeDraft.fromExisting(RelType.HAS_EVENT, cards.get("케이터링 업체"), "hanbit",
								evidence(n, "s44")),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "hanbit", n.get("s44"), evidence(n, "s44")))));

		// ---- 4. 질문: 물은 언어로 답하고, 근거 발언은 원문 그대로 붙는다 ----
		answer(minji, "지금 어디까지 결정됐어?", """
				세 가지가 확정됐습니다.

				• 개최 방식: 대면 개최 [1]
				• 장소: 본관 강당 [1]
				• 날짜: 12월 5일 금요일. 처음에는 11월 28일이었는데 강당 일정이 겹쳐서 옮겼습니다 [2]

				아직 남은 것도 있습니다. 인쇄는 예산 승인 전까지 보류이고 [3], 케이터링 업체는 견적을 받아 보고 정하기로 한 상태입니다 [4].""",
				n, "s31", "s41", "s22", "s24");
		answer(minji, "인쇄 지금 진행해도 돼?", """
				아직 안 됩니다. 초청장과 포스터 인쇄는 예산 승인 전까지 보류입니다 [1]. 예산이 승인됐다는 기록은 없습니다.

				승인이 나면 바로 발주하기로 했고, 발주 담당은 아직 정해지지 않았습니다 [2].""",
				n, "s22", "s33");
		answer(minji, "Who is checking the Arabic text on the invitation?", """
				Ahmed said he will review it before printing. In his words: "سأراجع النص العربي للدعوة قبل الطباعة." [1]

				He also said the invitations go out on Monday, once that review is finished [2].

				Note that both of these are AI candidates from the 4th meeting. No reviewer has confirmed them yet.""",
				n, "s42", "s43");

		return new Seeded(EMAIL, PASSWORD, projectId, true);
	}

	/** 전사문을 올리고 발언으로 자른다. 발언 이름은 prefix에 순번을 붙인 것이다(s1 → s11, s12, …). */
	private void transcript(Caller uploader, Map<String, NodeRef> nodes, String title, String prefix,
			String... speakerAndText) {
		List<SpanDraft> drafts = new ArrayList<>();
		StringBuilder content = new StringBuilder();
		for (int i = 0; i < speakerAndText.length; i += 2) {
			drafts.add(new SpanDraft(speakerAndText[i], speakerAndText[i + 1]));
			content.append(speakerAndText[i]).append(": ").append(speakerAndText[i + 1]).append('\n');
		}
		SourceVersionRef version = document.registerDocument(uploader, new DocumentInput(title,
				DocumentKind.TRANSCRIPT, null, Visibility.PROJECT, false, content.toString().strip()));
		List<NodeRef> spans = document.saveSpans(version.sourceVersionId(), drafts);
		for (int i = 0; i < spans.size(); i++) {
			nodes.put(prefix + (i + 1), spans.get(i));
		}
	}

	/** 질문을 올리고, AI 인턴 대신 답을 써서 보관한다. 근거로는 발언만 건다(발언은 시연 중에 바뀌지 않는다). */
	private void answer(Caller asker, String question, String content, Map<String, NodeRef> nodes, String... spans) {
		GenerationRequested requested = generation.requestGeneration(asker, GenKind.ANSWER, question, null);
		List<NodeRef> citations = java.util.Arrays.stream(spans).map(nodes::get).toList();
		generation.publishArtifact(jobs.get(asker.projectId(), requested.jobId()),
				new Draft(content, List.of(), List.of(), citations));
		// 답이 이미 있으므로 AI 인턴이 이 일을 가져가지 않게 한다
		jobs.cancel(asker.projectId(), requested.jobId());
	}

	/** 처리한 묶음의 카드를 이름으로 찾을 수 있게 모은다. 모두 현재 버전이다. */
	private Map<String, NodeRef> currentCards(Caller reviewer) {
		Map<String, NodeRef> cards = new HashMap<>();
		review.listReviews(reviewer, new ReviewFilter(ReviewItem.State.DONE, null, ReviewFilter.MAX_LIMIT, null))
				.items().stream()
				.filter(item -> item.card() != null)
				.forEach(item -> cards.put(item.card().label(), item.card().ref()));
		return cards;
	}

	private static NodeDraft card(String key, NodeType type, String field, String value) {
		return new NodeDraft(key, type, Map.of(field, value));
	}

	private static NodeDraft task(String key, String title, String dueAt) {
		Map<String, Object> fields = new HashMap<>();
		fields.put("title", title);
		if (dueAt != null) {
			fields.put("dueAt", dueAt);
		}
		return new NodeDraft(key, NodeType.TASK, fields);
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

	/** 새 카드 사이의 줄, 또는 새 카드에서 이미 있는 발언으로 가는 줄. */
	private static EdgeDraft link(Map<String, NodeRef> nodes, RelType rel, String src, String dst, String span) {
		return nodes.containsKey(dst)
				? EdgeDraft.toExisting(rel, src, nodes.get(dst), evidence(nodes, span))
				: EdgeDraft.between(rel, src, dst, evidence(nodes, span));
	}
}
