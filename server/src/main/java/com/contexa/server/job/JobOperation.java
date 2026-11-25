package com.contexa.server.job;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 접수대: AI 인턴에게 맡길 일을 줄 세우고, 가져간 일꾼에게서 결과를 받는다.
 *
 * <ul>
 * <li>일꾼은 일을 가져갈 때 임대증(시도 id와 임대 토큰)을 받는다. 결과는 그 임대증과 함께 내야 받는다.</li>
 * <li>임대 시간 안에 결과가 없으면 다른 일꾼이 다시 가져간다. 그러면 앞 일꾼의 임대증은 무효가 된다.</li>
 * <li>정해진 횟수만큼 실패하면 FAILED로 끝난다.</li>
 * <li>같은 일을 두 번 올리면(같은 idempotencyKey) 새로 만들지 않고 처음 것을 돌려준다.</li>
 * </ul>
 * 결과의 내용은 여기서 다루지 않는다. 내용을 알맞은 부서로 넘기는 것은 작업 흐름 관리(pipeline)의 일이다.
 */
@Service
public class JobOperation {

	static final Duration LEASE = Duration.ofMinutes(10);

	private static final String COLUMNS = """
			id, project_id, type, status, subject_id, payload, requested_by, attempts, last_error,
			created_at, finished_at
			""";

	private final JdbcClient jdbc;
	private final SecureRandom random = new SecureRandom();

	public JobOperation(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 일을 올린다. 같은 프로젝트에 같은 idempotencyKey의 일이 이미 있으면 그 일의 id를 돌려준다. */
	@Transactional
	public UUID enqueue(JobType type, UUID projectId, UUID subjectId, String payload, UUID requestedBy,
			String idempotencyKey) {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(subjectId, "subjectId");
		Objects.requireNonNull(requestedBy, "requestedBy");
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("idempotencyKey가 비어 있다");
		}
		Optional<UUID> created = jdbc.sql("""
				insert into job (id, project_id, type, subject_id, payload, requested_by, idempotency_key, created_at)
				values (:id, :projectId, :type, :subjectId, :payload, :requestedBy, :key, clock_timestamp())
				on conflict (project_id, idempotency_key) do nothing
				returning id
				""")
				.param("id", UUID.randomUUID()).param("projectId", projectId).param("type", type.name())
				.param("subjectId", subjectId).param("payload", payload).param("requestedBy", requestedBy)
				.param("key", idempotencyKey)
				.query((rs, n) -> rs.getObject("id", UUID.class))
				.optional();
		return created.orElseGet(() -> jdbc.sql(
				"select id from job where project_id = :projectId and idempotency_key = :key")
				.param("projectId", projectId).param("key", idempotencyKey)
				.query((rs, n) -> rs.getObject("id", UUID.class)).single());
	}

	/**
	 * 가장 오래 기다린 일을 가져간다. 없으면 빈 값.
	 * 임대 시간이 지난 일도 다시 가져갈 수 있다. 시도 횟수를 다 쓴 일은 FAILED로 끝낸다.
	 */
	@Transactional
	public Optional<JobLease> claim(String workerId) {
		if (workerId == null || workerId.isBlank()) {
			throw new IllegalArgumentException("workerId가 비어 있다");
		}
		while (true) {
			record Candidate(UUID id, int attempts, int maxAttempts) {
			}
			Optional<Candidate> next = jdbc.sql("""
					select id, attempts, max_attempts from job
					 where status = 'QUEUED' or (status = 'RUNNING' and lease_expires_at < now())
					 order by created_at, id
					 for update skip locked limit 1
					""")
					.query((rs, n) -> new Candidate(rs.getObject("id", UUID.class), rs.getInt("attempts"),
							rs.getInt("max_attempts")))
					.optional();
			if (next.isEmpty()) {
				return Optional.empty();
			}
			Candidate candidate = next.get();
			if (candidate.attempts() >= candidate.maxAttempts()) {
				finish(candidate.id(), JobStatus.FAILED, "임대 시간 안에 결과가 오지 않았다");
				continue;
			}
			UUID attemptId = UUID.randomUUID();
			byte[] bytes = new byte[32];
			random.nextBytes(bytes);
			String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
			OffsetDateTime expires = OffsetDateTime.now().plus(LEASE);
			jdbc.sql("""
					update job set status = 'RUNNING', attempts = attempts + 1, attempt_id = :attemptId,
					       lease_token_hash = :hash, leased_by = :worker, lease_expires_at = :expires,
					       updated_at = now()
					 where id = :id
					""")
					.param("attemptId", attemptId).param("hash", sha256(token)).param("worker", workerId.strip())
					.param("expires", expires).param("id", candidate.id()).update();
			return Optional.of(new JobLease(require(candidate.id()), attemptId, token, expires.toInstant()));
		}
	}

	/**
	 * 임대증이 지금도 유효한지 확인하고 그 일을 돌려준다. 일꾼이 일하는 중에 자료를 읽을 때 쓴다.
	 *
	 * @throws LeaseRejectedException 다른 시도의 임대증이거나, 만료됐거나, 이미 끝난 일
	 */
	@Transactional(readOnly = true)
	public Job requireLease(UUID jobId, UUID attemptId, String leaseToken) {
		checkLease(jobId, attemptId, leaseToken, false);
		return require(jobId);
	}

	/**
	 * 결과를 받아들인다. 임대증이 맞을 때만 SUCCEEDED로 끝낸다.
	 * 이미 끝난 일에 결과가 또 오면 거절한다.
	 */
	@Transactional
	public Job acceptResult(UUID jobId, UUID attemptId, String leaseToken) {
		checkLease(jobId, attemptId, leaseToken, true);
		finish(jobId, JobStatus.SUCCEEDED, null);
		return require(jobId);
	}

	/**
	 * 일꾼이 실패를 알린다. 다시 해 볼 만하고 시도 횟수가 남았으면 줄 맨 뒤가 아니라 원래 자리에서 다시 기다리고,
	 * 아니면 FAILED로 끝난다.
	 */
	@Transactional
	public Job fail(UUID jobId, UUID attemptId, String leaseToken, String reason, boolean retryable) {
		checkLease(jobId, attemptId, leaseToken, true);
		record Attempts(int attempts, int max) {
		}
		Attempts attempts = jdbc.sql("select attempts, max_attempts from job where id = :id").param("id", jobId)
				.query((rs, n) -> new Attempts(rs.getInt("attempts"), rs.getInt("max_attempts"))).single();
		String error = reason == null || reason.isBlank() ? "이유 없음" : truncate(reason.strip());
		if (retryable && attempts.attempts() < attempts.max()) {
			jdbc.sql("""
					update job set status = 'QUEUED', last_error = :error, lease_token_hash = null,
					       lease_expires_at = null, updated_at = now()
					 where id = :id
					""")
					.param("error", error).param("id", jobId).update();
		} else {
			finish(jobId, JobStatus.FAILED, error);
		}
		return require(jobId);
	}

	/**
	 * 실패로 끝난 일을 다시 맡긴다. 시도 횟수를 0으로 되돌리고, 처음 올린 시각의 자리에서 다시 기다린다.
	 * 마지막 실패 이유는 지우지 않는다(다시 시도해서 성공한 일에도 남는 것과 같다). 누가 다시 맡길 수 있는지는 호출한 쪽이 정한다.
	 *
	 * @throws JobNotFoundException  이 프로젝트에 없는 일
	 * @throws IllegalStateException 실패로 끝난 일이 아니다(이미 다시 맡겼거나, 진행 중이거나, 성공했거나, 취소됐다)
	 */
	@Transactional
	public Job retry(UUID projectId, UUID jobId) {
		int updated = jdbc.sql("""
				update job set status = 'QUEUED', attempts = 0, attempt_id = null, lease_token_hash = null,
				       leased_by = null, lease_expires_at = null, finished_at = null, updated_at = now()
				 where id = :id and project_id = :projectId and status = 'FAILED'
				""")
				.param("id", jobId).param("projectId", projectId).update();
		if (updated == 0) {
			Job job = get(projectId, jobId);
			throw new IllegalStateException("실패로 끝난 작업만 다시 맡길 수 있다(지금 " + job.status() + ")");
		}
		return require(jobId);
	}

	/** 아직 끝나지 않은 일을 취소한다. 일하던 일꾼의 임대증은 무효가 된다. */
	@Transactional
	public Job cancel(UUID projectId, UUID jobId) {
		Job job = get(projectId, jobId);
		if (job.status() == JobStatus.QUEUED || job.status() == JobStatus.RUNNING) {
			finish(jobId, JobStatus.CANCELLED, null);
		}
		return require(jobId);
	}

	@Transactional(readOnly = true)
	public Job get(UUID projectId, UUID jobId) {
		return jdbc.sql("select " + COLUMNS + " from job where id = :id and project_id = :projectId")
				.param("id", jobId).param("projectId", projectId)
				.query(JobOperation::mapJob).optional()
				.orElseThrow(() -> new JobNotFoundException(jobId));
	}

	/** 이 프로젝트의 일. 최근 것부터. */
	@Transactional(readOnly = true)
	public List<Job> list(UUID projectId, int limit) {
		return jdbc.sql("select " + COLUMNS + " from job where project_id = :projectId"
				+ " order by created_at desc, id limit :limit")
				.param("projectId", projectId).param("limit", Math.max(1, Math.min(limit, 200)))
				.query(JobOperation::mapJob).list();
	}

	/** 이 프로젝트에서 주어진 대상(문서의 판 등)에 대한 일. 오래된 것부터. */
	@Transactional(readOnly = true)
	public List<Job> bySubjects(UUID projectId, Collection<UUID> subjectIds) {
		Objects.requireNonNull(projectId, "projectId");
		if (subjectIds == null || subjectIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("select " + COLUMNS + " from job where project_id = :projectId and subject_id in (:ids)"
				+ " order by created_at, id")
				.param("projectId", projectId).param("ids", subjectIds)
				.query(JobOperation::mapJob).list();
	}

	// ---- 내부 ----

	private void checkLease(UUID jobId, UUID attemptId, String leaseToken, boolean lock) {
		record Lease(String status, UUID attemptId, String hash, OffsetDateTime expires) {
		}
		Lease lease = jdbc.sql("select status, attempt_id, lease_token_hash, lease_expires_at from job"
				+ " where id = :id" + (lock ? " for update" : ""))
				.param("id", jobId)
				.query((rs, n) -> new Lease(rs.getString("status"), rs.getObject("attempt_id", UUID.class),
						rs.getString("lease_token_hash"), rs.getObject("lease_expires_at", OffsetDateTime.class)))
				.optional()
				.orElseThrow(() -> new JobNotFoundException(jobId));
		if (!lease.status().equals("RUNNING")) {
			throw new LeaseRejectedException(jobId, "진행 중인 작업이 아니다(" + lease.status() + ")");
		}
		boolean sameToken = leaseToken != null && lease.hash() != null && MessageDigest.isEqual(
				lease.hash().getBytes(StandardCharsets.UTF_8), sha256(leaseToken).getBytes(StandardCharsets.UTF_8));
		if (!sameToken || !Objects.equals(lease.attemptId(), attemptId)) {
			throw new LeaseRejectedException(jobId, "다른 시도의 임대증이다");
		}
		if (lease.expires() == null || lease.expires().isBefore(OffsetDateTime.now())) {
			throw new LeaseRejectedException(jobId, "임대 시간이 지났다");
		}
	}

	private void finish(UUID jobId, JobStatus status, String error) {
		jdbc.sql("""
				update job set status = :status, last_error = coalesce(:error, last_error),
				       lease_token_hash = null, lease_expires_at = null, finished_at = now(), updated_at = now()
				 where id = :id
				""")
				.param("status", status.name()).param("error", error).param("id", jobId).update();
	}

	private Job require(UUID jobId) {
		return jdbc.sql("select " + COLUMNS + " from job where id = :id").param("id", jobId)
				.query(JobOperation::mapJob).optional()
				.orElseThrow(() -> new JobNotFoundException(jobId));
	}

	private static String truncate(String text) {
		return text.length() <= 2000 ? text : text.substring(0, 2000);
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Job mapJob(ResultSet rs, int rowNum) throws SQLException {
		OffsetDateTime finished = rs.getObject("finished_at", OffsetDateTime.class);
		Instant finishedAt = finished == null ? null : finished.toInstant();
		return new Job(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
				JobType.valueOf(rs.getString("type")), JobStatus.valueOf(rs.getString("status")),
				rs.getObject("subject_id", UUID.class), rs.getString("payload"),
				rs.getObject("requested_by", UUID.class), rs.getInt("attempts"), rs.getString("last_error"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant(), finishedAt);
	}
}
