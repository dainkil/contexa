package com.contexa.server.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Caller;

/** 출입 관리: 가입·로그인·토큰, 프로젝트 구성원과 역할, 문서 열람 범위. 테스트마다 트랜잭션을 되돌린다. */
@SpringBootTest
@Transactional
class IdentityAccessTest {

	static final String PASSWORD = "correct-horse-1";

	@Autowired
	IdentityAccess identity;

	@Autowired
	JdbcClient jdbc;

	String email;
	UUID owner;
	UUID projectId;

	@BeforeEach
	void 사용자가_가입하고_프로젝트를_만든다() {
		email = "Owner-" + UUID.randomUUID() + "@Contexa.Test";
		owner = identity.signUp(email, "민지", PASSWORD);
		projectId = identity.createProject(owner, "국제교류 행사");
	}

	// ---- 가입과 로그인 ----

	@Test
	void 로그인하면_토큰을_받고_그_토큰으로_프로젝트에_들어간다() {
		String token = identity.login(email.toLowerCase(), PASSWORD);

		Caller caller = identity.authenticate(token, projectId);

		assertThat(caller.kind()).isEqualTo(Caller.Kind.USER);
		assertThat(caller.id()).isEqualTo(owner);
		assertThat(caller.projectId()).isEqualTo(projectId);
		assertThat(caller.audience()).isEqualTo(Caller.Audience.INTERNAL);
		assertThat(identity.authenticateUser(token)).isEqualTo(owner);
	}

	@Test
	void 내_정보는_가입할_때_적은_이메일과_이름이다() {
		IdentityAccess.Profile profile = identity.profileOf(owner);

		assertThat(profile.email()).isEqualTo(email.toLowerCase());
		assertThat(profile.displayName()).isEqualTo("민지");
		assertThatThrownBy(() -> identity.profileOf(UUID.randomUUID())).isInstanceOf(UnauthenticatedException.class);
	}

	@Test
	void 비밀번호와_토큰은_원문으로_저장하지_않는다() {
		String token = identity.login(email, PASSWORD);

		String storedPassword = jdbc.sql("select password_hash from app_user where id = :id").param("id", owner)
				.query(String.class).single();
		assertThat(storedPassword).startsWith("pbkdf2$").doesNotContain(PASSWORD);
		assertThat(jdbc.sql("select count(*) from access_token where token_hash = :t").param("t", token)
				.query(Long.class).single()).isZero();
	}

	@Test
	void 틀린_비밀번호와_없는_이메일은_같은_말로_거절한다() {
		assertThatThrownBy(() -> identity.login(email, "wrong-password"))
				.isInstanceOf(UnauthenticatedException.class).hasMessage("이메일 또는 비밀번호가 맞지 않는다");
		assertThatThrownBy(() -> identity.login("nobody@contexa.test", PASSWORD))
				.isInstanceOf(UnauthenticatedException.class).hasMessage("이메일 또는 비밀번호가 맞지 않는다");
	}

	@Test
	void 같은_이메일로_두_번_가입할_수_없고_형식이_틀리면_거절한다() {
		assertThatThrownBy(() -> identity.signUp(email.toUpperCase(), "다른 사람", PASSWORD))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identity.signUp("not-an-email", "이름", PASSWORD))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identity.signUp("a-" + UUID.randomUUID() + "@contexa.test", "이름", "short"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identity.signUp("b-" + UUID.randomUUID() + "@contexa.test", " ", PASSWORD))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 토큰이_없거나_틀리거나_만료됐으면_누구인지_알_수_없다() {
		String token = identity.login(email, PASSWORD);

		assertThatThrownBy(() -> identity.authenticate(null, projectId)).isInstanceOf(UnauthenticatedException.class);
		assertThatThrownBy(() -> identity.authenticate("made-up", projectId))
				.isInstanceOf(UnauthenticatedException.class);

		jdbc.sql("update access_token set expires_at = now() - interval '1 minute'").update();
		assertThatThrownBy(() -> identity.authenticate(token, projectId))
				.isInstanceOf(UnauthenticatedException.class);
	}

	// ---- 프로젝트와 역할 ----

	@Test
	void 구성원이_아닌_프로젝트에는_들어갈_수_없다() {
		UUID stranger = identity.signUp("s-" + UUID.randomUUID() + "@contexa.test", "지나가던 사람", PASSWORD);
		String token = identity.login(email, PASSWORD);
		UUID theirs = identity.createProject(stranger, "다른 프로젝트");

		assertThatThrownBy(() -> identity.authenticate(token, theirs)).isInstanceOf(NotAllowedException.class);
		assertThat(identity.projectsOf(owner)).extracting(ProjectSummary::id).containsExactly(projectId);
		assertThat(identity.projectsOf(owner).get(0).role()).isEqualTo(Role.OWNER);
	}

	@Test
	void 역할에_따라_검토_권한이_다르다() {
		UUID member = identity.signUp("m-" + UUID.randomUUID() + "@contexa.test", "구성원", PASSWORD);
		identity.setMember(projectId, owner, member, Role.MEMBER);

		assertThat(identity.canReview(Caller.user(owner, projectId))).isTrue();
		assertThat(identity.canReview(Caller.user(member, projectId))).isFalse();
		assertThat(identity.isMember(Caller.user(member, projectId))).isTrue();
		assertThat(identity.isMember(Caller.user(UUID.randomUUID(), projectId))).isFalse();

		identity.setMember(projectId, owner, member, Role.REVIEWER);
		assertThat(identity.canReview(Caller.user(member, projectId))).isTrue();

		// AI 작업은 요청한 사용자가 OWNER여도 검토할 수 없다
		Caller job = new Caller(Caller.Kind.JOB, UUID.randomUUID(), projectId, owner, Caller.Audience.INTERNAL);
		assertThat(identity.canReview(job)).isFalse();
	}

	@Test
	void 구성원_관리는_OWNER만_하고_마지막_OWNER는_내려올_수_없다() {
		UUID member = identity.signUp("m-" + UUID.randomUUID() + "@contexa.test", "구성원", PASSWORD);
		identity.setMember(projectId, owner, member, Role.REVIEWER);

		assertThatThrownBy(() -> identity.setMember(projectId, member, member, Role.OWNER))
				.isInstanceOf(NotAllowedException.class);
		assertThatThrownBy(() -> identity.setMember(projectId, owner, owner, Role.MEMBER))
				.isInstanceOf(IllegalStateException.class);

		identity.setMember(projectId, owner, member, Role.OWNER);
		identity.setMember(projectId, member, owner, Role.MEMBER);
		assertThat(identity.roleOf(projectId, owner)).contains(Role.MEMBER);
	}

	// ---- 외부 에이전트 ----

	@Test
	void 외부_에이전트_토큰은_발급된_프로젝트만_읽고_폐기하면_못_쓴다() {
		String token = identity.issueAgentToken(projectId, owner);

		Caller agent = identity.authenticate(token, projectId);
		assertThat(agent.kind()).isEqualTo(Caller.Kind.AGENT);
		assertThat(agent.audience()).isEqualTo(Caller.Audience.EXTERNAL);
		assertThat(identity.canReview(agent)).isFalse();
		assertThatThrownBy(() -> identity.authenticate(token, identity.createProject(owner, "다른 프로젝트")))
				.isInstanceOf(NotAllowedException.class);
		assertThatThrownBy(() -> identity.authenticateUser(token)).isInstanceOf(NotAllowedException.class);

		assertThat(identity.revokeAgentTokens(projectId, owner)).isEqualTo(1);
		assertThatThrownBy(() -> identity.authenticate(token, projectId))
				.isInstanceOf(UnauthenticatedException.class);
	}

	@Test
	void 외부_에이전트_토큰은_OWNER만_발급한다() {
		UUID member = identity.signUp("m-" + UUID.randomUUID() + "@contexa.test", "구성원", PASSWORD);
		identity.setMember(projectId, owner, member, Role.REVIEWER);

		assertThatThrownBy(() -> identity.issueAgentToken(projectId, member)).isInstanceOf(NotAllowedException.class);
	}

	// ---- 문서 열람 범위 ----

	@Test
	void 개인_메모는_올린_사람만_읽고_외부로는_공유가_허용된_문서만_나간다() {
		UUID member = identity.signUp("m-" + UUID.randomUUID() + "@contexa.test", "구성원", PASSWORD);
		identity.setMember(projectId, owner, member, Role.MEMBER);
		UUID minutes = UUID.randomUUID();
		UUID memo = UUID.randomUUID();
		UUID notice = UUID.randomUUID();
		identity.registerDocument(minutes, projectId, owner, Visibility.PROJECT, false);
		identity.registerDocument(memo, projectId, member, Visibility.PRIVATE, false);
		identity.registerDocument(notice, projectId, owner, Visibility.PROJECT, true);

		Caller ownerCaller = Caller.user(owner, projectId);
		Caller memberCaller = Caller.user(member, projectId);
		Caller jobForMember = new Caller(Caller.Kind.JOB, UUID.randomUUID(), projectId, member,
				Caller.Audience.INTERNAL);
		Caller externalDraft = new Caller(Caller.Kind.JOB, UUID.randomUUID(), projectId, member,
				Caller.Audience.EXTERNAL);
		Caller agent = identity.authenticate(identity.issueAgentToken(projectId, owner), projectId);

		assertThat(identity.readableDocumentIds(ownerCaller)).containsExactlyInAnyOrder(minutes, notice);
		assertThat(identity.readableDocumentIds(memberCaller)).containsExactlyInAnyOrder(minutes, notice, memo);
		// AI 작업은 작업을 요청한 사용자가 읽을 수 있는 만큼만 읽는다
		assertThat(identity.readableDocumentIds(jobForMember)).containsExactlyInAnyOrder(minutes, notice, memo);
		assertThat(identity.readableDocumentIds(externalDraft)).containsExactly(notice);
		assertThat(identity.readableDocumentIds(agent)).containsExactly(notice);
		assertThat(identity.canRead(ownerCaller, memo)).isFalse();

		// 공개 범위를 바꾸면 바로 반영된다
		identity.registerDocument(memo, projectId, member, Visibility.PROJECT, false);
		assertThat(identity.canRead(ownerCaller, memo)).isTrue();
	}
}
