package com.contexa.server.decision;

import java.util.Objects;

import com.contexa.server.contract.NodeKey;

/**
 * 남길 표시 하나: node가 cause에 distance 칸 떨어져 기대고 있다.
 *
 * @param node     다시 볼 카드
 * @param cause    바뀐 카드나 발언
 * @param distance 몇 칸 떨어져 있나. 1 이상
 */
public record RecheckMark(NodeKey node, NodeKey cause, int distance) {

	public RecheckMark {
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(cause, "cause");
		if (distance < 1) {
			throw new IllegalArgumentException("distance는 1 이상이어야 한다: " + distance);
		}
	}
}
