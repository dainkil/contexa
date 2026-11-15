package com.contexa.server.decision;

import com.contexa.server.contract.NodeType;

import java.util.UUID;

/** 그런 카드가 없다. */
public class NodeNotFoundException extends RuntimeException {

	public NodeNotFoundException(NodeType type, UUID id) {
		super("카드가 없다: " + type + " " + id);
	}
}
