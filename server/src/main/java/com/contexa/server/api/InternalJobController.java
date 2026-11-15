package com.contexa.server.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.contexa.server.contract.Caller;
import com.contexa.server.contract.ContextPacket;
import com.contexa.server.contract.Direction;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.Ontology;
import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.document.Span;
import com.contexa.server.document.VersionInfo;
import com.contexa.server.gateway.ContextGateway;
import com.contexa.server.generation.GenerationRequest;
import com.contexa.server.generation.GenerationWiki;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.UnauthenticatedException;
import com.contexa.server.job.Job;
import com.contexa.server.job.JobLease;
import com.contexa.server.job.JobOperation;
import com.contexa.server.pipeline.JobResult;
import com.contexa.server.pipeline.Pipeline;

/**
 * 뒷문: AI 인턴(Python 일꾼)만 쓰는 주소. 일을 가져가고, 일하는 데 필요한 자료를 읽고, 결과를 낸다.
 *
 * <ul>
 * <li>모든 요청은 일꾼 토큰(X-Worker-Token)이 있어야 한다. 사람의 로그인 토큰으로는 들어올 수 없다.</li>
 * <li>일을 가져간 뒤의 요청은 임대증(X-Attempt-Id, X-Lease-Token)도 내야 한다. 임대증이 가리키는 작업의 자료만 읽는다.</li>
 * <li>일꾼이 보낸 사용자 id는 믿지 않는다. 누구의 권한으로 읽을지는 서버가 작업 기록에서 정한다.</li>
 * <li>AI 인턴은 데이터베이스에 접속하지 않고, 결정 정보는 안내 데스크가 돌려주는 답변서로만 읽는다.</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/v1/jobs")
public class InternalJobController {

	private final JobOperation jobs;
	private final Pipeline pipeline;
	private final DocumentEvidence document;
	private final ContextGateway gateway;
	private final IdentityAccess identity;
	private final GenerationWiki generation;
	private final String workerToken;

	public InternalJobController(JobOperation jobs, Pipeline pipeline, DocumentEvidence document,
			ContextGateway gateway, IdentityAccess identity, GenerationWiki generation,
			@Value("${contexa.worker.token}") String workerToken) {
		this.generation = generation;
		this.jobs = jobs;
		this.pipeline = pipeline;
		this.document = document;
		this.gateway = gateway;
		this.identity = identity;
		this.workerToken = workerToken;
	}

	/** 결정 온톨로지: 버전과, 후보로 낼 수 있는 줄의 허용 조합. 시스템이 기록하는 줄은 뺀다. */
	@GetMapping("/ontology")
	public OntologyView ontology(@RequestHeader(value = "X-Worker-Token", required = false) String token) {
		requireWorker(token);
		return new OntologyView(Ontology.VERSION,
				Ontology.rules().stream().filter(rule -> !rule.rel().isSystemRecorded()).toList());
	}

	/** 가장 오래 기다린 일을 가져간다. 없으면 204. */
	@PostMapping("/claim")
	public ResponseEntity<JobLease> claim(@RequestHeader(value = "X-Worker-Token", required = false) String token,
			@RequestBody ClaimRequest request) {
		requireWorker(token);
		return jobs.claim(request.workerId()).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
	}

	/** 자르기 작업: 자를 문서의 제목, 종류, 내용. */
	@GetMapping("/{jobId}/content")
	public Content content(@RequestHeader(value = "X-Worker-Token", required = false) String token,
			@RequestHeader("X-Attempt-Id") UUID attemptId, @RequestHeader("X-Lease-Token") String leaseToken,
			@PathVariable UUID jobId) {
		requireWorker(token);
		Job job = jobs.requireLease(jobId, attemptId, leaseToken);
		return new Content(document.versionInfo(job.subjectId()), document.readContent(job.subjectId()));
	}

	/** 뽑기 작업: 이 문서 판의 발언들, 순서대로. */
	@GetMapping("/{jobId}/spans")
	public List<Span> spans(@RequestHeader(value = "X-Worker-Token", required = false) String token,
			@RequestHeader("X-Attempt-Id") UUID attemptId, @RequestHeader("X-Lease-Token") String leaseToken,
			@PathVariable UUID jobId) {
		requireWorker(token);
		Job job = jobs.requireLease(jobId, attemptId, leaseToken);
		return document.spansOf(job.subjectId());
	}

	/**
	 * 뽑기 작업: 이 프로젝트에 이미 있는 안건·결정·할 일·인물. 같은 카드를 또 만들지 않고 거기에 잇기 위해 읽는다.
	 * 작업을 시킨 사용자가 볼 수 있는 만큼만 나온다.
	 */
	@GetMapping("/{jobId}/context/state")
	public ContextPacket state(@RequestHeader(value = "X-Worker-Token", required = false) String token,
			@RequestHeader("X-Attempt-Id") UUID attemptId, @RequestHeader("X-Lease-Token") String leaseToken,
			@PathVariable UUID jobId) {
		requireWorker(token);
		Job job = jobs.requireLease(jobId, attemptId, leaseToken);
		Caller caller = identity.callerForJob(job.id(), job.projectId(), job.requestedBy());
		return gateway.currentState(caller, null);
	}

	/** 글쓰기 작업: 무엇에 대해, 어떤 종류의 글을, 어느 언어로 쓸지. */
	@GetMapping("/{jobId}/request")
	public GenerationRequest request(@RequestHeader(value = "X-Worker-Token", required = false) String token,
			@RequestHeader("X-Attempt-Id") UUID attemptId, @RequestHeader("X-Lease-Token") String leaseToken,
			@PathVariable UUID jobId) {
		requireWorker(token);
		return generation.requestOf(jobs.requireLease(jobId, attemptId, leaseToken));
	}

	/** 글쓰기 작업: 질문과 관련된 발언과, 그 발언에 기댄 카드. 작업을 시킨 사용자가 읽을 수 있는 문서에서만 찾는다. */
	@PostMapping("/{jobId}/context/search")
	public ContextPacket search(@RequestHeader(value = "X-Worker-Token", required = false) String token,
			@RequestHeader("X-Attempt-Id") UUID attemptId, @RequestHeader("X-Lease-Token") String leaseToken,
			@PathVariable UUID jobId, @RequestBody SearchRequest request) {
		requireWorker(token);
		Job job = jobs.requireLease(jobId, attemptId, leaseToken);
		return gateway.searchEvidence(identity.callerForJob(job.id(), job.projectId(), job.requestedBy()),
				request.query(), request.k() == null ? 8 : request.k());
	}

	/** 글쓰기 작업: 한 카드에서 근거 쪽이나 영향 쪽으로 줄을 따라간다. */
	@GetMapping("/{jobId}/context/trace")
	public ContextPacket trace(@RequestHeader(value = "X-Worker-Token", required = false) String token,
			@RequestHeader("X-Attempt-Id") UUID attemptId, @RequestHeader("X-Lease-Token") String leaseToken,
			@PathVariable UUID jobId, @RequestParam NodeType type, @RequestParam UUID id,
			@RequestParam(defaultValue = "BASIS") Direction direction, @RequestParam(defaultValue = "2") int depth) {
		requireWorker(token);
		Job job = jobs.requireLease(jobId, attemptId, leaseToken);
		return gateway.trace(identity.callerForJob(job.id(), job.projectId(), job.requestedBy()),
				new NodeRef(type, id, 1), direction, depth);
	}

	/** 결과를 낸다. 내용이 받아들일 수 없는 것이면 400과 이유가 돌아오고, 같은 임대증으로 고쳐서 다시 낼 수 있다. */
	@PostMapping("/{jobId}/result")
	public Job result(@RequestHeader(value = "X-Worker-Token", required = false) String token,
			@RequestHeader("X-Attempt-Id") UUID attemptId, @RequestHeader("X-Lease-Token") String leaseToken,
			@PathVariable UUID jobId, @RequestBody JobResult result) {
		requireWorker(token);
		return pipeline.handleResult(jobId, attemptId, leaseToken, result);
	}

	/** 실패를 알린다. retryable이 참이고 시도 횟수가 남았으면 다시 줄을 선다. */
	@PostMapping("/{jobId}/failure")
	public Job failure(@RequestHeader(value = "X-Worker-Token", required = false) String token,
			@RequestHeader("X-Attempt-Id") UUID attemptId, @RequestHeader("X-Lease-Token") String leaseToken,
			@PathVariable UUID jobId, @RequestBody FailureRequest request) {
		requireWorker(token);
		return jobs.fail(jobId, attemptId, leaseToken, request.reason(), request.retryable());
	}

	public record OntologyView(String version, List<Ontology.Allowed> rules) {
	}

	public record SearchRequest(String query, Integer k) {
	}

	public record ClaimRequest(String workerId) {
	}

	public record Content(VersionInfo document, String content) {
	}

	public record FailureRequest(String reason, boolean retryable) {
	}

	private void requireWorker(String token) {
		boolean ok = token != null && !workerToken.isBlank() && MessageDigest.isEqual(
				token.getBytes(StandardCharsets.UTF_8), workerToken.getBytes(StandardCharsets.UTF_8));
		if (!ok) {
			throw new UnauthenticatedException("일꾼 토큰이 올바르지 않다");
		}
	}
}
