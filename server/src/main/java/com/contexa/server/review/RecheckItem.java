package com.contexa.server.review;

import java.util.List;

/**
 * 검토함 "다시 확인"의 한 장: 다시 볼 카드의 검토 묶음(지금 상태)과, 다시 봐야 하는 이유.
 *
 * @param causes 이유. 먼저 생긴 것부터
 */
public record RecheckItem(ReviewItem review, List<RecheckCauseView> causes) {

	public RecheckItem {
		causes = List.copyOf(causes);
	}
}
