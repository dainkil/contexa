package com.contexa.server.review;

import java.util.List;
import java.util.UUID;

import com.contexa.server.contract.Edge;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeView;
import com.contexa.server.contract.Source;

/**
 * 검토 묶음: 카드 한 장과 그 카드에 딸린 줄.
 *
 * @param version 묶음이 바뀔 때마다 오른다. 처리할 때 "내가 본 버전"으로 보낸다
 * @param state   확인할 것이 남아 있으면 OPEN
 * @param card    카드 내용. 아직 담당 부서가 없는 종류(발언 등)면 null
 * @param edges   이 카드에 딸린 살아 있는 줄
 * @param related 줄 건너편 카드의 내용. 근거를 하나도 읽을 수 없는 카드는 빠진다(줄은 edges에 그대로 있다)
 * @param sources 이 묶음의 줄이 가리키거나 근거로 대는 발언의 원문. 읽을 수 있는 문서의 것만 실린다
 */
public record ReviewItem(UUID id, long version, State state, NodeRef owner, NodeView card, List<Edge> edges,
		List<NodeView> related, List<Source> sources) {

	public enum State {
		OPEN, DONE
	}

	public ReviewItem {
		edges = List.copyOf(edges);
		related = List.copyOf(related);
		sources = List.copyOf(sources);
	}
}
