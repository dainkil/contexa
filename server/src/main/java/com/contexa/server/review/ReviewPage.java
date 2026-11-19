package com.contexa.server.review;

import java.util.List;

/**
 * 검토함 한 쪽.
 *
 * <p>확인할 것(OPEN)은 오래된 묶음부터, 처리한 것(DONE)은 최근에 바뀐 묶음부터 나온다.
 * 이 사람이 근거를 하나도 읽을 수 없는 묶음은 빠지므로 items가 limit보다 적을 수 있다.
 * 그래도 next가 있으면 뒤에 더 있다.
 *
 * @param next 다음 쪽을 부를 때 after로 보낸다. 더 없으면 null
 */
public record ReviewPage(List<ReviewItem> items, String next) {

	public ReviewPage {
		items = List.copyOf(items);
	}
}
