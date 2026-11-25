package com.contexa.server.job;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 한 사람이 최근 24시간에 맡길 수 있는 AI 작업 수. 문서 올리기(자르기 뒤 뽑기)와 질문이 모델 비용을 쓰기 때문이다.
 *
 * <p>센 것: 그 사람이 시킨 자르기(PARSE, 문서 하나에 하나)와 글쓰기(GENERATE). 다시 맡기기는 새 작업이 아니라 세지 않는다.
 * {@code contexa.ai.daily-limit-per-user}가 0이면 제한하지 않는다(개발).
 * 같은 순간에 몰린 요청은 몇 개 넘칠 수 있다. 비용 상한은 모델 공급자 쪽 월 한도로 한 번 더 막는다.
 */
@Component
public class AiQuota {

	private final JdbcClient jdbc;
	private final int limit;

	public AiQuota(JdbcClient jdbc, @Value("${contexa.ai.daily-limit-per-user:0}") int limit) {
		this.jdbc = jdbc;
		this.limit = limit;
	}

	/** @throws QuotaExceededException 오늘 맡길 수 있는 작업을 다 썼다 */
	public void check(UUID userId) {
		if (limit <= 0) {
			return;
		}
		long used = jdbc.sql("""
				select count(*) from job
				 where requested_by = :userId and type in ('PARSE', 'GENERATE')
				   and created_at > now() - interval '24 hours'
				""").param("userId", userId).query(Long.class).single();
		if (used >= limit) {
			throw new QuotaExceededException("하루에 맡길 수 있는 AI 작업(" + limit + "개)을 다 썼다. 내일 다시 해 주세요");
		}
	}
}
