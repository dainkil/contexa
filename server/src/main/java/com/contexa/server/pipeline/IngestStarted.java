package com.contexa.server.pipeline;

import java.util.UUID;

import com.contexa.server.document.SourceVersionRef;

/** 문서를 올린 결과: 저장된 판과, AI 인턴에게 맡긴 "자르기" 작업. */
public record IngestStarted(SourceVersionRef version, UUID jobId) {
}
