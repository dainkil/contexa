package com.contexa.server.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class SignupPolicyTest {

	@Test
	void 목록이_비어_있으면_누구나_가입한다() {
		assertThatCode(() -> new SignupPolicy(List.of(), false).check("anyone@x.test")).doesNotThrowAnyException();
	}

	@Test
	void 목록이_있으면_그_안의_이메일만_대소문자와_공백을_무시하고_받는다() {
		SignupPolicy policy = new SignupPolicy(List.of(" Me@X.test ", "friend@x.test"), true);

		assertThatCode(() -> policy.check("me@x.test")).doesNotThrowAnyException();
		assertThatThrownBy(() -> policy.check("stranger@x.test")).isInstanceOf(NotAllowedException.class);
		assertThatThrownBy(() -> policy.check(null)).isInstanceOf(NotAllowedException.class);
	}

	@Test
	void 배포_설정인데_목록이_비어_있으면_켜지지_않는다() {
		assertThatThrownBy(() -> new SignupPolicy(List.of(""), true)).isInstanceOf(IllegalStateException.class);
	}
}
