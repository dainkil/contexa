package com.contexa.server.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.contract.Caller;
import com.contexa.server.contract.ContextPacket;
import com.contexa.server.document.DocumentKind;
import com.contexa.server.document.SpanDraft;
import com.contexa.server.gateway.ContextGateway;
import com.contexa.server.identity.IdentityAccess;

/** 평가용 문서 넣기: 발언을 그대로 저장하고, 작업 큐에는 아무것도 올리지 않는다. */
@SpringBootTest
@Transactional
class EvalCorpusTest {

	@Autowired
	EvalCorpus eval;

	@Autowired
	ContextGateway gateway;

	@Autowired
	IdentityAccess identity;

	@Autowired
	JdbcClient jdbc;

	@Test
	void 잘린_발언을_순서대로_저장하고_작업은_만들지_않는다() {
		EvalCorpus.Loaded loaded = eval.load(new EvalCorpus.Corpus("평가 1", List.of(
				new EvalCorpus.Doc("1차 회의", DocumentKind.TRANSCRIPT, List.of(
						new SpanDraft("민지", "행사는 온라인으로 하면 어떨까요?"),
						new SpanDraft("Sara", "I think an in-person event would get more students."))),
				new EvalCorpus.Doc("예산 신청서", DocumentKind.DOCUMENT, List.of(
						new SpanDraft(null, "인쇄비 45만 원은 학생처 승인 뒤에 집행한다."))))));

		assertThat(loaded.email()).isEqualTo(EvalCorpus.EMAIL);
		assertThat(loaded.documents()).extracting(EvalCorpus.LoadedDoc::title).containsExactly("1차 회의", "예산 신청서");
		assertThat(loaded.documents().get(0).spanIds()).hasSize(2);
		List<String> texts = jdbc.sql("select text from span where id in (:ids) order by seq")
				.param("ids", loaded.documents().get(0).spanIds()).query(String.class).list();
		assertThat(texts).containsExactly("행사는 온라인으로 하면 어떨까요?",
				"I think an in-person event would get more students.");
		assertThat(jdbc.sql("select count(*) from job where project_id = :projectId")
				.param("projectId", loaded.projectId()).query(Long.class).single()).isZero();

		Caller owner = Caller.user(identity.userIdByEmail(EvalCorpus.EMAIL).orElseThrow(), loaded.projectId());
		ContextPacket found = gateway.searchEvidence(owner, "인쇄비 승인", 8);
		assertThat(found.sources()).extracting(source -> source.span().id())
				.contains(loaded.documents().get(1).spanIds().get(0));
	}

	@Test
	void 부를_때마다_새_프로젝트를_만든다() {
		EvalCorpus.Corpus corpus = new EvalCorpus.Corpus("평가", List.of(new EvalCorpus.Doc("메모",
				DocumentKind.MEMO, List.of(new SpanDraft(null, "다음 주에 다시 본다.")))));

		assertThat(eval.load(corpus).projectId()).isNotEqualTo(eval.load(corpus).projectId());
	}

	@Test
	void 비어_있는_발언이나_종류가_없는_문서는_받지_않는다() {
		assertThatThrownBy(() -> eval.load(new EvalCorpus.Corpus("평가", List.of(new EvalCorpus.Doc("메모",
				DocumentKind.MEMO, List.of(new SpanDraft(null, " "))))))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> eval.load(new EvalCorpus.Corpus("평가", List.of(new EvalCorpus.Doc("메모", null,
				List.of(new SpanDraft(null, "내용")))))))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> eval.load(new EvalCorpus.Corpus(" ", List.of())))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
