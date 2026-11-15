package com.contexa.server.document;

import java.util.UUID;

/** 문서의 한 판(버전)을 가리키는 참조. */
public record SourceVersionRef(UUID documentId, UUID sourceVersionId, int versionNo) {
}
