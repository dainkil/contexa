package com.contexa.server.document;

import java.util.UUID;

/** 그런 문서, 판, 또는 발언이 없다. */
public class DocumentNotFoundException extends RuntimeException {

	public DocumentNotFoundException(String what, UUID id) {
		super(what + "을(를) 찾을 수 없다: " + id);
	}
}
