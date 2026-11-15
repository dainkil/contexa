package com.contexa.server.review;

import java.util.List;

import com.contexa.server.contract.NodeRef;

/** 바뀐 카드(현재 버전)와, 그 변경으로 영향받는 카드들. */
public record Changed(NodeRef node, List<Impact> impacts) {

	public Changed {
		impacts = List.copyOf(impacts);
	}
}
