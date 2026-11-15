package com.contexa.server.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.contexa.server.contract.Caller;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.NotAllowedException;
import com.contexa.server.identity.ProjectSummary;
import com.contexa.server.identity.Role;

/** 정문: 가입, 로그인, 프로젝트와 구성원 관리. 요청을 출입 관리의 함수 호출로 옮기기만 한다. */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

	private final IdentityAccess identity;

	public AuthController(IdentityAccess identity) {
		this.identity = identity;
	}

	@PostMapping("/auth/signup")
	public Map<String, UUID> signUp(@RequestBody SignUpRequest request) {
		return Map.of("userId", identity.signUp(request.email(), request.displayName(), request.password()));
	}

	/** 토큰은 여기서 한 번만 돌려준다. 이후 요청마다 "Authorization: Bearer 토큰"으로 보낸다. */
	@PostMapping("/auth/login")
	public Map<String, String> login(@RequestBody LoginRequest request) {
		return Map.of("token", identity.login(request.email(), request.password()));
	}

	/** 내가 속한 프로젝트. */
	@GetMapping("/projects")
	public List<ProjectSummary> projects(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
			String authorization) {
		return identity.projectsOf(user(authorization));
	}

	@PostMapping("/projects")
	public Map<String, UUID> createProject(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
			String authorization, @RequestBody CreateProjectRequest request) {
		return Map.of("id", identity.createProject(user(authorization), request.title()));
	}

	/** 가입한 사람을 이메일로 구성원에 넣거나 역할을 바꾼다. OWNER만 할 수 있다. */
	@PutMapping("/projects/{projectId}/members")
	public Map<String, UUID> setMember(Caller caller, @PathVariable UUID projectId,
			@RequestBody MemberRequest request) {
		UUID userId = identity.userIdByEmail(request.email())
				.orElseThrow(() -> new IllegalArgumentException("가입하지 않은 이메일이다"));
		identity.setMember(projectId, human(caller), userId, request.role());
		return Map.of("userId", userId);
	}

	/**
	 * 외부 AI 에이전트용 토큰을 발급한다. OWNER만 할 수 있다. 에이전트는 이 토큰으로 /mcp에 접속한다.
	 * audience를 생략하면 EXTERNAL(외부 공유가 허용된 문서의 내용만)이다.
	 */
	@PostMapping("/projects/{projectId}/agent-tokens")
	public Map<String, String> issueAgentToken(Caller caller, @PathVariable UUID projectId,
			@RequestBody(required = false) AgentTokenRequest request) {
		Caller.Audience audience = request == null || request.audience() == null ? Caller.Audience.EXTERNAL
				: request.audience();
		return Map.of("token", identity.issueAgentToken(projectId, human(caller), audience));
	}

	@DeleteMapping("/projects/{projectId}/agent-tokens")
	public Map<String, Integer> revokeAgentTokens(Caller caller, @PathVariable UUID projectId) {
		return Map.of("revoked", identity.revokeAgentTokens(projectId, human(caller)));
	}

	public record SignUpRequest(String email, String displayName, String password) {
	}

	public record LoginRequest(String email, String password) {
	}

	public record CreateProjectRequest(String title) {
	}

	public record MemberRequest(String email, Role role) {
	}

	public record AgentTokenRequest(Caller.Audience audience) {
	}

	private UUID user(String authorization) {
		return identity.authenticateUser(CallerResolver.bearer(authorization));
	}

	private static UUID human(Caller caller) {
		if (caller.kind() != Caller.Kind.USER) {
			throw new NotAllowedException("사람만 할 수 있는 일이다");
		}
		return caller.id();
	}
}
