"""근거 모으기 평가: 답을 쓰기 전에 일꾼(answer_graph)이 모은 발언에 찾아야 하는 발언이 드는지 잰다.

    python -m eval.gather                                    # 모델 없이: 질문 원문 하나로 한 번 찾기(비용 없음)
    python -m eval.gather --model anthropic:claude-opus-5-5  # 질의 계획·근거 판단을 모델로(API 비용, 답은 쓰지 않는다)

- 검색 평가와 같은 방법으로 합성 문서를 새 프로젝트에 넣고, 앱 주소(검색·따라가기·현황)를 평가 계정 토큰으로 부른다.
- 일꾼과 같은 흐름(answer_graph.gather)을 쓰고, 답 쓰기와 내기는 하지 않는다.
- 회차마다 그때까지 모은 발언으로 recall을 잰다. 검색어가 늘면 모은 발언 수도 늘어나므로 질문당 평균 발언 수를 함께 적고,
  같은 프로젝트에서 질문 원문 하나로 k=20(서버 최대) 찾은 결과도 비교로 적는다.
- 평가 프로젝트에는 카드가 없다(발언만 넣는다). 그래서 안건 따라가기는 이 평가에 들어가지 않는다.
"""

from __future__ import annotations

import argparse
import logging
import urllib.error
import urllib.parse
from datetime import datetime
from typing import Any

from contexa_ai import answer_graph, tracing
from contexa_ai.config import Config
from contexa_ai.llm import make_llm
from contexa_ai.server import ServerError

from .common import ROOT, load_labels, save
from .extract import count_tokens
from .retrieval import Project, _get, found_ids, load_project, score, search

WIDE_K = 20


class AppSource:
    """answer_graph가 근거를 읽는 곳을 앱 주소로 잇는다. 평가는 답을 내지 않는다."""

    def __init__(self, server_url: str, project: Project, question: str, lang: str):
        self._base = f"{server_url}/api/v1/projects/{project.project_id}/context"
        self._server_url = server_url
        self._project = project
        self._question = question
        self._lang = lang

    def request(self) -> dict[str, Any]:
        return {"artifactId": "eval", "kind": "ANSWER", "question": self._question, "lang": self._lang}

    def state(self) -> dict[str, Any]:
        return _get(f"{self._base}/state", self._project.token)

    def search(self, query: str, k: int) -> dict[str, Any]:
        return search(self._server_url, self._project, query, k)

    def trace(self, node: dict[str, Any], direction: str, depth: int) -> dict[str, Any]:
        params = urllib.parse.urlencode({"type": node["type"], "id": node["id"], "direction": direction,
                                         "depth": depth})
        try:
            return _get(f"{self._base}/trace?{params}", self._project.token)
        except urllib.error.HTTPError as error:
            raise ServerError(f"{error.code}: 따라가기 실패") from error

    def submit(self, draft: dict[str, Any]) -> None:
        raise RuntimeError("평가는 답을 내지 않는다")


def run(server_url: str, model: str | None, languages: tuple[str, ...]) -> dict[str, Any]:
    labels = load_labels("retrieval")
    project = load_project(server_url, "근거 모으기 평가")
    spans = {span.id: span for document in project.documents for span in document.spans}
    llm = make_llm(model) if model else None
    queries = labels["queries"]
    rounds: list[dict[str, list[str]]] = []  # 회차 → 질문 id → 그때까지 모은 발언
    log: list[dict[str, Any]] = []
    with count_tokens() as counter:
        for query in queries:
            source = AppSource(server_url, project, query["query"], query["langs"][0])
            with tracing.job(f"eval-{query['id']}", "GENERATE", "eval"):
                state = answer_graph.gather(source, llm, languages=languages)
            entries = state["rounds_log"]
            for index, entry in enumerate(entries):
                while len(rounds) <= index:
                    rounds.append({})
                rounds[index][query["id"]] = [project.eval_id[span_id] for span_id in entry["sources"]
                                              if span_id in project.eval_id]
            # 두 번째 판단 없이 끝난 질문은 마지막 회차의 결과를 다음 회차에도 그대로 센다
            log.append({"id": query["id"], "query": query["query"], "rounds": len(entries),
                        "queries": [q for entry in entries for q in entry["queries"]]})
    for index in range(1, len(rounds)):
        for query in queries:
            rounds[index].setdefault(query["id"], rounds[index - 1][query["id"]])
    wide = {query["id"]: found_ids(project, search(server_url, project, query["query"], WIDE_K))
            for query in queries}

    def scored(found: dict[str, list[str]], label: int | str) -> dict[str, Any]:
        result = score(queries, found, spans, label)
        result["avg_sources"] = round(sum(len(found[query["id"]]) for query in queries) / len(queries), 1)
        return result

    return {
        "kind": "gather",
        "date": datetime.now().astimezone().isoformat(timespec="seconds"),
        "model": model or "없음(질문 원문 하나로 한 번)",
        "prompts": [answer_graph.PLAN_PROMPT.ref, answer_graph.JUDGE_PROMPT.ref] if model else [],
        "languages": list(languages),
        "search_k": answer_graph.SEARCH_K,
        "labels": "data/synthetic/labels/retrieval.json",
        "project_id": project.project_id,
        "rounds": [scored(found, "all") for found in rounds],
        f"search_k{WIDE_K}": scored(wide, WIDE_K),
        "tokens": {"calls": counter.calls, "input": counter.input_tokens, "output": counter.output_tokens},
        "log": log,
    }


def _row(name: str, result: dict[str, Any], key: str) -> str:
    total = result["groups"]["ALL"]
    same, cross = result["same_language"], result["cross_language"]
    return (f"{name:<18}{total[f'recall@{key}']:>8.3f}{total[f'hit@{key}']:>7.3f}"
            f"{same['found']:>6}/{same['total']:<4}{cross['found']:>5}/{cross['total']:<4}{result['avg_sources']:>8}")


def report(result: dict[str, Any]) -> str:
    lines = [f"근거 모으기 평가: 모델 {result['model']}, 검색어 하나에 k={result['search_k']}", "",
             f"{'':<18}{'recall':>8}{'hit':>7}{'같은 언어':>11}{'다른 언어':>10}{'평균 발언':>8}"]
    for index, found in enumerate(result["rounds"], start=1):
        lines.append(_row(f"모은 것({index}차까지)", found, "all"))
    lines.append(_row(f"질문 원문 k={WIDE_K}", result[f"search_k{WIDE_K}"], WIDE_K))
    names = {"ko": "한국어", "en": "영어", "ar": "아랍어", "mixed": "섞어 씀"}
    last = result["rounds"][-1]["groups"]
    lines += ["", "질문 언어별 recall(마지막 회차): "
              + ", ".join(f"{names[name]} {row['recall@all']:.3f}" for name, row in last.items() if name in names)]
    tokens = result["tokens"]
    if tokens["calls"]:
        lines.append(f"모델 호출 {tokens['calls']}번, 입력 토큰 {tokens['input']}, 출력 토큰 {tokens['output']}")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description="근거 모으기 평가 (합성 문서 + 정답 발언)")
    parser.add_argument("--model", help="공급자:모델. 주면 질의 계획·근거 판단에 모델을 쓴다(API 비용이 든다)")
    parser.add_argument("--out", help="결과 JSON 경로. 없으면 eval/results/gather-날짜시각.json")
    parser.add_argument("--errors", action="store_true", help="질문마다 놓친 발언과 쓴 검색어를 함께 보여 준다")
    args = parser.parse_args()
    logging.basicConfig(level=logging.WARNING)

    config = Config.from_env()
    result = run(config.server_url, args.model, config.languages)
    print(report(result))
    path = save(result, "gather" if args.model else "gather-nomodel", args.out)
    print(f"\n결과: {path.relative_to(ROOT) if path.is_relative_to(ROOT) else path}")
    if args.errors:
        searched = {entry["id"]: entry["queries"] for entry in result["log"]}
        for row in result["rounds"][-1]["queries"]:
            if row["missed"]:
                print(f"[{row['id']}] {row['query']}  놓침 {row['missed']}  검색어 {searched[row['id']]}")


if __name__ == "__main__":
    main()
