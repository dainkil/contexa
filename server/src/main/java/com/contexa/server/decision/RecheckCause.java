package com.contexa.server.decision;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.contexa.server.contract.NodeKey;

/**
 * 카드를 다시 확인해야 하는 이유 하나.
 *
 * @param changed  바뀐 카드나 발언
 * @param reason   어떤 일로 바뀌었나
 * @param distance 다시 볼 카드에서 몇 칸 떨어져 있나
 * @param actorId  바꾼 사람
 * @param at       바뀐 때
 */
public record RecheckCause(NodeKey changed, RecheckReason reason, int distance, UUID actorId, OffsetDateTime at) {
}
