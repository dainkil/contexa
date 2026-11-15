package com.contexa.server.generation;

import java.util.UUID;

/** 그런 산출물이 없다. 다른 사람이 요청한 산출물도 없는 것으로 본다. */
public class ArtifactNotFoundException extends RuntimeException {

	public ArtifactNotFoundException(UUID artifactId) {
		super("산출물이 없다: " + artifactId);
	}
}
