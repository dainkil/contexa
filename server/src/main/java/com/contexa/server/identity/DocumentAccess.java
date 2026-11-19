package com.contexa.server.identity;

/**
 * 문서 하나의 공개 범위.
 *
 * @param visibility 구성원 모두가 읽는지, 올린 사람만 읽는지(개인 메모)
 * @param externalOk 외부로 나가는 답변과 외부 에이전트에게 근거로 써도 되는가
 */
public record DocumentAccess(Visibility visibility, boolean externalOk) {
}
