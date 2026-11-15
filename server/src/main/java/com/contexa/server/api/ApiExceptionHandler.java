package com.contexa.server.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.contexa.server.decision.NodeNotFoundException;
import com.contexa.server.decision.NodeRevisionConflictException;
import com.contexa.server.document.DocumentNotFoundException;
import com.contexa.server.entity.EntityNotFoundException;
import com.contexa.server.generation.ArtifactNotFoundException;
import com.contexa.server.graph.EdgeNotFoundException;
import com.contexa.server.graph.EdgeVersionConflictException;
import com.contexa.server.identity.NotAllowedException;
import com.contexa.server.identity.UnauthenticatedException;
import com.contexa.server.job.JobNotFoundException;
import com.contexa.server.job.LeaseRejectedException;
import com.contexa.server.review.ReviewNotFoundException;
import com.contexa.server.review.ReviewVersionConflictException;

/** 안쪽에서 난 예외를 HTTP 상태 코드로 옮긴다. */
@RestControllerAdvice
public class ApiExceptionHandler {

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
			ReviewVersionConflictException.class, LeaseRejectedException.class })
	@ResponseStatus(HttpStatus.CONFLICT)
	public Map<String, String> stale(RuntimeException e) {
		return body("STALE", e);
	}

	/** 지금 상태에서는 할 수 없는 일이다. 예: 근거 없는 카드를 확인, 거절된 것을 다시 확인. */
	@ExceptionHandler(IllegalStateException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public Map<String, String> notPossible(IllegalStateException e) {
		return body("NOT_POSSIBLE", e);
	}

	private static Map<String, String> body(String error, RuntimeException e) {
		return Map.of("error", error, "message", String.valueOf(e.getMessage()));
	}
}
