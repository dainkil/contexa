package com.contexa.server.document;

/** 아직 저장되지 않은 발언. speakerLabel은 전사문에 적힌 화자 표기 그대로이고, 없으면 null이다. */
public record SpanDraft(String speakerLabel, String text) {
}
