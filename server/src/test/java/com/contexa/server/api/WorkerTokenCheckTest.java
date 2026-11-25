package com.contexa.server.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WorkerTokenCheckTest {

	@Test
	void 배포에서는_개발용_토큰이나_짧은_토큰으로_켜지지_않는다() {
		assertThatThrownBy(() -> WorkerTokenCheck.validate("dev-worker-token")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> WorkerTokenCheck.validate("short")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> WorkerTokenCheck.validate(null)).isInstanceOf(IllegalStateException.class);
		assertThatCode(() -> WorkerTokenCheck.validate("a".repeat(64))).doesNotThrowAnyException();
	}

	@Test
	void 개발에서는_검사하지_않는다() {
		assertThatCode(() -> new WorkerTokenCheck("dev-worker-token", false)).doesNotThrowAnyException();
	}
}
