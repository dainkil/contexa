package com.contexa.server.identity;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 하루 한 번, 오래전에 만료되거나 폐기된 토큰을 지운다({@link IdentityAccess#purgeStaleTokens}). */
@Component
class TokenCleanup {

	private static final Logger log = LoggerFactory.getLogger(TokenCleanup.class);

	private final IdentityAccess identity;
	private final Duration keep;

	TokenCleanup(IdentityAccess identity, @Value("${contexa.auth.token-cleanup.keep:7d}") Duration keep) {
		this.identity = identity;
		this.keep = keep;
	}

	@Scheduled(cron = "${contexa.auth.token-cleanup.cron:0 30 4 * * *}")
	void run() {
		int removed = identity.purgeStaleTokens(keep);
		if (removed > 0) {
			log.info("만료·폐기된 토큰 {}개를 지웠다", removed);
		}
	}
}
