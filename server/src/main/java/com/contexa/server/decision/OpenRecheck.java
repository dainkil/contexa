package com.contexa.server.decision;

import java.time.OffsetDateTime;
import java.util.List;

import com.contexa.server.contract.NodeKey;

/**
 * 다시 확인할 카드 한 장과 아직 닫히지 않은 이유들.
 *
 * @param since  가장 먼저 열린 이유의 시각. 검토함은 이 순서로 보여 준다
 * @param causes 이유. 먼저 열린 것부터
 */
public record OpenRecheck(NodeKey node, OffsetDateTime since, List<RecheckCause> causes) {

	public OpenRecheck {
		causes = List.copyOf(causes);
	}
}
