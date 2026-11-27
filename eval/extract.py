"""뽑기 평가: 합성 회의 4회를 차례로 모델에게 보여 주고, 낸 카드·줄 후보를 정답 라벨과 맞춰 본다.

    python -m eval.extract --oracle                         # 모델 없이 정답을 답으로 넣어 채점·라벨을 확인(비용 없음)
    python -m eval.extract --model anthropic:claude-opus-5-5   # 실제 모델 호출(API 비용이 든다)
    python -m eval.extract --model … --window 4 --overlap 1    # 회의를 작은 창으로 잘라 창 나누기·합치기까지 잰다

- 일꾼과 같은 흐름(extract_graph.run: 창 나누기 → 창마다 뽑기·거르기 → 합치기)을 쓴다.
  지시문(prompts/extract.vN.md), 자료 모양(build_user_prompt), 거르기(to_candidates)도 같다.
  서버에 내지는 않으므로 서버의 검증과 "거절되면 한 번 다시 뽑기"는 들어가지 않는다.
- 합성 회의는 발언이 10개도 안 되어 기본 창(200발언)이면 창이 하나다. --window를 작게 주면 긴 회의처럼 나눠 본다.
  --oracle에 --window를 주면 창마다 정답 전체를 답으로 준다. 창 밖 발언만 근거로 댄 후보는 그 창에서 걸러지고
  ("버린 후보"로 센다), 여러 창에 나온 같은 카드는 합쳐진다. 그래서 합치기가 맞으면 여전히 1.0이다.
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

from contexa_ai import extract, extract_graph, tracing
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


class _Oracle:
    """모델 대신 정답을 답으로 준다. 창이 여럿이면 창마다 같은 답을 주고, 창 밖 근거는 일꾼이 거른다."""

    def __init__(self, output: dict[str, Any]):
        self._output = output

    def structured(self, system: str, user: str, schema: dict[str, Any]) -> dict[str, Any]:
        return self._output


def run(model: str | None, ontology: dict[str, Any], window: int | None = None,
        overlap: int | None = None) -> dict[str, Any]:
    """model이 None이면 정답을 답으로 넣는다(oracle)."""
    _, documents = load_corpus()
    by_id = {document.id: document for document in documents}
    labels = load_labels("extract")
    model_llm = make_llm(model) if model else None
    meetings, outputs = [], []
    with count_tokens() as counter:
        for index, meeting in enumerate(labels["meetings"]):
            document = by_id[meeting["document"]]
            existing = scoring.existing_cards(labels["meetings"], index)
            spans = scoring.spans_of(document)
            state = scoring.state_of(existing)
            llm = model_llm or _Oracle(scoring.oracle_output(meeting, existing))
            with tracing.job(f"eval-{document.id}", "EXTRACT", "eval"):
                result = extract_graph.run(llm, {"document": {"title": document.title, "kind": document.kind}},
                                           spans, state, ontology, window=window, overlap=overlap)
            scored = scoring.score_meeting(meeting, existing, result.output, result.extraction, len(spans))
            scored["windows"] = result.windows
            meetings.append(scored)
            outputs.append({"document": document.id, "output": result.output})
    return {
        "kind": "extract",
        "date": datetime.now().astimezone().isoformat(timespec="seconds"),
        "model": model or "oracle",
        "prompt": extract.SYSTEM_PROMPT.ref,
        "window": {"size": window or extract_graph.WINDOW, "overlap": extract_graph.OVERLAP if overlap is None else overlap},
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
    window = result["window"]
    windows = sum(meeting["windows"] for meeting in result["meetings"])
    lines = [f"뽑기 평가: 모델 {result['model']}, 지시문 {result['prompt']}, "
             f"창 {window['size']}발언(겹침 {window['overlap']}) → 회의 {len(result['meetings'])}개에 창 {windows}개", "",
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
    parser.add_argument("--window", type=int, help=f"창 하나의 발언 수(기본 {extract_graph.WINDOW})")
    parser.add_argument("--overlap", type=int, help=f"앞 창과 겹치는 발언 수(기본 {extract_graph.OVERLAP})")
    args = parser.parse_args()
    logging.basicConfig(level=logging.WARNING)

    config = Config.from_env()
    ontology = ServerClient(config.server_url, config.worker_token, "eval").ontology()
    result = run(None if args.oracle else args.model, ontology, args.window, args.overlap)
    print(report(result))
    path = save(result, "extract-oracle" if args.oracle else "extract", args.out)
    print(f"\n결과: {path.relative_to(ROOT) if path.is_relative_to(ROOT) else path}")
    if args.errors:
        for meeting in result["meetings"]:
            print(f"\n[{meeting['document']}] 틀림 {meeting['extra']}\n놓침 {meeting['missed']}")


if __name__ == "__main__":
    main()
