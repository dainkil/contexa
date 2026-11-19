package com.contexa.server.pipeline;

import java.time.Instant;
import java.util.UUID;

/**
 * 문서의 한 판과 그 판을 처리하는 작업들의 상태.
 *
 * @param spanCount 이 판을 잘라낸 발언 수. 아직 자르지 않았으면 0
 * @param parse     발언으로 자르는 작업. 없으면 null
 * @param extract   카드·줄 후보를 뽑는 작업. 자르기가 끝나야 생긴다
 */
public record VersionStatus(UUID sourceVersionId, int versionNo, Instant createdAt, int spanCount, JobBrief parse,
		JobBrief extract) {
}
