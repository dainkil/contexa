package com.contexa.server.generation;

import java.util.UUID;

/** AI 인턴이 받아 가는 주문서: 무엇에 대해, 어떤 종류의 글을, 어느 언어로 쓸지. lang이 null이면 질문을 쓴 언어로 쓴다. */
public record GenerationRequest(UUID artifactId, GenKind kind, String question, String lang) {
}
