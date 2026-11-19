package com.contexa.server.pipeline;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import com.contexa.server.contract.Caller;
import com.contexa.server.identity.IdentityAccess;
import com.jayway.jsonpath.JsonPath;

/**
 * "문서를 올리면 검토함에 후보가 들어온다"를 HTTP로 끝까지 따라간다.
 * AI 인턴(Python 일꾼)이 부를 주소를 테스트가 대신 부른다. 실제 모델은 부르지 않는다.
 *
 * <pre>
 *   사람: 문서 올리기 → 일꾼: 자르기 작업 가져감 → 문서 읽음 → 발언 제출
 *                     → 일꾼: 뽑기 작업 가져감 → 발언과 기존 카드 읽음 → 후보 제출 → 사람: 검토함
 * </pre>
 */
@SpringBootTest
@Transactional
class IngestFlowTest {

	static final String WORKER = "dev-worker-token";

	@Autowired
	WebApplicationContext context;

	@Autowired
	IdentityAccess identity;

	@Autowired
	JdbcClient jdbc;

	MockMvc mvc;
	String base;
	String userToken;

	@BeforeEach
	void 사용자가_로그인한다() {
		jdbc.sql("update job set status = 'CANCELLED' where status in ('QUEUED', 'RUNNING')").update();
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
		String email = "uploader-" + UUID.randomUUID() + "@contexa.test";
		UUID userId = identity.signUp(email, "민지", Fixtures.PASSWORD);
		base = "/api/v1/projects/" + identity.createProject(userId, "국제교류 행사");
		userToken = identity.login(email, Fixtures.PASSWORD);
	}

	@Test
	void 문서를_올리면_자르기와_뽑기를_거쳐_검토함에_후보가_들어온다() throws Exception {
		// 1. 사람이 문서를 올린다
		String started = mvc.perform(asUser(post(base + "/documents")).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"title":"3차 회의 전사문","kind":"TRANSCRIPT","visibility":"PROJECT","externalOk":false,
						 "content":"민지: 예산 승인 전까지 인쇄는 보류입니다.\\n준호: 인쇄 발주는 다음 주에 진행하죠."}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version.versionNo").value(1))
				.andReturn().getResponse().getContentAsString();
		String parseJob = JsonPath.read(started, "$.jobId");
		mvc.perform(asUser(get(base + "/jobs/" + parseJob))).andExpect(jsonPath("$.status").value("QUEUED"));

		// 2. 일꾼이 자르기 작업을 가져가 문서를 읽고 발언을 낸다
		String lease = claim();
		org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(lease, "$.job.id")).isEqualTo(parseJob);
		mvc.perform(leased(get("/internal/v1/jobs/" + parseJob + "/content"), lease))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.document.title").value("3차 회의 전사문"))
				.andExpect(jsonPath("$.document.kind").value("TRANSCRIPT"))
				.andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.startsWith("민지: 예산 승인")));
		mvc.perform(leased(post("/internal/v1/jobs/" + parseJob + "/result"), lease)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"spans":[{"speakerLabel":"민지","text":"예산 승인 전까지 인쇄는 보류입니다."},
						          {"speakerLabel":"준호","text":"인쇄 발주는 다음 주에 진행하죠."}]}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUCCEEDED"));

		// 3. 뽑기 작업이 이어서 맡겨진다. 일꾼이 발언과 기존 카드를 읽는다
		String extract = claim();
		String extractJob = JsonPath.read(extract, "$.job.id");
		org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(extract, "$.job.type")).isEqualTo("EXTRACT");
		String spans = mvc.perform(leased(get("/internal/v1/jobs/" + extractJob + "/spans"), extract))
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].seq").value(1))
				.andExpect(jsonPath("$[0].speakerLabel").value("민지"))
				.andReturn().getResponse().getContentAsString();
		String span1 = JsonPath.read(spans, "$[0].ref.id");
		String span2 = JsonPath.read(spans, "$[1].ref.id");
		mvc.perform(leased(get("/internal/v1/jobs/" + extractJob + "/context/state"), extract))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.facts", hasSize(0)));

		// 4. 일꾼이 틀린 후보를 내면 이유와 함께 거절되고, 아무것도 저장되지 않는다
		mvc.perform(leased(post("/internal/v1/jobs/" + extractJob + "/result"), extract)
				.contentType(MediaType.APPLICATION_JSON).content(candidates(span1, span2, "CONDITION", "TASK")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("허용되지 않은 줄")));
		mvc.perform(asUser(get(base + "/reviews"))).andExpect(jsonPath("$.items", hasSize(0)));

		// 5. 같은 임대증으로 고친 후보를 다시 낸다
		mvc.perform(leased(post("/internal/v1/jobs/" + extractJob + "/result"), extract)
				.contentType(MediaType.APPLICATION_JSON).content(candidates(span1, span2, "TASK", "CONDITION")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUCCEEDED"));

		// 6. 사람의 검토함에 AI 후보가 들어와 있다. 줄 세운 일은 더 없다
		mvc.perform(asUser(get(base + "/reviews")))
				.andExpect(jsonPath("$.items[*].card.label", hasItems("인쇄 발주", "예산 승인")))
				.andExpect(jsonPath("$.items[*].card.status", hasItem("CANDIDATE")));
		mvc.perform(asUser(get(base + "/context/state")))
				.andExpect(jsonPath("$.facts[*].label", hasItems("인쇄 발주", "예산 승인")))
				.andExpect(jsonPath("$.sources[*].text", hasItem("예산 승인 전까지 인쇄는 보류입니다.")));
		mvc.perform(post("/internal/v1/jobs/claim").header("X-Worker-Token", WORKER)
				.contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"test-worker\"}"))
				.andExpect(status().isNoContent());
		mvc.perform(asUser(get(base + "/jobs"))).andExpect(jsonPath("$[*].status", hasItems("SUCCEEDED")))
				.andExpect(jsonPath("$", hasSize(2)));
	}

	@Test
	void 일꾼_토큰이_없거나_임대증이_틀리면_뒷문으로_들어올_수_없다() throws Exception {
		mvc.perform(asUser(post(base + "/documents")).contentType(MediaType.APPLICATION_JSON).content("""
				{"title":"메모","kind":"MEMO","visibility":"PRIVATE","externalOk":false,"content":"내용"}
				""")).andExpect(status().isOk());

		mvc.perform(post("/internal/v1/jobs/claim").contentType(MediaType.APPLICATION_JSON)
				.content("{\"workerId\":\"w\"}")).andExpect(status().isUnauthorized());
		// 사람의 로그인 토큰은 일꾼 토큰이 아니다
		mvc.perform(post("/internal/v1/jobs/claim").header("X-Worker-Token", userToken)
				.contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"w\"}"))
				.andExpect(status().isUnauthorized());

		String lease = claim();
		String jobId = JsonPath.read(lease, "$.job.id");
		mvc.perform(get("/internal/v1/jobs/" + jobId + "/content").header("X-Worker-Token", WORKER)
				.header("X-Attempt-Id", (String) JsonPath.read(lease, "$.attemptId"))
				.header("X-Lease-Token", "wrong"))
				.andExpect(status().isConflict());
		// 자르기 작업의 임대증으로 빈 결과를 낼 수 없다
		mvc.perform(leased(post("/internal/v1/jobs/" + jobId + "/result"), lease)
				.contentType(MediaType.APPLICATION_JSON).content("{\"spans\":[]}"))
				.andExpect(status().isBadRequest());
		mvc.perform(leased(post("/internal/v1/jobs/" + jobId + "/failure"), lease)
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"자를 수 없는 문서\",\"retryable\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.lastError").value("자를 수 없는 문서"));
	}

	@Test
	void 로그인하지_않으면_문서를_올릴_수_없다() throws Exception {
		mvc.perform(post(base + "/documents").contentType(MediaType.APPLICATION_JSON).content("""
				{"title":"메모","kind":"MEMO","visibility":"PROJECT","externalOk":false,"content":"내용"}
				""")).andExpect(status().isUnauthorized());
	}

	// ---- 도우미 ----

	private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request) {
		return request.header("Authorization", "Bearer " + userToken);
	}

	private String claim() throws Exception {
		return mvc.perform(post("/internal/v1/jobs/claim").header("X-Worker-Token", WORKER)
				.contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"test-worker\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private static MockHttpServletRequestBuilder leased(MockHttpServletRequestBuilder request, String lease) {
		return request.header("X-Worker-Token", WORKER)
				.header("X-Attempt-Id", (String) JsonPath.read(lease, "$.attemptId"))
				.header("X-Lease-Token", (String) JsonPath.read(lease, "$.leaseToken"));
	}

	/** 할 일 "인쇄 발주"와 조건 "예산 승인", 그리고 둘을 잇는 "필요로 한다" 줄. 줄의 방향을 인자로 받는다. */
	private static String candidates(String span1, String span2, String srcType, String dstType) {
		String src = srcType.equals("TASK") ? "print" : "budget";
		String dst = dstType.equals("TASK") ? "print" : "budget";
		return """
				{"candidates":{
				  "nodes":[
				    {"key":"print","type":"TASK","fields":{"title":"인쇄 발주"}},
				    {"key":"budget","type":"CONDITION","fields":{"description":"예산 승인"}}],
				  "edges":[
				    {"rel":"REQUIRES","srcKey":"%s","dstKey":"%s","evidenceSpanIds":["%s"]},
				    {"rel":"EVIDENCED_BY","srcKey":"budget","dstRef":{"type":"SPAN","id":"%s","revision":1},
				     "evidenceSpanIds":["%s"]},
				    {"rel":"EVIDENCED_BY","srcKey":"print","dstRef":{"type":"SPAN","id":"%s","revision":1},
				     "evidenceSpanIds":["%s"]}]}}
				""".formatted(src, dst, span1, span1, span1, span2, span2);
	}
}
