package com.contexa.server.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.contexa.server.contract.Edge;
import com.contexa.server.contract.NodeKey;

/**
 * 한 카드에서 줄을 따라가 닿은 범위.
 * depths에는 출발 카드(0)와 닿은 카드마다 가장 짧은 거리가, 발견한 순서대로 들어 있다.
 */
public record Subgraph(NodeKey start, List<Edge> edges, Map<NodeKey, Integer> depths) {

	public Subgraph {
		edges = List.copyOf(edges);
		depths = Collections.unmodifiableMap(new LinkedHashMap<>(depths));
	}

	public boolean reaches(NodeKey node) {
		return depths.containsKey(node);
	}

	/** 출발 카드에서 몇 칸 떨어져 있는가. 닿지 않았으면 -1. */
	public int depthOf(NodeKey node) {
		return depths.getOrDefault(node, -1);
	}
}
