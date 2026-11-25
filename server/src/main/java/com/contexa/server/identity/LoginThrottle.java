package com.contexa.server.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 로그인 실패를 세어, 짧은 시간에 너무 많이 틀리면 잠시 막는다(비밀번호를 계속 찍어 보는 것을 막기 위해).
 *
 * <p>같은 이메일로 {@code per-email}번, 같은 접속 주소로 {@code per-address}번 넘게 {@code window} 안에 틀리면
 * 가장 오래된 실패가 창 밖으로 나갈 때까지 막는다. 맞히면 그 이메일의 기록은 지운다.
 * 기록은 메모리에만 둔다. server가 하나라는 전제이고, 다시 켜면 처음부터 센다.
 */
@Component
public class LoginThrottle {

	/** 기록이 이만큼 쌓이면 비어 있는 것을 치운다(주소가 계속 바뀌는 요청에 메모리가 늘지 않게). */
	private static final int SWEEP_AT = 10_000;

	private final int perEmail;
	private final int perAddress;
	private final Duration window;
	private final Clock clock;
	private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

	@Autowired
	public LoginThrottle(@Value("${contexa.auth.login-limit.per-email:5}") int perEmail,
			@Value("${contexa.auth.login-limit.per-address:20}") int perAddress,
			@Value("${contexa.auth.login-limit.window:15m}") Duration window) {
		this(perEmail, perAddress, window, Clock.systemUTC());
	}

	LoginThrottle(int perEmail, int perAddress, Duration window, Clock clock) {
		this.perEmail = perEmail;
		this.perAddress = perAddress;
		this.window = window;
		this.clock = clock;
	}

	/** @throws TooManyAttemptsException 이 이메일이나 주소로 너무 많이 틀렸다 */
	public void checkAllowed(String email, String address) {
		Duration wait = waitFor(emailKey(email), perEmail);
		Duration byAddress = waitFor(addressKey(address), perAddress);
		if (byAddress.compareTo(wait) > 0) {
			wait = byAddress;
		}
		if (!wait.isZero()) {
			throw new TooManyAttemptsException(wait);
		}
	}

	public void failed(String email, String address) {
		Instant now = clock.instant();
		for (String key : new String[] { emailKey(email), addressKey(address) }) {
			Deque<Instant> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
			synchronized (times) {
				prune(times, now);
				times.addLast(now);
			}
		}
		if (failures.size() > SWEEP_AT) {
			failures.entrySet().removeIf(entry -> {
				synchronized (entry.getValue()) {
					prune(entry.getValue(), now);
					return entry.getValue().isEmpty();
				}
			});
		}
	}

	public void succeeded(String email) {
		failures.remove(emailKey(email));
	}

	private Duration waitFor(String key, int limit) {
		Deque<Instant> times = failures.get(key);
		if (times == null) {
			return Duration.ZERO;
		}
		Instant now = clock.instant();
		synchronized (times) {
			prune(times, now);
			if (times.size() < limit) {
				return Duration.ZERO;
			}
			return Duration.between(now, times.peekFirst().plus(window));
		}
	}

	private void prune(Deque<Instant> times, Instant now) {
		while (!times.isEmpty() && !times.peekFirst().plus(window).isAfter(now)) {
			times.removeFirst();
		}
	}

	private static String emailKey(String email) {
		return "email:" + (email == null ? "" : email.strip().toLowerCase(Locale.ROOT));
	}

	private static String addressKey(String address) {
		return "address:" + (address == null ? "" : address);
	}
}
