package com.contexa.server.contract;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;


/**
 * 카드 한 장을 읽은 결과.
 *
 * @param ref    카드와 현재 버전
 * @param label  사람이 읽는 한 줄(제목, 결정 문장, 조건 설명)
 * @param states 카드 종류별 상태. 예: 조건은 state, 할 일은 workStatus·acceptance
 */
public record NodeView(NodeRef ref, UUID projectId, ReviewStatus status, String label,
		Map<String, String> states, UUID confirmedBy, Instant confirmedAt) {
}
