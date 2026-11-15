package com.contexa.server.job;

import java.util.UUID;

/** 임대증이 맞지 않는다: 다른 시도의 것이거나, 만료됐거나, 이미 끝난 작업이다. */
public class LeaseRejectedException extends RuntimeException {

	public LeaseRejectedException(UUID jobId, String why) {
		super("작업 " + jobId + "의 결과를 받을 수 없다: " + why);
	}
}
