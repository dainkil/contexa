package com.contexa.server.review;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.contexa.server.contract.NodeKey;
import com.contexa.server.decision.RecheckReason;

/**
 * 카드를 다시 봐야 하는 이유 하나.
 *
 * @param changed  바뀐 카드나 발언
 * @param label    바뀐 것의 내용(카드 제목, 발언 원문). 이 사람이 볼 수 없는 것이면 null
 * @param distance 다시 볼 카드에서 몇 칸 떨어져 있나
 * @param actorId  바꾼 사람
 * @param at       바뀐 때
 */
public record RecheckCauseView(NodeKey changed, String label, RecheckReason reason, int distance, UUID actorId,
		OffsetDateTime at) {
}
