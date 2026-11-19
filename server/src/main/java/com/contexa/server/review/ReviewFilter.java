package com.contexa.server.review;

import com.contexa.server.contract.NodeType;

/**
 * 검토함 조회 조건.
 *
 * @param state     null이면 OPEN
 * @param ownerType null이면 모든 종류
 * @param limit     한 번에 훑을 묶음 수. null이면 {@link #DEFAULT_LIMIT}, 1~{@link #MAX_LIMIT}
 * @param after     앞 쪽의 {@link ReviewPage#next()}. null이면 처음부터
 */
public record ReviewFilter(ReviewItem.State state, NodeType ownerType, Integer limit, String after) {

	public static final int DEFAULT_LIMIT = 50;
	public static final int MAX_LIMIT = 200;

	public ReviewFilter(ReviewItem.State state, NodeType ownerType) {
		this(state, ownerType, null, null);
	}

	public static ReviewFilter open() {
		return new ReviewFilter(ReviewItem.State.OPEN, null);
	}
}
