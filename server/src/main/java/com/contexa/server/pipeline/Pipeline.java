package com.contexa.server.pipeline;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Caller;
import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.document.DocumentInfo;
import com.contexa.server.document.DocumentInput;
import com.contexa.server.document.DocumentNotFoundException;
import com.contexa.server.document.SourceVersionRef;
import com.contexa.server.document.Span;
import com.contexa.server.document.VersionSummary;
import com.contexa.server.generation.GenerationWiki;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.NotAllowedException;
import com.contexa.server.identity.Role;
import com.contexa.server.job.Job;
import com.contexa.server.job.JobNotFoundException;
import com.contexa.server.job.JobOperation;
import com.contexa.server.job.JobType;
import com.contexa.server.review.ReviewImpact;

/**
 * 작업 흐름 관리: 문서가 올라온 뒤의 일을 순서대로 이어 붙이고, AI 인턴이 낸 결과를 알맞은 부서로 넘긴다.
 *
 * <pre>
 *   문서 올리기 → [자르기 작업] → 발언 저장 → [뽑기 작업] → 결재 창구에 후보 제출 → 검토함
 *   질문하기   → [글쓰기 작업] → 문서 작성이 검사하고 보관 → 요청한 사람이 읽음
 * </pre>
 *
 * 자르기와 뽑기를 나눈 이유는, 발언에 번호가 먼저 붙어야 "이 카드의 근거는 이 발언"이라고 적을 수 있기 때문이다.
 * 결과를 받아 넘기는 일은 한 묶음(트랜잭션)으로 한다. 넘기다 실패하면 결과를 받지 않은 것으로 되돌아가고,
 * 일꾼은 같은 임대증으로 고친 결과를 다시 낼 수 있다.
 */
@Service
public class Pipeline {

	private final JobOperation jobs;
	private final DocumentEvidence document;
	private final ReviewImpact review;
	private final IdentityAccess identity;
	private final GenerationWiki generation;

	public Pipeline(JobOperation jobs, DocumentEvidence document, ReviewImpact review, IdentityAccess identity,
			GenerationWiki generation) {
		this.generation = generation;
		this.jobs = jobs;
		this.document = document;
		this.review = review;
		this.identity = identity;
	}

	/** 문서를 올리고 "자르기" 작업을 맡긴다. 프로젝트 구성원인 사람만 할 수 있다. */
	@Transactional
	public IngestStarted startIngest(Caller caller, DocumentInput input) {
		SourceVersionRef version = document.registerDocument(caller, input);
		return new IngestStarted(version, enqueueParse(caller, version));
	}

	/** 이미 있는 문서의 새 판을 올리고 "자르기" 작업을 맡긴다. */
	@Transactional
	public IngestStarted startIngestOfNewVersion(Caller caller, UUID documentId, String content) {
		SourceVersionRef version = document.addVersion(caller, documentId, content);
		return new IngestStarted(version, enqueueParse(caller, version));
	}

	/**
	 * AI 인턴이 낸 결과를 받아 알맞은 부서로 넘긴다.
	 * 자르기 결과는 자료실에 저장하고 뽑기 작업을 맡긴다. 뽑기 결과는 결재 창구에 후보로 제출한다
	 * (작업을 시킨 사용자의 권한으로). AI 인턴은 여기까지만 할 수 있고, 확인은 사람이 검토함에서 한다.
	 * 글쓰기 결과는 문서 작성에 넘긴다. 참고한 카드가 그사이 바뀌었으면 받지 않는다.
	 *
	 * @throws com.contexa.server.job.LeaseRejectedException 임대증이 맞지 않음
	 * @throws IllegalArgumentException                      결과의 내용이 받아들일 수 없는 것임. 고쳐서 다시 낼 수 있다
	 */
	@Transactional
	public Job handleResult(UUID jobId, UUID attemptId, String leaseToken, JobResult result) {
		Job job = jobs.requireLease(jobId, attemptId, leaseToken);
		if (result == null) {
			throw new IllegalArgumentException("결과가 비어 있다");
		}
		switch (job.type()) {
			case PARSE -> {
				if (result.spans() == null || result.spans().isEmpty()) {
					throw new IllegalArgumentException("자르기 결과에 발언이 하나도 없다");
				}
				document.saveSpans(job.subjectId(), result.spans());
				jobs.enqueue(JobType.EXTRACT, job.projectId(), job.subjectId(), null, job.requestedBy(),
						"extract:" + job.subjectId());
			}
			case EXTRACT -> {
				if (result.candidates() == null) {
					throw new IllegalArgumentException("뽑기 결과에 후보 목록이 없다");
				}
				review.submitCandidates(identity.callerForJob(job.id(), job.projectId(), job.requestedBy()),
						result.candidates());
			}
			case GENERATE -> {
				if (result.draft() == null) {
					throw new IllegalArgumentException("글쓰기 결과에 글이 없다");
				}
				generation.publishArtifact(job, result.draft());
			}
		}
		return jobs.acceptResult(jobId, attemptId, leaseToken);
	}

	/** 작업 하나의 진행 상태. 볼 수 없는 작업은 없는 작업과 똑같이 거절한다. */
	@Transactional(readOnly = true)
	public Job getJob(Caller caller, UUID jobId) {
		Job job = jobs.get(caller.projectId(), jobId);
		if (!visibleTo(caller, List.of(job)).test(job)) {
			throw new JobNotFoundException(jobId);
		}
		return job;
	}

	/**
	 * 실패로 끝난 작업을 다시 맡긴다. 시도 횟수는 처음부터 다시 센다.
	 * 볼 수 없는 작업은 없는 작업과 똑같이 거절하고, 볼 수 있어도 그 작업을 시킨 사람이나 프로젝트 OWNER만 다시 맡긴다.
	 *
	 * @throws JobNotFoundException  없거나 볼 수 없는 작업
	 * @throws NotAllowedException   사람이 아니거나, 시킨 사람도 OWNER도 아니다
	 * @throws IllegalStateException 실패로 끝난 작업이 아니다
	 */
	@Transactional
	public Job retryJob(Caller caller, UUID jobId) {
		Job job = getJob(caller, jobId);
		if (caller.kind() != Caller.Kind.USER) {
			throw new NotAllowedException("작업은 사람만 다시 맡길 수 있다");
		}
		boolean owner = identity.roleOf(caller.projectId(), caller.id()).filter(Role.OWNER::equals).isPresent();
		if (!owner && !job.requestedBy().equals(caller.id())) {
			throw new NotAllowedException("작업을 시킨 사람이나 프로젝트 OWNER만 다시 맡길 수 있다");
		}
		return jobs.retry(caller.projectId(), jobId);
	}

	/** 이 프로젝트에서 요청자가 볼 수 있는 작업. 최근 것부터 50개. */
	@Transactional(readOnly = true)
	public List<Job> listJobs(Caller caller) {
		List<Job> recent = jobs.list(caller.projectId(), 200);
		return recent.stream().filter(visibleTo(caller, recent)).limit(50).toList();
	}

	/** 요청자가 읽을 수 있는 문서 목록. 최근에 올린 것부터, 가장 최근 판의 처리 상태와 함께. */
	@Transactional(readOnly = true)
	public List<DocumentStatus> listDocuments(Caller caller) {
		List<DocumentInfo> documents = document.listReadable(caller);
		Map<UUID, List<VersionStatus>> versions = versionStatuses(caller.projectId(),
				documents.stream().map(DocumentInfo::id).toList());
		return documents.stream().map(info -> {
			List<VersionStatus> ofDocument = versions.getOrDefault(info.id(), List.of());
			return new DocumentStatus(info, ofDocument.size(),
					ofDocument.isEmpty() ? null : ofDocument.get(ofDocument.size() - 1));
		}).toList();
	}

	/**
	 * 문서 한 건을 연다. 판을 고르지 않으면 가장 최근 판을 연다.
	 * 근거 발언(spanId)을 주면 그 발언이 들어 있는 판을 연다. 근거 정보에는 문서만 적혀 있고 판은 적혀 있지 않기 때문이다.
	 *
	 * @param versionNo 열 판 번호. null이면 가장 최근 판
	 * @param spanId    이 발언이 들어 있는 판을 연다. versionNo와 함께 줄 수 없다
	 * @throws DocumentNotFoundException 문서·판·발언이 없거나 읽을 수 없음. 구분하지 않는다
	 */
	@Transactional(readOnly = true)
	public DocumentView openDocument(Caller caller, UUID documentId, Integer versionNo, UUID spanId) {
		if (versionNo != null && spanId != null) {
			throw new IllegalArgumentException("판 번호와 발언 중 하나만 줄 수 있다");
		}
		DocumentInfo info = document.readable(caller, documentId);
		List<VersionStatus> versions = versionStatuses(caller.projectId(), List.of(documentId))
				.getOrDefault(documentId, List.of());
		Integer wanted = versionNo;
		if (spanId != null) {
			wanted = document.versionOfSpan(caller.projectId(), spanId)
					.filter(ref -> ref.documentId().equals(documentId))
					.map(SourceVersionRef::versionNo)
					.orElseThrow(() -> new DocumentNotFoundException("발언", spanId));
		}
		Integer target = wanted;
		VersionStatus opened = versions.stream()
				.filter(v -> target == null ? v == versions.get(versions.size() - 1) : v.versionNo() == target)
				.findFirst()
				.orElseThrow(() -> new DocumentNotFoundException("문서의 판", documentId));
		List<Span> spans = document.spansOf(opened.sourceVersionId());
		String content = spans.isEmpty() ? document.readContent(opened.sourceVersionId()) : null;
		return new DocumentView(info, versions,
				new DocumentView.OpenedVersion(opened.sourceVersionId(), opened.versionNo(), spans, content));
	}

	/** 문서마다 판 목록(1판부터)에 자르기·뽑기 작업의 상태를 붙인다. 권한 검사는 호출한 쪽이 한다. */
	private Map<UUID, List<VersionStatus>> versionStatuses(UUID projectId, Collection<UUID> documentIds) {
		Map<UUID, List<VersionSummary>> versions = document.versionsOf(projectId, documentIds);
		List<UUID> versionIds = versions.values().stream().flatMap(List::stream)
				.map(VersionSummary::sourceVersionId).toList();
		Map<UUID, Job> parse = new HashMap<>();
		Map<UUID, Job> extract = new HashMap<>();
		for (Job job : jobs.bySubjects(projectId, versionIds)) {
			switch (job.type()) {
				case PARSE -> parse.put(job.subjectId(), job);
				case EXTRACT -> extract.put(job.subjectId(), job);
				case GENERATE -> {
				}
			}
		}
		Map<UUID, List<VersionStatus>> statuses = new HashMap<>();
		versions.forEach((documentId, list) -> statuses.put(documentId, list.stream()
				.map(v -> new VersionStatus(v.sourceVersionId(), v.versionNo(), v.createdAt(), v.spanCount(),
						JobBrief.of(parse.get(v.sourceVersionId())), JobBrief.of(extract.get(v.sourceVersionId()))))
				.toList()));
		return statuses;
	}

	/**
	 * 요청자가 볼 수 있는 작업인가. 문서를 자르고 뽑는 작업은 그 문서를 읽을 수 있어야 보이고(남의 개인 메모는
	 * 안 보인다), 글쓰기 작업은 요청한 사람에게만 보인다(답변도 요청한 사람만 읽는다).
	 */
	private Predicate<Job> visibleTo(Caller caller, Collection<Job> candidates) {
		Set<UUID> readable = identity.readableDocumentIds(caller);
		Map<UUID, UUID> documentOf = document.documentsOfVersions(caller.projectId(), candidates.stream()
				.filter(job -> job.type() != JobType.GENERATE).map(Job::subjectId).toList());
		UUID user = caller.kind() == Caller.Kind.AGENT ? null : caller.actingUser();
		return job -> job.type() == JobType.GENERATE
				? job.requestedBy().equals(user)
				: readable.contains(documentOf.get(job.subjectId()));
	}

	private UUID enqueueParse(Caller caller, SourceVersionRef version) {
		if (caller.kind() != Caller.Kind.USER) {
			throw new NotAllowedException("문서는 사람만 올릴 수 있다");
		}
		return jobs.enqueue(JobType.PARSE, caller.projectId(), version.sourceVersionId(), null, caller.id(),
				"parse:" + version.sourceVersionId());
	}
}
