package com.contexa.server.contract;

import java.util.UUID;

/**
 * 답변서에서 "아직 정리되지 않은 것" 하나. node와 edgeId 중 해당하는 쪽만 채운다.
 */
public record OpenIssue(Kind kind, NodeRef node, UUID edgeId) {

	public enum Kind {
		/** 검토자가 판단을 보류한 카드. 사실로 쓰지 않는다. */
		HELD_NODE,
		/** 검토자가 판단을 보류한 줄. 따라가지 않는다. */
		HELD_EDGE,
		/** 확정된 결정이 둘 이상 겹친다. 어느 쪽이 유효한지 사람이 정해야 한다. */
		CONTESTED_DECISION,
		/** 아직 충족되지 않은 조건. */
		UNMET_CONDITION,
		/** 같은 이름 표기에 해당하는 인물·기관이 여럿이다. 어느 쪽인지 사람이 정해야 한다. */
		AMBIGUOUS_NAME
	}

	public static OpenIssue ofNode(Kind kind, NodeRef node) {
		return new OpenIssue(kind, node, null);
	}

	public static OpenIssue ofEdge(UUID edgeId) {
		return new OpenIssue(Kind.HELD_EDGE, null, edgeId);
	}
}
