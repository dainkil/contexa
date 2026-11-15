package com.contexa.server.generation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.contexa.server.contract.Source;
import com.contexa.server.contract.StaleRef;

/**
 * 산출물 하나를 연 모습.
 *
 * @param status    PENDING(쓰는 중), PUBLISHED(완성), FAILED(쓰지 못함)
 * @param content   본문. 근거는 [1], [2] 처럼 번호로 적혀 있다. 완성 전에는 null
 * @param citations 번호가 가리키는 발언의 원문. 첫 번째가 [1]. 지금 읽을 수 없는 발언은 text가 null이다
 * @param stale     이 글이 참고한 카드·발언·줄 중 그사이 바뀐 것. 비어 있지 않으면 "다시 확인 필요"다
 * @param failure   FAILED일 때의 이유
 */
public record ArtifactView(UUID id, GenKind kind, String question, String lang, Status status, String content,
		List<Source> citations, List<StaleRef> stale, String failure, Instant createdAt, Instant publishedAt) {

	public enum Status {
		PENDING, PUBLISHED, FAILED
	}

	public boolean needsRecheck() {
		return !stale.isEmpty();
	}
}
