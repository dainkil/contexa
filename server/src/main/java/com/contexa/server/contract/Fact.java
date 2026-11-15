package com.contexa.server.contract;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 답변서에 담기는 사실 하나(카드 한 장).
 *
 * @param node     카드와 현재 버전
 * @param status   확인 상태. CANDIDATE면 AI 후보다
 * @param label    사람이 읽는 한 줄
 * @param states   카드 종류별 상태. 예: 조건의 state, 할 일의 workStatus
 * @param evidence 이 카드의 근거 발언. 비어 있으면 근거가 연결되지 않은 것이다
 */
public record Fact(NodeRef node, ReviewStatus status, String label, Map<String, String> states,
		UUID confirmedBy, Instant confirmedAt, List<EvidenceRef> evidence) {

	public Fact {
		evidence = List.copyOf(evidence);
	}
}
