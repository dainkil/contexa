package com.contexa.server.contract;

import java.util.UUID;

/**
 * 답변서가 무엇에 대한 것인지.
 *
 * @param focus     기준이 된 카드. 프로젝트 전체 현황이면 null
 * @param direction TRACE일 때 따라간 방향
 * @param depth     TRACE일 때 따라간 칸 수
 */
public record Scope(UUID projectId, View view, NodeRef focus, Direction direction, Integer depth) {

	public enum View {
		TRACE, CURRENT_STATE, DECISION_HISTORY, TASKS, RESOLVE_ENTITY, SEARCH_EVIDENCE
	}
}
