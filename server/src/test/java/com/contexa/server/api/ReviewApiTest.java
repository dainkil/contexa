package com.contexa.server.api;

import static org.hamcrest.Matchers.hasItem;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

/** 검토함을 HTTP로 불러 본다. 예시 데이터에는 아직 아무도 확인하지 않은 "아랍어 문구 검토"가 남아 있다. */
@SpringBootTest
@Transactional
class ReviewApiTest {

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
		base = "/api/v1/projects/" + JsonPath.read(seeded, "$.projectId");
	}

	@Test
	void 검토함에는_확인할_것이_남은_묶음만_나온다() throws Exception {
		mvc.perform(get(base + "/reviews"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].card.label", hasItem("아랍어 문구 검토")))
				.andExpect(jsonPath("$[*].card.label", hasItem("인쇄 발주")))
				.andExpect(jsonPath("$[*].card.label", hasItem("12월 5일로 변경")))
				.andExpect(jsonPath("$[?(@.card.label == '아랍어 문구 검토')].card.status").value("CANDIDATE"));
		mvc.perform(get(base + "/reviews").param("state", "DONE"))
				.andExpect(jsonPath("$[*].card.label", hasItem("인쇄 예산 승인")));
	}

	@Test
	void 묶음을_확인하면_검토함에서_빠지고_같은_요청을_다시_보내도_한_번만_처리된다() throws Exception {
		String reviews = mvc.perform(get(base + "/reviews")).andReturn().getResponse().getContentAsString();
		List<String> ids = JsonPath.read(reviews, "$[?(@.card.label == '아랍어 문구 검토')].id");
		List<Integer> versions = JsonPath.read(reviews, "$[?(@.card.label == '아랍어 문구 검토')].version");
		String body = """
				{"card":"CONFIRM","expectedVersion":%d,"idempotencyKey":"web-1"}
				""".formatted(versions.get(0));

		mvc.perform(post(base + "/reviews/" + ids.get(0) + "/actions").contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.item.state").value("DONE"))
				.andExpect(jsonPath("$.item.card.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.impacts[0].affected.type").value("TASK"));
		mvc.perform(post(base + "/reviews/" + ids.get(0) + "/actions").contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.impacts", hasSize(0)));
		mvc.perform(get(base + "/reviews/" + ids.get(0)))
				.andExpect(jsonPath("$.state").value("DONE"));
	}

	@Test
	void 낡은_버전으로_처리하면_409이고_없는_묶음은_404다() throws Exception {
		String reviews = mvc.perform(get(base + "/reviews")).andReturn().getResponse().getContentAsString();
		List<String> ids = JsonPath.read(reviews, "$[?(@.card.label == '아랍어 문구 검토')].id");

		mvc.perform(post(base + "/reviews/" + ids.get(0) + "/actions").contentType(MediaType.APPLICATION_JSON)
				.content("{\"card\":\"CONFIRM\",\"expectedVersion\":999,\"idempotencyKey\":\"web-2\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("STALE"));
		mvc.perform(post(base + "/reviews/" + UUID.randomUUID() + "/actions").contentType(MediaType.APPLICATION_JSON)
				.content("{\"card\":\"CONFIRM\",\"expectedVersion\":1,\"idempotencyKey\":\"web-3\"}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void 조건을_충족으로_확인하고_할_일_상태를_바꿀_수_있다() throws Exception {
		String budget = JsonPath.read(seeded, "$.nodes.budget.id");
		String span = JsonPath.read(seeded, "$.nodes.span22.id");
		String print = JsonPath.read(seeded, "$.nodes.print.id");
		int revision = JsonPath.read(seeded, "$.nodes.print.revision");

		mvc.perform(post(base + "/conditions/" + budget + "/satisfied").contentType(MediaType.APPLICATION_JSON)
				.content("{\"by\":{\"type\":\"SPAN\",\"id\":\"%s\",\"revision\":1}}".formatted(span)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.impacts[*].affected.id", hasItem(print)));
		mvc.perform(get(base + "/context/trace").param("type", "TASK").param("id", print))
				.andExpect(jsonPath("$.facts[?(@.label == '인쇄 예산 승인')].states.state").value("MET"));

		mvc.perform(post(base + "/tasks/" + print + "/status").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"IN_PROGRESS\",\"expectedRevision\":%d}".formatted(revision)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.node.revision").value(revision + 1));
		mvc.perform(get(base + "/impacts").param("type", "SPAN").param("id", span))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].affected.id", hasItem(budget)));
	}
}
