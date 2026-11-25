package com.contexa.server.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.contexa.server.Fixtures;
import com.contexa.server.contract.Caller;
import com.contexa.server.contract.EdgeDraft;
import com.contexa.server.contract.GraphCandidates;
import com.contexa.server.contract.NodeDraft;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.RelType;
import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.generation.GenKind;
import com.contexa.server.generation.GenerationWiki;
import com.contexa.server.generation.ReportReason;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.Role;
import com.contexa.server.identity.UnauthenticatedException;
import com.contexa.server.identity.Visibility;
import com.contexa.server.review.ReviewImpact;

/**
 * 계정 삭제: 혼자인 프로젝트는 통째로 지우고, 함께 쓰는 프로젝트에서는 개인 메모·그 메모에서만 나온 카드·내 질문을 지우고
 * 나간다. 공유 문서와 다른 사람의 것은 남고, 내 이름은 "탈퇴한 사용자"가 된다.
 */
@SpringBootTest
@Transactional
class AccountDeletionTest {

	@Autowired
	AccountDeletion deletion;

	@Autowired
	IdentityAccess identity;

	@Autowired
	DocumentEvidence document;

	@Autowired
	ReviewImpact review;

	@Autowired
	GenerationWiki generation;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	WebApplicationContext context;

	String email;
	UUID me;
	UUID solo;
	UUID shared;
	UUID reviewer;
	UUID member;
	NodeRef t1;
	NodeRef t2;
	NodeRef m1;
	NodeRef m2;
	UUID memberMemo;
	Map<String, UUID> cards = new HashMap<>();
	UUID myAnswer;
	UUID reviewerAnswer;

	@BeforeEach
	void 혼자인_프로젝트와_함께_쓰는_프로젝트에_자료가_있다() {
		jdbc.sql("update job set status = 'CANCELLED' where status in ('QUEUED', 'RUNNING')").update();
		email = "leaving-" + UUID.randomUUID() + "@contexa.test";
		me = identity.signUp(email, "민지", Fixtures.PASSWORD);

		// 혼자인 프로젝트: 문서, 카드, 질문
		solo = identity.createProject(me, "혼자 쓰는 프로젝트");
		Caller soloMe = Caller.user(me, solo);
		NodeRef soloSpan = Fixtures.transcript(document, soloMe, "포스터는 다음 주까지 만듭니다.").get(0);
		review.submitCandidates(job(solo), new GraphCandidates(
				List.of(new NodeDraft("poster", NodeType.TASK, Map.of("title", "포스터 제작"))),
				List.of(EdgeDraft.toExisting(RelType.EVIDENCED_BY, "poster", soloSpan, List.of(soloSpan.id())))));
		UUID soloAnswer = generation.requestGeneration(soloMe, GenKind.ANSWER, "포스터는 언제까지야?", "ko").artifactId();
		generation.reportArtifact(soloMe, soloAnswer, ReportReason.WRONG, null);

		// 함께 쓰는 프로젝트: 내가 OWNER, 검토자 한 명, 구성원 한 명
		shared = identity.createProject(me, "국제교류 행사");
		reviewer = identity.signUp("rev-" + UUID.randomUUID() + "@contexa.test", "준호", Fixtures.PASSWORD);
		member = identity.signUp("mem-" + UUID.randomUUID() + "@contexa.test", "Sara", Fixtures.PASSWORD);
		identity.setMember(shared, me, member, Role.MEMBER);
		identity.setMember(shared, me, reviewer, Role.REVIEWER);
		Caller sharedMe = Caller.user(me, shared);
		List<NodeRef> transcript = Fixtures.transcript(document, sharedMe,
				"대면으로 개최합니다.", "인쇄 발주는 다음 주에 진행하죠.");
		t1 = transcript.get(0);
		t2 = transcript.get(1);
		List<NodeRef> memo = Fixtures.document(document, sharedMe, Visibility.PRIVATE, false,
				"사회는 Sara가 보기로 하자.", "인쇄 전에 색 교정을 한 번 더.");
		m1 = memo.get(0);
		m2 = memo.get(1);
		memberMemo = Fixtures.document(document, Caller.user(member, shared), Visibility.PRIVATE, false,
				"내 메모: 연사 목록 확인").get(0).id();

		review.submitCandidates(job(shared), new GraphCandidates(List.of(
				new NodeDraft("format", NodeType.DECISION_ITEM, Map.of("title", "개최 방식")),
				event("inPerson", "DECISION", "대면 개최"),
				new NodeDraft("print", NodeType.TASK, Map.of("title", "인쇄 발주")),
				new NodeDraft("proof", NodeType.CONDITION, Map.of("description", "색 교정")),
				new NodeDraft("hostTopic", NodeType.TOPIC, Map.of("title", "사회자")),
				new NodeDraft("hostItem", NodeType.DECISION_ITEM, Map.of("title", "사회자 선정")),
				event("sara", "DECISION", "사회는 Sara"),
				new NodeDraft("saraPerson", NodeType.ENTITY, Map.of("name", "Sara", "kind", "PERSON"))),
				List.of(
						// 공유 문서에서 나온 것
						EdgeDraft.between(RelType.HAS_EVENT, "format", "inPerson", List.of(t1.id())),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "inPerson", t1, List.of(t1.id())),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "print", t2, List.of(t2.id())),
						// 근거가 공유 문서와 메모에 걸친 것
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "print", m2, List.of(m2.id())),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "proof", t2, List.of(t2.id())),
						EdgeDraft.between(RelType.REQUIRES, "print", "proof", List.of(t2.id(), m2.id())),
						// 메모에서만 나온 것
						EdgeDraft.between(RelType.HAS_ITEM, "hostTopic", "hostItem", List.of(m1.id())),
						EdgeDraft.between(RelType.HAS_EVENT, "hostItem", "sara", List.of(m1.id())),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "sara", m1, List.of(m1.id())),
						EdgeDraft.fromExisting(RelType.MENTIONS, m1, "saraPerson", List.of(m1.id())))));
		cards.put("format", cardId("decision_item", "title", "개최 방식"));
		cards.put("inPerson", cardId("decision_event", "statement", "대면 개최"));
		cards.put("print", cardId("task", "title", "인쇄 발주"));
		cards.put("proof", cardId("decision_condition", "description", "색 교정"));
		cards.put("hostTopic", cardId("topic", "title", "사회자"));
		cards.put("hostItem", cardId("decision_item", "title", "사회자 선정"));
		cards.put("sara", cardId("decision_event", "statement", "사회는 Sara"));
		cards.put("saraPerson", cardId("entity", "name", "Sara"));

		myAnswer = generation.requestGeneration(sharedMe, GenKind.ANSWER, "인쇄 진행해도 돼?", "ko").artifactId();
		reviewerAnswer = generation.requestGeneration(Caller.user(reviewer, shared), GenKind.ANSWER,
				"개최 방식은?", "ko").artifactId();
		generation.reportArtifact(sharedMe, myAnswer, ReportReason.UNSUPPORTED, "근거가 안 맞아요");
	}

	@Test
	void 혼자인_프로젝트는_통째로_사라진다() {
		deletion.delete(me, Fixtures.PASSWORD);

		for (String table : List.of("project_member", "document", "span", "job", "artifact", "artifact_report",
				"graph_edge", "task",
				"review_item", "document_acl", "access_token")) {
			assertThat(count("select count(*) from " + table + " where project_id = :p", solo)).as(table).isZero();
		}
		assertThat(count("select count(*) from project where id = :p", solo)).isZero();
	}

	@Test
	void 함께_쓰는_프로젝트에서는_개인_자료만_지우고_나간다() {
		deletion.delete(me, Fixtures.PASSWORD);

		// 나는 나가고, 가장 먼저 들어온 검토자가 OWNER가 된다
		assertThat(identity.roleOf(shared, me)).isEmpty();
		assertThat(identity.roleOf(shared, reviewer)).contains(Role.OWNER);
		assertThat(identity.roleOf(shared, member)).contains(Role.MEMBER);

		// 공유 문서와 그 발언은 남고, 올린 사람은 "탈퇴한 사용자"로 보인다
		assertThat(spanExists(t1.id())).isTrue();
		assertThat(jdbc.sql("""
				select u.display_name from document d join app_user u on u.id = d.created_by
				 join span s on s.document_id = d.id where s.id = :span
				""").param("span", t1.id()).query(String.class).single()).isEqualTo(IdentityAccess.DELETED_NAME);

		// 내 개인 메모는 지우고, 다른 사람의 개인 메모는 남는다
		assertThat(spanExists(m1.id())).isFalse();
		assertThat(spanExists(m2.id())).isFalse();
		assertThat(spanExists(memberMemo)).isTrue();

		// 메모에서만 나온 카드는 지우고, 공유 문서에 근거가 있는 카드는 남는다
		assertThat(cardExists("decision_event", cards.get("sara"))).isFalse();
		assertThat(cardExists("decision_item", cards.get("hostItem"))).isFalse();
		assertThat(cardExists("topic", cards.get("hostTopic"))).isFalse();
		assertThat(count("select count(*) from entity where id = :p", cards.get("saraPerson"))).isZero();
		assertThat(cardExists("decision_event", cards.get("inPerson"))).isTrue();
		assertThat(cardExists("decision_item", cards.get("format"))).isTrue();
		assertThat(cardExists("task", cards.get("print"))).isTrue();
		assertThat(cardExists("decision_condition", cards.get("proof"))).isTrue();

		// 지운 발언을 가리키는 줄과 근거는 남지 않는다
		assertThat(count("""
				select count(*) from graph_edge
				 where project_id = :p and (src_id in (:spans) or dst_id in (:spans)
				       or evidence_span_ids && array[cast(:m1 as uuid), cast(:m2 as uuid)])
				""", shared)).isZero();
		assertThat(jdbc.sql("""
				select evidence_span_ids from graph_edge where project_id = :p and rel_type = 'REQUIRES'
				""").param("p", shared).query((rs, n) -> List.of((UUID[]) rs.getArray(1).getArray())).single())
				.containsExactly(t2.id());
		assertThat(count("select count(*) from review_item where project_id = :p and owner_id in (:owner)", shared))
				.isEqualTo(4);

		// 내 질문은 지우고, 검토자의 질문은 남는다
		assertThat(count("select count(*) from artifact where id = :p", myAnswer)).isZero();
		assertThat(count("select count(*) from artifact where id = :p", reviewerAnswer)).isEqualTo(1);
		assertThat(count("select count(*) from job where subject_id = :p", myAnswer)).isZero();
	}

	@Test
	void 지운_계정으로는_로그인할_수_없고_같은_이메일로_다시_가입할_수_있다() {
		String token = identity.login(email, Fixtures.PASSWORD);

		deletion.delete(me, Fixtures.PASSWORD);

		assertThatThrownBy(() -> identity.login(email, Fixtures.PASSWORD))
				.isInstanceOf(UnauthenticatedException.class);
		assertThatThrownBy(() -> identity.authenticateUser(token)).isInstanceOf(UnauthenticatedException.class);
		assertThat(identity.profileOf(me).displayName()).isEqualTo(IdentityAccess.DELETED_NAME);
		assertThat(identity.profileOf(me).email()).doesNotContain(email);
		identity.signUp(email, "민지", Fixtures.PASSWORD);
	}

	@Test
	void 비밀번호가_틀리면_아무것도_지우지_않는다() {
		assertThatThrownBy(() -> deletion.delete(me, "wrong-password")).isInstanceOf(IllegalArgumentException.class);

		assertThat(identity.roleOf(solo, me)).contains(Role.OWNER);
		assertThat(spanExists(m1.id())).isTrue();
	}

	@Test
	void 앱은_비밀번호를_보내_계정을_지우고_그_토큰은_더_쓸_수_없다() throws Exception {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
		String token = identity.login(email, Fixtures.PASSWORD);

		mvc.perform(post("/api/v1/auth/me/delete").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"wrong-password\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_REQUEST"));
		mvc.perform(post("/api/v1/auth/me/delete").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + Fixtures.PASSWORD + "\"}"))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	private Caller job(UUID projectId) {
		return new Caller(Caller.Kind.JOB, UUID.randomUUID(), projectId, me, Caller.Audience.INTERNAL);
	}

	private static NodeDraft event(String key, String kind, String statement) {
		Map<String, Object> fields = new HashMap<>();
		fields.put("statement", statement);
		fields.put("kind", kind);
		return new NodeDraft(key, NodeType.DECISION_EVENT, fields);
	}

	private UUID cardId(String table, String column, String text) {
		return jdbc.sql("select id from " + table + " where project_id = :p and " + column + " = :text")
				.param("p", shared).param("text", text).query(UUID.class).single();
	}

	private boolean spanExists(UUID spanId) {
		return count("select count(*) from span where id = :p", spanId) == 1;
	}

	private boolean cardExists(String table, UUID id) {
		return count("select count(*) from " + table + " where id = :p", id) == 1;
	}

	private long count(String sql, UUID p) {
		var spec = jdbc.sql(sql).param("p", p);
		if (sql.contains(":spans")) {
			spec = spec.param("spans", List.of(m1.id(), m2.id())).param("m1", m1.id().toString())
					.param("m2", m2.id().toString());
		}
		if (sql.contains(":owner")) {
			spec = spec.param("owner", List.of(cards.get("format"), cards.get("inPerson"), cards.get("print"),
					cards.get("proof"), cards.get("saraPerson"), cards.get("sara"), cards.get("hostItem"),
					cards.get("hostTopic")));
		}
		return spec.query(Long.class).single();
	}
}
