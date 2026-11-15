package com.contexa.server;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.contexa.server.contract.Caller;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.document.DocumentEvidence;
import com.contexa.server.document.DocumentInput;
import com.contexa.server.document.DocumentKind;
import com.contexa.server.document.SourceVersionRef;
import com.contexa.server.document.SpanDraft;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.Role;
import com.contexa.server.identity.Visibility;

/** 테스트에서 자주 쓰는 준비물: 사용자, 프로젝트, 문서와 발언. */
public final class Fixtures {

	public static final String PASSWORD = "password-1234";

	private Fixtures() {
	}

	/** 새 사용자를 가입시키고 새 프로젝트의 OWNER로 만든다. */
	public static Caller newOwner(IdentityAccess identity) {
		UUID userId = identity.signUp("owner-" + UUID.randomUUID() + "@contexa.test", "검토자", PASSWORD);
		return Caller.user(userId, identity.createProject(userId, "테스트 프로젝트"));
	}

	/** 새 사용자를 가입시키고 owner의 프로젝트에 주어진 역할로 넣는다. */
	public static Caller newMember(IdentityAccess identity, Caller owner, Role role) {
		UUID userId = identity.signUp("member-" + UUID.randomUUID() + "@contexa.test", "구성원", PASSWORD);
		identity.setMember(owner.projectId(), owner.id(), userId, role);
		return Caller.user(userId, owner.projectId());
	}

	/** 구성원 모두가 읽는 회의 전사문을 올리고, 줄마다 발언 하나로 자른다. */
	public static List<NodeRef> transcript(DocumentEvidence document, Caller uploader, String... lines) {
		return document(document, uploader, Visibility.PROJECT, false, lines);
	}

	/** 공개 범위를 정해 문서를 올리고, 줄마다 발언 하나로 자른다. */
	public static List<NodeRef> document(DocumentEvidence document, Caller uploader, Visibility visibility,
			boolean externalOk, String... lines) {
		SourceVersionRef version = document.registerDocument(uploader, new DocumentInput(
				"문서 " + UUID.randomUUID(), visibility == Visibility.PRIVATE ? DocumentKind.MEMO : DocumentKind.TRANSCRIPT,
				null, visibility, externalOk, String.join("\n", lines)));
		return document.saveSpans(version.sourceVersionId(),
				Arrays.stream(lines).map(line -> new SpanDraft(null, line)).toList());
	}
}
