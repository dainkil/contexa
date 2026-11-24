"""질문에 대한 답변을 쓴다.

1. 서버의 안내 데스크에서 근거를 모은다: 질문과 가까운 발언, 그 발언에 기댄 카드, 그 카드가 기대는 조건.
   작업을 시킨 사용자가 볼 수 있는 것만 온다.
2. 모델에게 카드에는 f1, 줄에는 r1, 발언에는 s1 같은 이름표를 붙여 보여 주고 답을 쓰게 한다.
3. 모델이 본문에 적은 [s3] 같은 인용이 실제로 보여 준 발언인지 확인하고, [1], [2] 번호로 바꾼다.
   보여 주지 않은 발언을 인용했으면 그 답은 버린다.
4. 답이 참고한 카드·줄·발언을 읽었을 때의 버전과 함께 낸다. 서버는 그사이 바뀐 것이 있으면 받지 않는다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from . import prompts
from .server import Lease, ServerClient, ServerError

# 줄을 더 따라가 볼 카드의 종류와 개수
_TRACEABLE = ("TASK", "CONDITION", "DECISION_ITEM", "DECISION_EVENT")
_MAX_TRACES = 5
_MAX_FACTS = 150
_MARKER = re.compile(r"\[s(\d+)\]")

_LANG = {"ko": "한국어", "en": "영어", "ar": "아랍어"}
_STATUS = {"CONFIRMED": "확인됨", "CANDIDATE": "AI 후보(사람이 아직 확인하지 않음)"}
_OPEN = {
    "HELD_NODE": "검토자가 판단을 보류한 카드",
    "HELD_EDGE": "검토자가 판단을 보류한 관계",
    "CONTESTED_DECISION": "확정된 결정이 둘 이상 겹쳐 어느 쪽이 유효한지 정해지지 않음",
    "UNMET_CONDITION": "아직 충족되지 않은 조건",
    "AMBIGUOUS_NAME": "같은 이름에 해당하는 대상이 여럿",
}

# 지시문은 버전이 붙은 파일(prompts/answer.vN.md)에 있다. 바꿀 때는 새 판 파일을 만든다.
SYSTEM_PROMPT = prompts.load("answer")

OUTPUT_SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["answer", "used_facts", "used_relations", "cited_sources"],
    "properties": {
        "answer": {"type": "string"},
        "used_facts": {"type": "array", "items": {"type": "string"}},
        "used_relations": {"type": "array", "items": {"type": "string"}},
        "cited_sources": {"type": "array", "items": {"type": "string"}},
    },
}


class AnswerInvalid(Exception):
    """모델의 답을 그대로 낼 수 없다. 이유를 알려 주고 다시 쓰게 할 수 있다."""


@dataclass
class Context:
    """모델에게 보여 줄 근거와, 이름표가 가리키는 실제 대상."""

    facts: dict[str, dict[str, Any]]       # f1 → 카드
    relations: dict[str, dict[str, Any]]   # r1 → 줄
    sources: dict[str, dict[str, Any]]     # s1 → 발언
    open: list[dict[str, Any]]


def gather(server: ServerClient, lease: Lease, question: str) -> Context:
    """안내 데스크에서 근거를 모은다: 근거 검색, 걸린 카드에서 줄 따라가기, 프로젝트 현황."""
    packets = [server.search(lease, question)]
    starts = [fact["node"] for fact in packets[0]["facts"] if fact["node"]["type"] in _TRACEABLE][:_MAX_TRACES]
    for node in starts:
        try:
            packets.append(server.trace(lease, node))
        except ServerError:
            # 그사이 보이지 않게 된 카드다. 건너뛴다.
            continue
    packets.append(server.state(lease))

    facts: dict[str, dict[str, Any]] = {}
    relations: dict[str, dict[str, Any]] = {}
    sources: dict[str, dict[str, Any]] = {}
    open_issues: dict[tuple, dict[str, Any]] = {}
    for packet in packets:
        for fact in packet["facts"]:
            if len(facts) < _MAX_FACTS:
                facts.setdefault(fact["node"]["id"], fact)
        for relation in packet["relations"]:
            relations.setdefault(relation["id"], relation)
        for source in packet["sources"]:
            sources.setdefault(source["span"]["id"], source)
        for issue in packet["open"]:
            key = (issue["kind"], (issue.get("node") or {}).get("id"), issue.get("edgeId"))
            open_issues.setdefault(key, issue)
    return Context(
        facts={f"f{i}": fact for i, fact in enumerate(facts.values(), start=1)},
        relations={f"r{i}": relation for i, relation in enumerate(relations.values(), start=1)},
        sources={f"s{i}": source for i, source in enumerate(sources.values(), start=1)},
        open=list(open_issues.values()),
    )


def build_user_prompt(request: dict[str, Any], context: Context) -> str:
    fact_name = {fact["node"]["id"]: name for name, fact in context.facts.items()}
    source_name = {source["span"]["id"]: name for name, source in context.sources.items()}

    def name_of(node: dict[str, Any]) -> str:
        return fact_name.get(node["id"]) or source_name.get(node["id"]) or f"({node['type']})"

    fact_lines = []
    for name, fact in context.facts.items():
        states = ", ".join(f"{key}={value}" for key, value in fact.get("states", {}).items()
                           if key not in ("currentEventId", "acceptanceBy"))
        evidence = ", ".join(source_name[e["span"]["id"]] + ("" if e["status"] == "CONFIRMED" else "(AI 후보)")
                             for e in fact.get("evidence", []) if e["span"]["id"] in source_name)
        fact_lines.append(f"{name}: [{fact['node']['type']}] {fact['label']} — {_STATUS.get(fact['status'], fact['status'])}"
                          + (f"; {states}" if states else "")
                          + (f"; 근거: {evidence}" if evidence else "; 근거 발언 없음"))
    relation_lines = [
        f"{name}: {name_of(rel['src'])} -{rel['rel']}-> {name_of(rel['dst'])} ({_STATUS.get(rel['status'], rel['status'])})"
        for name, rel in context.relations.items()]
    source_lines = [
        f"{name}: " + (f"[{source['speaker']}] " if source.get("speaker") else "") + source["text"]
        for name, source in context.sources.items()]
    open_lines = []
    for issue in context.open:
        target = name_of(issue["node"]) if issue.get("node") else "관계"
        open_lines.append(f"- {_OPEN.get(issue['kind'], issue['kind'])}: {target}")

    def block(tag: str, lines: list[str]) -> str:
        return f"<{tag}>\n" + ("\n".join(lines) if lines else "(없음)") + f"\n</{tag}>"

    return (
        "<context>\n"
        + block("facts", fact_lines) + "\n"
        + block("relations", relation_lines) + "\n"
        + block("sources", source_lines) + "\n"
        + block("open", open_lines) + "\n"
        "</context>\n\n"
        + (f"답할 언어: {_LANG.get(request['lang'], request['lang'])}\n" if request.get("lang") else "")
        + f"글의 종류: {request['kind']}\n"
        f"질문: {request['question']}"
    )


def to_draft(output: dict[str, Any], context: Context) -> dict[str, Any]:
    """모델의 답을 서버가 받는 글 모양으로 바꾼다. 보여 주지 않은 발언을 인용했으면 거절한다."""
    answer = (output.get("answer") or "").strip()
    if not answer:
        raise AnswerInvalid("본문이 비어 있다")

    order: list[str] = []
    for number in _MARKER.findall(answer):
        name = f"s{number}"
        if name not in context.sources:
            raise AnswerInvalid(f"<sources>에 없는 발언을 인용했다: [{name}]")
        if name not in order:
            order.append(name)
    # 본문의 [s7]을 등장한 순서대로 [1], [2] 로 바꾼다
    content = _MARKER.sub(lambda match: f"[{order.index('s' + match.group(1)) + 1}]", answer)
    citations = [context.sources[name]["span"] for name in order]

    used: dict[str, dict[str, Any]] = {}
    edges: dict[str, dict[str, Any]] = {}
    for name in output.get("used_relations", []):
        relation = context.relations.get(name)
        if relation is None:
            continue
        edges[relation["id"]] = {"id": relation["id"], "version": relation["version"]}
        # 줄을 썼다면 그 양끝 카드도 쓴 것이다
        for end in (relation["src"], relation["dst"]):
            fact = next((f for f in context.facts.values() if f["node"]["id"] == end["id"]), None)
            if fact is not None:
                used[end["id"]] = fact["node"]
    for name in output.get("used_facts", []):
        fact = context.facts.get(name)
        if fact is not None:
            used[fact["node"]["id"]] = fact["node"]
    for name in list(output.get("cited_sources", [])) + order:
        source = context.sources.get(name)
        if source is not None:
            used[source["span"]["id"]] = source["span"]

    return {"content": content, "usedNodes": list(used.values()), "usedEdges": list(edges.values()),
            "citations": citations}
