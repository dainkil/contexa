package com.contexa.server.document;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Caller;
import com.contexa.server.contract.NodeKey;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.identity.DocumentAccess;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.NotAllowedException;

/**
 * 자료실: 올린 문서를 판(버전)별로 보관하고, 발언 단위로 잘라 번호를 붙인다.
 *
 * <ul>
 * <li>같은 문서를 다시 올리면 새 판이 된다. 이전 판과 그 발언은 그대로 남는다.</li>
 * <li>발언에는 그 판 안에서의 순서(seq)가 붙는다. 다른 부서는 발언을 SPAN 참조로 가리킨다.</li>
 * <li>발언을 정정하면 이전 내용을 이력에 남기고 버전을 올린다.</li>
 * <li>누가 읽을 수 있는지는 출입 관리에 맡긴다. 여기서 돌려주는 발언은 권한 검사를 거치지 않은 것이므로,
 * 사용자에게 보여 주기 전에 호출한 쪽(안내 데스크)이 걸러야 한다.</li>
 * </ul>
 */
@Service
public class DocumentEvidence {

	private final JdbcClient jdbc;
	private final IdentityAccess identity;

	public DocumentEvidence(JdbcClient jdbc, IdentityAccess identity) {
		this.jdbc = jdbc;
		this.identity = identity;
	}

	/** 문서를 새로 올린다. 프로젝트 구성원인 사람만 할 수 있다. 첫 판이 함께 만들어진다. */
	@Transactional
	public SourceVersionRef registerDocument(Caller caller, DocumentInput input) {
		requireMember(caller);
		Objects.requireNonNull(input, "input");
		Objects.requireNonNull(input.kind(), "kind");
		Objects.requireNonNull(input.visibility(), "visibility");
		if (input.title() == null || input.title().isBlank()) {
			throw new IllegalArgumentException("문서 제목이 비어 있다");
		}
		requireContent(input.content());
		UUID documentId = UUID.randomUUID();
		jdbc.sql("""
				insert into document (id, project_id, title, kind, language, created_by, created_at)
				values (:id, :projectId, :title, :kind, :language, :userId, clock_timestamp())
				""")
				.param("id", documentId)
				.param("projectId", caller.projectId())
				.param("title", input.title().strip())
				.param("kind", input.kind().name())
				.param("language", input.language())
				.param("userId", caller.id())
				.update();
		identity.registerDocument(documentId, caller.projectId(), caller.id(), input.visibility(), input.externalOk());
		return insertVersion(documentId, 1, input.content(), caller.id());
	}

	/**
	 * 이미 있는 문서의 새 판을 올린다. 이전 판은 덮어쓰지 않는다.
	 * 내용이 이전 판 중 하나와 똑같으면 새 판을 만들지 않고 거절한다.
	 * 읽을 수 없는 문서(남의 개인 메모)는 없는 문서와 똑같이 거절한다.
	 */
	@Transactional
	public SourceVersionRef addVersion(Caller caller, UUID documentId, String content) {
		requireMember(caller);
		requireContent(content);
		requireReadable(caller, documentId);
		boolean duplicate = jdbc.sql("""
				select count(*) from source_version where document_id = :documentId and content_hash = :hash
				""")
				.param("documentId", documentId).param("hash", sha256(content)).query(Long.class).single() > 0;
		if (duplicate) {
			throw new IllegalStateException("이미 같은 내용의 판이 있다");
		}
		int next = jdbc.sql("select coalesce(max(version_no), 0) + 1 from source_version where document_id = :id")
				.param("id", documentId).query(Integer.class).single();
		return insertVersion(documentId, next, content, caller.id());
	}

	/** 판의 글자 내용. AI 인턴이 문서를 자를 때 읽는다. */
	@Transactional(readOnly = true)
	public String readContent(UUID sourceVersionId) {
		return jdbc.sql("select content from source_version where id = :id")
				.param("id", sourceVersionId)
				.query(String.class)
				.optional()
				.orElseThrow(() -> new DocumentNotFoundException("문서의 판", sourceVersionId));
	}

	/** 판이 속한 문서의 제목과 종류. AI 인턴이 문서를 자를 때 함께 읽는다. */
	@Transactional(readOnly = true)
	public VersionInfo versionInfo(UUID sourceVersionId) {
		return jdbc.sql("""
				select d.id, d.project_id, d.title, d.kind, d.language, v.version_no
				  from source_version v join document d on d.id = v.document_id
				 where v.id = :id
				""")
				.param("id", sourceVersionId)
				.query((rs, n) -> new VersionInfo(rs.getObject("id", UUID.class),
						rs.getObject("project_id", UUID.class), rs.getString("title"),
						DocumentKind.valueOf(rs.getString("kind")), rs.getString("language"), rs.getInt("version_no")))
				.optional()
				.orElseThrow(() -> new DocumentNotFoundException("문서의 판", sourceVersionId));
	}

	/**
	 * 한 판을 잘라낸 발언들을 저장하고 순서대로 1번부터 번호를 붙인다. 한 판에 한 번만 할 수 있다.
	 *
	 * @return 발언마다의 SPAN 참조. 준 순서와 같다
	 */
	@Transactional
	public List<NodeRef> saveSpans(UUID sourceVersionId, List<SpanDraft> spans) {
		Objects.requireNonNull(spans, "spans");
		record Version(UUID documentId, UUID projectId) {
		}
		Version version = jdbc.sql("""
				select v.document_id, d.project_id from source_version v
				  join document d on d.id = v.document_id
				 where v.id = :id for update of v
				""")
				.param("id", sourceVersionId)
				.query((rs, n) -> new Version(rs.getObject("document_id", UUID.class),
						rs.getObject("project_id", UUID.class)))
				.optional()
				.orElseThrow(() -> new DocumentNotFoundException("문서의 판", sourceVersionId));
		if (jdbc.sql("select count(*) from span where source_version_id = :id").param("id", sourceVersionId)
				.query(Long.class).single() > 0) {
			throw new IllegalStateException("이 판은 이미 발언으로 잘려 있다");
		}
		for (SpanDraft draft : spans) {
			if (draft.text() == null || draft.text().isBlank()) {
				throw new IllegalArgumentException("내용이 비어 있는 발언이 있다");
			}
		}
		List<NodeRef> refs = new ArrayList<>();
		int seq = 0;
		for (SpanDraft draft : spans) {
			UUID id = UUID.randomUUID();
			jdbc.sql("""
					insert into span (id, project_id, document_id, source_version_id, seq, speaker_label, text)
					values (:id, :projectId, :documentId, :versionId, :seq, :speaker, :text)
					""")
					.param("id", id)
					.param("projectId", version.projectId())
					.param("documentId", version.documentId())
					.param("versionId", sourceVersionId)
					.param("seq", ++seq)
					.param("speaker", draft.speakerLabel() == null || draft.speakerLabel().isBlank() ? null
							: draft.speakerLabel().strip())
					.param("text", draft.text().strip())
					.update();
			refs.add(new NodeRef(NodeType.SPAN, id, 1));
		}
		return refs;
	}

	/** 요청자가 읽을 수 있는 이 프로젝트의 문서. 최근에 올린 것부터. */
	@Transactional(readOnly = true)
	public List<DocumentInfo> listReadable(Caller caller) {
		Objects.requireNonNull(caller, "caller");
		Map<UUID, DocumentAccess> readable = identity.readableDocuments(caller);
		if (readable.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				select id, title, kind, language, created_at from document
				 where project_id = :projectId and id in (:ids)
				 order by created_at desc, id
				""")
				.param("projectId", caller.projectId()).param("ids", readable.keySet())
				.query((rs, n) -> mapInfo(rs, readable))
				.list();
	}

	/**
	 * 요청자가 읽을 수 있는 문서 한 건.
	 *
	 * @throws DocumentNotFoundException 없거나, 다른 프로젝트 것이거나, 읽을 수 없음(남의 개인 메모). 셋을 구분하지 않는다
	 */
	@Transactional(readOnly = true)
	public DocumentInfo readable(Caller caller, UUID documentId) {
		Objects.requireNonNull(caller, "caller");
		DocumentAccess access = identity.readableDocuments(caller).get(documentId);
		if (access == null) {
			throw new DocumentNotFoundException("문서", documentId);
		}
		return jdbc.sql("select id, title, kind, language, created_at from document"
				+ " where id = :id and project_id = :projectId")
				.param("id", documentId).param("projectId", caller.projectId())
				.query((rs, n) -> mapInfo(rs, Map.of(documentId, access)))
				.optional()
				.orElseThrow(() -> new DocumentNotFoundException("문서", documentId));
	}

	/** 문서마다 판 목록(1판부터). 권한 검사는 호출한 쪽이 한다. */
	@Transactional(readOnly = true)
	public Map<UUID, List<VersionSummary>> versionsOf(UUID projectId, Collection<UUID> documentIds) {
		Objects.requireNonNull(projectId, "projectId");
		Map<UUID, List<VersionSummary>> versions = new HashMap<>();
		if (documentIds == null || documentIds.isEmpty()) {
			return versions;
		}
		record Row(UUID documentId, VersionSummary version) {
		}
		jdbc.sql("""
				select v.document_id, v.id, v.version_no, v.created_at,
				       (select count(*) from span s where s.source_version_id = v.id) as span_count
				  from source_version v join document d on d.id = v.document_id
				 where d.project_id = :projectId and v.document_id in (:ids)
				 order by v.document_id, v.version_no
				""")
				.param("projectId", projectId).param("ids", documentIds)
				.query((rs, n) -> new Row(rs.getObject("document_id", UUID.class),
						new VersionSummary(rs.getObject("id", UUID.class), rs.getInt("version_no"),
								rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getInt("span_count"))))
				.list()
				.forEach(row -> versions.computeIfAbsent(row.documentId(), id -> new ArrayList<>()).add(row.version()));
		return versions;
	}

	/** 발언이 들어 있는 문서와 판. 이 프로젝트에 없는 발언이면 빈 값. 권한 검사는 호출한 쪽이 한다. */
	@Transactional(readOnly = true)
	public Optional<SourceVersionRef> versionOfSpan(UUID projectId, UUID spanId) {
		return jdbc.sql("""
				select s.document_id, s.source_version_id, v.version_no
				  from span s join source_version v on v.id = s.source_version_id
				 where s.id = :id and s.project_id = :projectId
				""")
				.param("id", spanId).param("projectId", projectId)
				.query((rs, n) -> new SourceVersionRef(rs.getObject("document_id", UUID.class),
						rs.getObject("source_version_id", UUID.class), rs.getInt("version_no")))
				.optional();
	}

	/** 판마다 그 판이 속한 문서. 이 프로젝트에 없는 판은 결과에서 빠진다. */
	@Transactional(readOnly = true)
	public Map<UUID, UUID> documentsOfVersions(UUID projectId, Collection<UUID> sourceVersionIds) {
		Objects.requireNonNull(projectId, "projectId");
		Map<UUID, UUID> documents = new HashMap<>();
		if (sourceVersionIds == null || sourceVersionIds.isEmpty()) {
			return documents;
		}
		record Row(UUID versionId, UUID documentId) {
		}
		jdbc.sql("""
				select v.id, v.document_id from source_version v join document d on d.id = v.document_id
				 where d.project_id = :projectId and v.id in (:ids)
				""")
				.param("projectId", projectId).param("ids", sourceVersionIds)
				.query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class)))
				.list()
				.forEach(row -> documents.put(row.versionId(), row.documentId()));
		return documents;
	}

	/** 이 프로젝트의 발언을 읽는다. 없는 발언은 결과에서 빠진다. 권한 검사는 호출한 쪽이 한다. */
	@Transactional(readOnly = true)
	public List<Span> getSpans(UUID projectId, Collection<UUID> spanIds) {
		Objects.requireNonNull(projectId, "projectId");
		if (spanIds == null || spanIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				select id, project_id, document_id, source_version_id, seq, speaker_label, text, revision
				  from span where project_id = :projectId and id in (:ids)
				 order by document_id, source_version_id, seq
				""")
				.param("projectId", projectId)
				.param("ids", spanIds)
				.query(DocumentEvidence::mapSpan)
				.list();
	}

	/** 한 판의 발언 전부, 순서대로. */
	@Transactional(readOnly = true)
	public List<Span> spansOf(UUID sourceVersionId) {
		return jdbc.sql("""
				select id, project_id, document_id, source_version_id, seq, speaker_label, text, revision
				  from span where source_version_id = :id order by seq
				""")
				.param("id", sourceVersionId)
				.query(DocumentEvidence::mapSpan)
				.list();
	}

	/**
	 * 발언 내용을 정정한다(전사 오류 등). 이전 내용은 이력에 남는다.
	 * 결재 창구만 호출한다. 이 발언에 기댄 카드가 영향을 받기 때문이다.
	 *
	 * @param expectedRevision 정정하는 사람이 화면에서 본 발언 버전
	 */
	@Transactional
	public NodeRef reviseSpan(UUID projectId, UUID spanId, long expectedRevision, String text, UUID actor) {
		Objects.requireNonNull(actor, "actor");
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("발언 내용이 비어 있다");
		}
		record Current(String text, long revision) {
		}
		Current current = jdbc.sql("select text, revision from span where id = :id and project_id = :projectId for update")
				.param("id", spanId).param("projectId", projectId)
				.query((rs, n) -> new Current(rs.getString("text"), rs.getLong("revision")))
				.optional()
				.orElseThrow(() -> new DocumentNotFoundException("발언", spanId));
		if (current.revision() != expectedRevision) {
			throw new IllegalStateException("발언이 이미 바뀌었다: 기대한 버전 " + expectedRevision
					+ ", 현재 버전 " + current.revision());
		}
		jdbc.sql("insert into span_history (span_id, revision, text, changed_by) values (:id, :revision, :text, :actor)")
				.param("id", spanId).param("revision", current.revision()).param("text", current.text())
				.param("actor", actor).update();
		jdbc.sql("update span set text = :text, revision = revision + 1, updated_at = now() where id = :id")
				.param("text", text.strip()).param("id", spanId).update();
		return new NodeRef(NodeType.SPAN, spanId, current.revision() + 1);
	}

	/** 발언마다 현재 버전. 이 프로젝트에 없는 발언은 결과에서 빠진다. SPAN이 아닌 참조는 무시한다. */
	@Transactional(readOnly = true)
	public Map<NodeKey, Long> currentRevisions(UUID projectId, Collection<NodeRef> refs) {
		Map<NodeKey, Long> revisions = new HashMap<>();
		List<UUID> ids = refs == null ? List.of()
				: refs.stream().filter(ref -> ref.type() == NodeType.SPAN).map(NodeRef::id).toList();
		getSpans(projectId, ids).forEach(span -> revisions.put(span.ref().key(), span.ref().revision()));
		return revisions;
	}

	// ---- 내부 ----

	private SourceVersionRef insertVersion(UUID documentId, int versionNo, String content, UUID userId) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into source_version (id, document_id, version_no, content, content_hash, created_by)
				values (:id, :documentId, :versionNo, :content, :hash, :userId)
				""")
				.param("id", id).param("documentId", documentId).param("versionNo", versionNo)
				.param("content", content).param("hash", sha256(content)).param("userId", userId).update();
		return new SourceVersionRef(documentId, id, versionNo);
	}

	private void requireMember(Caller caller) {
		Objects.requireNonNull(caller, "caller");
		if (!identity.isMember(caller)) {
			throw new NotAllowedException("문서는 프로젝트 구성원만 올릴 수 있다");
		}
	}

	private void requireReadable(Caller caller, UUID documentId) {
		boolean exists = jdbc.sql("select count(*) from document where id = :id and project_id = :projectId")
				.param("id", documentId).param("projectId", caller.projectId()).query(Long.class).single() > 0;
		if (!exists || !identity.canRead(caller, documentId)) {
			throw new DocumentNotFoundException("문서", documentId);
		}
	}

	private static void requireContent(String content) {
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("문서 내용이 비어 있다");
		}
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	private static DocumentInfo mapInfo(ResultSet rs, Map<UUID, DocumentAccess> access) throws SQLException {
		UUID id = rs.getObject("id", UUID.class);
		DocumentAccess acl = access.get(id);
		return new DocumentInfo(id, rs.getString("title"), DocumentKind.valueOf(rs.getString("kind")),
				rs.getString("language"), acl.visibility(), acl.externalOk(),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

	private static Span mapSpan(ResultSet rs, int rowNum) throws SQLException {
		return new Span(new NodeRef(NodeType.SPAN, rs.getObject("id", UUID.class), rs.getLong("revision")),
				rs.getObject("project_id", UUID.class), rs.getObject("document_id", UUID.class),
				rs.getObject("source_version_id", UUID.class), rs.getInt("seq"), rs.getString("speaker_label"),
				rs.getString("text"));
	}
}
