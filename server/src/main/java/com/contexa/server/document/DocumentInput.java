package com.contexa.server.document;

import com.contexa.server.identity.Visibility;

/**
 * 새로 올리는 문서.
 *
 * @param language   문서의 언어(ko, en, ar). 혼합언어면 null
 * @param visibility 구성원 모두가 읽는지, 올린 사람만 읽는지(개인 메모)
 * @param externalOk 외부로 나가는 답변과 외부 에이전트에게 근거로 써도 되는가
 * @param content    글자 내용
 */
public record DocumentInput(String title, DocumentKind kind, String language, Visibility visibility,
		boolean externalOk, String content) {
}
