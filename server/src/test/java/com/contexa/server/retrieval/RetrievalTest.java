package com.contexa.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.Fixtures;
import com.contexa.server.contract.Caller;
import com.contexa.server.contract.ContextPacket;
import com.contexa.server.contract.Fact;
import com.contexa.server.contract.GraphCandidates;
import com.contexa.server.contract.EdgeDraft;
import com.contexa.server.contract.NodeDraft;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.RelType;
import com.contexa.server.contract.Scope;
import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.document.DocumentInput;
import com.contexa.server.document.DocumentKind;
import com.contexa.server.document.SourceVersionRef;
import com.contexa.server.document.SpanDraft;
import com.contexa.server.gateway.ContextGateway;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.Role;
import com.contexa.server.identity.Visibility;
import com.contexa.server.review.ReviewImpact;

/** 검색과, 안내 데스크의 근거 검색. 테스트마다 트랜잭션을 되돌린다. */
@SpringBootTest
@Transactional
class RetrievalTest {

	@Autowired
	Retrieval retrieval;

	@Autowired
	ContextGateway gateway;

	@Autowired
	ReviewImpact review;

	@Autowired
	DocumentEvidence document;

	@Autowired
	IdentityAccess identity;

	Caller owner;
	UUID projectId;
	List<NodeRef> spans;

	@BeforeEach
	void 회의_전사문을_올린다() {
		owner = Fixtures.newOwner(identity);
		projectId = owner.projectId();
		spans = Fixtures.transcript(document, owner,
				"예산 승인 전까지 인쇄는 보류입니다.",
				"포스터 디자인은 Friday까지 가능합니다.",
				"سأراجع النص العربي غدا.",
				"장소가 확보됐으니 대면 개최로 확정합니다.");
	}

	private List<String> texts(String query) {
		return retrieval.search(projectId, query, identity.readableDocumentIds(owner), 5).stream()
				.map(hit -> hit.span().text()).toList();
	}

	@Test
	void 질문의_낱말이_들어_있는_발언을_가까운_순서로_찾는다() {
		assertThat(texts("인쇄 진행해도 돼?")).first().isEqualTo("예산 승인 전까지 인쇄는 보류입니다.");
		assertThat(texts("대면 개최")).first().isEqualTo("장소가 확보됐으니 대면 개최로 확정합니다.");
	}

	@Test
	void 조사가_붙은_낱말과_영어_아랍어도_찾는다() {
		assertThat(texts("인쇄도 해도 되나요")).contains("예산 승인 전까지 인쇄는 보류입니다.");
		assertThat(texts("poster FRIDAY")).containsExactly("포스터 디자인은 Friday까지 가능합니다.");
		assertThat(texts("النص العربي")).containsExactly("سأراجع النص العربي غدا.");
	}

	@Test
	void 관련된_발언이_없으면_빈_목록이고_특수문자는_그대로_글자로_다룬다() {
		assertThat(texts("주차장 요금")).isEmpty();
		assertThat(texts("100%_할인")).isEmpty();
		assertThat(texts("  ")).isEmpty();
		assertThat(retrieval.search(projectId, "인쇄", Set.of(), 5)).isEmpty();
	}

	@Test
	void 읽어도_되는_문서와_가장_최근_판에서만_찾는다() {
		Caller member = Fixtures.newMember(identity, owner, Role.MEMBER);
		Fixtures.document(document, member, Visibility.PRIVATE, false, "(개인 메모) 인쇄소 견적을 따로 받자.");
		SourceVersionRef first = document.registerDocument(owner, new DocumentInput("공지", DocumentKind.DOCUMENT,
				"ko", Visibility.PROJECT, false, "인쇄 부수는 200부입니다."));
		document.saveSpans(first.sourceVersionId(), List.of(new SpanDraft(null, "인쇄 부수는 200부입니다.")));
		SourceVersionRef second = document.addVersion(owner, first.documentId(), "인쇄 부수는 300부입니다.");
		document.saveSpans(second.sourceVersionId(), List.of(new SpanDraft(null, "인쇄 부수는 300부입니다.")));

		assertThat(texts("인쇄")).contains("인쇄 부수는 300부입니다.")
				.doesNotContain("인쇄 부수는 200부입니다.", "(개인 메모) 인쇄소 견적을 따로 받자.");
		assertThat(retrieval.search(projectId, "인쇄", identity.readableDocumentIds(member), 5))
				.extracting(hit -> hit.span().text()).contains("(개인 메모) 인쇄소 견적을 따로 받자.");
		assertThat(retrieval.search(UUID.randomUUID(), "인쇄", identity.readableDocumentIds(owner), 5)).isEmpty();
	}

	@Test
	void 질문을_낱말로_나누고_한글_낱말은_끝_글자를_뗀_모양도_찾는다() {
		assertThat(Retrieval.terms("인쇄도 진행해도 돼? OK")).containsOnlyKeys("인쇄도", "인쇄", "진행해도", "진행해", "진행", "ok");
		assertThat(Retrieval.terms("인쇄도").get("인쇄도")).isEqualTo(1.0);
		assertThat(Retrieval.terms("인쇄도").get("인쇄")).isEqualTo(0.6);
	}

	@Test
	void 근거_검색은_찾은_발언에_기댄_카드를_함께_돌려준다() {
		Caller job = new Caller(Caller.Kind.JOB, UUID.randomUUID(), projectId, owner.id(), Caller.Audience.INTERNAL);
		List<UUID> ev = List.of(spans.get(0).id());
		review.submitCandidates(job, new GraphCandidates(List.of(
				new NodeDraft("print", NodeType.TASK, java.util.Map.of("title", "인쇄 발주")),
				new NodeDraft("budget", NodeType.CONDITION, java.util.Map.of("description", "예산 승인"))),
				List.of(EdgeDraft.between(RelType.REQUIRES, "print", "budget", ev),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "budget", spans.get(0), ev),
						EdgeDraft.toExisting(RelType.EVIDENCED_BY, "print", spans.get(0), ev))));

		ContextPacket packet = gateway.searchEvidence(owner, "인쇄 진행해도 돼?", 3);

		assertThat(packet.scope().view()).isEqualTo(Scope.View.SEARCH_EVIDENCE);
		assertThat(packet.sources().get(0).text()).isEqualTo("예산 승인 전까지 인쇄는 보류입니다.");
		assertThat(packet.facts()).extracting(Fact::label).containsExactlyInAnyOrder("인쇄 발주", "예산 승인");
		assertThat(packet.relations()).extracting(edge -> edge.rel()).containsOnly(RelType.EVIDENCED_BY);
		// 다른 프로젝트 사람에게는 아무것도 나오지 않는다
		assertThat(gateway.searchEvidence(Fixtures.newOwner(identity), "인쇄", 3).sources()).isEmpty();
	}
}
