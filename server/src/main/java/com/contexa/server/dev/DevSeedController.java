package com.contexa.server.dev;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 개발용: 예시 데이터를 새 프로젝트에 넣고 그 프로젝트 id와 카드 id를 돌려준다. */
@RestController
@ConditionalOnProperty(name = "contexa.dev-seed.enabled", havingValue = "true")
public class DevSeedController {

	private final DevSeed seed;
	private final DemoSeed demo;

	public DevSeedController(DevSeed seed, DemoSeed demo) {
		this.seed = seed;
		this.demo = demo;
	}

	@PostMapping("/dev/seed")
	public DevSeed.Seeded seed() {
		return seed.seed();
	}

	/** 시연용: 정해진 계정과 "국제교류의 밤 준비" 프로젝트를 만든다. 이미 있으면 그대로 돌려준다. */
	@PostMapping("/dev/demo")
	public DemoSeed.Seeded demo() {
		return demo.seed();
	}
}
