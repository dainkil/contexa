package com.contexa.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 예상하지 못한 오류의 응답에는 안쪽 메시지(SQL, 클래스 이름 등)를 싣지 않는다. */
class ApiExceptionHandlerTest {

	final ApiExceptionHandler handler = new ApiExceptionHandler();

	@Test
	void 예상하지_못한_오류는_500이고_안쪽_메시지를_숨긴다() {
		ResponseEntity<Map<String, String>> response = handler.unexpected(
				new RuntimeException("ERROR: relation \"secret_table\" does not exist"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody()).containsEntry("error", "INTERNAL");
		assertThat(response.getBody().get("message")).doesNotContain("secret_table");
	}

	@Test
	void 받을_쪽이_연결을_끊었으면_응답을_쓰지_않는다() {
		assertThat(handler.disconnected(new AsyncRequestNotUsableException("Broken pipe"))).isNull();
	}

	@Test
	void Spring이_정한_상태_코드는_그대로_쓰되_메시지는_싣지_않는다() {
		ResponseEntity<Map<String, String>> response = handler.unexpected(
				new NoResourceFoundException(HttpMethod.GET, "/api/v1/hidden/path", "hidden/path"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).containsEntry("error", "NOT_FOUND");
		assertThat(response.getBody().get("message")).doesNotContain("hidden");
	}
}
