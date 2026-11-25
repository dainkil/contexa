package com.contexa.server.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 배포({@code contexa.worker.require-strong-token=true})에서는 일꾼 토큰이 개발용 기본값이거나 짧으면 server를 켜지 않는다.
 * 일꾼 토큰을 아는 사람은 일꾼인 척 작업을 가져가고 결과를 낼 수 있기 때문이다.
 */
@Component
class WorkerTokenCheck {

	static final String DEV_TOKEN = "dev-worker-token";
	static final int MIN_LENGTH = 32;

	WorkerTokenCheck(@Value("${contexa.worker.token}") String token,
			@Value("${contexa.worker.require-strong-token:false}") boolean requireStrong) {
		if (requireStrong) {
			validate(token);
		}
	}

	static void validate(String token) {
		if (token == null || token.equals(DEV_TOKEN) || token.length() < MIN_LENGTH) {
			throw new IllegalStateException("배포 설정인데 일꾼 토큰(CONTEXA_WORKER_TOKEN)이 개발용 값이거나 "
					+ MIN_LENGTH + "자보다 짧다. openssl rand -hex 32 같은 긴 무작위 값을 넣는다");
		}
	}
}
