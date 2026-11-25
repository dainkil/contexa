package com.contexa.server.review;

import java.util.List;

/**
 * 검토함 "다시 확인" 한 쪽. 다시 볼 이유가 먼저 생긴 카드부터 나온다.
 * 이 사람이 볼 수 없는 카드는 빠지므로 items가 limit보다 적어도 next가 있을 수 있다.
 *
 * @param next 다음 쪽을 부를 때 after로 보낸다. 더 없으면 null
 */
public record RecheckPage(List<RecheckItem> items, String next) {

	public RecheckPage {
		items = List.copyOf(items);
	}
}
