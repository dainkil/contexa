package com.contexa.server.pipeline;

import java.util.List;

import com.contexa.server.contract.GraphCandidates;
import com.contexa.server.document.SpanDraft;
import com.contexa.server.generation.Draft;

/**
 * AI 인턴이 제출하는 결과. 작업 종류에 맞는 것 하나만 채운다.
 *
 * @param spans      PARSE: 문서를 자른 발언들, 순서대로
 * @param candidates EXTRACT: 카드 후보와 줄 후보. 뽑을 것이 없으면 빈 목록
 * @param draft      GENERATE: 써 온 글과, 그 글이 참고한 카드·발언·줄
 */
public record JobResult(List<SpanDraft> spans, GraphCandidates candidates, Draft draft) {
}
