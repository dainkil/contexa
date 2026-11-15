package com.contexa.server.entity;

import java.util.List;

import com.contexa.server.contract.NodeView;

/** 이름 카드: 인물·기관 한 명과, 언어별로 적힌 표기 전부. */
public record NameCard(NodeView entity, List<NameForm> names) {

	public NameCard {
		names = List.copyOf(names);
	}
}
