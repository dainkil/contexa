package com.contexa.server.gateway;

import java.util.UUID;

/**
 * 할 일 조회 조건.
 *
 * @param mine             요청자 본인에게 맡겨진 할 일만. 계정이 인물 카드에 연결되어 있어야 한다
 * @param assigneeEntityId 이 인물·기관에게 맡겨진 할 일만. mine이 참이면 무시한다
 * @param workStatus       이 진행 상태인 할 일만. 예: "PLANNED"
 */
public record TaskFilter(boolean mine, UUID assigneeEntityId, String workStatus) {

	public static TaskFilter all() {
		return new TaskFilter(false, null, null);
	}

	public static TaskFilter ofMine() {
		return new TaskFilter(true, null, null);
	}
}
