package com.contexa.server.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.contexa.server.contract.Caller;
import com.contexa.server.generation.ArtifactView;
import com.contexa.server.generation.GenKind;
import com.contexa.server.generation.GenerationRequested;
import com.contexa.server.generation.GenerationWiki;
import com.contexa.server.generation.ReportReason;

/** 정문: 질문하고(글을 요청하고), 만들어진 답변을 연다. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/generations")
public class GenerationController {

	private final GenerationWiki generation;

	public GenerationController(GenerationWiki generation) {
		this.generation = generation;
	}

	/** 질문한다. 바로 돌아오고, 답변은 AI 인턴이 쓴 뒤에 생긴다. kind를 생략하면 ANSWER, lang을 생략하면 질문을 쓴 언어로 답한다. */
	@PostMapping
	public GenerationRequested ask(Caller caller, @RequestBody AskRequest request) {
		return generation.requestGeneration(caller, request.kind() == null ? GenKind.ANSWER : request.kind(),
				request.question(), request.lang());
	}

	/** 내가 요청한 글. 최근 것부터. */
	@GetMapping
	public List<GenerationWiki.ArtifactSummary> list(Caller caller) {
		return generation.listArtifacts(caller);
	}

	/** 글 하나를 연다. 참고한 카드가 그사이 바뀌었으면 stale에 담겨 나온다. */
	@GetMapping("/{artifactId}")
	public ArtifactView get(Caller caller, @PathVariable UUID artifactId) {
		return generation.getArtifact(caller, artifactId);
	}

	/** AI가 쓴 답변·브리핑을 신고한다. 요청한 사람만 할 수 있다. */
	@PostMapping("/{artifactId}/reports")
	public Map<String, UUID> report(Caller caller, @PathVariable UUID artifactId,
			@RequestBody ReportRequest request) {
		return Map.of("reportId",
				generation.reportArtifact(caller, artifactId, request.reason(), request.note()));
	}

	public record ReportRequest(ReportReason reason, String note) {
	}

	public record AskRequest(GenKind kind, String question, String lang) {
	}
}
