package com.contexa.server.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Caller;

/**
 * 출입 관리: 누구인지 확인하고(로그인, 토큰), 프로젝트 안에서 무엇을 할 수 있는지와
 * 어떤 문서를 읽을 수 있는지 알려준다.
 *
 * <ul>
 * <li>{@link Caller}는 여기서만 만들어진다. 다른 부서는 받은 Caller를 "이미 확인된 요청자"로 믿는다.</li>
 * <li>비밀번호와 토큰은 원문을 저장하지 않는다. 비밀번호는 PBKDF2 해시로, 토큰은 SHA-256 해시로 둔다.</li>
 * <li>AI 작업(JOB)은 작업을 요청한 사용자의 권한으로 읽는다. 외부 에이전트(AGENT)는 외부 공유가 허용된
 * 프로젝트 문서만 읽는다.</li>
 * </ul>
 */
@Service
public class IdentityAccess {

	private static final Duration USER_TOKEN_TTL = Duration.ofHours(12);
	private static final Duration AGENT_TOKEN_TTL = Duration.ofDays(30);
	private static final int PBKDF2_ITERATIONS = 600_000;
	private static final int MIN_PASSWORD_LENGTH = 8;

	private final JdbcClient jdbc;
	private final SecureRandom random = new SecureRandom();

	public IdentityAccess(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ---- 가입과 로그인 ----

	/** 가입한다. 이메일은 대소문자를 구분하지 않는다. */
	@Transactional
	public UUID signUp(String email, String displayName, String password) {
		String normalized = normalizeEmail(email);
		if (displayName == null || displayName.isBlank()) {
			throw new IllegalArgumentException("이름이 비어 있다");
		}
		if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
			throw new IllegalArgumentException("비밀번호는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 한다");
		}
		UUID id = UUID.randomUUID();
		int inserted = jdbc.sql("""
				insert into app_user (id, email, display_name, password_hash)
				values (:id, :email, :name, :hash)
				on conflict (email) do nothing
				""")
				.param("id", id)
				.param("email", normalized)
				.param("name", displayName.strip())
				.param("hash", hashPassword(password))
				.update();
		if (inserted == 0) {
			throw new IllegalArgumentException("이미 가입된 이메일이다");
		}
		return id;
	}

	/**
	 * 로그인하고 접속 토큰을 발급한다. 토큰 원문은 여기서 한 번만 돌려준다.
	 * 이메일이 없는 경우와 비밀번호가 틀린 경우를 구분해서 알려주지 않는다.
	 */
	@Transactional
	public String login(String email, String password) {
		record Account(UUID id, String hash) {
		}
		Optional<Account> account = jdbc.sql("select id, password_hash from app_user where email = :email")
				.param("email", email == null ? "" : email.strip().toLowerCase(Locale.ROOT))
				.query((rs, n) -> new Account(rs.getObject("id", UUID.class), rs.getString("password_hash")))
				.optional();
		if (account.isEmpty() || password == null || !verifyPassword(password, account.get().hash())) {
			throw new UnauthenticatedException("이메일 또는 비밀번호가 맞지 않는다");
		}
		return issueToken("USER", account.get().id(), null, USER_TOKEN_TTL);
	}

	/**
	 * 토큰으로 요청자를 확인하고, 이 프로젝트에 들어올 수 있는지 본다.
	 * 사람은 구성원이어야 하고, 외부 에이전트 토큰은 그 프로젝트용으로 발급된 것이어야 한다.
	 *
	 * @throws UnauthenticatedException 토큰이 없거나, 틀렸거나, 만료·폐기됨
	 * @throws NotAllowedException      누구인지는 알지만 이 프로젝트에 들어올 수 없음
	 */
	@Transactional(readOnly = true)
	public Caller authenticate(String token, UUID projectId) {
		Objects.requireNonNull(projectId, "projectId");
		TokenRow row = findToken(token);
		if (row.kind().equals("AGENT")) {
			if (!projectId.equals(row.projectId())) {
				throw new NotAllowedException("이 토큰으로는 이 프로젝트를 읽을 수 없다");
			}
			return new Caller(Caller.Kind.AGENT, row.id(), projectId, null, row.audience());
		}
		if (roleOf(projectId, row.userId()).isEmpty()) {
			throw new NotAllowedException("이 프로젝트의 구성원이 아니다");
		}
		return Caller.user(row.userId(), projectId);
	}

	/**
	 * 외부 에이전트 토큰으로 요청자를 확인한다. 토큰이 발급된 프로젝트의 요청자가 된다.
	 * 사람의 로그인 토큰은 받지 않는다.
	 */
	@Transactional(readOnly = true)
	public Caller authenticateAgent(String token) {
		TokenRow row = findToken(token);
		if (!row.kind().equals("AGENT")) {
			throw new NotAllowedException("외부 에이전트 토큰이 필요하다");
		}
		return new Caller(Caller.Kind.AGENT, row.id(), row.projectId(), null, row.audience());
	}

	/**
	 * AI 작업의 요청자를 만든다. 작업은 그 일을 시킨 사용자의 권한으로만 읽는다.
	 * 시킨 사용자가 그사이 프로젝트에서 빠졌으면 거절한다.
	 */
	@Transactional(readOnly = true)
	public Caller callerForJob(UUID jobId, UUID projectId, UUID requestedBy) {
		if (roleOf(projectId, requestedBy).isEmpty()) {
			throw new NotAllowedException("작업을 요청한 사용자가 더 이상 이 프로젝트의 구성원이 아니다");
		}
		return new Caller(Caller.Kind.JOB, jobId, projectId, requestedBy, Caller.Audience.INTERNAL);
	}

	/** 토큰의 주인(사람). 프로젝트를 고르기 전, 예를 들어 내 프로젝트 목록을 볼 때 쓴다. */
	@Transactional(readOnly = true)
	public UUID authenticateUser(String token) {
		TokenRow row = findToken(token);
		if (!row.kind().equals("USER")) {
			throw new NotAllowedException("사람의 로그인 토큰이 필요하다");
		}
		return row.userId();
	}

	// ---- 프로젝트와 구성원 ----

	/** 프로젝트를 만든다. 만든 사람이 OWNER가 된다. */
	@Transactional
	public UUID createProject(UUID userId, String title) {
		Objects.requireNonNull(userId, "userId");
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("프로젝트 제목이 비어 있다");
		}
		UUID id = UUID.randomUUID();
		jdbc.sql("insert into project (id, title, created_by) values (:id, :title, :userId)")
				.param("id", id).param("title", title.strip()).param("userId", userId).update();
		jdbc.sql("insert into project_member (project_id, user_id, role) values (:id, :userId, 'OWNER')")
				.param("id", id).param("userId", userId).update();
		return id;
	}

	/** 구성원을 추가하거나 역할을 바꾼다. OWNER만 할 수 있다. 마지막 OWNER의 역할은 내릴 수 없다. */
	@Transactional
	public void setMember(UUID projectId, UUID actorId, UUID userId, Role role) {
		Objects.requireNonNull(role, "role");
		requireOwner(projectId, actorId);
		if (role != Role.OWNER && roleOf(projectId, userId).orElse(null) == Role.OWNER && ownerCount(projectId) == 1) {
			throw new IllegalStateException("프로젝트에는 OWNER가 한 명 이상 있어야 한다");
		}
		jdbc.sql("""
				insert into project_member (project_id, user_id, role) values (:projectId, :userId, :role)
				on conflict (project_id, user_id) do update set role = excluded.role
				""")
				.param("projectId", projectId).param("userId", userId).param("role", role.name()).update();
	}

	/** 이메일로 가입한 사용자를 찾는다. 구성원으로 초대할 때 쓴다. */
	@Transactional(readOnly = true)
	public Optional<UUID> userIdByEmail(String email) {
		return jdbc.sql("select id from app_user where email = :email")
				.param("email", email == null ? "" : email.strip().toLowerCase(Locale.ROOT))
				.query((rs, n) -> rs.getObject("id", UUID.class))
				.optional();
	}

	@Transactional(readOnly = true)
	public Optional<Role> roleOf(UUID projectId, UUID userId) {
		return jdbc.sql("select role from project_member where project_id = :projectId and user_id = :userId")
				.param("projectId", projectId).param("userId", userId)
				.query((rs, n) -> Role.valueOf(rs.getString("role")))
				.optional();
	}

	@Transactional(readOnly = true)
	public List<ProjectSummary> projectsOf(UUID userId) {
		return jdbc.sql("""
				select p.id, p.title, m.role from project p
				  join project_member m on m.project_id = p.id
				 where m.user_id = :userId order by p.created_at, p.id
				""")
				.param("userId", userId)
				.query((rs, n) -> new ProjectSummary(rs.getObject("id", UUID.class), rs.getString("title"),
						Role.valueOf(rs.getString("role"))))
				.list();
	}

	// ---- 권한 ----

	/** 이 요청자가 확인·정정(도장)을 할 수 있는가. 사람이고, 이 프로젝트의 OWNER나 REVIEWER여야 한다. */
	@Transactional(readOnly = true)
	public boolean canReview(Caller caller) {
		return caller.kind() == Caller.Kind.USER
				&& roleOf(caller.projectId(), caller.id()).map(Role::canReview).orElse(false);
	}

	/** 이 요청자가 사람이고 이 프로젝트의 구성원인가. */
	@Transactional(readOnly = true)
	public boolean isMember(Caller caller) {
		return caller.kind() == Caller.Kind.USER && roleOf(caller.projectId(), caller.id()).isPresent();
	}

	// ---- 외부 에이전트 토큰 ----

	/** 외부 공유가 허용된 문서의 내용만 읽는 에이전트 토큰을 발급한다. */
	@Transactional
	public String issueAgentToken(UUID projectId, UUID adminUserId) {
		return issueAgentToken(projectId, adminUserId, Caller.Audience.EXTERNAL);
	}

	/**
	 * 외부 AI 에이전트가 이 프로젝트를 읽을 토큰을 발급한다. OWNER만 할 수 있다. 원문은 한 번만 돌려준다.
	 *
	 * @param audience EXTERNAL이면 외부 공유가 허용된 문서의 내용만, INTERNAL이면 구성원 모두가 읽는 문서의 내용을 읽는다.
	 *                 어느 쪽이든 개인 메모는 읽지 못하고, 확인(도장)도 할 수 없다
	 */
	@Transactional
	public String issueAgentToken(UUID projectId, UUID adminUserId, Caller.Audience audience) {
		Objects.requireNonNull(audience, "audience");
		requireOwner(projectId, adminUserId);
		String token = issueToken("AGENT", adminUserId, projectId, AGENT_TOKEN_TTL);
		jdbc.sql("update access_token set audience = :audience where token_hash = :hash")
				.param("audience", audience.name()).param("hash", sha256(token)).update();
		return token;
	}

	/** 외부 에이전트 토큰을 모두 폐기한다. OWNER만 할 수 있다. 폐기한 개수를 돌려준다. */
	@Transactional
	public int revokeAgentTokens(UUID projectId, UUID adminUserId) {
		requireOwner(projectId, adminUserId);
		return jdbc.sql("""
				update access_token set revoked_at = now()
				 where kind = 'AGENT' and project_id = :projectId and revoked_at is null
				""")
				.param("projectId", projectId).update();
	}

	// ---- 문서 열람 범위 ----

	/** 자료실이 문서를 등록하거나 공개 범위를 바꿀 때 부른다. */
	@Transactional
	public void registerDocument(UUID documentId, UUID projectId, UUID ownerId, Visibility visibility,
			boolean externalOk) {
		Objects.requireNonNull(visibility, "visibility");
		jdbc.sql("""
				insert into document_acl (document_id, project_id, owner_id, visibility, external_ok)
				values (:documentId, :projectId, :ownerId, :visibility, :externalOk)
				on conflict (document_id) do update
				    set visibility = excluded.visibility, external_ok = excluded.external_ok
				""")
				.param("documentId", documentId).param("projectId", projectId).param("ownerId", ownerId)
				.param("visibility", visibility.name()).param("externalOk", externalOk).update();
	}

	/**
	 * 이 요청자가 근거로 읽을 수 있는 문서.
	 * 사람과 AI 작업은 프로젝트 공개 문서와 자기(요청한 사용자)의 개인 문서를 읽는다.
	 * 외부로 나가는 요청(EXTERNAL)은 외부 공유가 허용된 프로젝트 공개 문서만 읽는다.
	 * 외부 에이전트는 사람이 아니므로 개인 문서는 어떤 경우에도 읽지 못한다.
	 */
	@Transactional(readOnly = true)
	public Set<UUID> readableDocumentIds(Caller caller) {
		Objects.requireNonNull(caller, "caller");
		boolean external = caller.audience() == Caller.Audience.EXTERNAL;
		UUID user = caller.kind() == Caller.Kind.AGENT ? null : caller.actingUser();
		return new HashSet<>(jdbc.sql("""
				select document_id from document_acl
				 where project_id = :projectId
				   and (visibility = 'PROJECT' or (:external = false and owner_id = :user))
				   and (:external = false or external_ok)
				""")
				.param("projectId", caller.projectId())
				.param("external", external)
				.param("user", user)
				.query((rs, n) -> rs.getObject("document_id", UUID.class))
				.list());
	}

	@Transactional(readOnly = true)
	public boolean canRead(Caller caller, UUID documentId) {
		return readableDocumentIds(caller).contains(documentId);
	}

	// ---- 내부 ----

	private record TokenRow(UUID id, String kind, UUID userId, UUID projectId, Caller.Audience audience) {
	}

	private TokenRow findToken(String token) {
		if (token == null || token.isBlank()) {
			throw new UnauthenticatedException("로그인이 필요하다");
		}
		return jdbc.sql("""
				select id, kind, user_id, project_id, audience from access_token
				 where token_hash = :hash and revoked_at is null and expires_at > now()
				""")
				.param("hash", sha256(token.strip()))
				.query((rs, n) -> new TokenRow(rs.getObject("id", UUID.class), rs.getString("kind"),
						rs.getObject("user_id", UUID.class), rs.getObject("project_id", UUID.class),
						Caller.Audience.valueOf(rs.getString("audience"))))
				.optional()
				.orElseThrow(() -> new UnauthenticatedException("토큰이 올바르지 않거나 만료됐다"));
	}

	private String issueToken(String kind, UUID userId, UUID projectId, Duration ttl) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		jdbc.sql("""
				insert into access_token (id, token_hash, kind, user_id, project_id, expires_at)
				values (:id, :hash, :kind, :userId, :projectId, :expiresAt)
				""")
				.param("id", UUID.randomUUID())
				.param("hash", sha256(token))
				.param("kind", kind)
				.param("userId", userId)
				.param("projectId", projectId)
				.param("expiresAt", OffsetDateTime.now().plus(ttl))
				.update();
		return token;
	}

	private void requireOwner(UUID projectId, UUID userId) {
		if (roleOf(projectId, userId).orElse(null) != Role.OWNER) {
			throw new NotAllowedException("프로젝트의 OWNER만 할 수 있다");
		}
	}

	private long ownerCount(UUID projectId) {
		return jdbc.sql("select count(*) from project_member where project_id = :projectId and role = 'OWNER'")
				.param("projectId", projectId).query(Long.class).single();
	}

	private static String normalizeEmail(String email) {
		if (email == null || !email.strip().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
			throw new IllegalArgumentException("이메일 형식이 올바르지 않다");
		}
		return email.strip().toLowerCase(Locale.ROOT);
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 저장 형식: pbkdf2$반복횟수$소금$해시 */
	private String hashPassword(String password) {
		byte[] salt = new byte[16];
		random.nextBytes(salt);
		Base64.Encoder b64 = Base64.getEncoder();
		return "pbkdf2$" + PBKDF2_ITERATIONS + "$" + b64.encodeToString(salt) + "$"
				+ b64.encodeToString(pbkdf2(password, salt, PBKDF2_ITERATIONS));
	}

	private static boolean verifyPassword(String password, String stored) {
		String[] parts = stored.split("\\$");
		if (parts.length != 4 || !parts[0].equals("pbkdf2")) {
			return false;
		}
		byte[] salt = Base64.getDecoder().decode(parts[2]);
		byte[] expected = Base64.getDecoder().decode(parts[3]);
		return MessageDigest.isEqual(expected, pbkdf2(password, salt, Integer.parseInt(parts[1])));
	}

	private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
		try {
			PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
			return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}
}
