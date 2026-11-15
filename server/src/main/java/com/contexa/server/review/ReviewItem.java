package com.contexa.server.review;

import java.util.List;
import java.util.UUID;

import com.contexa.server.contract.Edge;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeView;

/**
 * 검토 묶음: 카드 한 장과 그 카드에 딸린 줄.
 *
 * @param version 묶음이 바뀔 때마다 오른다. 처리할 때 "내가 본 버전"으로 보낸다
 * @param state   확인할 것이 남아 있으면 OPEN
 * @param card    카드 내용. 아직 담당 부서가 없는 종류(발언 등)면 null
 * @param edges   이 카드에 딸린 살아 있는 줄
 */
public record ReviewItem(UUID id, long version, State state, NodeRef owner, NodeView card, List<Edge> edges) {

	public enum State {
		OPEN, DONE
	}

	public ReviewItem {
		edges = List.copyOf(edges);
	}
}
