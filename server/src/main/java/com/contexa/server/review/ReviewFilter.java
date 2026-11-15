package com.contexa.server.review;

import com.contexa.server.contract.NodeType;

/**
 * 검토함 조회 조건.
 *
 * @param state     null이면 OPEN
 * @param ownerType null이면 모든 종류
 */
public record ReviewFilter(ReviewItem.State state, NodeType ownerType) {

	public static ReviewFilter open() {
		return new ReviewFilter(ReviewItem.State.OPEN, null);
	}
}
