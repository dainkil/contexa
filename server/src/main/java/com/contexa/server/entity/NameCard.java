package com.contexa.server.entity;

import java.util.List;

import com.contexa.server.contract.NodeView;
import com.contexa.server.contract.Source;

/**
 * 이름 카드: 인물·기관 한 명과, 언어별로 적힌 표기 전부(제안 표기와 거절된 표기 포함).
 *
 * @param sources 이 인물·기관을 언급한 발언과 표기의 근거 발언 원문. 이름 확인은 원문을 모르므로 비워 두고,
 *                안내 데스크가 요청자가 읽을 수 있는 것만 채운다
 */
public record NameCard(NodeView entity, List<NameForm> names, List<Source> sources) {

	public NameCard {
		names = List.copyOf(names);
		sources = List.copyOf(sources);
	}

	public NameCard(NodeView entity, List<NameForm> names) {
		this(entity, names, List.of());
	}

	public NameCard withSources(List<Source> sources) {
		return new NameCard(entity, names, sources);
	}
}
