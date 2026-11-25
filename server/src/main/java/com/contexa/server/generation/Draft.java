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
 * @param citations 본문의 번호가 가리키는 발언. 첫 번째가 [1]이다. 사실을 말하는 답이면 하나 이상 있어야 한다
 * @param noEvidence 기록에서 답할 근거를 찾지 못했다는 짧은 답이다. 이때만 인용 없이 받는다(참고한 것도 없어야 한다)
 */
public record Draft(String content, List<NodeRef> usedNodes, List<EdgeRef> usedEdges, List<NodeRef> citations,
		Boolean noEvidence) {

	public Draft {
		usedNodes = usedNodes == null ? List.of() : List.copyOf(usedNodes);
		usedEdges = usedEdges == null ? List.of() : List.copyOf(usedEdges);
		citations = citations == null ? List.of() : List.copyOf(citations);
		noEvidence = Boolean.TRUE.equals(noEvidence);
	}

	/** 근거가 있는 답. 인용이 하나 이상 있어야 한다. */
	public Draft(String content, List<NodeRef> usedNodes, List<EdgeRef> usedEdges, List<NodeRef> citations) {
		this(content, usedNodes, usedEdges, citations, false);
	}
}
