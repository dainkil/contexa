package com.contexa.server.generation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Caller;
import com.contexa.server.contract.Edge;
import com.contexa.server.contract.EdgeRef;
import com.contexa.server.contract.NodeKey;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.Ontology;
import com.contexa.server.contract.RelType;
import com.contexa.server.contract.ReviewStatus;
import com.contexa.server.contract.Source;
import com.contexa.server.contract.StaleRef;
import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.document.Span;
import com.contexa.server.gateway.ContextGateway;
import com.contexa.server.graph.DecisionGraph;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.NotAllowedException;
import com.contexa.server.job.AiQuota;
import com.contexa.server.job.Job;
import com.contexa.server.job.JobOperation;
import com.contexa.server.job.JobStatus;
import com.contexa.server.job.JobType;

/**
 * 문서 작성: 질문에 대한 답변·브리핑·초안을 AI 인턴에게 맡기고, 써 온 글을 검사해 보관한다.
 *
 * <ul>
 * <li>글은 요청한 사람의 권한으로 읽은 내용으로만 쓰인다. 그래서 요청한 사람만 그 글을 읽는다.</li>
 * <li>써 온 글을 저장하기 직전에, 글이 참고한 카드·발언·줄이 그사이 바뀌지 않았는지 안내 데스크에 확인한다.
 * 바뀌었으면 저장하지 않는다. 낡은 내용으로 쓴 답을 내보내지 않기 위해서다.</li>
 * <li>본문의 [1], [2] 가 가리키는 발언은 실제로 있고 요청한 사람이 읽을 수 있는 것이어야 한다.
 * 없는 인용이 달린 글은 저장하지 않는다.</li>
 * <li>저장하면서 "이 글은 이 카드를 참고했다"(USES) 줄을 게시판에 기록한다. 나중에 그 카드가 바뀌면
 * 결재 창구의 영향 목록에 이 글이 나온다.</li>
 * <li>글을 열 때마다 참고한 것이 바뀌었는지 다시 확인해서 "다시 확인 필요" 여부를 알려준다.</li>
 * </ul>
 * 글 자체는 결정 정보를 확정하지 않는다. 글에 적혔다고 사실이 되는 것은 아니다.
 */
@Service
public class GenerationWiki {

	private static final Logger log = LoggerFactory.getLogger(GenerationWiki.class);

	private static final Set<String> LANGS = Set.of("ko", "en", "ar");
	private static final int MAX_QUESTION = 2000;

	private record Row(UUID id, UUID projectId, GenKind kind, String question, String lang, String content,
			UUID jobId, UUID requestedBy, Instant createdAt, Instant publishedAt) {
	}

	private final JdbcClient jdbc;
	private final JobOperation jobs;
	private final ContextGateway gateway;
	private final DecisionGraph graph;
	private final IdentityAccess identity;
	private final DocumentEvidence document;
	private final AiQuota quota;

	public GenerationWiki(JdbcClient jdbc, JobOperation jobs, ContextGateway gateway, DecisionGraph graph,
			IdentityAccess identity, DocumentEvidence document, AiQuota quota) {
		this.jdbc = jdbc;
		this.jobs = jobs;
		this.gateway = gateway;
		this.graph = graph;
		this.identity = identity;
		this.document = document;
		this.quota = quota;
	}

	/**
	 * 글을 요청한다. 프로젝트 구성원인 사람만 할 수 있다. 바로 돌아오고, 글은 AI 인턴이 쓴 뒤에 생긴다.
	 *
	 * @param lang 글의 언어(ko, en, ar). null이면 AI 인턴이 질문을 쓴 언어로 답한다
	 */
	@Transactional
	public GenerationRequested requestGeneration(Caller caller, GenKind kind, String question, String lang) {
		Objects.requireNonNull(caller, "caller");
		Objects.requireNonNull(kind, "kind");
		if (!identity.isMember(caller)) {
			throw new NotAllowedException("글은 프로젝트 구성원만 요청할 수 있다");
		}
		if (question == null || question.isBlank() || question.length() > MAX_QUESTION) {
			throw new IllegalArgumentException("질문은 1~" + MAX_QUESTION + "자여야 한다");
		}
		String language = lang;
		if (language != null && !LANGS.contains(language)) {
			throw new IllegalArgumentException("언어는 ko, en, ar 중 하나여야 한다: " + lang);
		}
		quota.check(caller.id());
		UUID artifactId = UUID.randomUUID();
		jdbc.sql("""
				insert into artifact (id, project_id, kind, question, lang, requested_by, created_at)
				values (:id, :projectId, :kind, :question, :lang, :userId, clock_timestamp())
				""")
				.param("id", artifactId).param("projectId", caller.projectId()).param("kind", kind.name())
				.param("question", question.strip()).param("lang", language).param("userId", caller.id()).update();
		UUID jobId = jobs.enqueue(JobType.GENERATE, caller.projectId(), artifactId, null, caller.id(),
				"generate:" + artifactId);
		jdbc.sql("update artifact set job_id = :jobId where id = :id").param("jobId", jobId).param("id", artifactId)
				.update();
		return new GenerationRequested(artifactId, jobId);
	}

	/** 글쓰기 작업의 주문서. AI 인턴이 일을 가져간 뒤에 읽는다. */
	@Transactional(readOnly = true)
	public GenerationRequest requestOf(Job job) {
		Row row = find(job.projectId(), job.subjectId(), false);
		return new GenerationRequest(row.id(), row.kind(), row.question(), row.lang());
	}

	/**
	 * AI 인턴이 써 온 글을 검사하고 보관한다. 작업 흐름 관리만 호출한다.
	 *
	 * @throws StaleContextException    참고한 것이 그사이 바뀜. 저장하지 않는다
	 * @throws IllegalArgumentException 본문이 비었거나, 인용한 발언이 없거나 읽을 수 없음
	 */
	@Transactional
	public NodeRef publishArtifact(Job job, Draft draft) {
		Objects.requireNonNull(draft, "draft");
		Row row = find(job.projectId(), job.subjectId(), true);
		if (row.content() != null) {
			throw new IllegalStateException("이미 완성된 산출물이다");
		}
		if (draft.content() == null || draft.content().isBlank()) {
			throw new IllegalArgumentException("글의 본문이 비어 있다");
		}
		requireGrounded(draft);
		Caller caller = identity.callerForJob(job.id(), job.projectId(), job.requestedBy());
		requireReadableCitations(caller, draft.citations());

		// 참고한 카드와 발언(인용 포함)을 한 번씩만 센다
		Map<NodeKey, NodeRef> used = new java.util.LinkedHashMap<>();
		draft.usedNodes().forEach(ref -> used.putIfAbsent(ref.key(), ref));
		draft.citations().forEach(ref -> used.putIfAbsent(ref.key(), ref));
		List<StaleRef> stale = gateway.verifyCurrent(caller, new ArrayList<>(used.values()), draft.usedEdges());
		if (!stale.isEmpty()) {
			throw new StaleContextException(stale.size());
		}

		jdbc.sql("update artifact set content = :content, published_at = now() where id = :id")
				.param("content", draft.content().strip()).param("id", row.id()).update();
		int position = 0;
		for (NodeRef ref : used.values()) {
			saveRef(row.id(), "NODE", position++, ref.type().name(), ref.id(), ref.revision());
		}
		position = 0;
		for (EdgeRef ref : new LinkedHashSet<>(draft.usedEdges())) {
			saveRef(row.id(), "EDGE", position++, null, ref.id(), ref.version());
		}
		position = 1;
		for (NodeRef ref : draft.citations()) {
			saveRef(row.id(), "CITATION", position++, ref.type().name(), ref.id(), ref.revision());
		}

		NodeRef artifact = new NodeRef(NodeType.ARTIFACT, row.id(), 1);
		for (NodeRef ref : used.values()) {
			if (Ontology.isAllowed(RelType.USES, NodeType.ARTIFACT, ref.type())) {
				graph.recordSystemEdge(new Edge(UUID.randomUUID(), row.projectId(), RelType.USES, artifact, ref,
						ReviewStatus.CONFIRMED, List.of(), null, Instant.now(), Ontology.VERSION, 1));
			}
		}
		return artifact;
	}

	/**
	 * 산출물을 연다. 요청한 사람만 열 수 있다.
	 * 열 때마다 참고한 카드·발언·줄이 바뀌었는지 확인해서 stale에 담는다.
	 */
	@Transactional(readOnly = true)
	public ArtifactView getArtifact(Caller caller, UUID artifactId) {
		Row row = find(caller.projectId(), artifactId, false);
		if (caller.kind() != Caller.Kind.USER || !row.requestedBy().equals(caller.id())) {
			throw new ArtifactNotFoundException(artifactId);
		}
		List<NodeRef> nodes = new ArrayList<>();
		List<EdgeRef> edges = new ArrayList<>();
		List<NodeRef> citations = new ArrayList<>();
		jdbc.sql("select kind, ref_type, ref_id, seen_version from artifact_ref where artifact_id = :id"
				+ " order by kind, position")
				.param("id", artifactId)
				.query((rs, n) -> {
					String kind = rs.getString("kind");
					UUID id = rs.getObject("ref_id", UUID.class);
					long version = rs.getLong("seen_version");
					if (kind.equals("EDGE")) {
						edges.add(new EdgeRef(id, version));
					} else {
						NodeRef ref = new NodeRef(NodeType.valueOf(rs.getString("ref_type")), id, version);
						(kind.equals("NODE") ? nodes : citations).add(ref);
					}
					return kind;
				})
				.list();

		ArtifactView.Status status = ArtifactView.Status.PUBLISHED;
		String failure = null;
		if (row.content() == null) {
			Job job = jobs.get(row.projectId(), row.jobId());
			boolean ended = job.status() == JobStatus.FAILED || job.status() == JobStatus.CANCELLED;
			status = ended ? ArtifactView.Status.FAILED : ArtifactView.Status.PENDING;
			failure = ended ? job.lastError() : null;
		}
		List<StaleRef> stale = row.content() == null ? List.of() : gateway.verifyCurrent(caller, nodes, edges);
		return new ArtifactView(row.id(), row.kind(), row.question(), row.lang(), status, row.content(),
				sourcesOf(caller, citations), stale, failure, row.createdAt(), row.publishedAt());
	}

	/** 내가 요청한 산출물. 최근 것부터. 본문과 바뀜 여부는 열어야 나온다. */
	@Transactional(readOnly = true)
	public List<ArtifactSummary> listArtifacts(Caller caller) {
		return jdbc.sql("""
				select id, kind, question, lang, content is not null as published, created_at from artifact
				 where project_id = :projectId and requested_by = :userId
				 order by created_at desc, id limit 100
				""")
				.param("projectId", caller.projectId()).param("userId", caller.id())
				.query((rs, n) -> new ArtifactSummary(rs.getObject("id", UUID.class),
						GenKind.valueOf(rs.getString("kind")), rs.getString("question"), rs.getString("lang"),
						rs.getBoolean("published"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
				.list();
	}

	/** 이 가운데 내가 요청한 산출물. 영향 목록에서 남의 질문을 빼는 데 쓴다(산출물은 요청한 사람만 읽는다). */
	@Transactional(readOnly = true)
	public Set<UUID> ownArtifactIds(Caller caller, Collection<UUID> artifactIds) {
		if (caller.kind() != Caller.Kind.USER || artifactIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(jdbc.sql("""
				select id from artifact
				 where project_id = :projectId and requested_by = :userId and id in (:ids)
				""")
				.param("projectId", caller.projectId()).param("userId", caller.id()).param("ids", artifactIds)
				.query(UUID.class).list());
	}

	public record ArtifactSummary(UUID id, GenKind kind, String question, String lang, boolean published,
			Instant createdAt) {
	}

	// ---- 신고 ----

	/** 신고에 덧붙이는 글의 최대 길이. */
	static final int MAX_REPORT_NOTE = 500;

	/**
	 * AI가 쓴 산출물을 신고한다. 산출물을 연 사람(요청한 사람)만 신고할 수 있다.
	 * 운영자가 볼 수 있게 로그에 남긴다. 로그에는 본문·질문을 적지 않는다.
	 *
	 * @return 신고 id
	 */
	@Transactional
	public UUID reportArtifact(Caller caller, UUID artifactId, ReportReason reason, String note) {
		Objects.requireNonNull(reason, "reason");
		Row row = find(caller.projectId(), artifactId, false);
		if (caller.kind() != Caller.Kind.USER || !row.requestedBy().equals(caller.id())) {
			throw new ArtifactNotFoundException(artifactId);
		}
		String text = note == null || note.isBlank() ? null : note.strip();
		if (text != null && text.length() > MAX_REPORT_NOTE) {
			throw new IllegalArgumentException("신고 내용은 " + MAX_REPORT_NOTE + "자까지 쓸 수 있다");
		}
		if (reason == ReportReason.OTHER && text == null) {
			throw new IllegalArgumentException("기타 이유로 신고할 때는 내용을 적어 주세요");
		}
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into artifact_report (id, project_id, artifact_id, reported_by, reason, note)
				values (:id, :projectId, :artifactId, :userId, :reason, :note)
				""")
				.param("id", id).param("projectId", caller.projectId()).param("artifactId", artifactId)
				.param("userId", caller.id()).param("reason", reason.name()).param("note", text).update();
		log.warn("artifact_report id={} project={} artifact={} kind={} reason={}", id, caller.projectId(), artifactId,
				row.kind(), reason);
		return id;
	}

	// ---- 계정 삭제 ----

	/**
	 * 이 사람이 요청한 산출물(질문과 답변)을 지운다. 계정 삭제 전용. 산출물은 요청한 사람만 읽으므로 남길 까닭이 없다.
	 *
	 * @return 지운 산출물 id. 그 작업 기록은 작업 관리가 지운다
	 */
	@Transactional
	public List<UUID> deleteArtifactsOf(UUID projectId, UUID userId) {
		List<UUID> ids = jdbc.sql("select id from artifact where project_id = :projectId and requested_by = :userId")
				.param("projectId", projectId).param("userId", userId).query(UUID.class).list();
		if (ids.isEmpty()) {
			return ids;
		}
		graph.deleteEdges(projectId, graph.edgesTouching(projectId, ids).stream().map(Edge::id).toList());
		for (String sql : List.of("delete from artifact_report where artifact_id in (:ids)",
				"delete from artifact_ref where artifact_id in (:ids)",
				"delete from artifact where id in (:ids)")) {
			jdbc.sql(sql).param("ids", ids).update();
		}
		return ids;
	}

	/** 프로젝트의 산출물을 모두 지운다. 계정 삭제로 프로젝트를 없앨 때만 쓴다. 줄은 결재 창구가 지운다. */
	@Transactional
	public void purgeProject(UUID projectId) {
		for (String sql : List.of("delete from artifact_report where project_id = :projectId",
				"delete from artifact_ref where artifact_id in (select id from artifact where project_id = :projectId)",
				"delete from artifact where project_id = :projectId")) {
			jdbc.sql(sql).param("projectId", projectId).update();
		}
	}

	// ---- 내부 ----

	/**
	 * 모든 주장에는 원문 위치가 붙는다(설계 원칙 2). 사실을 말하는 답은 인용이 하나 이상 있어야 하고,
	 * 본문의 [n]은 모두 실제 인용을 가리켜야 한다. 인용 없이 받는 것은 "기록에서 찾지 못했다"는 짧은 답뿐이다.
	 */
	private static void requireGrounded(Draft draft) {
		String content = draft.content().strip();
		if (draft.noEvidence()) {
			if (!draft.citations().isEmpty() || !draft.usedNodes().isEmpty() || !draft.usedEdges().isEmpty()) {
				throw new IllegalArgumentException("근거를 찾지 못했다는 답에는 인용이나 참고한 카드를 붙이지 않는다");
			}
			if (content.codePointCount(0, content.length()) > NO_EVIDENCE_MAX_LENGTH || CITE.matcher(content).find()) {
				throw new IllegalArgumentException("근거를 찾지 못했다는 답은 인용 없는 짧은 한두 문장이어야 한다");
			}
			return;
		}
		if (draft.citations().isEmpty()) {
			throw new IllegalArgumentException("인용이 하나도 없는 답은 받지 않는다. 사실을 말하는 문장마다 근거 발언을 인용해야 한다");
		}
		Matcher marker = CITE.matcher(content);
		while (marker.find()) {
			int number = Integer.parseInt(marker.group(1));
			if (number < 1 || number > draft.citations().size()) {
				throw new IllegalArgumentException("본문의 인용 [" + number + "]에 해당하는 발언이 없다");
			}
		}
	}

	/** 본문의 인용 번호 [1], [2]. */
	private static final Pattern CITE = Pattern.compile("\\[(\\d{1,4})]");
	/** 근거 없음 답의 최대 길이(글자). 인용 없이 길게 설명하는 것을 막는다. */
	private static final int NO_EVIDENCE_MAX_LENGTH = 300;

	/** 인용한 발언이 실제로 있고, 요청한 사람이 읽을 수 있는 문서의 것인지 확인한다. */
	private void requireReadableCitations(Caller caller, List<NodeRef> citations) {
		if (citations.isEmpty()) {
			return;
		}
		Map<UUID, Span> spans = spansById(caller.projectId(), citations);
		Set<UUID> readable = identity.readableDocumentIds(caller);
		for (NodeRef citation : citations) {
			Span span = citation.type() == NodeType.SPAN ? spans.get(citation.id()) : null;
			if (span == null || !readable.contains(span.documentId())) {
				throw new IllegalArgumentException("없거나 읽을 수 없는 발언을 인용했다: " + citation.id());
			}
		}
	}

	/** 인용 번호 순서대로 원문을 돌려준다. 지금 읽을 수 없게 된 발언은 내용 없이 자리만 남긴다. */
	private List<Source> sourcesOf(Caller caller, List<NodeRef> citations) {
		Map<UUID, Span> spans = spansById(caller.projectId(), citations);
		Set<UUID> readable = identity.readableDocumentIds(caller);
		List<Source> sources = new ArrayList<>();
		for (NodeRef citation : citations) {
			Span span = spans.get(citation.id());
			boolean visible = span != null && readable.contains(span.documentId());
			sources.add(visible
					? new Source(span.ref(), span.documentId(), span.seq(), span.speakerLabel(), span.text())
					: new Source(citation, null, 0, null, null));
		}
		return sources;
	}

	private Map<UUID, Span> spansById(UUID projectId, List<NodeRef> refs) {
		Map<UUID, Span> spans = new HashMap<>();
		document.getSpans(projectId, refs.stream().map(NodeRef::id).toList())
				.forEach(span -> spans.put(span.ref().id(), span));
		return spans;
	}

	private void saveRef(UUID artifactId, String kind, int position, String type, UUID id, long version) {
		jdbc.sql("""
				insert into artifact_ref (artifact_id, kind, position, ref_type, ref_id, seen_version)
				values (:artifactId, :kind, :position, :type, :id, :version)
				""")
				.param("artifactId", artifactId).param("kind", kind).param("position", position)
				.param("type", type).param("id", id).param("version", version).update();
	}

	private Row find(UUID projectId, UUID artifactId, boolean forUpdate) {
		return jdbc.sql("select * from artifact where id = :id and project_id = :projectId"
				+ (forUpdate ? " for update" : ""))
				.param("id", artifactId).param("projectId", projectId)
				.query(GenerationWiki::mapRow).optional()
				.orElseThrow(() -> new ArtifactNotFoundException(artifactId));
	}

	private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
		OffsetDateTime published = rs.getObject("published_at", OffsetDateTime.class);
		return new Row(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
				GenKind.valueOf(rs.getString("kind")), rs.getString("question"), rs.getString("lang"),
				rs.getString("content"), rs.getObject("job_id", UUID.class),
				rs.getObject("requested_by", UUID.class),
				rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				published == null ? null : published.toInstant());
	}
}
