package com.contexa.server.review;

import com.contexa.server.contract.NodeKey;

/**
 * "바뀐 것 없음"으로 닫은 결과.
 *
 * @param resolved 닫은 이유의 수
 */
public record RecheckResolved(NodeKey node, int resolved) {
}
