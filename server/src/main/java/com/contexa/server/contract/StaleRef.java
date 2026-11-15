package com.contexa.server.contract;

/**
 * 참고했던 카드나 줄이 그사이 바뀌었다. node와 edge 중 해당하는 쪽만 채운다.
 *
 * @param currentVersion 지금 버전. 더 이상 찾을 수 없으면 null
 */
public record StaleRef(NodeRef node, EdgeRef edge, Long currentVersion) {
}
