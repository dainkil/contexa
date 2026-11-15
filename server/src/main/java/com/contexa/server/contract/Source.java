package com.contexa.server.contract;

import java.util.UUID;

/**
 * 답변서에 실리는 원문 발언 하나. 카드의 근거나 줄의 끝점으로 나온 발언의 실제 내용이다.
 *
 * @param span    발언과 현재 버전
 * @param seq     그 문서 판 안에서의 순서. "22번 발언"의 22
 * @param speaker 전사문에 적힌 화자 표기. 없으면 null
 */
public record Source(NodeRef span, UUID documentId, int seq, String speaker, String text) {
}
