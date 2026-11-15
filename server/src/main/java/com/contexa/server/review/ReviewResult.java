package com.contexa.server.review;

import java.util.List;

/** 처리한 뒤의 묶음과, 이 처리로 영향받는 카드들. */
public record ReviewResult(ReviewItem item, List<Impact> impacts) {

	public ReviewResult {
		impacts = List.copyOf(impacts);
	}
}
