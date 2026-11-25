package com.contexa.server.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.contexa.server.Fixtures;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.Role;

/** 정문의 로그아웃과 구성원 목록. 테스트마다 트랜잭션을 되돌린다. */
@SpringBootTest
@Transactional
class AuthApiTest {

	@Autowired
	WebApplicationContext context;

	@Autowired
	IdentityAccess identity;

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
	}

	@Test
	void 로그아웃하면_그_토큰은_더_쓸_수_없고_다른_기기의_로그인은_그대로다() throws Exception {
		String email = signUp("나가는 사람");
		String phone = identity.login(email, Fixtures.PASSWORD);
		String tablet = identity.login(email, Fixtures.PASSWORD);
		mvc.perform(as(phone, get("/api/v1/auth/me"))).andExpect(status().isOk());

		mvc.perform(as(phone, post("/api/v1/auth/logout"))).andExpect(status().isNoContent());

		mvc.perform(as(phone, get("/api/v1/auth/me"))).andExpect(status().isUnauthorized());
		// 다시 보내도 같은 답이다(앱이 응답을 못 받고 다시 보낼 수 있다)
		mvc.perform(as(phone, post("/api/v1/auth/logout"))).andExpect(status().isNoContent());
		mvc.perform(as(tablet, get("/api/v1/auth/me"))).andExpect(status().isOk());
		mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
	}

	@Test
	void 구성원_목록은_역할_순서로_나오고_이메일은_OWNER와_본인_것만_보인다() throws Exception {
		String ownerEmail = signUp("주인");
		UUID ownerId = identity.authenticateUser(identity.login(ownerEmail, Fixtures.PASSWORD));
		UUID projectId = identity.createProject(ownerId, "구성원 테스트");
		// 일반 구성원이 먼저 들어와도 검토자가 앞에 나온다
		String memberEmail = signUp("일반 구성원");
		identity.setMember(projectId, ownerId, identity.userIdByEmail(memberEmail).orElseThrow(), Role.MEMBER);
		String reviewerEmail = signUp("검토자");
		identity.setMember(projectId, ownerId, identity.userIdByEmail(reviewerEmail).orElseThrow(), Role.REVIEWER);
		String members = "/api/v1/projects/" + projectId + "/members";

		mvc.perform(as(identity.login(ownerEmail, Fixtures.PASSWORD), get(members)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].role", contains("OWNER", "REVIEWER", "MEMBER")))
				.andExpect(jsonPath("$[*].displayName", contains("주인", "검토자", "일반 구성원")))
				.andExpect(jsonPath("$[*].email", contains(ownerEmail, reviewerEmail, memberEmail)))
				.andExpect(jsonPath("$[*].me", contains(true, false, false)));

		mvc.perform(as(identity.login(memberEmail, Fixtures.PASSWORD), get(members)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].email").value(nullValue()))
				.andExpect(jsonPath("$[1].email").value(nullValue()))
				.andExpect(jsonPath("$[2].email").value(memberEmail))
				.andExpect(jsonPath("$[2].me").value(true));

		String outsider = signUp("다른 사람");
		mvc.perform(as(identity.login(outsider, Fixtures.PASSWORD), get(members)))
				.andExpect(status().isForbidden());
	}

	private String signUp(String name) {
		String email = "auth-" + UUID.randomUUID() + "@contexa.test";
		identity.signUp(email, name, Fixtures.PASSWORD);
		return email;
	}

	private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}
}
