package com.contexa.server.job;

/** 오늘 맡길 수 있는 AI 작업을 다 썼다. */
public class QuotaExceededException extends RuntimeException {

	public QuotaExceededException(String message) {
		super(message);
	}
}
