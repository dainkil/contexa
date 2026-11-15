package com.contexa.server.api;

import java.util.List;
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

/** 정문: 질문하고(글을 요청하고), 만들어진 답변을 연다. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/generations")
public class GenerationController {

	private final GenerationWiki generation;

	public GenerationController(GenerationWiki generation) {
		this.generation = generation;
	}

	/** 질문한다. 바로 돌아오고, 답변은 AI 인턴이 쓴 뒤에 생긴다. kind를 생략하면 ANSWER, lang을 생략하면 ko. */
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

	public record AskRequest(GenKind kind, String question, String lang) {
	}
}
