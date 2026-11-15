package com.contexa.server.retrieval;

import com.contexa.server.document.Span;

/** 검색에 걸린 발언 하나. score가 클수록 질문과 가깝다. */
public record SpanHit(Span span, double score) {
}
