package com.contexa.server.pipeline;

import java.util.List;
import java.util.UUID;

import com.contexa.server.document.DocumentInfo;
import com.contexa.server.document.Span;

/**
 * 원문 뷰어가 받는 것: 문서 정보, 판마다의 처리 상태, 그리고 연 판의 내용.
 *
 * @param versions 1판부터
 * @param opened   지금 연 판
 */
public record DocumentView(DocumentInfo document, List<VersionStatus> versions, OpenedVersion opened) {

	/**
	 * 연 판의 내용.
	 *
	 * @param spans   발언, 순서대로. 아직 자르지 않았으면 비어 있다
	 * @param content 아직 자르지 않았을 때만 원문 전체. 잘랐으면 null
	 */
	public record OpenedVersion(UUID sourceVersionId, int versionNo, List<Span> spans, String content) {
	}
}
