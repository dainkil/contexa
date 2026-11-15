package com.contexa.server.contract;

import java.util.UUID;

/**
 * 카드의 근거가 되는 발언 하나.
 *
 * @param span   근거 발언
 * @param edgeId 카드와 발언을 잇는 "근거다" 줄
 * @param status 그 줄의 확인 상태. 후보면 AI가 근거라고 본 것일 뿐이다
 */
public record EvidenceRef(NodeRef span, UUID edgeId, ReviewStatus status) {
}
