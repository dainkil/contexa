package com.contexa.server.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.contexa.server.Fixtures;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.Role;
import com.jayway.jsonpath.JsonPath;

/**
 * "인쇄 진행해도 돼?"를 HTTP로 끝까지 따라간다. 예시 데이터(국제교류 행사) 위에서 돈다.
 * AI 인턴이 부를 주소를 테스트가 대신 부른다. 실제 모델은 부르지 않는다.
 *
 * <pre>
 *   사람: 질문 → 일꾼: 글쓰기 작업 가져감 → 주문서 읽음 → 근거 검색 → 줄 따라가기 → 글 제출
 *        → 서버: 참고한 것이 그대로인지, 인용이 실제로 있는지 검사하고 보관 → 사람: 답변 열기
 * </pre>
 */
@SpringBootTest
@Transactional
class AnswerFlowTest {

	static final String WORKER = "dev-worker-token";
	static final String ANSWER = "예산 승인 전이라 인쇄는 보류입니다 [1].";

	@Autowired
	WebApplicationContext context;

	@Autowired
	IdentityAccess identity;

	@Autowired
	JdbcClient jdbc;

	MockMvc mvc;
	String seeded;
	String base;
	String userToken;
	String artifactId;
	String lease;
	String jobId;

	@BeforeEach
	void 질문하고_일꾼이_글쓰기_작업을_가져간다() throws Exception {
		jdbc.sql("update job set status = 'CANCELLED' where status in ('QUEUED', 'RUNNING')").update();
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
		seeded = mvc.perform(post("/dev/seed")).andReturn().getResponse().getContentAsString();
		base = "/api/v1/projects/" + JsonPath.read(seeded, "$.projectId");
		userToken = JsonPath.read(seeded, "$.token");

		String asked = mvc.perform(asUser(post(base + "/generations")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"인쇄 진행해도 돼?\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		artifactId = JsonPath.read(asked, "$.artifactId");
		mvc.perform(asUser(get(base + "/generations/" + artifactId)))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.content").doesNotExist());

		lease = mvc.perform(post("/internal/v1/jobs/claim").header("X-Worker-Token", WORKER)
				.contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"test-worker\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		jobId = JsonPath.read(lease, "$.job.id");
		assertThat((String) JsonPath.read(lease, "$.job.type")).isEqualTo("GENERATE");
	}

	@Test
	void 질문하면_근거가_달린_답변이_만들어지고_참고한_카드가_바뀌면_다시_확인_필요로_나온다() throws Exception {
		// 일꾼이 주문서를 읽고, 근거를 찾고, 줄을 따라간다
		mvc.perform(leased(get("/internal/v1/jobs/" + jobId + "/request")))
				.andExpect(jsonPath("$.question").value("인쇄 진행해도 돼?"))
				.andExpect(jsonPath("$.kind").value("ANSWER"))
				.andExpect(jsonPath("$.lang").doesNotExist());
		mvc.perform(leased(post("/internal/v1/jobs/" + jobId + "/context/search"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"인쇄 진행해도 돼?\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sources[*].text", hasItem("예산 승인 전까지 인쇄는 보류입니다.")))
				.andExpect(jsonPath("$.facts[*].label", hasItems("인쇄 예산 승인", "인쇄 발주")));
		String trace = trace();

		// 글을 낸다. 서버가 검사하고 보관한다
		mvc.perform(leased(post("/internal/v1/jobs/" + jobId + "/result"))
				.contentType(MediaType.APPLICATION_JSON).content(draft(trace, citation(trace))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUCCEEDED"));

		// 요청한 사람이 답변을 연다
		mvc.perform(asUser(get(base + "/generations/" + artifactId)))
				.andExpect(jsonPath("$.status").value("PUBLISHED"))
				.andExpect(jsonPath("$.content").value(ANSWER))
				.andExpect(jsonPath("$.citations[0].text").value("예산 승인 전까지 인쇄는 보류입니다."))
				.andExpect(jsonPath("$.stale", hasSize(0)));
		mvc.perform(asUser(get(base + "/generations"))).andExpect(jsonPath("$[0].published").value(true));

		// 조건이 바뀌면 이 답변이 영향 목록에 나온다
		String budget = JsonPath.read(seeded, "$.nodes.budget.id");
		mvc.perform(asUser(get(base + "/impacts")).param("type", "CONDITION").param("id", budget))
				.andExpect(jsonPath("$[?(@.affected.type == 'ARTIFACT')].affected.id", hasItem(artifactId)));

		// 예산이 승인되면(조건 충족) 답변은 "다시 확인 필요"가 된다
		String span = JsonPath.read(seeded, "$.nodes.span40.id");
		mvc.perform(asUser(post(base + "/conditions/" + budget + "/satisfied"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"by\":{\"type\":\"SPAN\",\"id\":\"%s\",\"revision\":1}}".formatted(span)))
				.andExpect(status().isOk());
		mvc.perform(asUser(get(base + "/generations/" + artifactId)))
				.andExpect(jsonPath("$.stale[*].node.id", hasItem(budget)))
				.andExpect(jsonPath("$.content").value(ANSWER));
	}

	@Test
	void 글을_쓰는_사이에_참고한_카드가_바뀌면_받지_않고_다시_읽어서_내면_받는다() throws Exception {
		String before = trace();
		String print = JsonPath.read(seeded, "$.nodes.print.id");
		int revision = JsonPath.read(seeded, "$.nodes.print.revision");
		mvc.perform(asUser(post(base + "/tasks/" + print + "/status")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ON_HOLD\",\"expectedRevision\":%d}".formatted(revision)))
				.andExpect(status().isOk());

		mvc.perform(leased(post("/internal/v1/jobs/" + jobId + "/result"))
				.contentType(MediaType.APPLICATION_JSON).content(draft(before, citation(before))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("바뀌었다")));
		mvc.perform(asUser(get(base + "/generations/" + artifactId)))
				.andExpect(jsonPath("$.status").value("PENDING"));

		String after = trace();
		mvc.perform(leased(post("/internal/v1/jobs/" + jobId + "/result"))
				.contentType(MediaType.APPLICATION_JSON).content(draft(after, citation(after))))
				.andExpect(status().isOk());
	}

	@Test
	void 없는_발언을_인용했거나_본문이_비었으면_받지_않는다() throws Exception {
		String trace = trace();
		String ghost = "[{\"type\":\"SPAN\",\"id\":\"%s\",\"revision\":1}]".formatted(UUID.randomUUID());

		mvc.perform(leased(post("/internal/v1/jobs/" + jobId + "/result"))
				.contentType(MediaType.APPLICATION_JSON).content(draft(trace, ghost)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("인용")));
		mvc.perform(leased(post("/internal/v1/jobs/" + jobId + "/result"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"draft\":{\"content\":\" \",\"usedNodes\":[],\"usedEdges\":[],\"citations\":[]}}"))
				.andExpect(status().isBadRequest());
		mvc.perform(leased(post("/internal/v1/jobs/" + jobId + "/result"))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 인용이_없거나_본문의_번호가_인용을_벗어난_답은_받지_않는다() throws Exception {
		String trace = trace();

		// 사실을 말하면서 인용을 하나도 달지 않았다
		mvc.perform(leased(post("/internal/v1/jobs/" + jobId + "/result"))
				.contentType(MediaType.APPLICATION_JSON).content(draft(trace, "[]")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("인용이 하나도 없는")));
		// 본문에 [2]가 있는데 인용은 하나뿐이다
		mvc.perform(leased(post("/internal/v1/jobs/" + jobId + "/result"))
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"draft":{"content":"인쇄는 보류입니다 [1]. 담당은 민지입니다 [2].","citations":%s}}
						""".formatted(citation(trace))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("[2]")));
		mvc.perform(asUser(get(base + "/generations/" + artifactId)))
				.andExpect(jsonPath("$.status").value("PENDING"));
	}

	@Test
	void 기록에서_찾지_못했다는_짧은_답만_인용_없이_받는다() throws Exception {
		String notFound = "기록에서 이 질문에 답할 근거를 찾지 못했습니다.";

		// 근거 없음이라면서 인용·참고한 카드를 붙이거나 길게 쓰면 받지 않는다
		String trace = trace();
		mvc.perform(leased(post("/internal/v1/jobs/" + jobId + "/result"))
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"draft":{"content":"%s","citations":%s,"noEvidence":true}}
						""".formatted(notFound, citation(trace))))
				.andExpect(status().isBadRequest());
		mvc.perform(leased(post("/internal/v1/jobs/" + jobId + "/result"))
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"draft":{"content":"%s","noEvidence":true}}
						""".formatted("가".repeat(301))))
				.andExpect(status().isBadRequest());

		mvc.perform(leased(post("/internal/v1/jobs/" + jobId + "/result"))
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"draft":{"content":"%s","usedNodes":[],"usedEdges":[],"citations":[],"noEvidence":true}}
						""".formatted(notFound)))
				.andExpect(status().isOk());
		mvc.perform(asUser(get(base + "/generations/" + artifactId)))
				.andExpect(jsonPath("$.status").value("PUBLISHED"))
				.andExpect(jsonPath("$.content").value(notFound))
				.andExpect(jsonPath("$.citations", hasSize(0)));
	}

	@Test
	void 답변은_요청한_사람만_읽고_일꾼이_실패하면_실패로_보인다() throws Exception {
		UUID ownerId = identity.authenticateUser(userToken);
		String email = "other-" + UUID.randomUUID() + "@contexa.test";
		UUID other = identity.signUp(email, "다른 구성원", Fixtures.PASSWORD);
		identity.setMember(UUID.fromString(JsonPath.read(seeded, "$.projectId")), ownerId, other, Role.REVIEWER);
		String otherToken = identity.login(email, Fixtures.PASSWORD);

		mvc.perform(get(base + "/generations/" + artifactId).header("Authorization", "Bearer " + otherToken))
				.andExpect(status().isNotFound());
		mvc.perform(get(base + "/generations").header("Authorization", "Bearer " + otherToken))
				.andExpect(jsonPath("$", hasSize(0)));

		mvc.perform(leased(post("/internal/v1/jobs/" + jobId + "/failure"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"모델이 답하기를 거절했다\",\"retryable\":false}"))
				.andExpect(status().isOk());
		mvc.perform(asUser(get(base + "/generations/" + artifactId)))
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.failure").value("모델이 답하기를 거절했다"));
	}

	@Test
	void 남의_답변은_영향_목록에_나오지_않는다() throws Exception {
		String trace = trace();
		mvc.perform(leased(post("/internal/v1/jobs/" + jobId + "/result"))
				.contentType(MediaType.APPLICATION_JSON).content(draft(trace, citation(trace))))
				.andExpect(status().isOk());
		UUID ownerId = identity.authenticateUser(userToken);
		String email = "other-" + UUID.randomUUID() + "@contexa.test";
		UUID other = identity.signUp(email, "다른 구성원", Fixtures.PASSWORD);
		identity.setMember(UUID.fromString(JsonPath.read(seeded, "$.projectId")), ownerId, other, Role.REVIEWER);
		String otherToken = identity.login(email, Fixtures.PASSWORD);
		String budget = JsonPath.read(seeded, "$.nodes.budget.id");

		mvc.perform(asUser(get(base + "/impacts")).param("type", "CONDITION").param("id", budget))
				.andExpect(jsonPath("$[*].affected.id", hasItem(artifactId)));
		mvc.perform(get(base + "/impacts").header("Authorization", "Bearer " + otherToken)
				.param("type", "CONDITION").param("id", budget))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].affected.type", hasItem("TASK")))
				.andExpect(jsonPath("$[*].affected.id", not(hasItem(artifactId))));
	}

	@Test
	void 질문이_비었거나_지원하지_않는_언어면_받지_않는다() throws Exception {
		mvc.perform(asUser(post(base + "/generations")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"  \"}")).andExpect(status().isBadRequest());
		mvc.perform(asUser(post(base + "/generations")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"인쇄?\",\"lang\":\"fr\"}")).andExpect(status().isBadRequest());
		mvc.perform(post(base + "/generations").contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"인쇄?\"}")).andExpect(status().isUnauthorized());
	}

	// ---- 도우미 ----

	private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request) {
		return request.header("Authorization", "Bearer " + userToken);
	}

	private MockHttpServletRequestBuilder leased(MockHttpServletRequestBuilder request) {
		return request.header("X-Worker-Token", WORKER)
				.header("X-Attempt-Id", (String) JsonPath.read(lease, "$.attemptId"))
				.header("X-Lease-Token", (String) JsonPath.read(lease, "$.leaseToken"));
	}

	/** 일꾼이 "인쇄 발주"에서 근거 쪽으로 두 칸 따라가 읽은 답변서. */
	private String trace() throws Exception {
		return mvc.perform(leased(get("/internal/v1/jobs/" + jobId + "/context/trace"))
				.param("type", "TASK").param("id", (String) JsonPath.read(seeded, "$.nodes.print.id")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.facts[*].label", hasItems("인쇄 발주", "인쇄 예산 승인")))
				.andReturn().getResponse().getContentAsString();
	}

	/** 답변서에서 "예산 승인 전까지…" 발언의 참조를 꺼낸다. */
	private static String citation(String packet) {
		return JsonPath.read(packet, "$.sources[?(@.text == '예산 승인 전까지 인쇄는 보류입니다.')].span").toString();
	}

	/** 답변서에 담긴 카드와 줄을 모두 참고했다고 적은 글. */
	private static String draft(String packet, String citations) {
		Object nodes = JsonPath.read(packet, "$.facts[*].node");
		Object edges = JsonPath.read(packet, "$.relations[*]['id','version']");
		return """
				{"draft":{"content":"%s","usedNodes":%s,"usedEdges":%s,"citations":%s}}
				""".formatted(ANSWER, nodes, edges, citations);
	}
}
