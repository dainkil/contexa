"""검색 평가: 질문마다 찾아야 하는 발언이 검색 결과 k개 안에 드는지 잰다(recall@k). 모델 비용이 없다.

    python -m eval.retrieval [--k 8]

- 합성 문서를 일꾼과 같은 규칙(parse.py)으로 잘라 서버의 평가용 주소(POST /dev/eval/corpus)로 새 프로젝트에 넣는다.
  작업 큐를 거치지 않으므로 일꾼이 켜져 있어도 모델을 부르지 않는다.
- 앱과 같은 검색 주소(POST /api/v1/projects/{id}/context/search)를 평가 계정의 로그인 토큰으로 부른다.
- 질문 언어별로, 그리고 찾아야 하는 발언이 질문과 같은 언어인지 다른 언어인지 나눠서 센다.
"""

from __future__ import annotations

import argparse
import json
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from contexa_ai.config import Config

from .common import ROOT, Document, Span, languages_of, load_corpus, load_labels, ratio, save


def _request(url: str, body: dict[str, Any] | None, token: str | None, method: str) -> dict[str, Any]:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def _post(url: str, body: dict[str, Any], token: str | None = None) -> dict[str, Any]:
    return _request(url, body, token, "POST")


def _get(url: str, token: str) -> dict[str, Any]:
    return _request(url, None, token, "GET")


@dataclass(frozen=True)
class Project:
    """평가용으로 새로 만든 프로젝트. eval_id는 서버의 발언 id → "m1:3" 같은 평가용 id."""
    documents: list[Document]
    project_id: str
    token: str
    eval_id: dict[str, str]


def load_project(server_url: str, purpose: str) -> Project:
    """합성 문서를 서버의 평가용 주소로 새 프로젝트에 넣고, 평가 계정으로 로그인한다."""
    title, documents = load_corpus()
    loaded = _post(f"{server_url}/dev/eval/corpus", {
        "title": f"{title} {purpose} {datetime.now().strftime('%m-%d %H:%M')}",
        "documents": [{"title": document.title, "kind": document.kind,
                       "spans": [{"speakerLabel": span.speaker, "text": span.text} for span in document.spans]}
                      for document in documents]})
    eval_id: dict[str, str] = {}
    for document, stored in zip(documents, loaded["documents"]):
        for span, span_id in zip(document.spans, stored["spanIds"]):
            eval_id[span_id] = span.id
    token = _post(f"{server_url}/api/v1/auth/login", {"email": loaded["email"], "password": loaded["password"]})["token"]
    return Project(documents, loaded["projectId"], token, eval_id)


def search(server_url: str, project: Project, query: str, k: int) -> dict[str, Any]:
    """앱과 같은 검색 주소를 부른다."""
    return _post(f"{server_url}/api/v1/projects/{project.project_id}/context/search", {"query": query, "k": k},
                 project.token)


def found_ids(project: Project, packet: dict[str, Any]) -> list[str]:
    """답변서의 발언을 평가용 id로 바꾼다."""
    return [project.eval_id[source["span"]["id"]] for source in packet.get("sources", [])
            if source["span"]["id"] in project.eval_id]


def group_of(langs: list[str]) -> str:
    return langs[0] if len(langs) == 1 else "mixed"


def score(queries: list[dict[str, Any]], found: dict[str, list[str]], spans: dict[str, Span],
          k: int | str) -> dict[str, Any]:
    """found: 질문 id → 검색 결과로 나온 발언 id. 질문 하나의 recall = 찾은 정답 수 / 정답 수.

    k는 열 이름(recall@k)에만 쓴다. 근거 모으기 평가는 "all"(모은 발언 전부)을 준다.
    """
    rows = []
    pairs = {"same": [0, 0], "cross": [0, 0]}  # [찾음, 전체]
    for query in queries:
        got = set(found.get(query["id"], []))
        relevant = query["relevant"]
        hits = [span_id for span_id in relevant if span_id in got]
        for span_id in relevant:
            side = "same" if set(query["langs"]) & languages_of(spans[span_id].text) else "cross"
            pairs[side][1] += 1
            pairs[side][0] += span_id in got
        rows.append({"id": query["id"], "group": group_of(query["langs"]), "query": query["query"],
                     "recall": len(hits) / len(relevant), "hit": bool(hits),
                     "missed": [span_id for span_id in relevant if span_id not in got],
                     "found": found.get(query["id"], [])})
    groups: dict[str, dict[str, Any]] = {}
    for name in ["ko", "en", "ar", "mixed", "ALL"]:
        members = [row for row in rows if name == "ALL" or row["group"] == name]
        if members:
            groups[name] = {"queries": len(members),
                            f"recall@{k}": round(sum(row["recall"] for row in members) / len(members), 3),
                            f"hit@{k}": ratio(sum(row["hit"] for row in members), len(members))}
    return {
        "groups": groups,
        "same_language": {"found": pairs["same"][0], "total": pairs["same"][1],
                          "rate": ratio(pairs["same"][0], pairs["same"][1])},
        "cross_language": {"found": pairs["cross"][0], "total": pairs["cross"][1],
                           "rate": ratio(pairs["cross"][0], pairs["cross"][1])},
        "queries": rows,
    }


def run(server_url: str, k: int) -> dict[str, Any]:
    labels = load_labels("retrieval")
    project = load_project(server_url, "검색 평가")
    found = {query["id"]: found_ids(project, search(server_url, project, query["query"], k))
             for query in labels["queries"]}
    spans = {span.id: span for document in project.documents for span in document.spans}
    return {
        "kind": "retrieval",
        "date": datetime.now().astimezone().isoformat(timespec="seconds"),
        "method": "pg_trgm word_similarity (글자 검색만)",
        "k": k,
        "labels": "data/synthetic/labels/retrieval.json",
        "project_id": project.project_id,
        **score(labels["queries"], found, spans, k),
    }


def report(result: dict[str, Any]) -> str:
    k = result["k"]
    names = {"ko": "한국어", "en": "영어", "ar": "아랍어", "mixed": "섞어 씀", "ALL": "전체"}
    lines = [f"검색 평가: {result['method']}, k={k}", "",
             f"{'질문 언어':<10}{'질문 수':>6}{f'recall@{k}':>12}{f'hit@{k}':>9}"]
    for name, row in result["groups"].items():
        lines.append(f"{names[name]:<10}{row['queries']:>6}{row[f'recall@{k}']:>12.3f}{row[f'hit@{k}']:>9.3f}")
    same, cross = result["same_language"], result["cross_language"]
    lines += ["",
              f"질문과 같은 언어의 정답 발언: {same['found']}/{same['total']} 찾음",
              f"질문과 다른 언어의 정답 발언: {cross['found']}/{cross['total']} 찾음"]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description="검색 평가 (합성 문서 + 정답 발언)")
    parser.add_argument("--k", type=int, default=8, help="검색 결과 수(서버 최대 20)")
    parser.add_argument("--out", help="결과 JSON 경로. 없으면 eval/results/retrieval-날짜시각.json")
    parser.add_argument("--errors", action="store_true", help="질문마다 놓친 발언을 함께 보여 준다")
    args = parser.parse_args()

    result = run(Config.from_env().server_url, args.k)
    print(report(result))
    path = save(result, "retrieval", args.out)
    print(f"\n결과: {path.relative_to(ROOT) if path.is_relative_to(ROOT) else path}")
    if args.errors:
        for row in result["queries"]:
            if row["missed"]:
                print(f"[{row['id']}] {row['query']}  놓침 {row['missed']}  찾은 것 {row['found']}")


if __name__ == "__main__":
    main()
