package com.contexa.server.retrieval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.document.Span;

/**
 * 검색: 질문과 관련된 발언을 찾는다.
 *
 * <p>지금은 글자로 찾는다. 질문을 낱말로 나눠 그 낱말이 들어 있는 발언에 점수를 주고,
 * 글자가 일부만 겹치는 정도(트라이그램 유사도)를 더한다. 한국어는 조사가 붙으므로
 * ("인쇄도", "인쇄는") 낱말의 끝 글자를 뗀 모양으로도 찾아본다.
 * 뜻은 같지만 글자가 다른 표현("발주"와 "주문")은 찾지 못한다. 그것은 임베딩 검색을 붙일 때 해결한다.
 *
 * <p>읽어도 되는 문서(allowedDocumentIds) 안에서만, 그리고 문서마다 가장 최근 판의 발언만 찾는다.
 * 무엇을 읽어도 되는지는 호출한 쪽(안내 데스크)이 출입 관리에 물어서 넘긴다.
 */
@Service
public class Retrieval {

	private static final int MAX_TERMS = 12;
	private static final double MIN_SIMILARITY = 0.2;

	private final JdbcClient jdbc;
	private final DocumentEvidence document;

	public Retrieval(JdbcClient jdbc, DocumentEvidence document) {
		this.jdbc = jdbc;
		this.document = document;
	}

	/**
	 * @param k 돌려줄 발언 수. 1~50
	 * @return 질문과 가까운 순서. 걸린 것이 없으면 빈 목록
	 */
	@Transactional(readOnly = true)
	public List<SpanHit> search(UUID projectId, String queryText, Collection<UUID> allowedDocumentIds, int k) {
		Objects.requireNonNull(projectId, "projectId");
		if (queryText == null || queryText.isBlank() || allowedDocumentIds == null || allowedDocumentIds.isEmpty()) {
			return List.of();
		}
		String query = queryText.strip();
		Map<String, Double> terms = terms(query);

		StringBuilder score = new StringBuilder("word_similarity(:query, s.text)");
		StringBuilder match = new StringBuilder("word_similarity(:query, s.text) >= :minSimilarity");
		Map<String, Object> params = new HashMap<>();
		int index = 0;
		for (Map.Entry<String, Double> term : terms.entrySet()) {
			String name = "t" + index++;
			score.append(" + case when s.text ilike :").append(name).append(" then ").append(term.getValue())
					.append(" else 0 end");
			match.append(" or s.text ilike :").append(name);
			params.put(name, "%" + escapeLike(term.getKey()) + "%");
		}
		JdbcClient.StatementSpec spec = jdbc.sql("select s.id, (" + score + ") as score from span s"
				+ " where s.project_id = :projectId and s.document_id in (:documents)"
				+ " and s.source_version_id in (select distinct on (v.document_id) v.id from source_version v"
				+ "       where v.document_id in (:documents) order by v.document_id, v.version_no desc)"
				+ " and (" + match + ")"
				+ " order by score desc, s.created_at, s.seq limit :k")
				.param("projectId", projectId)
				.param("documents", allowedDocumentIds)
				.param("query", query)
				.param("minSimilarity", MIN_SIMILARITY)
				.param("k", Math.max(1, Math.min(k, 50)));
		for (Map.Entry<String, Object> param : params.entrySet()) {
			spec = spec.param(param.getKey(), param.getValue());
		}
		Map<UUID, Double> scores = new LinkedHashMap<>();
		spec.query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getDouble("score"))).list()
				.forEach(entry -> scores.put(entry.getKey(), entry.getValue()));

		Map<UUID, Span> spans = new HashMap<>();
		document.getSpans(projectId, scores.keySet()).forEach(span -> spans.put(span.ref().id(), span));
		List<SpanHit> hits = new ArrayList<>();
		scores.forEach((id, value) -> hits.add(new SpanHit(spans.get(id), value)));
		return hits;
	}

	/**
	 * 질문을 낱말로 나누고 낱말마다 점수를 정한다. 낱말 그대로는 1점.
	 * 세 글자 이상인 한글 낱말은 끝 글자를 뗀 모양(0.6점), 네 글자 이상이면 두 글자를 뗀 모양(0.4점)도 더한다.
	 */
	static Map<String, Double> terms(String query) {
		Map<String, Double> terms = new LinkedHashMap<>();
		for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
			if (token.length() < 2 || terms.size() >= MAX_TERMS) {
				continue;
			}
			terms.putIfAbsent(token, 1.0);
			if (token.chars().allMatch(c -> c >= 0xAC00 && c <= 0xD7A3)) {
				if (token.length() >= 3) {
					terms.putIfAbsent(token.substring(0, token.length() - 1), 0.6);
				}
				if (token.length() >= 4) {
					terms.putIfAbsent(token.substring(0, token.length() - 2), 0.4);
				}
			}
		}
		return terms;
	}

	private static String escapeLike(String term) {
		return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
