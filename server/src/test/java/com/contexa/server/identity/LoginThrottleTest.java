package com.contexa.server.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/** 로그인 실패 세기: 창 안에서 한도를 넘으면 막고, 시간이 지나거나 맞히면 풀린다. */
class LoginThrottleTest {

	/** 테스트가 시간을 앞으로 돌릴 수 있는 시계. */
	static class MovingClock extends Clock {
		Instant now = Instant.parse("2026-11-25T10:00:00Z");

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}
	}

	MovingClock clock = new MovingClock();
	LoginThrottle throttle = new LoginThrottle(3, 5, Duration.ofMinutes(15), clock);

	@Test
	void 같은_이메일로_한도만큼_틀리면_막고_가장_오래된_실패가_창을_벗어나면_풀린다() {
		for (int i = 0; i < 3; i++) {
			throttle.checkAllowed("A@x.test", "1.1.1.1");
			throttle.failed("a@x.test", "1.1.1.1");
			clock.now = clock.now.plusSeconds(60);
		}
		assertThatThrownBy(() -> throttle.checkAllowed("a@x.test", "2.2.2.2"))
				.isInstanceOf(TooManyAttemptsException.class)
				.satisfies(e -> assertThat(((TooManyAttemptsException) e).retryAfter()).isEqualTo(Duration.ofMinutes(12)));

		clock.now = clock.now.plus(Duration.ofMinutes(12));
		throttle.checkAllowed("a@x.test", "2.2.2.2");
	}

	@Test
	void 같은_주소에서_여러_이메일로_찍어_보는_것도_막는다() {
		for (int i = 0; i < 5; i++) {
			throttle.failed("user" + i + "@x.test", "9.9.9.9");
		}
		assertThatThrownBy(() -> throttle.checkAllowed("new@x.test", "9.9.9.9"))
				.isInstanceOf(TooManyAttemptsException.class);
		throttle.checkAllowed("new@x.test", "8.8.8.8");
	}

	@Test
	void 맞히면_그_이메일의_실패_기록은_지운다() {
		throttle.failed("a@x.test", "1.1.1.1");
		throttle.failed("a@x.test", "1.1.1.2");
		throttle.succeeded("a@x.test");
		throttle.failed("a@x.test", "1.1.1.3");
		throttle.failed("a@x.test", "1.1.1.4");

		throttle.checkAllowed("a@x.test", "1.1.1.5");
	}
}
