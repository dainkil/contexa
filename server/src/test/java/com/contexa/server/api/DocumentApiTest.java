package com.contexa.server.api;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
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
 * 문서 목록과 원문 뷰어 주소. 읽을 수 있는 문서만 보이고, 판마다 자르기·뽑기 작업의 상태가 붙는다.
 * 일꾼이 부를 뒷문 주소는 테스트가 대신 부른다. 테스트마다 트랜잭션을 되돌린다.
 */
@SpringBootTest
@Transactional
class DocumentApiTest {

	static final String WORKER = "dev-worker-token";
	static final String TRANSCRIPT = """
			{"title":"3차 회의 전사문","kind":"TRANSCRIPT","visibility":"PROJECT","externalOk":false,
			 "content":"민지: 예산 승인 전까지 인쇄는 보류입니다.\\nAhmed: الطباعة بعد الموافقة"}
			""";
	static final String MEMO = """
			{"title":"내 메모","kind":"MEMO","language":"ko","visibility":"PRIVATE","externalOk":false,
			 "content":"인쇄소 견적은 두 군데 더 받아 볼 것"}
			""";

	@Autowired
	WebApplicationContext context;

	@Autowired
	IdentityAccess identity;

	@Autowired
	JdbcClient jdbc;

	MockMvc mvc;
	String base;
	String owner;
	String member;
	String stranger;
	String strangerBase;

	@BeforeEach
	void 주인_구성원_다른_프로젝트_사람이_로그인한다() {
		jdbc.sql("update job set status = 'CANCELLED' where status in ('QUEUED', 'RUNNING')").update();
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
		String ownerEmail = "doc-owner-" + UUID.randomUUID() + "@contexa.test";
		UUID ownerId = identity.signUp(ownerEmail, "민지", Fixtures.PASSWORD);
		UUID projectId = identity.createProject(ownerId, "국제교류 행사");
		base = "/api/v1/projects/" + projectId;
		owner = identity.login(ownerEmail, Fixtures.PASSWORD);

		String memberEmail = "doc-member-" + UUID.randomUUID() + "@contexa.test";
		identity.setMember(projectId, ownerId, identity.signUp(memberEmail, "준호", Fixtures.PASSWORD), Role.MEMBER);
		member = identity.login(memberEmail, Fixtures.PASSWORD);

		String strangerEmail = "doc-stranger-" + UUID.randomUUID() + "@contexa.test";
		UUID strangerId = identity.signUp(strangerEmail, "남", Fixtures.PASSWORD);
		strangerBase = "/api/v1/projects/" + identity.createProject(strangerId, "다른 팀");
		stranger = identity.login(strangerEmail, Fixtures.PASSWORD);
	}

	@Test
	void 목록에는_읽을_수_있는_문서만_최근_것부터_처리_상태와_함께_나온다() throws Exception {
		upload(owner, base, TRANSCRIPT);
		upload(owner, base, MEMO);
		upload(member, base, """
				{"title":"행사 운영 계획","kind":"DOCUMENT","language":"en","visibility":"PROJECT","externalOk":true,
				 "content":"Venue: main hall"}
				""");

		mvc.perform(as(owner, get(base + "/documents")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[0].document.title").value("행사 운영 계획"))
				.andExpect(jsonPath("$[0].document.language").value("en"))
				.andExpect(jsonPath("$[0].document.externalOk").value(true))
				.andExpect(jsonPath("$[1].document.title").value("내 메모"))
				.andExpect(jsonPath("$[1].document.visibility").value("PRIVATE"))
				.andExpect(jsonPath("$[2].document.kind").value("TRANSCRIPT"))
				.andExpect(jsonPath("$[2].document.language").value(nullValue()))
				.andExpect(jsonPath("$[2].versionCount").value(1))
				.andExpect(jsonPath("$[2].latest.versionNo").value(1))
				.andExpect(jsonPath("$[2].latest.spanCount").value(0))
				.andExpect(jsonPath("$[2].latest.parse.status").value("QUEUED"))
				.andExpect(jsonPath("$[2].latest.extract").value(nullValue()));

		mvc.perform(as(member, get(base + "/documents")))
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[*].document.title", not(hasItem("내 메모"))));
		mvc.perform(as(stranger, get(base + "/documents"))).andExpect(status().isForbidden());
		mvc.perform(get(base + "/documents")).andExpect(status().isUnauthorized());
	}

	@Test
	void 자르기_전에는_원문_전체가_자른_뒤에는_발언이_나온다() throws Exception {
		String documentId = JsonPath.read(upload(owner, base, TRANSCRIPT), "$.version.documentId");

		mvc.perform(as(owner, get(base + "/documents/" + documentId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.document.title").value("3차 회의 전사문"))
				.andExpect(jsonPath("$.versions", hasSize(1)))
				.andExpect(jsonPath("$.versions[0].parse.status").value("QUEUED"))
				.andExpect(jsonPath("$.opened.versionNo").value(1))
				.andExpect(jsonPath("$.opened.spans", hasSize(0)))
				.andExpect(jsonPath("$.opened.content").value(startsWith("민지: 예산 승인")));

		parse("민지", "예산 승인 전까지 인쇄는 보류입니다.", "Ahmed", "الطباعة بعد الموافقة");

		mvc.perform(as(member, get(base + "/documents/" + documentId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.versions[0].spanCount").value(2))
				.andExpect(jsonPath("$.versions[0].parse.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.versions[0].extract.status").value("QUEUED"))
				.andExpect(jsonPath("$.opened.content").value(nullValue()))
				.andExpect(jsonPath("$.opened.spans[*].seq", hasItems(1, 2)))
				.andExpect(jsonPath("$.opened.spans[0].speakerLabel").value("민지"))
				.andExpect(jsonPath("$.opened.spans[1].text").value("الطباعة بعد الموافقة"))
				.andExpect(jsonPath("$.opened.spans[0].ref.type").value("SPAN"));
	}

	@Test
	void 판_번호나_근거_발언으로_판을_골라_연다() throws Exception {
		String documentId = JsonPath.read(upload(owner, base, TRANSCRIPT), "$.version.documentId");
		parse("민지", "예산 승인 전까지 인쇄는 보류입니다.", "Ahmed", "الطباعة بعد الموافقة");
		String firstSpan = JsonPath.read(mvc.perform(as(owner, get(base + "/documents/" + documentId)))
				.andReturn().getResponse().getContentAsString(), "$.opened.spans[0].ref.id");
		mvc.perform(as(member, post(base + "/documents/" + documentId + "/versions"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"민지: 예산이 승인됐습니다.\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version.versionNo").value(2));

		// 고르지 않으면 가장 최근 판(아직 자르지 않음)
		mvc.perform(as(owner, get(base + "/documents/" + documentId)))
				.andExpect(jsonPath("$.versions", hasSize(2)))
				.andExpect(jsonPath("$.versions[1].versionNo").value(2))
				.andExpect(jsonPath("$.opened.versionNo").value(2))
				.andExpect(jsonPath("$.opened.content").value("민지: 예산이 승인됐습니다."));
		mvc.perform(as(owner, get(base + "/documents")))
				.andExpect(jsonPath("$[0].versionCount").value(2))
				.andExpect(jsonPath("$[0].latest.versionNo").value(2));
		// 판 번호로
		mvc.perform(as(owner, get(base + "/documents/" + documentId).param("version", "1")))
				.andExpect(jsonPath("$.opened.versionNo").value(1))
				.andExpect(jsonPath("$.opened.spans", hasSize(2)));
		// 근거 발언으로: 그 발언이 들어 있는 판(1판)이 열린다
		mvc.perform(as(owner, get(base + "/documents/" + documentId).param("span", firstSpan)))
				.andExpect(jsonPath("$.opened.versionNo").value(1))
				.andExpect(jsonPath("$.opened.spans[0].ref.id").value(firstSpan));

		mvc.perform(as(owner, get(base + "/documents/" + documentId).param("version", "3")))
				.andExpect(status().isNotFound());
		mvc.perform(as(owner, get(base + "/documents/" + documentId).param("span", UUID.randomUUID().toString())))
				.andExpect(status().isNotFound());
		mvc.perform(as(owner, get(base + "/documents/" + documentId).param("version", "1").param("span", firstSpan)))
				.andExpect(status().isBadRequest());
		// 다른 문서의 발언으로는 이 문서를 열지 않는다
		String otherDocument = JsonPath.read(upload(owner, base, MEMO), "$.version.documentId");
		mvc.perform(as(owner, get(base + "/documents/" + otherDocument).param("span", firstSpan)))
				.andExpect(status().isNotFound());
	}

	@Test
	void 남의_개인_메모와_다른_프로젝트의_문서는_열리지_않는다() throws Exception {
		String memo = JsonPath.read(upload(owner, base, MEMO), "$.version.documentId");
		String foreign = JsonPath.read(upload(stranger, strangerBase, TRANSCRIPT), "$.version.documentId");

		mvc.perform(as(owner, get(base + "/documents/" + memo))).andExpect(status().isOk());
		mvc.perform(as(member, get(base + "/documents/" + memo))).andExpect(status().isNotFound());
		mvc.perform(as(member, post(base + "/documents/" + memo + "/versions"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"덮어쓰기\"}"))
				.andExpect(status().isNotFound());
		// 남의 프로젝트 문서를 내 프로젝트 주소로 열어도, 남의 프로젝트 주소로 열어도 안 된다
		mvc.perform(as(owner, get(base + "/documents/" + foreign))).andExpect(status().isNotFound());
		mvc.perform(as(owner, get(strangerBase + "/documents/" + foreign))).andExpect(status().isForbidden());
		mvc.perform(as(owner, get(base + "/documents/" + UUID.randomUUID()))).andExpect(status().isNotFound());
	}

	// ---- 도우미 ----

	private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
		return request.header("Authorization", "Bearer " + token);
	}

	private String upload(String token, String projectBase, String body) throws Exception {
		return mvc.perform(as(token, post(projectBase + "/documents")).contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	/** 일꾼 대신 가장 오래 기다린 자르기 작업을 가져가 화자·발언 쌍을 낸다. */
	private void parse(String... speakerAndText) throws Exception {
		String lease = mvc.perform(post("/internal/v1/jobs/claim").header("X-Worker-Token", WORKER)
				.contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"test-worker\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.job.type").value("PARSE"))
				.andReturn().getResponse().getContentAsString();
		StringBuilder spans = new StringBuilder();
		for (int i = 0; i < speakerAndText.length; i += 2) {
			spans.append(spans.isEmpty() ? "" : ",").append("{\"speakerLabel\":\"").append(speakerAndText[i])
					.append("\",\"text\":\"").append(speakerAndText[i + 1]).append("\"}");
		}
		mvc.perform(post("/internal/v1/jobs/" + JsonPath.read(lease, "$.job.id") + "/result")
				.header("X-Worker-Token", WORKER)
				.header("X-Attempt-Id", (String) JsonPath.read(lease, "$.attemptId"))
				.header("X-Lease-Token", (String) JsonPath.read(lease, "$.leaseToken"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"spans\":[" + spans + "]}"))
				.andExpect(status().isOk());
	}
}
