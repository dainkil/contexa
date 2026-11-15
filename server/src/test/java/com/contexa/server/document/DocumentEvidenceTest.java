package com.contexa.server.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.contexa.server.Fixtures;
import com.contexa.server.contract.Caller;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.NotAllowedException;
import com.contexa.server.identity.Visibility;

/** 자료실: 문서를 올리고, 판을 쌓고, 발언으로 자르고, 정정한다. 테스트마다 트랜잭션을 되돌린다. */
@SpringBootTest
@Transactional
class DocumentEvidenceTest {

	@Autowired
	DocumentEvidence document;

	@Autowired
	IdentityAccess identity;

	Caller owner;
	SourceVersionRef first;

	@BeforeEach
	void 회의_전사문을_올린다() {
		owner = Fixtures.newOwner(identity);
		first = document.registerDocument(owner, new DocumentInput("3차 회의 전사문", DocumentKind.TRANSCRIPT, null,
				Visibility.PROJECT, false, "민지: 예산 승인 전까지 인쇄는 보류입니다.\n준호: 인쇄 발주는 다음 주에 하죠."));
	}

	@Test
	void 문서를_올리면_첫_판이_생기고_출입_관리에_열람_범위가_등록된다() {
		assertThat(first.versionNo()).isEqualTo(1);
		assertThat(document.readContent(first.sourceVersionId())).startsWith("민지: 예산 승인");
		assertThat(identity.canRead(owner, first.documentId())).isTrue();
		assertThat(identity.canRead(Fixtures.newOwner(identity), first.documentId())).isFalse();
	}

	@Test
	void 발언으로_자르면_순서대로_번호가_붙고_한_판은_한_번만_자를_수_있다() {
		List<NodeRef> refs = document.saveSpans(first.sourceVersionId(), List.of(
				new SpanDraft("민지", "예산 승인 전까지 인쇄는 보류입니다."),
				new SpanDraft(" ", "인쇄 발주는 다음 주에 하죠.")));

		assertThat(refs).extracting(NodeRef::type).containsOnly(NodeType.SPAN);
		List<Span> spans = document.spansOf(first.sourceVersionId());
		assertThat(spans).extracting(Span::seq).containsExactly(1, 2);
		assertThat(spans).extracting(Span::speakerLabel).containsExactly("민지", null);
		assertThat(spans.get(0).ref()).isEqualTo(refs.get(0));
		assertThat(spans.get(0).documentId()).isEqualTo(first.documentId());

		assertThatThrownBy(() -> document.saveSpans(first.sourceVersionId(), List.of(new SpanDraft(null, "다시"))))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 내용이_빈_발언이_섞이면_아무것도_저장하지_않는다() {
		assertThatThrownBy(() -> document.saveSpans(first.sourceVersionId(),
				List.of(new SpanDraft(null, "정상 발언"), new SpanDraft(null, "  "))))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(document.spansOf(first.sourceVersionId())).isEmpty();
	}

	@Test
	void 다시_올리면_새_판이_되고_같은_내용은_거절한다() {
		SourceVersionRef second = document.addVersion(owner, first.documentId(), "민지: 예산이 승인됐습니다.");

		assertThat(second.versionNo()).isEqualTo(2);
		assertThat(second.documentId()).isEqualTo(first.documentId());
		assertThat(document.readContent(first.sourceVersionId())).startsWith("민지: 예산 승인 전까지");
		assertThatThrownBy(() -> document.addVersion(owner, first.documentId(), "민지: 예산이 승인됐습니다."))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> document.addVersion(Fixtures.newOwner(identity), first.documentId(), "남의 문서"))
				.isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void 구성원이_아니거나_사람이_아니면_문서를_올릴_수_없다() {
		DocumentInput input = new DocumentInput("메모", DocumentKind.MEMO, "ko", Visibility.PRIVATE, false, "내용");
		Caller stranger = Caller.user(UUID.randomUUID(), owner.projectId());
		Caller job = new Caller(Caller.Kind.JOB, UUID.randomUUID(), owner.projectId(), owner.id(),
				Caller.Audience.INTERNAL);

		assertThatThrownBy(() -> document.registerDocument(stranger, input)).isInstanceOf(NotAllowedException.class);
		assertThatThrownBy(() -> document.registerDocument(job, input)).isInstanceOf(NotAllowedException.class);
		assertThatThrownBy(() -> document.registerDocument(owner,
				new DocumentInput(" ", DocumentKind.MEMO, null, Visibility.PROJECT, false, "내용")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 발언은_자기_프로젝트에서만_읽히고_정정하면_버전이_오른다() {
		NodeRef span = document.saveSpans(first.sourceVersionId(),
				List.of(new SpanDraft("민지", "예산 승인 전까지 인쇄는 보류입니다."))).get(0);

		assertThat(document.getSpans(UUID.randomUUID(), List.of(span.id()))).isEmpty();
		assertThat(document.getSpans(owner.projectId(), List.of(span.id(), UUID.randomUUID()))).hasSize(1);

		NodeRef revised = document.reviseSpan(owner.projectId(), span.id(), 1, "예산 승인 후에 인쇄합니다.", owner.id());
		assertThat(revised.revision()).isEqualTo(2);
		assertThat(document.currentRevisions(owner.projectId(), List.of(span))).containsEntry(span.key(), 2L);
		assertThatThrownBy(() -> document.reviseSpan(owner.projectId(), span.id(), 1, "낡은 버전으로", owner.id()))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> document.reviseSpan(UUID.randomUUID(), span.id(), 2, "남의 프로젝트", owner.id()))
				.isInstanceOf(DocumentNotFoundException.class);
	}
}
