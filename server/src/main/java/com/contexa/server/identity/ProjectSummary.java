package com.contexa.server.identity;

import java.util.UUID;

/** 내가 속한 프로젝트 하나와 그 안에서의 역할. */
public record ProjectSummary(UUID id, String title, Role role) {
}
