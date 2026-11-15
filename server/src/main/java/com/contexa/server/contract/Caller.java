package com.contexa.server.contract;

import java.util.Objects;
import java.util.UUID;

/**
 * 지금 요청하고 있는 주체.
 *
 * @param kind       사람(USER), AI 작업(JOB), 외부 AI 에이전트(AGENT)
 * @param id         사용자 계정, 작업, 또는 에이전트 토큰의 id
 * @param projectId  이 요청이 볼 수 있는 프로젝트
 * @param onBehalfOf JOB일 때 그 작업을 요청한 사용자. AI 작업은 이 사용자의 권한으로만 읽는다
 * @param audience   결과가 조직 안에서 쓰이는지(INTERNAL) 밖으로 나가는지(EXTERNAL)
 */
public record Caller(Kind kind, UUID id, UUID projectId, UUID onBehalfOf, Audience audience) {

	public enum Kind {
		USER, JOB, AGENT
	}

	public enum Audience {
		INTERNAL, EXTERNAL
	}

	public Caller {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(projectId, "projectId");
		Objects.requireNonNull(audience, "audience");
		if (kind == Kind.JOB && onBehalfOf == null) {
			throw new IllegalArgumentException("AI 작업은 요청한 사용자(onBehalfOf)가 있어야 한다");
		}
		if (kind != Kind.JOB && onBehalfOf != null) {
			throw new IllegalArgumentException("onBehalfOf는 AI 작업에만 쓴다");
		}
	}

	public static Caller user(UUID userId, UUID projectId) {
		return new Caller(Kind.USER, userId, projectId, null, Audience.INTERNAL);
	}

	/** 권한을 따질 때 기준이 되는 계정. AI 작업이면 작업을 요청한 사용자다. */
	public UUID actingUser() {
		return kind == Kind.JOB ? onBehalfOf : id;
	}
}
