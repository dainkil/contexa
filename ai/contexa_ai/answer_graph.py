"""GENERATE 작업의 흐름: 질문 하나에 답 하나를 쓰는 동안의 단계를 LangGraph로 잇는다.

    load ─▶ plan ─▶ retrieve ─▶ judge ──(모자라면 한 번 더)──▶ retrieve
                                  └──(충분하면)──▶ write ─▶ submit ──(서버가 받지 않으면)──▶ retrieve(처음부터)

- load: 질문과 프로젝트 현황을 읽는다. 현황에 있는 안건(TOPIC)에 이름표(t1, t2, …)를 붙인다.
- plan: 모델이 검색어를 더 만들고(질문을 프로젝트의 다른 언어로 옮긴 것 포함) 관련 안건을 고른다.
  질문 원문은 언제나 첫 검색어다. 검색은 글자 일치(pg_trgm)라 다른 언어로 적힌 발언은 원문으로 찾지 못한다.
- retrieve: 새 검색어마다 검색하고, 고른 안건은 지난 제안·바뀐 결정까지 따라가 읽고, 검색에 걸린 카드는 줄을 따라간다.
  읽은 안건의 id는 state의 `topic_ids`에 남는다.
- judge: 모은 근거로 답할 수 있는지 모델이 판단한다. 모자라면 검색어·안건을 더 내고 한 번 더 모은다(MAX_ROUNDS).
- write: 답을 쓰고 인용을 검사한다. 낼 수 없는 답이면 이유를 알려 주고 같은 근거로 다시 쓴다.
- submit: 서버에 낸다. 서버가 받지 않으면(대개 그사이 참고한 카드가 바뀌었다) 근거를 처음부터 다시 읽고 다시 쓴다.
  다시 쓰기는 write와 submit을 합쳐 MAX_REPAIRS번이다.

LangGraph는 작업 한 번 안에서만 쓴다. 체크포인터와 interrupt는 쓰지 않는다. 실패하면 일꾼이 서버에 알리고,
다시 하는 것은 서버의 작업 임대·재시도가, 사람의 판단은 검토함이 맡는다.
"""

from __future__ import annotations

import logging
from typing import Any, Callable, Protocol, TypedDict

from langgraph.graph import END, START, StateGraph

from . import answer, prompts
from .config import DEFAULT_LANGUAGES
from .llm import LLM
from .server import Lease, ServerClient, ServerError, ServerRejected

log = logging.getLogger("contexa.answer")

# 검색어 하나로 가져오는 발언 수
SEARCH_K = 8
# 안건을 따라가 읽는 깊이: 안건 → 결정 항목 → 사건(지난 제안·대체된 결정 포함) → 대체·철회한 사건
TOPIC_DEPTH = 3
# 모델에게 보여 주는 안건 목록의 길이
MAX_TOPICS_LISTED = 40
# 질의 계획: 검색어(질문 원문 포함)와 고를 안건의 상한
MAX_QUERIES = 6
MAX_PLAN_TOPICS = 3
# 근거가 모자랄 때 더 낼 검색어·안건의 상한
MAX_JUDGE_QUERIES = 3
MAX_JUDGE_TOPICS = 2
_MAX_QUERY_CHARS = 200
# 근거를 모으는 횟수. 1이면 판단 없이 한 번만 모은다
MAX_ROUNDS = 2
# 낼 수 없는 답이거나 서버가 받지 않았을 때 다시 쓰는 횟수
MAX_REPAIRS = 1
RECURSION_LIMIT = 50

# 지시문은 버전이 붙은 파일(prompts/이름.vN.md)에 있다. 바꿀 때는 새 판 파일을 만든다.
PLAN_PROMPT = prompts.load("plan")
JUDGE_PROMPT = prompts.load("judge")

PLAN_SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["queries", "topics"],
    "properties": {
        "queries": {"type": "array", "items": {"type": "string"}},
        "topics": {"type": "array", "items": {"type": "string"}},
    },
}

JUDGE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["enough", "missing", "queries", "topics"],
    "properties": {
        "enough": {"type": "boolean"},
        "missing": {"type": "string"},
        "queries": {"type": "array", "items": {"type": "string"}},
        "topics": {"type": "array", "items": {"type": "string"}},
    },
}


class Source(Protocol):
    """근거를 읽고 답을 내는 곳. 일꾼은 작업 임대증으로(JobSource), 평가는 앱 주소로 부른다.

    어느 쪽이든 질문한 사용자가 볼 수 있는 것만 돌려준다. 권한은 서버가 판단한다.
    """

    def request(self) -> dict[str, Any]: ...

    def state(self) -> dict[str, Any]: ...

    def search(self, query: str, k: int) -> dict[str, Any]: ...

    def trace(self, node: dict[str, Any], direction: str, depth: int) -> dict[str, Any]: ...

    def submit(self, draft: dict[str, Any]) -> None: ...


class JobSource:
    """일꾼이 작업 임대증으로 서버의 /internal 주소를 부른다."""

    def __init__(self, server: ServerClient, lease: Lease):
        self._server = server
        self._lease = lease

    def request(self) -> dict[str, Any]:
        return self._server.request(self._lease)

    def state(self) -> dict[str, Any]:
        return self._server.state(self._lease)

    def search(self, query: str, k: int) -> dict[str, Any]:
        return self._server.search(self._lease, query, k)

    def trace(self, node: dict[str, Any], direction: str, depth: int) -> dict[str, Any]:
        return self._server.trace(self._lease, node, direction, depth)

    def submit(self, draft: dict[str, Any]) -> None:
        self._server.submit(self._lease, {"draft": draft})


class AnswerState(TypedDict, total=False):
    request: dict[str, Any]
    project: dict[str, Any]               # 프로젝트 현황(state) 답변서
    topics: dict[str, dict[str, Any]]     # t1 → {"node": 안건 카드, "title": 이름}
    queries: list[str]                    # 이미 검색한 것. 질문 원문이 첫째
    topic_ids: list[str]                  # 이미 따라가 읽은 안건의 id
    traced: list[str]                     # 이미 줄을 따라간 카드의 id
    pending_queries: list[str]            # 다음 retrieve가 검색할 것
    pending_topics: list[str]             # 다음 retrieve가 읽을 안건의 이름표
    packets: list[dict[str, Any]]         # 검색·따라가기로 모은 답변서(현황은 빼고)
    context: answer.Context               # 모델에게 보여 줄 근거
    rounds: int
    rounds_log: list[dict[str, Any]]      # 회차마다 무엇을 찾았나(평가용, 원문 없음)
    refresh: bool                         # 서버가 낡은 근거라고 받지 않아 처음부터 다시 읽는다
    refreshed: bool
    feedback: str                         # 다시 쓸 때 모델에게 알려 줄 이유
    repairs: int
    draft: dict[str, Any] | None
    submitted: bool


def run(source: Source, llm: LLM, *, check: Callable[[], None] = lambda: None,
        languages: tuple[str, ...] = DEFAULT_LANGUAGES, max_rounds: int = MAX_ROUNDS) -> dict[str, Any]:
    """질문에 답을 써서 낸다. 낸 글을 돌려준다.

    check는 모델을 부르기 전마다 부른다. 일꾼은 여기서 임대를 잃었는지, 꺼지라는 신호를 받았는지 본다.
    """
    final = build(source, llm, check=check, languages=languages, max_rounds=max_rounds).invoke(
        {}, {"recursion_limit": RECURSION_LIMIT})
    return final["draft"]


def gather(source: Source, llm: LLM | None, *, languages: tuple[str, ...] = DEFAULT_LANGUAGES,
           max_rounds: int = MAX_ROUNDS) -> AnswerState:
    """답을 쓰기 전까지만 돈다(평가용). llm이 None이면 질문 원문 하나로 한 번만 찾는다(모델 비용 없음)."""
    graph = build(source, llm, languages=languages, max_rounds=max_rounds if llm else 1, write=False)
    return graph.invoke({}, {"recursion_limit": RECURSION_LIMIT})


def build(source: Source, llm: LLM | None, *, check: Callable[[], None] = lambda: None,
          languages: tuple[str, ...] = DEFAULT_LANGUAGES, max_rounds: int = MAX_ROUNDS, write: bool = True):
    """흐름을 만든다. write가 False면 근거를 다 모은 뒤 멈춘다."""

    def load(state: AnswerState) -> AnswerState:
        project = source.state()
        topics: dict[str, dict[str, Any]] = {}
        for fact in project["facts"]:
            if fact["node"]["type"] == "TOPIC" and len(topics) < MAX_TOPICS_LISTED:
                topics[f"t{len(topics) + 1}"] = {"node": fact["node"], "title": fact["label"]}
        return {"request": source.request(), "project": project, "topics": topics, "queries": [], "topic_ids": [],
                "traced": [], "packets": [], "rounds": 0, "rounds_log": [], "refresh": False, "refreshed": False,
                "feedback": "", "repairs": 0, "draft": None, "submitted": False}

    def plan(state: AnswerState) -> AnswerState:
        question = state["request"]["question"].strip()
        if llm is None:
            return {"pending_queries": [question], "pending_topics": []}
        check()
        output = llm.structured(PLAN_PROMPT, plan_prompt(state, languages), PLAN_SCHEMA)
        queries = [question] + clean_queries(output.get("queries", []), [question], MAX_QUERIES - 1)
        topics = clean_topics(output.get("topics", []), state["topics"], set(), MAX_PLAN_TOPICS)
        return {"pending_queries": queries, "pending_topics": topics}

    def retrieve(state: AnswerState) -> AnswerState:
        topic_nodes = {topic["node"]["id"]: topic["node"] for topic in state["topics"].values()}
        if state["refresh"]:
            # 그사이 카드가 바뀌었다. 지금까지의 검색어와 안건으로 현황부터 처음부터 다시 읽는다
            project = source.state()
            queries, topic_ids, traced, packets = [], [], [], []
            new_queries = list(state["queries"])
            new_topics = [topic_nodes[topic_id] for topic_id in state["topic_ids"] if topic_id in topic_nodes]
        else:
            project = state["project"]
            queries, topic_ids = list(state["queries"]), list(state["topic_ids"])
            traced, packets = list(state["traced"]), list(state["packets"])
            new_queries = [query for query in state["pending_queries"] if query not in queries]
            new_topics = [state["topics"][label]["node"] for label in state["pending_topics"]
                          if state["topics"][label]["node"]["id"] not in topic_ids]

        found = []
        for query in new_queries:
            found.append(source.search(query, SEARCH_K))
            queries.append(query)
        packets.extend(found)
        for node in new_topics:
            topic_ids.append(node["id"])
            try:
                packets.append(source.trace(node, "BASIS", TOPIC_DEPTH))
            except ServerError:
                continue  # 그사이 보이지 않게 된 안건이다
        hits = {"facts": [fact for packet in found for fact in packet["facts"]]}
        for node in answer.trace_starts(hits, skip=set(traced)):
            traced.append(node["id"])
            try:
                packets.append(source.trace(node, "BASIS", 2))
            except ServerError:
                continue  # 그사이 보이지 않게 된 카드다

        context = answer.merge(packets + [project])
        rounds = state["rounds"] + (0 if state["refresh"] else 1)
        log.info("근거 %d차%s: 검색어 %d개, 안건 %d개 → 카드 %d, 발언 %d", rounds, "(다시 읽음)" if state["refresh"] else "",
                 len(new_queries), len(new_topics), len(context.facts), len(context.sources))
        entry = {"round": rounds, "refresh": state["refresh"], "queries": new_queries,
                 "topic_ids": [node["id"] for node in new_topics],
                 "sources": [source_["span"]["id"] for source_ in context.sources.values()]}
        return {"project": project, "queries": queries, "topic_ids": topic_ids, "traced": traced, "packets": packets,
                "context": context, "rounds": rounds, "rounds_log": state["rounds_log"] + [entry],
                "refresh": False, "refreshed": state["refresh"], "pending_queries": [], "pending_topics": []}

    def judge(state: AnswerState) -> AnswerState:
        check()
        output = llm.structured(JUDGE_PROMPT, judge_prompt(state, languages), JUDGE_SCHEMA)
        if output.get("enough", True):
            return {"pending_queries": [], "pending_topics": []}
        return {"pending_queries": clean_queries(output.get("queries", []), state["queries"], MAX_JUDGE_QUERIES),
                "pending_topics": clean_topics(output.get("topics", []), state["topics"], set(state["topic_ids"]),
                                               MAX_JUDGE_TOPICS)}

    def write_answer(state: AnswerState) -> AnswerState:
        check()
        user = answer.build_user_prompt(state["request"], state["context"]) + state["feedback"]
        output = llm.structured(answer.SYSTEM_PROMPT, user, answer.OUTPUT_SCHEMA)
        try:
            return {"draft": answer.to_draft(output, state["context"])}
        except answer.AnswerInvalid as error:
            if state["repairs"] >= MAX_REPAIRS:
                raise ServerRejected(str(error)) from error
            log.info("답을 다시 쓴다: %s", error)
            return {"draft": None, "repairs": state["repairs"] + 1, "feedback": _feedback(error)}

    def submit(state: AnswerState) -> AnswerState:
        try:
            source.submit(state["draft"])
        except ServerRejected as error:
            if state["repairs"] >= MAX_REPAIRS:
                raise
            log.info("서버가 답을 받지 않았다. 근거를 다시 읽고 다시 쓴다: %s", error)
            return {"draft": None, "repairs": state["repairs"] + 1, "feedback": _feedback(error), "refresh": True}
        return {"submitted": True}

    after_gather = "write" if write else END

    def after_retrieve(state: AnswerState) -> str:
        if state["refreshed"]:
            return after_gather
        if llm is not None and state["rounds"] < max_rounds:
            return "judge"
        return after_gather

    def after_judge(state: AnswerState) -> str:
        return "retrieve" if state["pending_queries"] or state["pending_topics"] else after_gather

    graph = StateGraph(AnswerState)
    graph.add_node("load", load)
    graph.add_node("plan", plan)
    graph.add_node("retrieve", retrieve)
    graph.add_node("judge", judge)
    graph.add_edge(START, "load")
    graph.add_edge("load", "plan")
    graph.add_edge("plan", "retrieve")
    graph.add_conditional_edges("retrieve", after_retrieve, ["judge", after_gather])
    graph.add_conditional_edges("judge", after_judge, ["retrieve", after_gather])
    if write:
        graph.add_node("write", write_answer)
        graph.add_node("submit", submit)
        graph.add_conditional_edges("write", lambda state: "submit" if state["draft"] else "write",
                                    ["submit", "write"])
        graph.add_conditional_edges("submit", lambda state: "retrieve" if state["refresh"] else END,
                                    ["retrieve", END])
    return graph.compile()


def plan_prompt(state: AnswerState, languages: tuple[str, ...]) -> str:
    return (_topics_block(state) + "\n\n"
            + f"프로젝트 언어: {_languages(languages)}\n"
            + f"질문: {state['request']['question']}")


def judge_prompt(state: AnswerState, languages: tuple[str, ...]) -> str:
    done = ", ".join(f"\"{query}\"" for query in state["queries"]) or "(없음)"
    return (answer.format_context(state["context"]) + "\n\n"
            + _topics_block(state) + "\n\n"
            + f"프로젝트 언어: {_languages(languages)}\n"
            + f"이미 찾은 검색어: {done}\n"
            + f"질문: {state['request']['question']}")


def clean_queries(raw: list[Any], existing: list[str], limit: int) -> list[str]:
    """모델이 낸 검색어를 다듬는다. 빈 것, 너무 긴 것의 뒷부분, 이미 찾은 것과 겹치는 것을 뺀다."""
    seen = {query.casefold() for query in existing}
    result = []
    for item in raw:
        if not isinstance(item, str):
            continue
        query = " ".join(item.split())[:_MAX_QUERY_CHARS]
        if query and query.casefold() not in seen:
            seen.add(query.casefold())
            result.append(query)
        if len(result) >= limit:
            break
    return result


def clean_topics(raw: list[Any], topics: dict[str, dict[str, Any]], read: set[str], limit: int) -> list[str]:
    """목록에 있는 안건 이름표만, 이미 읽은 안건은 빼고 남긴다."""
    result = []
    for label in raw:
        if label in topics and label not in result and topics[label]["node"]["id"] not in read:
            result.append(label)
        if len(result) >= limit:
            break
    return result


def _topics_block(state: AnswerState) -> str:
    read = set(state.get("topic_ids", []))
    lines = [f"{label}: {topic['title']}" + (" (읽음)" if topic["node"]["id"] in read else "")
             for label, topic in state["topics"].items()]
    return "<topics>\n" + ("\n".join(lines) if lines else "(없음)") + "\n</topics>"


def _languages(languages: tuple[str, ...]) -> str:
    return ", ".join(f"{answer.language_name(code)}({code})" for code in languages)


def _feedback(error: Exception) -> str:
    return f"\n\n앞서 쓴 답은 이 이유로 받지 않았습니다: {error}\n이 문제를 고쳐서 처음부터 다시 답해 주세요."
