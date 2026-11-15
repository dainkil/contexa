package com.contexa.server.document;

import java.util.UUID;

/** 문서의 한 판이 어느 문서의 몇 번째 판인지. */
public record VersionInfo(UUID documentId, UUID projectId, String title, DocumentKind kind, String language,
		int versionNo) {
}
