"""EXTRACT 작업의 흐름: 문서 하나에서 카드·줄 후보를 뽑는 동안의 단계를 LangGraph로 잇는다.

    prepare ─(창마다 Send)─▶ window ×N ─▶ merge ─▶ submit ──(서버가 받지 않으면, 문제의 창만 Send)──▶ window …

- prepare: 발언을 WINDOW개씩 자른다. 앞 창의 끝 OVERLAP개는 다음 창에도 넣어, 창 경계에 걸친 이야기를 잃지 않는다.
- window: 창마다 모델이 후보를 뽑는다(extract.build_user_prompt·to_candidates를 그대로 쓴다). 창들은 동시에
  돈다(concurrency). 창마다 key 앞에 표시(w0:, w1:, …)를 붙여 겹치지 않게 한다.
- merge: 창이 겹치는 곳에서는 같은 카드가 두 번 나온다. 다른 창에서 나온 카드 중 종류가 같고 글이 같거나
  (정규화해서 비교) 근거 발언이 똑같은 것을 하나로 합친다. 인물·기관은 이름이 같을 때만 합친다(비슷한 이름을
  합치지 않는다). 줄은 합친 카드로 다시 잇고, 같은 줄은 근거를 모아 하나로 둔다.
- submit: 서버에 낸다. 서버가 받지 않으면 거절 이유에 나온 key나 발언으로 문제가 된 창을 찾아, 그 창만 이유를
  알려 주고 다시 뽑는다(MAX_REPAIRS번). 어느 창인지 모르면 모든 창을 다시 뽑는다.

한계: 창들은 서로의 결과를 보지 못한다. 앞 창에서 처음 나온 안건에 뒤 창의 결정을 잇는 줄은 겹치는 부분이 아니면
생기지 않는다. 이미 있는 카드(state)에 잇는 줄은 창마다 생긴다.

LangGraph는 작업 한 번 안에서만 쓴다. 체크포인터와 interrupt는 쓰지 않는다.
여러 창이 동시에 돌 때 하나가 실패하면 LangGraph는 다른 창의 예외를 올릴 수 있다. 그래서 창은 예외를 올리지
않고 처음 난 실패를 기록해 두고, merge가 그 실패를 다시 올린다. 이미 모델을 부르는 중인 창은 끝날 때까지 기다린다.
"""

from __future__ import annotations

import logging
import re
import threading
import unicodedata
from dataclasses import dataclass
from typing import Annotated, Any, Callable, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.types import Send

from . import extract
from .llm import LLM
from .server import ServerRejected

log = logging.getLogger("contexa.extract")

# 창 하나의 발언 수와 이웃 창과 겹치는 발언 수
WINDOW = 200
OVERLAP = 20
# 서버가 받지 않았을 때 다시 뽑는 횟수
MAX_REPAIRS = 1
RECURSION_LIMIT = 50

_FEEDBACK = "\n\n앞서 낸 답은 서버가 이 이유로 받지 않았습니다: {error}\n이 문제를 고쳐서 처음부터 다시 답해 주세요."
_KEY_IN_MESSAGE = re.compile(r"(?<![\w:])([wr])(\d+):")
_UUID = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
_TEXT_FIELD = {"TOPIC": "title", "DECISION_ITEM": "title", "TASK": "title", "CONDITION": "description",
               "DECISION_EVENT": "statement", "ENTITY": "name"}


@dataclass
class Result:
    """뽑은 후보(서버에 내는 모양)와, 평가가 채점에 쓰는 모델의 원래 답(창마다의 답을 합친 것)."""

    extraction: extract.Extraction
    output: dict[str, Any]
    windows: int
    repairs: int


class _Run:
    """한 번 돌리는 동안 창들이 함께 쓰는 것: 처음 난 실패와 "그만" 표시."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.abort = threading.Event()
        self.error: BaseException | None = None

    def fail(self, error: BaseException) -> None:
        with self.lock:
            if self.error is None:
                self.error = error
        self.abort.set()


def _merge_dicts(left: dict, right: dict) -> dict:
    return {**left, **right}


class ExtractState(TypedDict, total=False):
    windows: list[list[dict[str, Any]]]        # 창마다의 발언
    results: Annotated[dict[int, dict[str, Any]], _merge_dicts]   # 창 번호 → 그 창에서 뽑은 것
    redo: list[int]                            # 다시 뽑을 창
    feedback: str
    repairs: int
    merged: dict[str, Any]                     # {"extraction": …, "output": …}
    submitted: bool


class WindowTask(TypedDict):
    index: int
    spans: list[dict[str, Any]]
    prefix: str
    feedback: str


def windows_of(spans: list[dict[str, Any]], window: int = WINDOW, overlap: int = OVERLAP) -> list[list[dict[str, Any]]]:
    """발언을 window개씩 자른다. 이웃 창은 overlap개씩 겹친다. 발언이 없으면 빈 목록."""
    if not spans:
        return []
    window = max(1, window)
    step = max(1, window - max(0, overlap))
    result = []
    start = 0
    while True:
        result.append(spans[start:start + window])
        if start + window >= len(spans):
            return result
        start += step


def run(llm: LLM, document: dict[str, Any], spans: list[dict[str, Any]], state: dict[str, Any],
        ontology: dict[str, Any], *, check: Callable[[], None] = lambda: None,
        submit: Callable[[dict[str, Any]], None] | None = None, concurrency: int = 1,
        window: int | None = None, overlap: int | None = None) -> Result:
    """후보를 뽑아 합친다. submit이 있으면 서버에 내고, 받지 않으면 문제의 창을 다시 뽑아 한 번 더 낸다.

    check는 모델을 부르기 전마다 부른다. 일꾼은 여기서 임대를 잃었는지, 꺼지라는 신호를 받았는지 본다.
    """
    run_state = _Run()
    windows = windows_of(spans, window or WINDOW, OVERLAP if overlap is None else overlap)

    def window_node(task: WindowTask) -> ExtractState:
        # 동시에 도는 창의 예외는 엉뚱한 것이 올라올 수 있다. 여기서 잡아 기록하고 merge에서 올린다
        if run_state.abort.is_set():
            return {}
        try:
            check()
            user = extract.build_user_prompt(document, task["spans"], state, ontology) + task["feedback"]
            output = llm.structured(extract.SYSTEM_PROMPT, user, extract.OUTPUT_SCHEMA)
            found = extract.to_candidates(output, task["spans"], state, ontology)
        except BaseException as error:  # noqa: BLE001 — 꺼지라는 신호(Stopping)도 merge에서 그대로 올린다
            run_state.fail(error)
            return {}
        for reason in found.dropped:
            log.info("버린 후보(창 %d): %s", task["index"], reason)
        return {"results": {task["index"]: _prefixed(found, output, task["spans"], task["prefix"])}}

    def prepare(graph_state: ExtractState) -> ExtractState:
        return {"windows": windows, "results": {}, "redo": list(range(len(windows))), "feedback": "", "repairs": 0}

    def fan_out(graph_state: ExtractState) -> list[Send] | str:
        if not graph_state["redo"]:
            return "merge"
        prefix = "w" if graph_state["repairs"] == 0 else "r"
        return [Send("window", WindowTask(index=index, spans=graph_state["windows"][index],
                                          prefix=f"{prefix}{index}:", feedback=graph_state["feedback"]))
                for index in graph_state["redo"]]

    def merge_node(graph_state: ExtractState) -> ExtractState:
        if run_state.error is not None:
            raise run_state.error
        results = graph_state["results"]
        extraction, output = merge([results[index] for index in sorted(results)])
        log.info("창 %d개에서 카드 %d, 줄 %d를 모았다", len(results), len(extraction.candidates["nodes"]),
                 len(extraction.candidates["edges"]))
        return {"merged": {"extraction": extraction, "output": output}, "redo": []}

    def submit_node(graph_state: ExtractState) -> ExtractState:
        try:
            submit(graph_state["merged"]["extraction"].candidates)
        except ServerRejected as error:
            if graph_state["repairs"] >= MAX_REPAIRS or not windows:
                raise
            redo = windows_to_redo(str(error), graph_state["windows"], graph_state["results"])
            log.info("서버가 후보를 받지 않았다. 창 %s를 다시 뽑는다: %s", redo, error)
            return {"redo": redo, "repairs": graph_state["repairs"] + 1,
                    "feedback": _FEEDBACK.format(error=error)}
        return {"submitted": True}

    graph = StateGraph(ExtractState)
    graph.add_node("prepare", prepare)
    graph.add_node("window", window_node)
    graph.add_node("merge", merge_node)
    graph.add_edge(START, "prepare")
    graph.add_conditional_edges("prepare", fan_out, ["window", "merge"])
    graph.add_edge("window", "merge")
    if submit is None:
        graph.add_edge("merge", END)
    else:
        graph.add_node("submit", submit_node)
        graph.add_edge("merge", "submit")
        graph.add_conditional_edges("submit", lambda s: END if s.get("submitted") else fan_out(s),
                                    ["window", "merge", END])
    final = graph.compile().invoke({}, {"recursion_limit": RECURSION_LIMIT, "max_concurrency": max(1, concurrency)})
    merged = final["merged"]
    return Result(merged["extraction"], merged["output"], len(windows), final["repairs"])


def _prefixed(found: extract.Extraction, output: dict[str, Any], spans: list[dict[str, Any]],
              prefix: str) -> dict[str, Any]:
    """창 안의 key(c1 등) 앞에 창 표시를 붙인다. 모델의 원래 답에도 같은 key를 붙여 둔다(평가가 맞춰 본다)."""
    nodes = [{**node, "key": prefix + node["key"]} for node in found.candidates["nodes"]]
    edges = []
    for edge in found.candidates["edges"]:
        edge = dict(edge)
        for end in ("srcKey", "dstKey"):
            if end in edge:
                edge[end] = prefix + edge[end]
        edges.append(edge)
    kept = {node["key"] for node in found.candidates["nodes"]}
    span_by_seq = {span["seq"]: span["ref"]["id"] for span in spans}
    cards = []
    evidence: dict[str, frozenset[str]] = {}
    for card in output.get("cards", []):
        key = card.get("key", "")
        if key in kept and prefix + key not in evidence:
            cards.append({**card, "key": prefix + key})
            evidence[prefix + key] = frozenset(span_by_seq[n] for n in card.get("evidence", []) if n in span_by_seq)
    links = [{**link, "from": _prefix_name(link.get("from", ""), kept, prefix),
              "to": _prefix_name(link.get("to", ""), kept, prefix)} for link in output.get("links", [])]
    return {"nodes": nodes, "edges": edges, "dropped": [f"{prefix} {reason}" for reason in found.dropped],
            "cards": cards, "links": links, "evidence": evidence, "spans": [span["ref"]["id"] for span in spans]}


def _prefix_name(name: str, kept: set[str], prefix: str) -> str:
    return prefix + name if name in kept else name


def merge(results: list[dict[str, Any]]) -> tuple[extract.Extraction, dict[str, Any]]:
    """창마다 뽑은 것을 하나로 합친다. 앞 창의 카드를 남기고, 뒤 창의 같은 카드는 앞 카드로 잇는다.

    같은 창 안의 카드끼리는 합치지 않는다(모델이 일부러 나눴을 수 있다). 다른 창의 카드 하나와는 하나만 짝짓는다.
    """
    kept: list[dict[str, Any]] = []           # 남긴 카드: {"node", "window", "text", "evidence", "card"}
    remap: dict[str, str] = {}
    cards: dict[str, dict[str, Any]] = {}
    dropped: list[str] = []
    for window, result in enumerate(results):
        dropped.extend(result["dropped"])
        raw = {card["key"]: card for card in result["cards"]}
        mine = [(node, _text_key(node), result["evidence"].get(node["key"], frozenset())) for node in result["nodes"]]
        same_evidence: dict[tuple, int] = {}
        for node, _, evidence in mine:
            if evidence:
                group = (node["type"], node["fields"].get("kind"), evidence)
                same_evidence[group] = same_evidence.get(group, 0) + 1
        used: set[int] = set()
        for node, text, evidence in mine:
            match = _find(kept, node, window, text, evidence, used, same_evidence)
            if match is None:
                kept.append({"node": dict(node), "window": window, "text": text, "evidence": evidence})
                cards[node["key"]] = dict(raw.get(node["key"], {"key": node["key"]}))
                continue
            used.add(match)
            target = kept[match]
            remap[node["key"]] = target["node"]["key"]
            if "occurredAt" in node["fields"] and "occurredAt" not in target["node"]["fields"]:
                target["node"]["fields"] = {**target["node"]["fields"], "occurredAt": node["fields"]["occurredAt"]}
            card = cards[target["node"]["key"]]
            card["evidence"] = list(dict.fromkeys(list(card.get("evidence", []))
                                                  + list(raw.get(node["key"], {}).get("evidence", []))))

    edges: dict[tuple, dict[str, Any]] = {}
    links: list[dict[str, Any]] = []
    for result in results:
        for edge in result["edges"]:
            edge = dict(edge)
            for end in ("srcKey", "dstKey"):
                if end in edge:
                    edge[end] = remap.get(edge[end], edge[end])
            identity = (edge["rel"], edge.get("srcKey") or edge["srcRef"]["id"], edge.get("dstKey") or edge["dstRef"]["id"])
            if identity in edges:
                same = edges[identity]
                same["evidenceSpanIds"] = list(dict.fromkeys(same["evidenceSpanIds"] + edge["evidenceSpanIds"]))
            else:
                edges[identity] = edge
        for link in result["links"]:
            links.append({**link, "from": remap.get(link["from"], link["from"]), "to": remap.get(link["to"], link["to"])})

    nodes = [entry["node"] for entry in kept]
    output = {"cards": list(cards.values()), "links": links}
    return extract.Extraction({"nodes": nodes, "edges": list(edges.values())}, dropped), output


def _find(kept: list[dict[str, Any]], node: dict[str, Any], window: int, text: str, evidence: frozenset[str],
          used: set[int], same_evidence: dict[tuple, int]) -> int | None:
    kind = node["fields"].get("kind")
    candidates = [index for index, entry in enumerate(kept)
                  if entry["window"] != window and index not in used
                  and entry["node"]["type"] == node["type"] and entry["node"]["fields"].get("kind") == kind]
    for index in candidates:
        if text and kept[index]["text"] == text:
            return index
    # 인물·기관은 이름이 같을 때만 합친다. 근거가 같아도 다른 사람일 수 있다
    if node["type"] == "ENTITY" or not evidence or same_evidence.get((node["type"], kind, evidence), 0) != 1:
        return None
    by_evidence = [index for index in candidates if kept[index]["evidence"] == evidence]
    return by_evidence[0] if len(by_evidence) == 1 else None


def _text_key(node: dict[str, Any]) -> str:
    return normalize(node["fields"].get(_TEXT_FIELD.get(node["type"], ""), ""))


def normalize(text: str) -> str:
    """비교용으로만 쓴다(원문은 바꾸지 않는다). NFC, 아랍어 모음 기호·타트윌 빼기, 알리프 통일, 대소문자·공백 정리."""
    text = unicodedata.normalize("NFC", text)
    text = re.sub("[ً-ْـ]", "", text)
    text = re.sub("[أإآ]", "ا", text)
    return " ".join(text.casefold().split())


def windows_to_redo(message: str, windows: list[list[dict[str, Any]]], results: dict[int, dict[str, Any]]) -> list[int]:
    """서버의 거절 이유에서 문제가 된 창을 찾는다. key(w1:c3)나 발언 id가 나오면 그 창만, 아니면 모든 창."""
    found = sorted({int(number) for _, number in _KEY_IN_MESSAGE.findall(message) if int(number) < len(windows)})
    if found:
        return found
    ids = set(_UUID.findall(message))
    if ids:
        found = sorted(index for index, window in enumerate(windows)
                       if ids & {span["ref"]["id"] for span in window})
        if found:
            return found
    return list(range(len(windows)))
