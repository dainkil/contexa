"""평가 스크립트가 함께 쓰는 것: 합성 자료 읽기, 발언 자르기, 언어 판별, 이름 정규화, 결과 저장."""

from __future__ import annotations

import json
import re
import unicodedata
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from contexa_ai import parse

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data" / "synthetic"
RESULTS = Path(__file__).resolve().parent / "results"


@dataclass(frozen=True)
class Span:
    id: str  # "m1:3" = 문서 m1을 자른 세 번째 발언
    document: str
    seq: int
    speaker: str | None
    text: str


@dataclass(frozen=True)
class Document:
    id: str
    title: str
    kind: str
    spans: list[Span]


def load_corpus(data: Path = DATA) -> tuple[str, list[Document]]:
    """합성 문서를 읽어 일꾼과 같은 규칙(parse.py)으로 자른다. 라벨의 발언 번호는 이 순서를 따른다."""
    manifest = json.loads((data / "corpus.json").read_text(encoding="utf-8"))
    documents = []
    for entry in manifest["documents"]:
        content = (data / entry["file"]).read_text(encoding="utf-8")
        drafts = parse.split_document(entry["kind"], content)
        spans = [Span(f"{entry['id']}:{seq}", entry["id"], seq, draft["speakerLabel"], draft["text"])
                 for seq, draft in enumerate(drafts, start=1)]
        documents.append(Document(entry["id"], entry["title"], entry["kind"], spans))
    return manifest["title"], documents


def load_labels(name: str, data: Path = DATA) -> dict[str, Any]:
    return json.loads((data / "labels" / f"{name}.json").read_text(encoding="utf-8"))


_SCRIPTS = {
    "ko": re.compile(r"[가-힣ㄱ-ㆎ]"),
    "ar": re.compile(r"[؀-ۿ]"),
    "en": re.compile(r"[A-Za-z]"),
}


def languages_of(text: str) -> set[str]:
    """글에 들어 있는 언어. 낱말마다 가장 많은 글자의 문자로 언어를 정하고, 낱말 둘 이상이거나 30% 이상인 언어만 센다.

    "그럼 인쇄 발주는 Sara가 맡아 주세요"의 Sara처럼 이름 하나가 섞인 것은 언어로 치지 않는다.
    """
    counts: dict[str, int] = {}
    words = 0
    for word in text.split():
        letters = {lang: len(pattern.findall(word)) for lang, pattern in _SCRIPTS.items()}
        lang, count = max(letters.items(), key=lambda item: item[1])
        if count == 0:
            continue
        words += 1
        counts[lang] = counts.get(lang, 0) + 1
    return {lang for lang, count in counts.items() if count >= 2 or count / max(words, 1) >= 0.3}


_ARABIC_MARKS = re.compile(r"[ً-ْـ]")
_ALIF = str.maketrans({"أ": "ا", "إ": "ا", "آ": "ا"})


def normalize(text: str) -> str:
    """비교용 정규화: NFC, 소문자, 공백 정리, 아랍어 모음 기호·타트윌 제거, 알리프 통일. 원문은 바꾸지 않는다."""
    text = unicodedata.normalize("NFC", text).casefold()
    text = _ARABIC_MARKS.sub("", text).translate(_ALIF)
    return " ".join(text.split())


def ratio(part: int, whole: int) -> float | None:
    return round(part / whole, 3) if whole else None


def save(result: dict[str, Any], name: str, out: str | None) -> Path:
    """결과를 JSON으로 남긴다. 경로를 주지 않으면 eval/results/이름-날짜시각.json"""
    path = Path(out) if out else RESULTS / f"{name}-{datetime.now().strftime('%Y%m%d-%H%M')}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path
