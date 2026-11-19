package com.contexa.server.pipeline;

import com.contexa.server.document.DocumentInfo;

/** 문서 목록의 한 줄: 문서 정보, 판 수, 가장 최근 판의 처리 상태. */
public record DocumentStatus(DocumentInfo document, int versionCount, VersionStatus latest) {
}
