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
import com.contexa.server.document.DocumentInput;
import com.contexa.server.job.Job;
import com.contexa.server.pipeline.IngestStarted;
import com.contexa.server.pipeline.Pipeline;

/** 정문: 문서를 올리고, 그 뒤에 AI 인턴에게 맡긴 작업의 진행 상태를 본다. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class DocumentController {

	private final Pipeline pipeline;

	public DocumentController(Pipeline pipeline) {
		this.pipeline = pipeline;
	}

	/** 문서를 올린다. 올리면 "자르기" 작업이 바로 맡겨진다. */
	@PostMapping("/documents")
	public IngestStarted upload(Caller caller, @RequestBody DocumentInput input) {
		return pipeline.startIngest(caller, input);
	}

	/** 이미 있는 문서의 새 판을 올린다. */
	@PostMapping("/documents/{documentId}/versions")
	public IngestStarted uploadVersion(Caller caller, @PathVariable UUID documentId,
			@RequestBody NewVersionRequest request) {
		return pipeline.startIngestOfNewVersion(caller, documentId, request.content());
	}

	@GetMapping("/jobs")
	public List<Job> jobs(Caller caller) {
		return pipeline.listJobs(caller);
	}

	@GetMapping("/jobs/{jobId}")
	public Job job(Caller caller, @PathVariable UUID jobId) {
		return pipeline.getJob(caller, jobId);
	}

	public record NewVersionRequest(String content) {
	}
}
