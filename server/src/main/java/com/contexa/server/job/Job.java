package com.contexa.server.job;

import java.time.Instant;
import java.util.UUID;

/**
 * 맡긴 일 하나.
 *
 * @param subjectId   무엇에 대한 일인가. PARSE·EXTRACT는 문서의 판(sourceVersionId)
 * @param requestedBy 이 일을 시킨 사람. AI는 이 사람의 권한으로만 읽는다
 */
public record Job(UUID id, UUID projectId, JobType type, JobStatus status, UUID subjectId, String payload,
		UUID requestedBy, int attempts, String lastError, Instant createdAt, Instant finishedAt) {
}
