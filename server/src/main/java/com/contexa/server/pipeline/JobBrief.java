package com.contexa.server.pipeline;

import java.time.Instant;
import java.util.UUID;

import com.contexa.server.job.Job;
import com.contexa.server.job.JobStatus;

/**
 * 화면에 보여 줄 작업 상태. 누가 시켰는지, 무엇에 대한 일인지는 빼고 진행 상태만 담는다.
 *
 * @param attempts  지금까지 일꾼이 가져간 횟수. 실패하면 최대 3번까지 다시 시도한다
 * @param lastError 마지막으로 실패한 이유. 다시 시도해서 성공했어도 남아 있을 수 있다
 */
public record JobBrief(UUID id, JobStatus status, int attempts, String lastError, Instant createdAt,
		Instant finishedAt) {

	static JobBrief of(Job job) {
		return job == null ? null
				: new JobBrief(job.id(), job.status(), job.attempts(), job.lastError(), job.createdAt(),
						job.finishedAt());
	}
}
