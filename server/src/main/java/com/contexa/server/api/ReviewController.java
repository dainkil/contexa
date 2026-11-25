package com.contexa.server.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.contexa.server.contract.Caller;
import com.contexa.server.contract.NodeKey;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.decision.TaskStatus;
import com.contexa.server.generation.GenerationWiki;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.review.Changed;
import com.contexa.server.review.Impact;
import com.contexa.server.review.RecheckPage;
import com.contexa.server.review.RecheckResolved;
import com.contexa.server.review.ReviewAction;
import com.contexa.server.review.ReviewFilter;
import com.contexa.server.review.ReviewImpact;
import com.contexa.server.review.ReviewItem;
import com.contexa.server.review.ReviewPage;
import com.contexa.server.review.ReviewResult;
import com.contexa.server.review.Verdict;

/**
 * 정문: 결정 정보를 바꾸는 HTTP 주소. 요청을 결재 창구의 함수 호출로 옮기기만 한다.
 * 요청자({@link Caller})는 {@link CallerResolver}가 토큰에서 만든다.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class ReviewController {

	private final ReviewImpact review;
	private final IdentityAccess identity;
	private final GenerationWiki generation;

	public ReviewController(ReviewImpact review, IdentityAccess identity, GenerationWiki generation) {
		this.review = review;
		this.identity = identity;
		this.generation = generation;
	}

	/**
	 * 검토함 한 쪽. state를 생략하면 확인할 것이 남은 묶음만 나온다.
	 * 다음 쪽은 응답의 next를 after로 보내 부른다.
	 */
	@GetMapping("/reviews")
	public ReviewPage reviews(Caller caller,
			@RequestParam(defaultValue = "OPEN") ReviewItem.State state,
			@RequestParam(required = false) NodeType ownerType,
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) String after) {
		return review.listReviews(caller, new ReviewFilter(state, ownerType, limit, after));
	}

	@GetMapping("/reviews/{reviewId}")
	public ReviewItem review(Caller caller, @PathVariable UUID reviewId) {
		return review.getReview(caller, reviewId);
	}

	/** 묶음을 확인·보류·거절·철회·정정한다. */
	@PostMapping("/reviews/{reviewId}/actions")
	public ReviewResult act(Caller caller, @PathVariable UUID reviewId,
			@RequestBody ActionRequest request) {
		ReviewAction action = new ReviewAction(request.card(), request.corrections(), request.edges(),
				request.edgeEvidence());
		ReviewResult result = review.applyAction(caller, reviewId, action, request.expectedVersion(),
				request.idempotencyKey());
		return new ReviewResult(result.item(), mine(caller, result.impacts()));
	}

	@PostMapping("/tasks/{taskId}/status")
	public Changed taskStatus(Caller caller, @PathVariable UUID taskId,
			@RequestBody TaskStatusRequest request) {
		return mine(caller, review.changeTaskStatus(caller, taskId, request.status(), request.expectedRevision()));
	}

	@PostMapping("/conditions/{conditionId}/satisfied")
	public Changed satisfied(Caller caller, @PathVariable UUID conditionId,
			@RequestBody SatisfiedRequest request) {
		return mine(caller, review.satisfyCondition(caller, conditionId, request.by(), request.evidence()));
	}

	/** 담당자 본인이 업무를 수락하거나 거절한다. */
	@PostMapping("/tasks/{taskId}/acceptance")
	public Changed acceptance(Caller caller, @PathVariable UUID taskId, @RequestBody AcceptanceRequest request) {
		return mine(caller, review.acceptTask(caller, taskId, request.accepted()));
	}

	/** 이 인물·기관을 이 언어로는 이렇게 적는다고 확인하거나(confirmed=true), 틀린 표기라고 거절한다. */
	@PostMapping("/entities/{entityId}/names")
	public Changed name(Caller caller, @PathVariable UUID entityId, @RequestBody NameRequest request) {
		return mine(caller, request.confirmed()
				? review.confirmName(caller, entityId, request.lang(), request.form(), request.evidenceSpanId())
				: review.rejectName(caller, entityId, request.lang(), request.form()));
	}

	/** 이 인물이 이 이메일의 계정이라고 확인한다. email이 없으면 연결을 끊는다. */
	@PostMapping("/entities/{entityId}/account")
	public Changed account(Caller caller, @PathVariable UUID entityId, @RequestBody AccountRequest request) {
		UUID accountId = request.email() == null ? null : identity.userIdByEmail(request.email())
				.orElseThrow(() -> new IllegalArgumentException("가입하지 않은 이메일이다"));
		return mine(caller, review.linkAccount(caller, entityId, accountId));
	}

	/** 발언 내용을 정정한다(전사 오류 등). */
	@PostMapping("/spans/{spanId}/revisions")
	public Changed reviseSpan(Caller caller, @PathVariable UUID spanId, @RequestBody ReviseSpanRequest request) {
		return mine(caller, review.reviseSpan(caller, spanId, request.expectedRevision(), request.text()));
	}

	/** 검토함 "다시 확인": 기대는 것이 바뀌어 다시 봐야 하는 카드와 그 이유. 다음 쪽은 next를 after로 보낸다. */
	@GetMapping("/rechecks")
	public RecheckPage rechecks(Caller caller, @RequestParam(required = false) Integer limit,
			@RequestParam(required = false) String after) {
		return review.listRechecks(caller, limit, after);
	}

	/** 다시 봤고 바뀐 것이 없다고 닫는다. 고치거나 철회하려면 그 카드의 검토 묶음을 처리한다. */
	@PostMapping("/rechecks/{type}/{id}/resolve")
	public RecheckResolved resolveRecheck(Caller caller, @PathVariable NodeType type, @PathVariable UUID id) {
		return review.resolveRecheck(caller, new NodeKey(type, id));
	}

	/** 이 카드가 바뀌면 다시 확인해야 하는 카드들. 요청한 사람이 볼 수 있는 것만 나온다. */
	@GetMapping("/impacts")
	public List<Impact> impacts(Caller caller, @RequestParam NodeType type, @RequestParam UUID id) {
		return mine(caller, review.listImpacts(caller, new NodeRef(type, id, 1)));
	}

	private Changed mine(Caller caller, Changed changed) {
		return new Changed(changed.node(), mine(caller, changed.impacts()));
	}

	/** 영향 목록의 산출물(답변·브리핑) 가운데 남이 요청한 것을 뺀다. 카드·발언은 검토 창구가 이미 걸렀다. */
	private List<Impact> mine(Caller caller, List<Impact> impacts) {
		List<UUID> artifacts = impacts.stream().filter(impact -> impact.affected().type() == NodeType.ARTIFACT)
				.map(impact -> impact.affected().id()).toList();
		if (artifacts.isEmpty()) {
			return impacts;
		}
		Set<UUID> own = generation.ownArtifactIds(caller, artifacts);
		return impacts.stream().filter(impact -> impact.affected().type() != NodeType.ARTIFACT
				|| own.contains(impact.affected().id())).toList();
	}

	public record ActionRequest(Verdict card, Map<String, Object> corrections, Map<UUID, Verdict> edges,
			Map<UUID, List<UUID>> edgeEvidence, long expectedVersion, String idempotencyKey) {
	}

	public record TaskStatusRequest(TaskStatus status, long expectedRevision) {
	}

	public record SatisfiedRequest(NodeRef by, List<UUID> evidence) {
	}

	public record AcceptanceRequest(boolean accepted) {
	}

	public record NameRequest(String lang, String form, boolean confirmed, UUID evidenceSpanId) {
	}

	public record AccountRequest(String email) {
	}

	public record ReviseSpanRequest(long expectedRevision, String text) {
	}
}
