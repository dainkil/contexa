package com.contexa.server.contract;

import java.time.Instant;
import java.util.List;

/**
 * 맥락 패킷: 안내 데스크가 돌려주는 표준 답변서. 화면, Q&A, 외부 AI가 모두 같은 양식을 받는다.
 *
 * @param asOf      이 답변서를 만든 시각
 * @param facts     카드 목록. 카드마다 확인 상태와 근거가 붙어 있다
 * @param relations 카드 사이의 줄. 줄마다 확인 상태가 붙어 있다
 * @param open      보류, 경합, 미충족 조건처럼 아직 정리되지 않은 것
 * @param sources   근거나 줄의 끝점으로 나온 발언의 원문. 요청자가 읽을 수 있는 것만 실린다
 */
public record ContextPacket(Instant asOf, Scope scope, List<Fact> facts, List<Edge> relations,
		List<OpenIssue> open, List<Source> sources) {

	public ContextPacket {
		facts = List.copyOf(facts);
		relations = List.copyOf(relations);
		open = List.copyOf(open);
		sources = List.copyOf(sources);
	}
}
