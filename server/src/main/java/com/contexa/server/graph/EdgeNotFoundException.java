package com.contexa.server.graph;

import java.util.UUID;

public class EdgeNotFoundException extends RuntimeException {

	public EdgeNotFoundException(UUID edgeId) {
		super("줄을 찾을 수 없다: " + edgeId);
	}
}
