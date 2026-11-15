package com.contexa.server.review;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 검토 묶음 하나에 대한 한 번의 처리.
 *
 * @param card         카드에 대한 판단. null이면 카드는 그대로 둔다
 * @param corrections  카드 내용 정정. 예: {"title": "인쇄 발주(포스터)"}
 * @param edges        줄마다 따로 내리는 판단. 여기에 없는 줄은 카드 판단을 따라간다:
 *                     카드를 확인하면 후보 줄도 확인, 보류하면 보류, 거절·철회하면 딸린 줄도 거절·철회
 * @param edgeEvidence 줄을 확인하면서 근거 발언을 바꿔 적을 때
 */
public record ReviewAction(Verdict card, Map<String, Object> corrections, Map<UUID, Verdict> edges,
		Map<UUID, List<UUID>> edgeEvidence) {

	public ReviewAction {
		corrections = corrections == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(corrections));
		edges = edges == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(edges));
		edgeEvidence = edgeEvidence == null ? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(edgeEvidence));
	}

	/** 카드와 딸린 줄을 한 번에 같은 판단으로. */
	public static ReviewAction ofCard(Verdict card) {
		return new ReviewAction(card, null, null, null);
	}
}
