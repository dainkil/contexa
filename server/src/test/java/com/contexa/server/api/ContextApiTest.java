package com.contexa.server.api;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

/**
 * 정문을 HTTP로 불러 본다. 개발용 예시 데이터(국제교류 행사 장면)를 넣고 답변서 JSON을 확인한다.
 * 테스트마다 트랜잭션을 되돌리므로 DB에 흔적이 남지 않는다.
 */
@SpringBootTest
@Transactional
class ContextApiTest {

	@Autowired
	WebApplicationContext context;

	MockMvc mvc;
	String base;
	String seeded;

	@BeforeEach
	void 예시_데이터를_넣는다() throws Exception {
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
		seeded = mvc.perform(post("/dev/seed")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		String token = JsonPath.read(seeded, "$.token");
		// 이후 요청은 모두 예시 프로젝트의 OWNER로 로그인한 상태로 보낸다
		mvc = MockMvcBuilders.webAppContextSetup(context)
				.defaultRequest(get("/").header("Authorization", "Bearer " + token))
				.build();
		base = "/api/v1/projects/" + JsonPath.read(seeded, "$.projectId") + "/context";
	}

	private String id(String name) {
		return JsonPath.read(seeded, "$.nodes." + name + ".id");
	}

	@Test
	void 현재_상태를_물으면_유효한_결정과_남은_것이_JSON으로_나온다() throws Exception {
		mvc.perform(get(base + "/state"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.scope.view").value("CURRENT_STATE"))
				.andExpect(jsonPath("$.asOf").isString())
				.andExpect(jsonPath("$.facts[*].label", hasItems("국제교류 행사", "개최 방식", "대면 개최 확정",
						"11월 28일 개최", "12월 5일로 변경", "인쇄 발주", "아랍어 문구 검토")))
				.andExpect(jsonPath("$.facts[*].label", not(hasItem("온라인 개최 제안"))))
				.andExpect(jsonPath("$.facts[?(@.label == '개최 방식')].states.decisionState").value("DECIDED"))
				.andExpect(jsonPath("$.facts[?(@.label == '행사 날짜')].states.decisionState").value("CONTESTED"))
				.andExpect(jsonPath("$.facts[?(@.label == '장소 확보')].states.state").value("MET"))
				.andExpect(jsonPath("$.facts[?(@.label == '아랍어 문구 검토')].status").value("CANDIDATE"))
				.andExpect(jsonPath("$.open[*].kind", hasItems("CONTESTED_DECISION", "UNMET_CONDITION")));
	}

	@Test
	void 인쇄_발주가_무엇에_달려_있는지_주소로_물을_수_있다() throws Exception {
		mvc.perform(get(base + "/trace").param("type", "TASK").param("id", id("print"))
				.param("direction", "BASIS").param("depth", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.scope.view").value("TRACE"))
				.andExpect(jsonPath("$.facts[0].label").value("인쇄 발주"))
				.andExpect(jsonPath("$.facts[0].status").value("CONFIRMED"))
				.andExpect(jsonPath("$.facts[0].evidence[0].span.id").value(id("span23")))
				.andExpect(jsonPath("$.facts[*].label", hasItems("인쇄 예산 승인", "아랍어 문구 검토")))
				.andExpect(jsonPath("$.facts[?(@.label == '인쇄 예산 승인')].states.state").value("UNMET"))
				.andExpect(jsonPath("$.relations[*].rel", hasItems("REQUIRES", "EVIDENCED_BY", "ASSIGNED_TO")))
				.andExpect(jsonPath("$.open[0].kind").value("UNMET_CONDITION"));
	}

	@Test
	void 방향과_칸_수를_생략하면_근거_쪽으로_두_칸_간다() throws Exception {
		mvc.perform(get(base + "/trace").param("type", "TASK").param("id", id("print")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.scope.direction").value("BASIS"))
				.andExpect(jsonPath("$.scope.depth").value(2));
	}

	@Test
	void 발언에서_영향_쪽으로_가면_그_발언에_기댄_조건과_할_일과_답변이_나온다() throws Exception {
		mvc.perform(get(base + "/trace").param("type", "SPAN").param("id", id("span22"))
				.param("direction", "IMPACT").param("depth", "3"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.facts[*].label", hasItems("인쇄 예산 승인", "인쇄 발주")))
				.andExpect(jsonPath("$.relations[?(@.rel == 'USES')].src.id").value(id("answer")));
	}

	@Test
	void 결정_이력은_일어난_순서로_나온다() throws Exception {
		mvc.perform(get(base + "/history/" + id("format")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.scope.view").value("DECISION_HISTORY"))
				.andExpect(jsonPath("$.facts[0].label").value("개최 방식"))
				.andExpect(jsonPath("$.facts[1].label").value("온라인 개최 제안"))
				.andExpect(jsonPath("$.facts[2].label").value("교류 활동을 위해 대면 전환 검토"))
				.andExpect(jsonPath("$.facts[3].label").value("대면 개최 확정"))
				.andExpect(jsonPath("$.facts[1].states.kind").value("PROPOSAL"));
	}

	@Test
	void 참고한_카드가_그대로면_빈_목록을_바뀌었으면_그_카드를_돌려준다() throws Exception {
		String print = id("print");
		int revision = JsonPath.read(seeded, "$.nodes.print.revision");

		mvc.perform(post(base + "/verify").contentType(MediaType.APPLICATION_JSON).content("""
				{"nodes":[{"type":"TASK","id":"%s","revision":%d}],"edges":[]}
				""".formatted(print, revision)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));

		mvc.perform(post(base + "/verify").contentType(MediaType.APPLICATION_JSON).content("""
				{"nodes":[{"type":"TASK","id":"%s","revision":1}],"edges":[{"id":"%s","version":1}]}
				""".formatted(print, UUID.randomUUID())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].node.id").value(print))
				.andExpect(jsonPath("$[0].currentVersion").value(revision))
				.andExpect(jsonPath("$[1].currentVersion").doesNotExist());
	}

	@Test
	void 없는_카드는_404다() throws Exception {
		mvc.perform(get(base + "/trace").param("type", "TASK").param("id", UUID.randomUUID().toString()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("NOT_FOUND"));
	}

	@Test
	void 로그인하지_않으면_401이고_구성원이_아닌_프로젝트는_403이다() throws Exception {
		MockMvc anonymous = MockMvcBuilders.webAppContextSetup(context).build();
		anonymous.perform(get(base + "/state"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
		anonymous.perform(get(base + "/state").header("Authorization", "Bearer wrong-token"))
				.andExpect(status().isUnauthorized());

		// 다른 예시 프로젝트를 하나 더 만든다. 내 토큰으로는 그 프로젝트에 들어갈 수 없다
		String other = anonymous.perform(post("/dev/seed")).andReturn().getResponse().getContentAsString();
		mvc.perform(get("/api/v1/projects/" + JsonPath.read(other, "$.projectId") + "/context/state"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("FORBIDDEN"));
		mvc.perform(get("/api/v1/projects/" + UUID.randomUUID() + "/context/state"))
				.andExpect(status().isForbidden());
	}

	@Test
	void 요청_값이_잘못되면_400이다() throws Exception {
		mvc.perform(get(base + "/trace").param("type", "TASK").param("id", id("print")).param("depth", "99"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("BAD_REQUEST"));
		mvc.perform(get(base + "/trace").param("type", "CARD").param("id", id("print")))
				.andExpect(status().isBadRequest());
		mvc.perform(get(base + "/trace").param("type", "TASK"))
				.andExpect(status().isBadRequest());
	}
}
