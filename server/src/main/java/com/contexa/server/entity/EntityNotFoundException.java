package com.contexa.server.entity;

import java.util.UUID;

/** 그런 인물·기관 카드가 없다(다른 프로젝트의 것도 없는 것으로 본다). */
public class EntityNotFoundException extends RuntimeException {

	public EntityNotFoundException(UUID entityId) {
		super("인물·기관 카드가 없다: " + entityId);
	}
}
