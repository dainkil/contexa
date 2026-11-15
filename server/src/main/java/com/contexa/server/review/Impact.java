package com.contexa.server.review;

import com.contexa.server.contract.NodeKey;

/**
 * 바뀐 카드에 기대고 있어서 다시 확인해야 하는 카드.
 *
 * @param distance 바뀐 카드에서 몇 칸 떨어져 있는가
 */
public record Impact(NodeKey changed, NodeKey affected, int distance) {
}
