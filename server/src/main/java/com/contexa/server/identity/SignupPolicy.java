package com.contexa.server.identity;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 누가 스스로 가입할 수 있나. 질문과 문서 처리마다 모델 비용이 나가므로 배포에서는 허용한 이메일만 받는다.
 *
 * <ul>
 * <li>{@code contexa.auth.allowed-emails}(쉼표 목록)가 비어 있으면 누구나 가입한다(개발).</li>
 * <li>{@code contexa.auth.require-allowlist=true}(배포)인데 목록이 비어 있으면 server를 켜지 않는다.
 * 설정을 빠뜨려 가입이 열린 채로 공개되는 일을 막는다.</li>
 * </ul>
 * 시연·평가 데이터(dev 패키지)는 이 검사를 거치지 않는다. 그 주소는 배포에서 꺼져 있다.
 */
@Component
public class SignupPolicy {

	private final Set<String> allowed;

	public SignupPolicy(@Value("${contexa.auth.allowed-emails:}") List<String> allowedEmails,
			@Value("${contexa.auth.require-allowlist:false}") boolean requireAllowlist) {
		this.allowed = allowedEmails.stream().map(SignupPolicy::normalize).filter(email -> !email.isEmpty())
				.collect(Collectors.toUnmodifiableSet());
		if (requireAllowlist && allowed.isEmpty()) {
			throw new IllegalStateException("배포 설정인데 가입 허용 목록(contexa.auth.allowed-emails)이 비어 있다");
		}
	}

	/** @throws NotAllowedException 허용 목록이 있는데 그 안에 없는 이메일 */
	public void check(String email) {
		if (!allowed.isEmpty() && !allowed.contains(normalize(email))) {
			throw new NotAllowedException("초대된 이메일만 가입할 수 있다");
		}
	}

	private static String normalize(String email) {
		return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
	}
}
