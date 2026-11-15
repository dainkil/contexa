package com.contexa.server.contract;

import java.util.List;

/** AI나 외부 에이전트가 한 번에 제출하는 카드 후보와 줄 후보. */
public record GraphCandidates(List<NodeDraft> nodes, List<EdgeDraft> edges) {

	public GraphCandidates {
		nodes = nodes == null ? List.of() : List.copyOf(nodes);
		edges = edges == null ? List.of() : List.copyOf(edges);
	}
}
