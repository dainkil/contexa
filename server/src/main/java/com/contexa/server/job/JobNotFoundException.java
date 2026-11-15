package com.contexa.server.job;

import java.util.UUID;

public class JobNotFoundException extends RuntimeException {

	public JobNotFoundException(UUID jobId) {
		super("작업이 없다: " + jobId);
	}
}
