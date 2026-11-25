package com.contexa.server.entity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.NodeDraft;
import com.contexa.server.contract.NodeKey;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.NodeView;
import com.contexa.server.contract.ReviewStatus;

/**
 * 이름 확인: 인물·기관 카드와 언어별 이름 표기를 관리한다.
 *
 * <p>서로 다른 사실을 따로 확인한다:
 * <ul>
 * <li>이 인물·기관이 실제로 있다 → 카드의 확인 상태</li>
 * <li>이 언어로는 이렇게 적는다 → 표기마다의 확인 상태. 확인되지 않은 음역·번역은 "제안 표기"로 남는다</li>
 * <li>이 인물이 이 계정이다 → 계정 연결</li>
 * <li>이 발언의 이 이름이 이 인물을 가리킨다 → 여기가 아니라 게시판의 MENTIONS 줄</li>
 * </ul>
 * 하나를 확인해도 다른 것이 저절로 확인되지 않는다. 상태를 바꾸는 함수는 결재 창구만 부른다.
 */
@Service
public class EntityReview {

	private static final Set<String> LANGS = Set.of("ko", "en", "ar");
	private static final Set<String> KINDS = Set.of("PERSON", "ORGANIZATION");

	private record Row(UUID id, UUID projectId, ReviewStatus status, long revision) {
	}

	private final JdbcClient jdbc;

	public EntityReview(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ---- 저장과 조회 ----

	/**
	 * 인물·기관 카드 후보를 저장한다. 모두 CANDIDATE, revision 1로 들어간다. 하나라도 틀리면 아무것도 저장하지 않는다.
	 * 카드 내용: name(대표 표기)과 kind(PERSON 또는 ORGANIZATION)가 필수이고,
	 * names에 언어별 표기({"en": "Ahmed Hassan"})를 주면 제안 표기로 함께 저장한다.
	 */
	@Transactional
	public Map<String, NodeRef> saveCandidates(UUID projectId, List<NodeDraft> drafts) {
		Objects.requireNonNull(projectId, "projectId");
		record Prepared(String key, UUID id, String name, String kind, Map<String, String> names) {
		}
		List<Prepared> prepared = new ArrayList<>();
		Set<String> keys = new HashSet<>();
		for (NodeDraft draft : drafts) {
			if (draft.type() != NodeType.ENTITY) {
				throw new IllegalArgumentException("이름 확인이 소유하지 않는 카드 종류다: " + draft.type());
			}
			if (!keys.add(draft.key())) {
				throw new IllegalArgumentException("같은 key가 두 번 나왔다: " + draft.key());
			}
			for (String field : draft.fields().keySet()) {
				if (!Set.of("name", "kind", "names").contains(field)) {
					throw new IllegalArgumentException("후보 '" + draft.key() + "'(ENTITY)에 쓸 수 없는 값이다: " + field);
				}
			}
			prepared.add(new Prepared(draft.key(), UUID.randomUUID(), requireName(draft.fields().get("name")),
					requireKind(draft.fields().get("kind")), requireNames(draft.fields().get("names"))));
		}
		Map<String, NodeRef> saved = new LinkedHashMap<>();
		for (Prepared p : prepared) {
			jdbc.sql("""
					insert into entity (id, project_id, kind, name, created_at)
					values (:id, :projectId, :kind, :name, clock_timestamp())
					""")
					.param("id", p.id()).param("projectId", projectId).param("kind", p.kind())
					.param("name", p.name()).update();
			p.names().forEach((lang, form) -> upsertName(p.id(), lang, form, null));
			saved.put(p.key(), new NodeRef(NodeType.ENTITY, p.id(), 1));
		}
		return saved;
	}

	/**
	 * 인물·기관 카드를 읽는다. 결과는 요청한 순서를 따르고, 없는 카드와 ENTITY가 아닌 참조는 빠진다.
	 * states에는 kind, 계정 연결 여부(accountLinked), 확인된 언어별 표기(name.ko 등)가 담긴다.
	 */
	@Transactional(readOnly = true)
	public List<NodeView> getNodes(UUID projectId, Collection<NodeRef> refs) {
		Objects.requireNonNull(projectId, "projectId");
		List<UUID> ids = refs.stream().filter(ref -> ref.type() == NodeType.ENTITY).map(NodeRef::id).distinct().toList();
		if (ids.isEmpty()) {
			return List.of();
		}
		Map<UUID, NodeView> found = new HashMap<>();
		Map<UUID, Map<String, String>> confirmed = confirmedNames(ids);
		jdbc.sql("select * from entity where project_id = :projectId and id in (:ids)")
				.param("projectId", projectId).param("ids", ids)
				.query((rs, n) -> view(rs, confirmed))
				.list()
				.forEach(view -> found.put(view.ref().id(), view));
		return ids.stream().map(found::get).filter(Objects::nonNull).toList();
	}

	/** 이 프로젝트의 인물·기관 카드 전부. 만들어진 순서로 돌려준다. */
	@Transactional(readOnly = true)
	public List<NodeView> listNodes(UUID projectId, Set<ReviewStatus> statuses) {
		Objects.requireNonNull(projectId, "projectId");
		if (statuses == null || statuses.isEmpty()) {
			throw new IllegalArgumentException("보려는 확인 상태를 하나 이상 지정해야 한다");
		}
		List<NodeRef> refs = jdbc.sql("select id, revision from entity where project_id = :projectId"
				+ " and status in (:statuses) order by created_at, id")
				.param("projectId", projectId)
				.param("statuses", statuses.stream().map(Enum::name).toList())
				.query((rs, n) -> new NodeRef(NodeType.ENTITY, rs.getObject("id", UUID.class), rs.getLong("revision")))
				.list();
		return getNodes(projectId, refs);
	}

	@Transactional(readOnly = true)
	public Map<NodeKey, Long> currentRevisions(UUID projectId, Collection<NodeRef> refs) {
		Map<NodeKey, Long> revisions = new HashMap<>();
		getNodes(projectId, refs).forEach(view -> revisions.put(view.ref().key(), view.ref().revision()));
		return revisions;
	}

	/** 이름 카드: 카드와 언어별 표기 전부(제안 표기와 거절된 표기 포함). */
	@Transactional(readOnly = true)
	public NameCard getNameCard(UUID projectId, UUID entityId) {
		NodeView entity = getNodes(projectId, List.of(new NodeRef(NodeType.ENTITY, entityId, 1))).stream()
				.findFirst().orElseThrow(() -> new EntityNotFoundException(entityId));
		return new NameCard(entity, namesOf(projectId, List.of(entityId)).getOrDefault(entityId, List.of()));
	}

	/**
	 * 여러 인물·기관의 언어별 표기 전부(제안·확인·거절)를 한 번에 읽는다. 언어, 넣은 순서로 정렬한다.
	 * 이 프로젝트에 없는 카드와 표기가 없는 카드는 결과에 없다.
	 */
	@Transactional(readOnly = true)
	public Map<UUID, List<NameForm>> namesOf(UUID projectId, Collection<UUID> entityIds) {
		Objects.requireNonNull(projectId, "projectId");
		if (entityIds == null || entityIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, List<NameForm>> names = new LinkedHashMap<>();
		jdbc.sql("""
				select n.entity_id, n.lang, n.form, n.status, n.evidence_span_id
				  from entity_name n join entity e on e.id = n.entity_id
				 where e.project_id = :projectId and n.entity_id in (:ids)
				 order by n.lang, n.created_at, n.form
				""")
				.param("projectId", projectId).param("ids", entityIds)
				.query((rs, n) -> {
					names.computeIfAbsent(rs.getObject("entity_id", UUID.class), id -> new ArrayList<>())
							.add(new NameForm(rs.getString("lang"), rs.getString("form"), rs.getString("status"),
									rs.getObject("evidence_span_id", UUID.class)));
					return null;
				})
				.list();
		return names;
	}

	/**
	 * 이 표기에 해당할 만한 인물·기관을 찾는다. 대소문자와 앞뒤 공백을 무시하고,
	 * 표기 전체가 같은 것을 먼저, 일부만 겹치는 것을 그다음에 돌려준다. 거절·철회된 카드와 거절된 표기는 뺀다.
	 * 후보가 여럿이어도 여기서 하나로 고르지 않는다. 고르는 것은 사람이다.
	 *
	 * @param lang 이 언어의 표기만 볼 때. null이면 모든 언어와 대표 표기를 본다
	 */
	@Transactional(readOnly = true)
	public List<EntityCandidate> findCandidates(UUID projectId, String surface, String lang) {
		Objects.requireNonNull(projectId, "projectId");
		if (surface == null || surface.isBlank()) {
			return List.of();
		}
		String needle = surface.strip().toLowerCase(Locale.ROOT);
		return jdbc.sql("""
				select e.id, e.revision, e.status, e.name, m.form, m.lang, m.confirmed,
				       lower(m.form) = :needle as exact
				  from entity e
				  join (select entity_id, form, lang, status = 'CONFIRMED' as confirmed
				          from entity_name where status <> 'REJECTED'
				        union all
				        select id, name, null, false from entity) m on m.entity_id = e.id
				 where e.project_id = :projectId
				   and e.status in ('CANDIDATE', 'CONFIRMED', 'HELD')
				   and (cast(:lang as text) is null or m.lang = :lang)
				   and (lower(m.form) = :needle
				        or position(:needle in lower(m.form)) > 0
				        or position(lower(m.form) in :needle) > 0)
				 order by exact desc, m.confirmed desc, e.created_at, e.id, m.form
				""")
				.param("projectId", projectId).param("needle", needle).param("lang", lang)
				.query((rs, n) -> new EntityCandidate(
						new NodeRef(NodeType.ENTITY, rs.getObject("id", UUID.class), rs.getLong("revision")),
						ReviewStatus.valueOf(rs.getString("status")), rs.getString("name"), rs.getString("form"),
						rs.getString("lang"), rs.getBoolean("confirmed"), rs.getBoolean("exact")))
				.list();
	}

	/** 이 계정에 연결된, 확인된 인물 카드. */
	@Transactional(readOnly = true)
	public Optional<UUID> entityOfAccount(UUID projectId, UUID accountId) {
		return jdbc.sql("""
				select id from entity
				 where project_id = :projectId and account_id = :accountId and status = 'CONFIRMED'
				""")
				.param("projectId", projectId).param("accountId", accountId)
				.query((rs, n) -> rs.getObject("id", UUID.class))
				.optional();
	}

	// ---- 상태 변경 (결재 창구만 호출) ----

	/** 카드의 확인 상태를 바꾼다. 줄과 같은 전이 규칙을 쓴다. */
	@Transactional
	public NodeRef setNodeStatus(NodeRef node, ReviewStatus next, UUID reviewer, long expectedRevision) {
		Objects.requireNonNull(next, "status");
		Objects.requireNonNull(reviewer, "reviewer");
		Row current = lock(node.id(), expectedRevision);
		if (!current.status().canMoveTo(next)) {
			throw new IllegalStateException("허용되지 않은 상태 변화: " + current.status() + " → " + next);
		}
		boolean confirming = next == ReviewStatus.CONFIRMED;
		return update(current, """
				status = :status,
				confirmed_by = case when :confirming then :reviewer else confirmed_by end,
				confirmed_at = case when :confirming then now() else confirmed_at end
				""", Map.of("status", next.name(), "confirming", confirming, "reviewer", reviewer));
	}

	/** 대표 표기(name)나 종류(kind)를 정정한다. */
	@Transactional
	public NodeRef updateFields(NodeRef node, Map<String, Object> fields, long expectedRevision) {
		if (fields == null || fields.isEmpty()) {
			throw new IllegalArgumentException("정정할 값이 없다");
		}
		Row current = lock(node.id(), expectedRevision);
		requireAlive(current);
		List<String> assignments = new ArrayList<>();
		Map<String, Object> params = new HashMap<>();
		for (Map.Entry<String, Object> field : fields.entrySet()) {
			switch (field.getKey()) {
				case "name" -> params.put("name", requireName(field.getValue()));
				case "kind" -> params.put("kind", requireKind(field.getValue()));
				default -> throw new IllegalArgumentException("ENTITY 카드에 쓸 수 없는 값이다: " + field.getKey());
			}
			assignments.add(field.getKey() + " = :" + field.getKey());
		}
		return update(current, String.join(", ", assignments), params);
	}

	/**
	 * 이 언어로는 이렇게 적는다고 확인한다. 같은 언어에 확인된 표기가 여럿 있을 수 있다(별칭).
	 * 이름을 확인해도 계정 연결이나 업무 수락은 바뀌지 않는다.
	 */
	@Transactional
	public NodeRef confirmName(UUID projectId, UUID entityId, String lang, String form, UUID reviewer,
			UUID evidenceSpanId) {
		Objects.requireNonNull(reviewer, "reviewer");
		Row current = lockIn(projectId, entityId);
		requireAlive(current);
		UUID nameId = upsertName(entityId, requireLang(lang), requireName(form), evidenceSpanId);
		jdbc.sql("""
				update entity_name set status = 'CONFIRMED', confirmed_by = :reviewer, confirmed_at = now(),
				       evidence_span_id = coalesce(:evidence, evidence_span_id)
				 where id = :id
				""")
				.param("reviewer", reviewer).param("evidence", evidenceSpanId).param("id", nameId).update();
		return update(current, "", Map.of());
	}

	/** 이 표기는 틀렸다고 거절한다. 거절된 표기는 이름 찾기에 쓰이지 않는다. */
	@Transactional
	public NodeRef rejectName(UUID projectId, UUID entityId, String lang, String form) {
		Row current = lockIn(projectId, entityId);
		int updated = jdbc.sql("""
				update entity_name set status = 'REJECTED'
				 where entity_id = :id and lang = :lang and form = :form
				""")
				.param("id", entityId).param("lang", requireLang(lang)).param("form", requireName(form)).update();
		if (updated == 0) {
			throw new IllegalArgumentException("그런 표기가 없다: " + lang + " " + form);
		}
		return update(current, "", Map.of());
	}

	/**
	 * 이 인물이 이 계정이라고 확인한다(accountId가 null이면 연결을 끊는다). 확인된 인물 카드만 연결할 수 있다.
	 * 그 계정이 프로젝트 구성원인지는 결재 창구가 확인한 뒤 호출한다.
	 */
	@Transactional
	public NodeRef linkAccount(UUID projectId, UUID entityId, UUID accountId) {
		Row current = lockIn(projectId, entityId);
		if (accountId != null) {
			if (current.status() != ReviewStatus.CONFIRMED) {
				throw new IllegalStateException("확인되지 않은 인물 카드에는 계정을 연결할 수 없다: " + current.status());
			}
			String kind = jdbc.sql("select kind from entity where id = :id").param("id", entityId)
					.query(String.class).single();
			if (!kind.equals("PERSON")) {
				throw new IllegalStateException("계정은 인물 카드에만 연결할 수 있다");
			}
			boolean taken = jdbc.sql("""
					select count(*) from entity
					 where project_id = :projectId and account_id = :accountId and id <> :id
					""")
					.param("projectId", projectId).param("accountId", accountId).param("id", entityId)
					.query(Long.class).single() > 0;
			if (taken) {
				throw new IllegalStateException("이 계정은 이미 다른 인물 카드에 연결되어 있다");
			}
		}
		Map<String, Object> params = new HashMap<>();
		params.put("accountId", accountId);
		return update(current, "account_id = :accountId", params);
	}

	// ---- 내부 ----

	private Row lock(UUID entityId, long expectedRevision) {
		Row row = jdbc.sql("select id, project_id, status, revision from entity where id = :id for update")
				.param("id", entityId).query(EntityReview::mapRow).optional()
				.orElseThrow(() -> new EntityNotFoundException(entityId));
		if (row.revision() != expectedRevision) {
			throw new IllegalStateException("인물·기관 카드가 이미 바뀌었다: 기대한 버전 " + expectedRevision
					+ ", 현재 버전 " + row.revision());
		}
		return row;
	}

	private Row lockIn(UUID projectId, UUID entityId) {
		return jdbc.sql("select id, project_id, status, revision from entity"
				+ " where id = :id and project_id = :projectId for update")
				.param("id", entityId).param("projectId", projectId).query(EntityReview::mapRow).optional()
				.orElseThrow(() -> new EntityNotFoundException(entityId));
	}

	private static void requireAlive(Row row) {
		if (row.status() == ReviewStatus.REJECTED || row.status() == ReviewStatus.RETRACTED) {
			throw new IllegalStateException("거절되거나 철회된 카드는 바꿀 수 없다: " + row.status());
		}
	}

	/** 행을 고치고 버전을 1 올린다. 고칠 값이 없으면 버전만 올린다(딸린 표기가 바뀐 경우). */
	private NodeRef update(Row row, String assignments, Map<String, ?> params) {
		String prefix = assignments.isBlank() ? "" : assignments.strip() + ", ";
		JdbcClient.StatementSpec spec = jdbc.sql("update entity set " + prefix
				+ "revision = revision + 1, updated_at = now() where id = :id and revision = :revision")
				.param("id", row.id()).param("revision", row.revision());
		for (Map.Entry<String, ?> param : params.entrySet()) {
			spec = spec.param(param.getKey(), param.getValue());
		}
		if (spec.update() == 0) {
			throw new IllegalStateException("인물·기관 카드가 이미 바뀌었다: " + row.id());
		}
		return new NodeRef(NodeType.ENTITY, row.id(), row.revision() + 1);
	}

	/** 표기를 제안 표기로 넣는다. 이미 있으면 그대로 두고 그 id를 돌려준다. */
	private UUID upsertName(UUID entityId, String lang, String form, UUID evidenceSpanId) {
		jdbc.sql("""
				insert into entity_name (id, entity_id, lang, form, evidence_span_id, created_at)
				values (:id, :entityId, :lang, :form, :evidence, clock_timestamp())
				on conflict (entity_id, lang, form) do nothing
				""")
				.param("id", UUID.randomUUID()).param("entityId", entityId).param("lang", lang)
				.param("form", form).param("evidence", evidenceSpanId).update();
		return jdbc.sql("select id from entity_name where entity_id = :entityId and lang = :lang and form = :form")
				.param("entityId", entityId).param("lang", lang).param("form", form)
				.query((rs, n) -> rs.getObject("id", UUID.class)).single();
	}

	private Map<UUID, Map<String, String>> confirmedNames(List<UUID> entityIds) {
		Map<UUID, Map<String, String>> names = new HashMap<>();
		jdbc.sql("""
				select entity_id, lang, form from entity_name
				 where entity_id in (:ids) and status = 'CONFIRMED' order by confirmed_at, form
				""")
				.param("ids", entityIds)
				.query((rs, n) -> new String[] { rs.getString("entity_id"), rs.getString("lang"), rs.getString("form") })
				.list()
				// 한 언어에 확인된 표기가 여럿이면 먼저 확인된 것을 대표로 싣는다
				.forEach(row -> names.computeIfAbsent(UUID.fromString(row[0]), id -> new LinkedHashMap<>())
						.putIfAbsent("name." + row[1], row[2]));
		return names;
	}

	private static NodeView view(ResultSet rs, Map<UUID, Map<String, String>> confirmed) throws SQLException {
		UUID id = rs.getObject("id", UUID.class);
		Map<String, String> states = new LinkedHashMap<>();
		states.put("kind", rs.getString("kind"));
		states.put("accountLinked", String.valueOf(rs.getObject("account_id", UUID.class) != null));
		states.putAll(confirmed.getOrDefault(id, Map.of()));
		OffsetDateTime confirmedAt = rs.getObject("confirmed_at", OffsetDateTime.class);
		Instant confirmedInstant = confirmedAt == null ? null : confirmedAt.toInstant();
		return new NodeView(new NodeRef(NodeType.ENTITY, id, rs.getLong("revision")),
				rs.getObject("project_id", UUID.class), ReviewStatus.valueOf(rs.getString("status")),
				rs.getString("name"), Collections.unmodifiableMap(states),
				rs.getObject("confirmed_by", UUID.class), confirmedInstant);
	}

	private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
				ReviewStatus.valueOf(rs.getString("status")), rs.getLong("revision"));
	}

	private static String requireName(Object value) {
		if (!(value instanceof String text) || text.isBlank()) {
			throw new IllegalArgumentException("이름 표기가 비어 있다");
		}
		return text.strip();
	}

	private static String requireKind(Object value) {
		String kind = value == null ? null : value.toString().strip();
		if (kind == null || !KINDS.contains(kind)) {
			throw new IllegalArgumentException("kind는 PERSON 또는 ORGANIZATION이어야 한다: " + value);
		}
		return kind;
	}

	private static String requireLang(String lang) {
		if (lang == null || !LANGS.contains(lang)) {
			throw new IllegalArgumentException("언어는 ko, en, ar 중 하나여야 한다: " + lang);
		}
		return lang;
	}

	private static Map<String, String> requireNames(Object value) {
		Map<String, String> names = new LinkedHashMap<>();
		if (value == null) {
			return names;
		}
		if (!(value instanceof Map<?, ?> map)) {
			throw new IllegalArgumentException("names는 {언어: 표기} 모양이어야 한다");
		}
		map.forEach((lang, form) -> names.put(requireLang(String.valueOf(lang)), requireName(form)));
		return names;
	}
}
