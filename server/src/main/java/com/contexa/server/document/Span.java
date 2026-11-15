package com.contexa.server.document;

import java.util.UUID;

import com.contexa.server.contract.NodeRef;

/**
 * 발언 한 조각.
 *
 * @param ref 결정 그래프에서 이 발언을 가리키는 SPAN 참조(현재 버전)
 * @param seq 그 판 안에서의 순서. "22번 발언"의 22
 */
public record Span(NodeRef ref, UUID projectId, UUID documentId, UUID sourceVersionId, int seq,
		String speakerLabel, String text) {
}
