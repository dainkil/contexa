package com.contexa.server.entity;

import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.ReviewStatus;

/**
 * 어떤 이름 표기에 해당할 만한 인물·기관 하나.
 *
 * @param matchedForm   맞아떨어진 표기
 * @param lang          그 표기의 언어. 대표 표기로 맞았으면 null
 * @param formConfirmed 그 표기가 사람이 확인한 것인가. 아니면 제안 표기다
 * @param exact         표기 전체가 일치했는가. 아니면 일부만 겹친 것이다
 */
public record EntityCandidate(NodeRef entity, ReviewStatus status, String name, String matchedForm, String lang,
		boolean formConfirmed, boolean exact) {
}
