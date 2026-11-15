package com.contexa.server.review;

import java.util.UUID;

/** 그런 검토 항목이 없다(다른 프로젝트의 것도 없는 것으로 본다). */
public class ReviewNotFoundException extends RuntimeException {

	public ReviewNotFoundException(UUID reviewId) {
		super("검토 항목이 없다: " + reviewId);
	}
}
