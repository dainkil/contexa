package com.contexa.server.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.Fixtures;
import com.contexa.server.contract.Caller;
import com.contexa.server.document.DocumentInput;
import com.contexa.server.document.DocumentKind;
import com.contexa.server.document.DocumentNotFoundException;
import com.contexa.server.generation.GenKind;
import com.contexa.server.generation.GenerationWiki;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.Role;
import com.contexa.server.identity.Visibility;
import com.contexa.server.job.Job;
import com.contexa.server.job.JobNotFoundException;

/** 작업 목록에도 문서 열람 범위가 적용된다. 남의 개인 메모를 자르는 작업과 남의 질문 작업은 보이지 않는다. */
@SpringBootTest
@Transactional
class JobVisibilityTest {

	@Autowired
	Pipeline pipeline;

	@Autowired
	GenerationWiki generation;

	@Autowired
	IdentityAccess identity;

	Caller owner;
	Caller member;
	IngestStarted memo;
	IngestStarted transcript;
	UUID question;

	@BeforeEach
	void 주인이_개인_메모와_전사문을_올리고_질문한다() {
		owner = Fixtures.newOwner(identity);
		member = Fixtures.newMember(identity, owner, Role.MEMBER);
		memo = pipeline.startIngest(owner,
				new DocumentInput("내 메모", DocumentKind.MEMO, "ko", Visibility.PRIVATE, false, "인쇄소 견적 메모"));
		transcript = pipeline.startIngest(owner, new DocumentInput("3차 회의 전사문", DocumentKind.TRANSCRIPT, null,
				Visibility.PROJECT, false, "민지: 예산 승인 전까지 인쇄는 보류입니다."));
		question = generation.requestGeneration(owner, GenKind.ANSWER, "지금 무엇이 결정됐어?", "ko").jobId();
	}

	@Test
	void 주인은_자기_작업을_모두_본다() {
		assertThat(pipeline.listJobs(owner)).extracting(Job::id)
				.contains(memo.jobId(), transcript.jobId(), question);
		assertThat(pipeline.getJob(owner, memo.jobId()).id()).isEqualTo(memo.jobId());
	}

	@Test
	void 다른_구성원에게는_공개_문서의_작업만_보인다() {
		assertThat(pipeline.listJobs(member)).extracting(Job::id)
				.contains(transcript.jobId())
				.doesNotContain(memo.jobId(), question);
		assertThat(pipeline.getJob(member, transcript.jobId()).id()).isEqualTo(transcript.jobId());
		assertThatThrownBy(() -> pipeline.getJob(member, memo.jobId())).isInstanceOf(JobNotFoundException.class);
		assertThatThrownBy(() -> pipeline.getJob(member, question)).isInstanceOf(JobNotFoundException.class);
	}

	@Test
	void 남의_개인_메모에_새_판을_올릴_수_없다() {
		assertThatThrownBy(() -> pipeline.startIngestOfNewVersion(member, memo.version().documentId(), "덮어쓰기"))
				.isInstanceOf(DocumentNotFoundException.class);
	}
}
