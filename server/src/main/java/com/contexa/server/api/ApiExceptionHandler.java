package com.contexa.server.api;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import com.contexa.server.decision.NodeNotFoundException;
import com.contexa.server.decision.NodeRevisionConflictException;
import com.contexa.server.document.DocumentNotFoundException;
import com.contexa.server.entity.EntityNotFoundException;
import com.contexa.server.generation.ArtifactNotFoundException;
import com.contexa.server.graph.EdgeNotFoundException;
import com.contexa.server.graph.EdgeVersionConflictException;
import com.contexa.server.identity.NotAllowedException;
import com.contexa.server.identity.TooManyAttemptsException;
import com.contexa.server.identity.UnauthenticatedException;
import com.contexa.server.job.JobNotFoundException;
import com.contexa.server.job.LeaseRejectedException;
import com.contexa.server.job.QuotaExceededException;
import com.contexa.server.review.ReviewNotFoundException;
import com.contexa.server.review.ReviewVersionConflictException;

/** 안쪽에서 난 예외를 HTTP 상태 코드로 옮긴다. 응답 본문은 항상 {error, message}다. */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	/** 없는 것, 또는 이 프로젝트에서 볼 수 없는 것. 둘을 구분해서 알려주지 않는다. */
	@ExceptionHandler({ NodeNotFoundException.class, EdgeNotFoundException.class, ReviewNotFoundException.class,
			EntityNotFoundException.class, DocumentNotFoundException.class, JobNotFoundException.class,
			ArtifactNotFoundException.class })
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public Map<String, String> notFound(RuntimeException e) {
		return body("NOT_FOUND", e);
	}

	/** 요청 값이 잘못됐다. 예: 따라갈 칸 수가 범위를 벗어남, 근거 없는 줄 후보. */
	@ExceptionHandler(IllegalArgumentException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, String> badRequest(IllegalArgumentException e) {
		return body("BAD_REQUEST", e);
	}

	/** 누구인지 확인할 수 없다. 로그인이 필요하다. */
	@ExceptionHandler(UnauthenticatedException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public Map<String, String> unauthenticated(UnauthenticatedException e) {
		return body("UNAUTHENTICATED", e);
	}

	/** 누구인지는 알지만 할 수 없는 일이다. 예: 구성원이 아님, AI가 확인을 시도함. */
	@ExceptionHandler(NotAllowedException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public Map<String, String> forbidden(NotAllowedException e) {
		return body("FORBIDDEN", e);
	}

	/** 내가 본 뒤에 다른 사람이 먼저 바꿨다. 다시 읽고 시도해야 한다. */
	@ExceptionHandler({ NodeRevisionConflictException.class, EdgeVersionConflictException.class,
			ReviewVersionConflictException.class })
	@ResponseStatus(HttpStatus.CONFLICT)
	public Map<String, String> stale(RuntimeException e) {
		return body("STALE", e);
	}

	/**
	 * 일꾼이 이 작업을 더는 맡고 있지 않다(임대가 끝났거나 다른 일꾼이 가져갔다). 일꾼은 이 코드일 때만 작업을 내려놓는다.
	 * 다른 409(NOT_POSSIBLE 등)는 결과가 규칙에 맞지 않는다는 뜻이라 내려놓지 않고 실패로 알려야 한다.
	 */
	@ExceptionHandler(LeaseRejectedException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public Map<String, String> leaseLost(LeaseRejectedException e) {
		return body("LEASE_LOST", e);
	}

	/** 지금 상태에서는 할 수 없는 일이다. 예: 근거 없는 카드를 확인, 거절된 것을 다시 확인. */
	@ExceptionHandler(IllegalStateException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public Map<String, String> notPossible(IllegalStateException e) {
		return body("NOT_POSSIBLE", e);
	}

	/** 로그인을 너무 많이 틀렸다. Retry-After(초) 뒤에 다시 할 수 있다. */
	@ExceptionHandler(TooManyAttemptsException.class)
	public ResponseEntity<Map<String, String>> tooManyAttempts(TooManyAttemptsException e) {
		long seconds = Math.max(1, (e.retryAfter().toMillis() + 999) / 1000);
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds)).body(body("TOO_MANY_ATTEMPTS", e));
	}

	/** 오늘 맡길 수 있는 AI 작업(문서 올리기·질문)을 다 썼다. */
	@ExceptionHandler(QuotaExceededException.class)
	@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
	public Map<String, String> quotaExceeded(QuotaExceededException e) {
		return body("QUOTA_EXCEEDED", e);
	}

	/** 본문을 읽을 수 없거나(JSON 형식) 주소·질의의 값을 바꿀 수 없다(UUID·열거형 등). */
	@ExceptionHandler({ HttpMessageNotReadableException.class, TypeMismatchException.class })
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, String> unreadable(Exception e) {
		return Map.of("error", "BAD_REQUEST", "message", "요청 형식이 맞지 않는다");
	}

	/**
	 * 받을 쪽이 이미 연결을 끊었다(답을 기다리다 앱을 닫음, MCP 스트림을 닫음). 오류가 아니고 쓸 곳도 없으니 본문 없이 끝낸다.
	 * null을 돌려주면 Spring은 응답을 처리한 것으로 보고 아무것도 쓰지 않는다.
	 */
	@ExceptionHandler(AsyncRequestNotUsableException.class)
	public ResponseEntity<Void> disconnected(AsyncRequestNotUsableException e) {
		log.debug("받을 쪽이 연결을 끊었다: {}", e.getMessage());
		return null;
	}

	/**
	 * 위에서 잡지 못한 것. Spring이 상태 코드를 정해 둔 오류(없는 주소, 빠진 헤더·질의, 허용되지 않는 방법 등)는
	 * 그 코드를 그대로 쓰고, 나머지는 500이다. 어느 쪽이든 안쪽 메시지(SQL, 클래스 이름 등)는 응답에 싣지 않고 로그에만 남긴다.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, String>> unexpected(Exception e) {
		if (e instanceof ErrorResponse known) {
			HttpStatusCode code = known.getStatusCode();
			HttpStatus status = HttpStatus.resolve(code.value());
			String error = status == null ? "HTTP_" + code.value() : status.name();
			String message = code.is4xxClientError() ? "요청을 처리할 수 없다(" + code.value() + ")" : INTERNAL_MESSAGE;
			if (code.is5xxServerError()) {
				log.error("처리하지 못한 오류", e);
			}
			return ResponseEntity.status(code).headers(known.getHeaders())
					.body(Map.of("error", code.is5xxServerError() ? "INTERNAL" : error, "message", message));
		}
		log.error("처리하지 못한 오류", e);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("error", "INTERNAL", "message", INTERNAL_MESSAGE));
	}

	private static final String INTERNAL_MESSAGE = "server 안에서 예상하지 못한 오류가 났다";

	private static Map<String, String> body(String error, RuntimeException e) {
		return Map.of("error", error, "message", String.valueOf(e.getMessage()));
	}
}
