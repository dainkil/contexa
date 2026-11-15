package com.contexa.server.dev;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 개발용: 예시 데이터를 새 프로젝트에 넣고 그 프로젝트 id와 카드 id를 돌려준다. */
@RestController
@ConditionalOnProperty(name = "contexa.dev-seed.enabled", havingValue = "true")
public class DevSeedController {

	private final DevSeed seed;

	public DevSeedController(DevSeed seed) {
		this.seed = seed;
	}

	@PostMapping("/dev/seed")
	public DevSeed.Seeded seed() {
		return seed.seed();
	}
}
