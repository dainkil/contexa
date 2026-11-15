package com.contexa.server.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.contexa.server.contract.Caller;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.UnauthenticatedException;

/**
 * 컨트롤러 함수의 {@link Caller} 인자를 채운다.
 * 요청의 "Authorization: Bearer 토큰"과 주소의 {projectId}를 출입 관리에 넘겨 요청자를 확인한다.
 * 요청 본문이나 주소에 적힌 사용자 id는 쓰지 않는다. 요청자는 항상 토큰에서 나온다.
 */
@Configuration
public class CallerResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

	private final IdentityAccess identity;

	public CallerResolver(IdentityAccess identity) {
		this.identity = identity;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(this);
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType().equals(Caller.class);
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest request, WebDataBinderFactory binderFactory) {
		@SuppressWarnings("unchecked")
		Map<String, String> pathVariables = (Map<String, String>) request.getAttribute(
				HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
		String projectId = pathVariables == null ? null : pathVariables.get("projectId");
		if (projectId == null) {
			throw new IllegalStateException("Caller 인자는 주소에 {projectId}가 있는 함수에서만 쓴다");
		}
		UUID project;
		try {
			project = UUID.fromString(projectId);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("프로젝트 id가 올바르지 않다: " + projectId);
		}
		return identity.authenticate(bearer(request.getHeader(HttpHeaders.AUTHORIZATION)), project);
	}

	/** "Bearer 토큰"에서 토큰만 꺼낸다. 형식이 다르면 로그인이 필요하다고 알린다. */
	static String bearer(String authorization) {
		if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			throw new UnauthenticatedException("로그인이 필요하다");
		}
		return authorization.substring(7).strip();
	}
}
