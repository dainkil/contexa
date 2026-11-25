package com.contexa.server.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.generation.GenerationWiki;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.IdentityAccess.Membership;
import com.contexa.server.job.JobOperation;
import com.contexa.server.review.Erasure;

/**
 * 계정 삭제. 앱의 설정 화면과 웹의 계정 삭제 페이지가 부른다. 한 트랜잭션이라 중간에 실패하면 아무것도 지우지 않는다.
 *
 * <ul>
 * <li>나 혼자인 프로젝트: 문서·카드·질문·작업 기록까지 통째로 지운다.</li>
 * <li>함께 쓰는 프로젝트: 내 개인 메모와 그 메모에서만 나온 카드, 내 질문과 답변을 지우고 프로젝트에서 나간다.
 * 내가 올린 공유 문서와 내가 한 검토 기록은 다른 사람의 결정 근거라서 남기고, 이름은 "탈퇴한 사용자"로 바뀐다.</li>
 * <li>마지막으로 토큰을 모두 없애고 이메일·이름·비밀번호를 지운다.</li>
 * </ul>
 * 지우는 순서는 기대는 쪽부터다: 산출물 → 카드·줄·검토 기록 → 문서 → 작업 → 구성원·프로젝트.
 */
@Service
public class AccountDeletion {

	private static final Logger log = LoggerFactory.getLogger(AccountDeletion.class);

	private final IdentityAccess identity;
	private final GenerationWiki generation;
	private final Erasure erasure;
	private final DocumentEvidence document;
	private final JobOperation jobs;

	public AccountDeletion(IdentityAccess identity, GenerationWiki generation, Erasure erasure,
			DocumentEvidence document, JobOperation jobs) {
		this.identity = identity;
		this.generation = generation;
		this.erasure = erasure;
		this.document = document;
		this.jobs = jobs;
	}

	/** 비밀번호를 다시 확인하고 계정을 지운다. @throws IllegalArgumentException 비밀번호가 맞지 않는다 */
	@Transactional
	public void delete(UUID userId, String password) {
		identity.checkPassword(userId, password);
		int purged = 0;
		int left = 0;
		for (Membership membership : identity.membershipsOf(userId)) {
			if (membership.memberCount() == 1) {
				purgeProject(membership.projectId());
				purged++;
			} else {
				leaveProject(membership.projectId(), userId);
				left++;
			}
		}
		erasure.forgetAccount(userId);
		identity.anonymize(userId);
		log.info("account_deleted user={} projects_deleted={} projects_left={}", userId, purged, left);
	}

	private void purgeProject(UUID projectId) {
		generation.purgeProject(projectId);
		erasure.purgeProject(projectId);
		document.purgeProject(projectId);
		jobs.purgeProject(projectId);
		identity.purgeProject(projectId);
	}

	private void leaveProject(UUID projectId, UUID userId) {
		List<UUID> artifacts = generation.deleteArtifactsOf(projectId, userId);
		List<UUID> memos = identity.privateDocumentIds(projectId, userId);
		erasure.forgetSpans(projectId, document.spanIdsOf(projectId, memos));
		List<UUID> subjects = new ArrayList<>(artifacts);
		subjects.addAll(document.versionIdsOf(projectId, memos));
		document.deleteDocuments(projectId, memos);
		jobs.deleteJobs(projectId, subjects);
		jobs.cancelOpenJobsOf(projectId, userId);
		identity.leaveProject(projectId, userId);
	}
}
