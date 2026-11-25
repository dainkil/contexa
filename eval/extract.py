"""뽑기 평가: 합성 회의 4회를 차례로 모델에게 보여 주고, 낸 카드·줄 후보를 정답 라벨과 맞춰 본다.

    python -m eval.extract --oracle                         # 모델 없이 정답을 답으로 넣어 채점·라벨을 확인(비용 없음)
    python -m eval.extract --model anthropic:claude-opus-5-5   # 실제 모델 호출(API 비용이 든다)

- 일꾼과 같은 지시문(prompts/extract.vN.md), 같은 자료 모양(build_user_prompt), 같은 거르기(to_candidates)를 쓴다.
  서버에 내지는 않으므로 서버의 검증과 "거절되면 한 번 다시 뽑기"는 들어가지 않는다.
- 회의마다 앞 회의의 정답 카드를 "이미 있는 카드"로 보여 준다(사람이 확인했다고 보고). 그래야 회의 간 연결을 따로 잴 수 있다.
- 규칙표(온톨로지)는 서버에서 받는다. 서버가 떠 있어야 한다(CONTEXA_SERVER_URL, CONTEXA_WORKER_TOKEN).
"""

from __future__ import annotations

import argparse
import contextlib
import json
import logging
from datetime import datetime
from typing import Any, Iterator

from contexa_ai import extract, tracing
from contexa_ai.config import Config
from contexa_ai.llm import make_llm
from contexa_ai.server import ServerClient

from . import scoring
from .common import ROOT, load_corpus, load_labels, save


class _TokenCounter(logging.Handler):
    """llm.py가 호출마다 남기는 로그 줄에서 토큰 수를 모은다."""

    def __init__(self) -> None:
        super().__init__()
        self.calls = 0
        self.input_tokens = 0
        self.output_tokens = 0

    def emit(self, record: logging.LogRecord) -> None:
        try:
            line = json.loads(record.getMessage())
        except ValueError:
            return
        if line.get("event") == "llm_call":
            self.calls += 1
            self.input_tokens += line.get("input_tokens") or 0
            self.output_tokens += line.get("output_tokens") or 0


@contextlib.contextmanager
def count_tokens() -> Iterator[_TokenCounter]:
    """그동안 llm.py가 남기는 호출 기록(INFO)을 모은다. 화면에는 찍지 않는다."""
    llm_log = logging.getLogger("contexa.llm")
    counter = _TokenCounter()
    level, propagate = llm_log.level, llm_log.propagate
    llm_log.addHandler(counter)
    llm_log.setLevel(logging.INFO)
    llm_log.propagate = False
    try:
        yield counter
    finally:
        llm_log.removeHandler(counter)
        llm_log.setLevel(level)
        llm_log.propagate = propagate


def run(model: str | None, ontology: dict[str, Any]) -> dict[str, Any]:
    """model이 None이면 정답을 답으로 넣는다(oracle)."""
    _, documents = load_corpus()
    by_id = {document.id: document for document in documents}
    labels = load_labels("extract")
    llm = make_llm(model) if model else None
    meetings, outputs = [], []
    with count_tokens() as counter:
        for index, meeting in enumerate(labels["meetings"]):
            document = by_id[meeting["document"]]
            existing = scoring.existing_cards(labels["meetings"], index)
            spans = scoring.spans_of(document)
            state = scoring.state_of(existing)
            if llm is None:
                output = scoring.oracle_output(meeting, existing)
            else:
                user = extract.build_user_prompt({"document": {"title": document.title, "kind": document.kind}},
                                                 spans, state, ontology)
                with tracing.job(f"eval-{document.id}", "EXTRACT", "eval"):
                    output = llm.structured(extract.SYSTEM_PROMPT, user, extract.OUTPUT_SCHEMA)
            extraction = extract.to_candidates(output, spans, state, ontology)
            meetings.append(scoring.score_meeting(meeting, existing, output, extraction, len(spans)))
            outputs.append({"document": document.id, "output": output})
    return {
        "kind": "extract",
        "date": datetime.now().astimezone().isoformat(timespec="seconds"),
        "model": model or "oracle",
        "prompt": extract.SYSTEM_PROMPT.ref,
        "labels": "data/synthetic/labels/extract.json",
        "summary": scoring.summarize(meetings),
        "tokens": {"calls": counter.calls, "input": counter.input_tokens, "output": counter.output_tokens},
        "meetings": meetings,
        "outputs": outputs,
    }


def _fmt(value: float | None) -> str:
    return "  -  " if value is None else f"{value:.3f}"


def report(result: dict[str, Any]) -> str:
    summary = result["summary"]
    lines = [f"뽑기 평가: 모델 {result['model']}, 지시문 {result['prompt']}", "",
             f"{'카드':<16}{'정밀도':>8}{'재현율':>8}   TP/FP/FN"]
    for name, row in summary["cards"].items():
        lines.append(f"{name:<16}{_fmt(row['precision']):>8}{_fmt(row['recall']):>8}   "
                     f"{row['tp']}/{row['fp']}/{row['fn']}")
    lines += ["", f"{'줄':<16}{'정밀도':>8}{'재현율':>8}   TP/FP/FN"]
    for name, row in summary["links"].items():
        lines.append(f"{name:<16}{_fmt(row['precision']):>8}{_fmt(row['recall']):>8}   "
                     f"{row['tp']}/{row['fp']}/{row['fn']}")
    cross = summary["cross_links"]
    kind, evidence = summary["event_kind_accuracy"], summary["evidence_exact"]
    lines += [
        f"{'회의 간 줄':<16}{_fmt(cross['precision']):>8}{_fmt(cross['recall']):>8}   "
        f"{cross['tp']}/{cross['fp']}/{cross['fn']}",
        "",
        f"결정 사건 종류가 맞음: {kind['correct']}/{kind['total']}",
        f"근거 발언이 정답 안에 있음: {evidence['correct']}/{evidence['total']}",
        f"확정되지 않은 발언을 확정(DECISION·CHANGE)으로 뽑음: {summary['misclassified_as_decided']}건",
        f"이미 있는 카드를 새로 만듦(이름·글자 일치만 셈): {summary['duplicates']}건",
        f"일꾼이 규칙에 안 맞아 버린 후보: {summary['dropped_by_worker']}건",
        f"모델 호출 {result['tokens']['calls']}번, 입력 토큰 {result['tokens']['input']}, 출력 토큰 {result['tokens']['output']}",
    ]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description="뽑기 평가 (합성 회의 + 정답 라벨)")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--model", help="공급자:모델. 예: anthropic:claude-opus-5-5 (API 비용이 든다)")
    group.add_argument("--oracle", action="store_true", help="모델 대신 정답을 넣어 채점과 라벨만 확인한다")
    parser.add_argument("--out", help="결과 JSON 경로. 없으면 eval/results/extract-날짜시각.json")
    parser.add_argument("--errors", action="store_true", help="회의마다 틀린 것과 놓친 것을 함께 보여 준다")
    args = parser.parse_args()
    logging.basicConfig(level=logging.WARNING)

    config = Config.from_env()
    ontology = ServerClient(config.server_url, config.worker_token, "eval").ontology()
    result = run(None if args.oracle else args.model, ontology)
    print(report(result))
    path = save(result, "extract-oracle" if args.oracle else "extract", args.out)
    print(f"\n결과: {path.relative_to(ROOT) if path.is_relative_to(ROOT) else path}")
    if args.errors:
        for meeting in result["meetings"]:
            print(f"\n[{meeting['document']}] 틀림 {meeting['extra']}\n놓침 {meeting['missed']}")


if __name__ == "__main__":
    main()
