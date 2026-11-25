package com.contexa.server.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.Fixtures;
import com.contexa.server.contract.Caller;
import com.contexa.server.identity.IdentityAccess;

/** 작업 접수대: 줄 세우기, 임대, 결과 받기, 실패와 재시도. 테스트마다 트랜잭션을 되돌린다. */
@SpringBootTest
@Transactional
class JobOperationTest {

	@Autowired
	JobOperation jobs;

	@Autowired
	IdentityAccess identity;

	@Autowired
	JdbcClient jdbc;

	Caller owner;
	UUID subject;
	UUID jobId;

	@BeforeEach
	void 일을_하나_올린다() {
		// 다른 테스트나 개발 중에 남은 일이 먼저 잡히지 않도록 줄을 비운다(테스트가 끝나면 되돌아간다)
		jdbc.sql("update job set status = 'CANCELLED' where status in ('QUEUED', 'RUNNING')").update();
		owner = Fixtures.newOwner(identity);
		subject = UUID.randomUUID();
		jobId = jobs.enqueue(JobType.PARSE, owner.projectId(), subject, null, owner.id(), "parse:" + subject);
	}

	@Test
	void 같은_일을_두_번_올리면_처음_것을_돌려준다() {
		UUID again = jobs.enqueue(JobType.PARSE, owner.projectId(), subject, null, owner.id(), "parse:" + subject);

		assertThat(again).isEqualTo(jobId);
		assertThat(jobs.list(owner.projectId(), 10)).hasSize(1);
		assertThat(jobs.get(owner.projectId(), jobId).status()).isEqualTo(JobStatus.QUEUED);
	}

	@Test
	void 일을_가져가면_임대증을_받고_다른_일꾼은_같은_일을_가져갈_수_없다() {
		JobLease lease = jobs.claim("worker-1").orElseThrow();

		assertThat(lease.job().id()).isEqualTo(jobId);
		assertThat(lease.job().status()).isEqualTo(JobStatus.RUNNING);
		assertThat(lease.job().attempts()).isEqualTo(1);
		assertThat(lease.leaseToken()).isNotBlank();
		assertThat(jobs.claim("worker-2")).isEmpty();
	}

	@Test
	void 임대증이_맞아야_결과를_받고_끝난_일에는_다시_낼_수_없다() {
		JobLease lease = jobs.claim("worker-1").orElseThrow();

		assertThatThrownBy(() -> jobs.acceptResult(jobId, lease.attemptId(), "wrong"))
				.isInstanceOf(LeaseRejectedException.class);
		assertThatThrownBy(() -> jobs.acceptResult(jobId, UUID.randomUUID(), lease.leaseToken()))
				.isInstanceOf(LeaseRejectedException.class);

		Job done = jobs.acceptResult(jobId, lease.attemptId(), lease.leaseToken());
		assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
		assertThat(done.finishedAt()).isNotNull();
		assertThatThrownBy(() -> jobs.acceptResult(jobId, lease.attemptId(), lease.leaseToken()))
				.isInstanceOf(LeaseRejectedException.class);
	}

	@Test
	void 임대_시간이_지나면_다른_일꾼이_다시_가져가고_앞_일꾼의_임대증은_무효가_된다() {
		JobLease first = jobs.claim("worker-1").orElseThrow();
		jdbc.sql("update job set lease_expires_at = now() - interval '1 second' where id = :id").param("id", jobId)
				.update();

		assertThatThrownBy(() -> jobs.requireLease(jobId, first.attemptId(), first.leaseToken()))
				.isInstanceOf(LeaseRejectedException.class);
		JobLease second = jobs.claim("worker-2").orElseThrow();

		assertThat(second.job().attempts()).isEqualTo(2);
		assertThatThrownBy(() -> jobs.acceptResult(jobId, first.attemptId(), first.leaseToken()))
				.isInstanceOf(LeaseRejectedException.class);
		assertThat(jobs.acceptResult(jobId, second.attemptId(), second.leaseToken()).status())
				.isEqualTo(JobStatus.SUCCEEDED);
	}

	@Test
	void 다시_해_볼_만한_실패는_횟수가_남은_동안_다시_줄을_서고_다_쓰면_끝난다() {
		for (int attempt = 1; attempt <= 3; attempt++) {
			JobLease lease = jobs.claim("worker-1").orElseThrow();
			Job after = jobs.fail(jobId, lease.attemptId(), lease.leaseToken(), "모델 응답 시간 초과", true);
			assertThat(after.status()).isEqualTo(attempt < 3 ? JobStatus.QUEUED : JobStatus.FAILED);
			assertThat(after.lastError()).isEqualTo("모델 응답 시간 초과");
		}
		assertThat(jobs.claim("worker-1")).isEmpty();
	}

	@Test
	void 실패로_끝난_일을_다시_맡기면_시도_횟수를_처음부터_세고_이유는_남긴다() {
		JobLease lease = jobs.claim("worker-1").orElseThrow();
		jobs.fail(jobId, lease.attemptId(), lease.leaseToken(), "빈 문서", false);

		Job again = jobs.retry(owner.projectId(), jobId);

		assertThat(again.status()).isEqualTo(JobStatus.QUEUED);
		assertThat(again.attempts()).isZero();
		assertThat(again.finishedAt()).isNull();
		assertThat(again.lastError()).isEqualTo("빈 문서");
		// 앞 시도의 임대증으로는 결과를 낼 수 없고, 다시 가져간 일꾼은 첫 시도로 센다
		assertThatThrownBy(() -> jobs.acceptResult(jobId, lease.attemptId(), lease.leaseToken()))
				.isInstanceOf(LeaseRejectedException.class);
		JobLease next = jobs.claim("worker-2").orElseThrow();
		assertThat(next.job().id()).isEqualTo(jobId);
		assertThat(next.job().attempts()).isEqualTo(1);
	}

	@Test
	void 실패로_끝나지_않은_일과_다른_프로젝트의_일은_다시_맡길_수_없다() {
		assertThatThrownBy(() -> jobs.retry(owner.projectId(), jobId)).isInstanceOf(IllegalStateException.class);
		JobLease lease = jobs.claim("worker-1").orElseThrow();
		assertThatThrownBy(() -> jobs.retry(owner.projectId(), jobId)).isInstanceOf(IllegalStateException.class);
		jobs.fail(jobId, lease.attemptId(), lease.leaseToken(), "빈 문서", false);

		Caller other = Fixtures.newOwner(identity);
		assertThatThrownBy(() -> jobs.retry(other.projectId(), jobId)).isInstanceOf(JobNotFoundException.class);
		assertThat(jobs.get(owner.projectId(), jobId).status()).isEqualTo(JobStatus.FAILED);
	}

	@Test
	void 다시_해도_소용없는_실패는_바로_끝난다() {
		JobLease lease = jobs.claim("worker-1").orElseThrow();

		assertThat(jobs.fail(jobId, lease.attemptId(), lease.leaseToken(), "빈 문서", false).status())
				.isEqualTo(JobStatus.FAILED);
	}

	@Test
	void 결과가_오지_않은_채_횟수를_다_쓴_일은_가져갈_때_실패로_끝난다() {
		for (int attempt = 1; attempt <= 3; attempt++) {
			jobs.claim("worker-1").orElseThrow();
			jdbc.sql("update job set lease_expires_at = now() - interval '1 second' where id = :id")
					.param("id", jobId).update();
		}

		assertThat(jobs.claim("worker-1")).isEmpty();
		assertThat(jobs.get(owner.projectId(), jobId).status()).isEqualTo(JobStatus.FAILED);
	}

	@Test
	void 취소한_일은_가져갈_수_없고_일하던_일꾼의_결과도_받지_않는다() {
		JobLease lease = jobs.claim("worker-1").orElseThrow();

		assertThat(jobs.cancel(owner.projectId(), jobId).status()).isEqualTo(JobStatus.CANCELLED);
		assertThatThrownBy(() -> jobs.acceptResult(jobId, lease.attemptId(), lease.leaseToken()))
				.isInstanceOf(LeaseRejectedException.class);
		assertThat(jobs.claim("worker-1")).isEmpty();
		assertThatThrownBy(() -> jobs.get(UUID.randomUUID(), jobId)).isInstanceOf(JobNotFoundException.class);
	}
}
