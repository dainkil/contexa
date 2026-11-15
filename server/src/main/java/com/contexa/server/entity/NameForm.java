package com.contexa.server.entity;

import java.util.UUID;

/** 한 언어로 적은 이름 표기 하나. status는 CANDIDATE(제안 표기), CONFIRMED, REJECTED 중 하나다. */
public record NameForm(String lang, String form, String status, UUID evidenceSpanId) {
}
