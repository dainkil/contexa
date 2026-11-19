package com.contexa.server.document;

import java.time.Instant;
import java.util.UUID;

/**
 * 문서의 한 판.
 *
 * @param spanCount 이 판을 잘라낸 발언 수. 아직 자르지 않았으면 0
 */
public record VersionSummary(UUID sourceVersionId, int versionNo, Instant createdAt, int spanCount) {
}
