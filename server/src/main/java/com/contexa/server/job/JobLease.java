package com.contexa.server.job;

import java.time.Instant;

/**
 * 일꾼이 일을 가져가며 받는 임대증. 결과를 낼 때 attemptId와 leaseToken을 함께 내야 한다.
 * leaseToken 원문은 이때 한 번만 돌려준다.
 */
public record JobLease(Job job, java.util.UUID attemptId, String leaseToken, Instant leaseExpiresAt) {
}
