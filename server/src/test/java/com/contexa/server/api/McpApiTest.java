package com.contexa.server.api;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

/**
 * 외부 AI 에이전트가 MCP로 들어오는 길을 확인한다. 예시 데이터(국제교류 행사) 위에서 돈다.
 * 에이전트가 보낼 JSON-RPC 요청을 테스트가 대신 보낸다.
 */
@SpringBootTest
@Transactional
class McpApiTest {

	@Autowired
	WebApplicationContext context;

	MockMvc mvc;
	String seeded;
	String base;
	String ownerToken;
	/** 구성원 공개 문서까지 읽는 에이전트. 팀이 직접 쓰는 AI 도구에 준다. */
	String internalAgent;
	/** 외부 공유가 허용된 문서의 내용만 읽는 에이전트(기본값). */
	String externalAgent;

	@BeforeEach
	void OWNER가_에이전트_토큰을_발급한다() throws Exception {
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
		seeded = mvc.perform(post("/dev/seed")).andReturn().getResponse().getContentAsString();
		base = "/api/v1/projects/" + JsonPath.read(seeded, "$.projectId");
		ownerToken = JsonPath.read(seeded, "$.token");
		internalAgent = JsonPath.read(mvc.perform(post(base + "/agent-tokens")
				.header("Authorization", "Bearer " + ownerToken).contentType(MediaType.APPLICATION_JSON)
				.content("{\"audience\":\"INTERNAL\"}")).andReturn().getResponse().getContentAsString(), "$.token");
		externalAgent = JsonPath.read(mvc.perform(post(base + "/agent-tokens")
				.header("Authorization", "Bearer " + ownerToken)).andReturn().getResponse().getContentAsString(),
				"$.token");
	}

	// ---- 프로토콜 ----

	@Test
	void 처음_인사하면_서버_정보와_쓸_수_있는_기능을_알려준다() throws Exception {
		rpc(internalAgent, """
				{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
				 "capabilities":{},"clientInfo":{"name":"test-agent","version":"1"}}}""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.jsonrpc").value("2.0"))
				.andExpect(jsonPath("$.id").value(1))
				.andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"))
				.andExpect(jsonPath("$.result.serverInfo.name").value("contexa"))
				.andExpect(jsonPath("$.result.capabilities.tools").exists())
				.andExpect(jsonPath("$.result.instructions", containsString("AI 후보")));
		// 서버가 모르는 버전을 요청하면 서버가 아는 가장 최신 버전으로 답한다
		rpc(internalAgent, """
				{"jsonrpc":"2.0","id":"a","method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")
				.andExpect(jsonPath("$.id").value("a"))
				.andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"));
		// 알림에는 답하지 않는다
		rpc(internalAgent, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
				.andExpect(status().isAccepted());
		rpc(internalAgent, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}")
				.andExpect(jsonPath("$.result").isMap());
	}

	@Test
	void 도구_목록에는_읽기_도구와_후보_제안만_있고_확인하는_도구는_없다() throws Exception {
		rpc(internalAgent, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
				.andExpect(jsonPath("$.result.tools[*].name", containsInAnyOrder("current_state", "decision_history",
						"trace", "search_evidence", "tasks", "resolve_entity", "verify_current", "propose_candidates")))
				.andExpect(jsonPath("$.result.tools[?(@.name == 'trace')].inputSchema.required[*]",
						containsInAnyOrder("type", "id")))
				.andExpect(jsonPath("$.result.tools[?(@.name == 'trace')].inputSchema.type").value("object"));
	}

	@Test
	void 토큰이_없거나_사람의_토큰이거나_브라우저의_다른_사이트면_들어올_수_없다() throws Exception {
		String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

		mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(ping))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer"));
		rpc(ownerToken, ping).andExpect(status().isUnauthorized());
		rpc("made-up-token", ping).andExpect(status().isUnauthorized());
		mvc.perform(post("/mcp").header("Authorization", "Bearer " + internalAgent)
				.header("Origin", "https://evil.example").contentType(MediaType.APPLICATION_JSON).content(ping))
				.andExpect(status().isForbidden());
		mvc.perform(get("/mcp").header("Authorization", "Bearer " + internalAgent))
				.andExpect(status().isMethodNotAllowed());
	}

	@Test
	void 잘못된_요청은_JSON_RPC_오류로_알린다() throws Exception {
		rpc(internalAgent, "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value(-32600));
		rpc(internalAgent, "{\"id\":1,\"method\":\"ping\"}").andExpect(status().isBadRequest());
		rpc(internalAgent, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.error.code").value(-32601));
		rpc(internalAgent, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"confirm\"}}")
				.andExpect(jsonPath("$.error.code").value(-32602));
		mvc.perform(post("/mcp").header("Authorization", "Bearer " + internalAgent)
				.header("MCP-Protocol-Version", "1999-01-01").contentType(MediaType.APPLICATION_JSON)
				.content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
				.andExpect(status().isBadRequest());
	}

	// ---- 읽기 도구 ----

	@Test
	void 에이전트가_근거를_찾고_줄을_따라가면_화면과_같은_답변서를_받는다() throws Exception {
		call(internalAgent, "search_evidence", "{\"query\":\"인쇄 진행해도 돼?\"}")
				.andExpect(jsonPath("$.result.isError").value(false))
				.andExpect(jsonPath("$.result.structuredContent.scope.view").value("SEARCH_EVIDENCE"))
				.andExpect(jsonPath("$.result.structuredContent.sources[*].text",
						hasItem("예산 승인 전까지 인쇄는 보류입니다.")))
				.andExpect(jsonPath("$.result.content[0].type").value("text"))
				.andExpect(jsonPath("$.result.content[0].text", containsString("예산 승인 전까지 인쇄는 보류입니다.")));

		String print = JsonPath.read(seeded, "$.nodes.print.id");
		call(internalAgent, "trace", "{\"type\":\"TASK\",\"id\":\"%s\"}".formatted(print))
				.andExpect(jsonPath("$.result.structuredContent.facts[*].label", hasItems("인쇄 발주", "인쇄 예산 승인")))
				.andExpect(jsonPath("$.result.structuredContent.facts[?(@.label == '아랍어 문구 검토')].status")
						.value("CANDIDATE"))
				.andExpect(jsonPath("$.result.structuredContent.open[*].kind", hasItem("UNMET_CONDITION")));
		call(internalAgent, "current_state", "{}")
				.andExpect(jsonPath("$.result.structuredContent.open[*].kind", hasItem("CONTESTED_DECISION")));
		call(internalAgent, "decision_history",
				"{\"decisionItemId\":\"%s\"}".formatted((String) JsonPath.read(seeded, "$.nodes.format.id")))
				.andExpect(jsonPath("$.result.structuredContent.facts[1].label").value("온라인 개최 제안"));
		call(internalAgent, "tasks", "{\"status\":\"DONE\"}")
				.andExpect(jsonPath("$.result.structuredContent.facts[*].label", hasItem("장소 예약")));
		call(internalAgent, "resolve_entity", "{\"surface\":\"아흐마드\"}")
				.andExpect(jsonPath("$.result.structuredContent.open[*].kind", hasItem("AMBIGUOUS_NAME")));
		call(internalAgent, "verify_current",
				"{\"nodes\":[{\"type\":\"TASK\",\"id\":\"%s\",\"revision\":1}],\"edges\":[]}".formatted(print))
				.andExpect(jsonPath("$.result.structuredContent.stale", hasSize(1)));
	}

	@Test
	void 외부용_토큰으로는_외부_공유가_허용되지_않은_문서에서_나온_내용이_보이지_않는다() throws Exception {
		String print = JsonPath.read(seeded, "$.nodes.print.id");

		call(externalAgent, "trace", "{\"type\":\"TASK\",\"id\":\"%s\"}".formatted(print))
				.andExpect(jsonPath("$.result.isError").value(true))
				.andExpect(jsonPath("$.result.content[0].text", containsString("카드가 없다")));
		call(externalAgent, "search_evidence", "{\"query\":\"인쇄\"}")
				.andExpect(jsonPath("$.result.structuredContent.sources", hasSize(0)));
		call(externalAgent, "current_state", "{}")
				.andExpect(jsonPath("$.result.structuredContent.facts[*].label", not(hasItem("인쇄 발주"))))
				.andExpect(jsonPath("$.result.structuredContent.sources", hasSize(0)));
	}

	@Test
	void 도구에_준_값이_틀리면_도구의_오류로_알려서_에이전트가_고쳐_부를_수_있게_한다() throws Exception {
		call(internalAgent, "trace", "{\"type\":\"TASK\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.isError").value(true))
				.andExpect(jsonPath("$.result.content[0].text", containsString("id")));
		call(internalAgent, "trace", "{\"type\":\"TASK\",\"id\":\"%s\",\"depth\":99}".formatted(UUID.randomUUID()))
				.andExpect(jsonPath("$.result.isError").value(true));
		call(internalAgent, "search_evidence", "{\"query\":\" \"}")
				.andExpect(jsonPath("$.result.isError").value(true));
	}

	// ---- 쓰기는 후보 제안뿐 ----

	@Test
	void 에이전트가_제안한_것은_AI_후보로_검토함에_들어가고_에이전트는_확인할_수_없다() throws Exception {
		String span = JsonPath.read(seeded, "$.nodes.span23.id");
		String print = JsonPath.read(seeded, "$.nodes.print.id");
		int revision = JsonPath.read(seeded, "$.nodes.print.revision");

		String proposed = call(internalAgent, "propose_candidates", """
				{"nodes":[{"key":"quote","type":"TASK","fields":{"title":"인쇄소 견적 받기"}}],
				 "edges":[{"rel":"EVIDENCED_BY","srcKey":"quote","dstRef":{"type":"SPAN","id":"%s","revision":1},
				           "evidenceSpanIds":["%s"]},
				          {"rel":"REQUIRES","srcRef":{"type":"TASK","id":"%s","revision":%d},"dstKey":"quote",
				           "evidenceSpanIds":["%s"]}]}""".formatted(span, span, print, revision, span))
				.andExpect(jsonPath("$.result.isError").value(false))
				.andExpect(jsonPath("$.result.structuredContent.reviewIds", hasSize(2)))
				.andReturn().getResponse().getContentAsString();
		String reviewId = JsonPath.read(proposed, "$.result.structuredContent.reviewIds[0]");

		mvc.perform(get(base + "/reviews").header("Authorization", "Bearer " + ownerToken))
				.andExpect(jsonPath("$.items[?(@.card.label == '인쇄소 견적 받기')].card.status").value("CANDIDATE"));
		// 에이전트 토큰으로는 정문으로 돌아가도 확인할 수 없다
		mvc.perform(post(base + "/reviews/" + reviewId + "/actions").header("Authorization", "Bearer " + internalAgent)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"card\":\"CONFIRM\",\"expectedVersion\":1,\"idempotencyKey\":\"agent\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void 근거_없는_제안과_읽을_수_없는_발언을_근거로_댄_제안은_받지_않는다() throws Exception {
		String span = JsonPath.read(seeded, "$.nodes.span23.id");
		String withSpan = """
				{"nodes":[{"key":"quote","type":"TASK","fields":{"title":"견적"}}],
				 "edges":[{"rel":"EVIDENCED_BY","srcKey":"quote","dstRef":{"type":"SPAN","id":"%s","revision":1},
				           "evidenceSpanIds":%s}]}""";

		call(internalAgent, "propose_candidates", withSpan.formatted(span, "[]"))
				.andExpect(jsonPath("$.result.isError").value(true))
				.andExpect(jsonPath("$.result.content[0].text", containsString("근거")));
		// 외부용 토큰은 이 발언이 든 문서를 읽을 수 없다
		call(externalAgent, "propose_candidates", withSpan.formatted(span, "[\"" + span + "\"]"))
				.andExpect(jsonPath("$.result.isError").value(true));
		mvc.perform(get(base + "/reviews").header("Authorization", "Bearer " + ownerToken))
				.andExpect(jsonPath("$.items[*].card.label", not(hasItem("견적"))));
	}

	// ---- 도우미 ----

	private ResultActions rpc(String token, String body) throws Exception {
		return mvc.perform(post("/mcp").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions call(String token, String tool, String arguments) throws Exception {
		return rpc(token, """
				{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"%s","arguments":%s}}
				""".formatted(tool, arguments));
	}
}
