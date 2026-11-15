"""문서를 발언으로 자른다. 모델을 쓰지 않고 규칙으로만 자른다.

자르기는 "어디서 끊을지"만 정하고 내용을 바꾸지 않는다. 원문을 고치거나 요약하지 않는다.
"""

from __future__ import annotations

import re

# 줄 맨 앞의 시간 표시. 예: "[00:12:03]", "(12:03)", "00:12:03"
_TIMESTAMP = re.compile(r"^\s*[\[(]?\d{1,2}:\d{2}(?::\d{2})?[\])]?\s*")
# "화자: 내용". 화자 표기는 짧고 문장 부호가 없어야 한다. 전각 콜론(：)도 받는다.
_SPEAKER = re.compile(r"^(?P<speaker>[^:：.!?。？！\n]{1,30}?)\s*[:：]\s*(?P<text>\S.*)$")
_MAX_CHARS = 1200


def split_document(kind: str, content: str) -> list[dict[str, str | None]]:
    """문서 종류에 맞게 자른다. 돌려주는 값은 서버의 SpanDraft 모양이다."""
    if kind == "TRANSCRIPT":
        return _split_transcript(content)
    return _split_paragraphs(content)


def _split_transcript(content: str) -> list[dict[str, str | None]]:
    """전사문: 줄마다 발언 하나. "화자: 내용"이면 화자 표기를 따로 떼고, 화자가 없는 줄은 앞 발언에 이어 붙인다."""
    spans: list[dict[str, str | None]] = []
    for raw in content.splitlines():
        line = _TIMESTAMP.sub("", raw).strip()
        if not line:
            continue
        match = _SPEAKER.match(line)
        if match:
            spans.append({"speakerLabel": match["speaker"].strip(), "text": match["text"].strip()})
        elif spans and spans[-1]["speakerLabel"] is not None and len(spans[-1]["text"]) + len(line) < _MAX_CHARS:
            # 한 사람의 말이 여러 줄로 적힌 경우
            spans[-1]["text"] = f"{spans[-1]['text']} {line}"
        else:
            spans.append({"speakerLabel": None, "text": line})
    return spans


def _split_paragraphs(content: str) -> list[dict[str, str | None]]:
    """그 밖의 문서: 빈 줄로 나뉜 문단마다 하나. 빈 줄이 없으면 줄마다 하나. 너무 긴 문단은 문장 경계에서 나눈다."""
    blocks = [block.strip() for block in re.split(r"\n\s*\n", content) if block.strip()]
    if len(blocks) <= 1:
        blocks = [line.strip() for line in content.splitlines() if line.strip()]
    spans: list[dict[str, str | None]] = []
    for block in blocks:
        for piece in _limit(" ".join(block.split())):
            spans.append({"speakerLabel": None, "text": piece})
    return spans


def _limit(text: str) -> list[str]:
    if len(text) <= _MAX_CHARS:
        return [text]
    pieces: list[str] = []
    current = ""
    for sentence in re.split(r"(?<=[.!?。？！])\s+", text):
        if current and len(current) + len(sentence) + 1 > _MAX_CHARS:
            pieces.append(current)
            current = sentence
        else:
            current = f"{current} {sentence}".strip()
    if current:
        pieces.append(current)
    return pieces
