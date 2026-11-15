package com.contexa.server.decision;

import java.util.List;

import com.contexa.server.contract.NodeRef;

/**
 * 결정 항목 하나의 "지금 유효한 결정".
 *
 * @param item       결정 항목과 현재 버전
 * @param current    DECIDED일 때만 있는 현재 결정 사건
 * @param contenders CONTESTED일 때 서로 겹치는 사건들(일어난 순서)
 */
public record DecisionState(NodeRef item, DecisionStatus status, NodeRef current, List<NodeRef> contenders) {

	public DecisionState {
		contenders = List.copyOf(contenders);
	}
}
