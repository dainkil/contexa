package com.contexa.server.generation;

import java.util.UUID;

/** 글을 요청한 결과: 만들어질 산출물과, AI 인턴에게 맡긴 작업. */
public record GenerationRequested(UUID artifactId, UUID jobId) {
}
