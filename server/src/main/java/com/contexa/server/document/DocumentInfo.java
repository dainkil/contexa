package com.contexa.server.document;

import java.time.Instant;
import java.util.UUID;

import com.contexa.server.identity.Visibility;

/**
 * 문서 한 건의 정보. 요청자가 읽을 수 있는 문서만 이 모양으로 나간다.
 *
 * @param language   문서의 언어(ko, en, ar). 혼합언어면 null
 * @param visibility 구성원 모두가 읽는지, 올린 사람만 읽는지(개인 메모)
 * @param externalOk 외부로 나가는 답변과 외부 에이전트에게 근거로 써도 되는가
 */
public record DocumentInfo(UUID id, String title, DocumentKind kind, String language, Visibility visibility,
		boolean externalOk, Instant createdAt) {
}
