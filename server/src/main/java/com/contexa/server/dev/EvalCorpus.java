package com.contexa.server.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Caller;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.document.DocumentInput;
import com.contexa.server.document.DocumentKind;
import com.contexa.server.document.SourceVersionRef;
import com.contexa.server.document.SpanDraft;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.Visibility;

/**
 * 평가용: 합성 문서를 새 프로젝트에 넣는다(eval/retrieval.py가 부른다).
 *
 * <ul>
 * <li>문서는 이미 잘린 발언으로 받는다. 자르기는 평가 스크립트가 일꾼과 같은 규칙(parse.py)으로 한다.</li>
 * <li>작업 큐를 거치지 않는다. 그래서 일꾼이 켜져 있어도 이 문서로 모델을 부르지 않고, 검색 평가에는 모델 비용이 없다.</li>
 * <li>부를 때마다 새 프로젝트를 만든다. 평가 계정은 하나를 같이 쓴다.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "contexa.dev-seed.enabled", havingValue = "true")
public class EvalCorpus {

	public static final String EMAIL = "eval@contexa.app";
	public static final String PASSWORD = "contexa-eval";

	/** 넣을 문서 묶음. title은 새 프로젝트의 이름이다. */
	public record Corpus(String title, List<Doc> documents) {
	}

	public record Doc(String title, DocumentKind kind, List<SpanDraft> spans) {
	}

	/** spanIds는 받은 발언과 같은 순서다. */
	public record Loaded(String email, String password, UUID projectId, List<LoadedDoc> documents) {
	}

	public record LoadedDoc(String title, UUID documentId, List<UUID> spanIds) {
	}

	private final IdentityAccess identity;
	private final DocumentEvidence document;

	public EvalCorpus(IdentityAccess identity, DocumentEvidence document) {
		this.identity = identity;
		this.document = document;
	}

	@Transactional
	public Loaded load(Corpus corpus) {
		if (corpus == null || corpus.title() == null || corpus.title().isBlank()) {
			throw new IllegalArgumentException("프로젝트 이름(title)이 비어 있다");
		}
		if (corpus.documents() == null || corpus.documents().isEmpty()) {
			throw new IllegalArgumentException("넣을 문서(documents)가 없다");
		}
		for (Doc doc : corpus.documents()) {
			if (doc.title() == null || doc.title().isBlank() || doc.kind() == null) {
				throw new IllegalArgumentException("문서의 title과 kind가 있어야 한다");
			}
			if (doc.spans() == null || doc.spans().isEmpty()
					|| doc.spans().stream().anyMatch(span -> span.text() == null || span.text().isBlank())) {
				throw new IllegalArgumentException("문서 '" + doc.title() + "'에 비어 있는 발언이 있다");
			}
		}
		UUID userId = identity.userIdByEmail(EMAIL).orElseGet(() -> identity.signUp(EMAIL, "평가", PASSWORD));
		UUID projectId = identity.createProject(userId, corpus.title());
		Caller owner = Caller.user(userId, projectId);
		List<LoadedDoc> loaded = new ArrayList<>();
		for (Doc doc : corpus.documents()) {
			SourceVersionRef version = document.registerDocument(owner,
					new DocumentInput(doc.title(), doc.kind(), null, Visibility.PROJECT, false, contentOf(doc)));
			List<NodeRef> spans = document.saveSpans(version.sourceVersionId(), doc.spans());
			loaded.add(new LoadedDoc(doc.title(), version.documentId(), spans.stream().map(NodeRef::id).toList()));
		}
		return new Loaded(EMAIL, PASSWORD, projectId, loaded);
	}

	/** 원문 뷰어에서 읽을 수 있도록 발언을 다시 이어 붙인 원문. */
	private static String contentOf(Doc doc) {
		StringBuilder content = new StringBuilder();
		for (SpanDraft span : doc.spans()) {
			if (span.speakerLabel() != null) {
				content.append(span.speakerLabel()).append(": ");
			}
			content.append(span.text()).append(doc.kind() == DocumentKind.TRANSCRIPT ? "\n" : "\n\n");
		}
		return content.toString().strip();
	}
}
