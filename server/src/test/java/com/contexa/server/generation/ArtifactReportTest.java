package com.contexa.server.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.contexa.server.Fixtures;
import com.contexa.server.contract.Caller;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.Role;

/** AI 답변 신고: 답변을 요청한 사람만 신고하고, 신고는 이유와 함께 남는다. */
@SpringBootTest
@Transactional
class ArtifactReportTest {

	@Autowired
	GenerationWiki generation;

	@Autowired
	IdentityAccess identity;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	WebApplicationContext context;

	Caller owner;
	Caller member;
	UUID answer;

	@BeforeEach
	void 답변을_하나_요청한다() {
		jdbc.sql("update job set status = 'CANCELLED' where status in ('QUEUED', 'RUNNING')").update();
		owner = Fixtures.newOwner(identity);
		member = Fixtures.newMember(identity, owner, Role.MEMBER);
		answer = generation.requestGeneration(owner, GenKind.ANSWER, "인쇄 진행해도 돼?", "ko").artifactId();
	}

	@Test
	void 요청한_사람이_이유와_함께_신고한다() {
		UUID id = generation.reportArtifact(owner, answer, ReportReason.OFFENSIVE, "  표현이 거칠어요 ");

		assertThat(jdbc.sql("select reason || '|' || note from artifact_report where id = :id").param("id", id)
				.query(String.class).single()).isEqualTo("OFFENSIVE|표현이 거칠어요");
	}

	@Test
	void 남의_답변은_신고할_수_없다() {
		assertThatThrownBy(() -> generation.reportArtifact(member, answer, ReportReason.WRONG, null))
				.isInstanceOf(ArtifactNotFoundException.class);
	}

	@Test
	void 기타_이유는_내용이_있어야_하고_내용은_500자까지다() {
		assertThatThrownBy(() -> generation.reportArtifact(owner, answer, ReportReason.OTHER, " "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> generation.reportArtifact(owner, answer, ReportReason.WRONG, "가".repeat(501)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 앱은_답변_주소로_신고한다() throws Exception {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
		String email = jdbc.sql("select email from app_user where id = :id").param("id", owner.id())
				.query(String.class).single();
		String token = identity.login(email, Fixtures.PASSWORD);

		mvc.perform(post("/api/v1/projects/" + owner.projectId() + "/generations/" + answer + "/reports")
				.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"WRONG\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.reportId").isNotEmpty());
	}
}
