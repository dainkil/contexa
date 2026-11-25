package com.contexa.server.identity;

import java.time.Duration;

/** 로그인을 짧은 시간에 너무 많이 틀렸다. retryAfter 뒤에 다시 할 수 있다. */
public class TooManyAttemptsException extends RuntimeException {

	private final Duration retryAfter;

	public TooManyAttemptsException(Duration retryAfter) {
		super("로그인 시도가 너무 많다. " + Math.max(1, retryAfter.toMinutes()) + "분쯤 뒤에 다시 해 주세요");
		this.retryAfter = retryAfter;
	}

	public Duration retryAfter() {
		return retryAfter;
	}
}
