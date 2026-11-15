package com.contexa.server.generation;

import java.util.List;

import com.contexa.server.contract.EdgeRef;
import com.contexa.server.contract.NodeRef;

/**
 * AI 인턴이 써 온 글과, 그 글이 무엇을 보고 쓰였는지.
 *
 * @param content   본문. 근거는 [1], [2] 처럼 번호로 적는다
 * @param usedNodes 참고한 카드와 발언, 읽었을 때의 버전
 * @param usedEdges 참고한 줄, 읽었을 때의 버전
 * @param citations 본문의 번호가 가리키는 발언. 첫 번째가 [1]이다
 */
public record Draft(String content, List<NodeRef> usedNodes, List<EdgeRef> usedEdges, List<NodeRef> citations) {

	public Draft {
		usedNodes = usedNodes == null ? List.of() : List.copyOf(usedNodes);
		usedEdges = usedEdges == null ? List.of() : List.copyOf(usedEdges);
		citations = citations == null ? List.of() : List.copyOf(citations);
	}
}
