package com.contexa.server.review;

import java.util.UUID;

/** 내가 본 뒤에 이 묶음이 바뀌었다(새 후보가 들어왔거나 다른 검토자가 먼저 처리했다). */
public class ReviewVersionConflictException extends RuntimeException {

	public ReviewVersionConflictException(UUID reviewId, long expected, long actual) {
		super("검토 항목 " + reviewId + "이 이미 바뀌었다: 기대한 버전 " + expected + ", 현재 버전 " + actual);
	}
}
