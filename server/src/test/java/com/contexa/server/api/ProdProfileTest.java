package com.contexa.server.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.contexa.server.dev.DevSeedController;
import com.contexa.server.identity.IdentityAccess;

/**
 * 배포 설정(prod)으로 켰을 때: 개발용 주소와 페이지가 닫히고, 허용한 이메일만 가입하고,
 * 로그인을 너무 많이 틀리면 막히고, 하루 AI 작업 수를 넘기면 거절한다.
 */
@SpringBootTest(properties = {
		"CONTEXA_DB_URL=jdbc:postgresql://localhost:5434/contexa", "CONTEXA_DB_USER=contexa",
		"CONTEXA_DB_PASSWORD=contexa",
		"CONTEXA_WORKER_TOKEN=0123456789abcdef0123456789abcdef0123456789abcdef",
		"CONTEXA_ALLOWED_EMAILS=invited@contexa.test, Second@contexa.test", "CONTEXA_AI_DAILY_LIMIT=1" })
@ActiveProfiles("prod")
@Transactional
class ProdProfileTest {

	static final String PASSWORD = "prod-test-password";

	@Autowired
	WebApplicationContext context;

	@Autowired
	IdentityAccess identity;

	@Autowired
	JdbcClient jdbc;

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
	}

	@Test
	void 개발용_주소와_페이지는_없다() throws Exception {
		assertThat(context.getBeanNamesForType(DevSeedController.class)).isEmpty();
		mvc.perform(post("/dev/demo")).andExpect(status().isNotFound());
		// "/"는 index.html로 넘기기만 한다(MockMvc는 넘김을 따라가지 않는다). 넘겨받는 쪽이 404여야 한다
		String forwarded = mvc.perform(get("/")).andReturn().getResponse().getForwardedUrl();
		if (forwarded != null) {
			mvc.perform(get("/" + forwarded)).andExpect(status().isNotFound());
		}
		mvc.perform(get("/index.html")).andExpect(status().isNotFound());
	}

	@Test
	void 허용한_이메일만_가입한다() throws Exception {
		mvc.perform(signUp("stranger@contexa.test")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("FORBIDDEN"));
		mvc.perform(signUp("second@contexa.test")).andExpect(status().isOk());
	}

	@Test
	void 로그인을_다섯_번_틀리면_잠시_막는다() throws Exception {
		String email = "throttle-" + UUID.randomUUID() + "@contexa.test";
		for (int i = 0; i < 5; i++) {
			mvc.perform(login(email, "wrong-password")).andExpect(status().isUnauthorized());
		}
		mvc.perform(login(email, "wrong-password")).andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.error").value("TOO_MANY_ATTEMPTS"));
	}

	@Test
	void 하루_AI_작업_수를_넘기면_질문을_받지_않는다() throws Exception {
		jdbc.sql("update job set status = 'CANCELLED' where status in ('QUEUED', 'RUNNING')").update();
		String email = "quota-" + UUID.randomUUID() + "@contexa.test";
		UUID userId = identity.signUp(email, "민지", PASSWORD);
		UUID projectId = identity.createProject(userId, "한도 확인");
		String token = identity.login(email, PASSWORD);
		String ask = "{\"question\":\"인쇄 진행해도 돼?\"}";

		mvc.perform(post("/api/v1/projects/" + projectId + "/generations").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(ask)).andExpect(status().is2xxSuccessful());
		mvc.perform(post("/api/v1/projects/" + projectId + "/generations").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(ask)).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error").value("QUOTA_EXCEEDED"));
	}

	private static org.springframework.test.web.servlet.RequestBuilder signUp(String email) {
		return post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"displayName\":\"민지\",\"password\":\"" + PASSWORD + "\"}");
	}

	private static org.springframework.test.web.servlet.RequestBuilder login(String email, String password) {
		return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
	}
}
