package com.contexa.server.identity;

/** 이 요청자는 이 일을 할 수 없다. 예: AI 작업이나 외부 에이전트가 확인(도장)을 시도함. */
public class NotAllowedException extends RuntimeException {

	public NotAllowedException(String message) {
		super(message);
	}
}
