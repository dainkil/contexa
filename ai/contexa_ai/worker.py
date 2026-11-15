"""일꾼: 서버에서 일을 가져와 처리하고 결과를 낸다.

    python -m contexa_ai.worker          # 계속 돌면서 일을 기다린다
    python -m contexa_ai.worker --once   # 기다리는 일을 한 번 훑고 끝낸다

할 줄 아는 일: PARSE(문서를 발언으로 자르기), EXTRACT(카드·줄 후보 뽑기), GENERATE(질문에 답 쓰기)
"""

from __future__ import annotations

import argparse
import logging
import time
from typing import Any

from . import answer, extract, parse
from .config import Config
from .llm import LLM, LLMError, LLMRefused, LLMTransient, make_llm
from .server import Lease, LeaseLost, ServerClient, ServerError, ServerRejected

log = logging.getLogger("contexa.worker")

# 한 번에 모델에게 보여 주는 발언 수. 긴 문서는 이만큼씩 나눠서 뽑는다.
WINDOW = 200
# 서버가 후보를 거절했을 때, 이유를 모델에게 알려 주고 다시 뽑게 하는 횟수
MAX_REPAIRS = 1


class Worker:
    def __init__(self, server: ServerClient, llm: LLM):
        self._server = server
        self._llm = llm
        self._ontology: dict[str, Any] | None = None

    def run_once(self) -> bool:
        """일을 하나 가져와 처리한다. 가져올 일이 없으면 False."""
        lease = self._server.claim()
        if lease is None:
            return False
        log.info("작업 %s (%s) 시작", lease.job_id, lease.job_type)
        try:
            if lease.job_type == "PARSE":
                self._parse(lease)
            elif lease.job_type == "EXTRACT":
                self._extract(lease)
            elif lease.job_type == "GENERATE":
                self._generate(lease)
            else:
                self._server.fail(lease, f"이 일꾼은 {lease.job_type} 작업을 할 줄 모른다", retryable=False)
                return True
            log.info("작업 %s 완료", lease.job_id)
        except LeaseLost as error:
            # 다른 일꾼이 이미 가져갔거나 취소된 일이다. 결과도 실패도 낼 수 없으니 내려놓는다.
            log.warning("작업 %s 을 내려놓는다: %s", lease.job_id, error)
        except LLMTransient as error:
            self._report(lease, f"모델 호출이 잠시 실패했다: {error}", retryable=True)
        except (LLMRefused, LLMError, ServerRejected) as error:
            self._report(lease, str(error), retryable=False)
        return True

    def _parse(self, lease: Lease) -> None:
        content = self._server.content(lease)
        spans = parse.split_document(content["document"]["kind"], content["content"])
        if not spans:
            raise LLMError("문서에서 발언을 하나도 찾지 못했다")
        self._server.submit(lease, {"spans": spans})

    def _extract(self, lease: Lease) -> None:
        if self._ontology is None:
            self._ontology = self._server.ontology()
        document = self._server.content(lease)
        spans = self._server.spans(lease)
        state = self._server.state(lease)

        nodes: list[dict[str, Any]] = []
        edges: list[dict[str, Any]] = []
        for start in range(0, len(spans), WINDOW):
            window = spans[start:start + WINDOW]
            user = extract.build_user_prompt(document, window, state, self._ontology)
            found = self._extract_window(user, window, state, prefix=f"w{start // WINDOW}:")
            nodes.extend(found["nodes"])
            edges.extend(found["edges"])
        self._submit_with_repair(lease, {"nodes": nodes, "edges": edges}, document, spans, state)

    def _extract_window(self, user: str, spans: list[dict[str, Any]], state: dict[str, Any],
                        prefix: str) -> dict[str, list[dict[str, Any]]]:
        output = self._llm.structured(extract.SYSTEM_PROMPT, user, extract.OUTPUT_SCHEMA)
        result = extract.to_candidates(output, spans, state, self._ontology)
        for reason in result.dropped:
            log.info("버린 후보: %s", reason)
        # 창마다 key가 겹치지 않게 앞에 표시를 붙인다
        for node in result.candidates["nodes"]:
            node["key"] = prefix + node["key"]
        for edge in result.candidates["edges"]:
            for end in ("srcKey", "dstKey"):
                if end in edge:
                    edge[end] = prefix + edge[end]
        return result.candidates

    def _submit_with_repair(self, lease: Lease, candidates: dict[str, Any], document: dict[str, Any],
                            spans: list[dict[str, Any]], state: dict[str, Any]) -> None:
        """후보를 낸다. 서버가 거절하면 이유를 모델에게 알려 주고 한 번 다시 뽑는다(짧은 문서만)."""
        try:
            self._server.submit(lease, {"candidates": candidates})
            return
        except ServerRejected as error:
            if MAX_REPAIRS < 1 or len(spans) > WINDOW:
                raise
            log.info("서버가 후보를 거절했다. 이유를 알려 주고 다시 뽑는다: %s", error)
            user = extract.build_user_prompt(document, spans, state, self._ontology) + (
                f"\n\n앞서 낸 답은 서버가 이 이유로 받지 않았습니다: {error}\n"
                "이 문제를 고쳐서 처음부터 다시 답해 주세요.")
            repaired = self._extract_window(user, spans, state, prefix="r:")
            self._server.submit(lease, {"candidates": repaired})

    def _generate(self, lease: Lease) -> None:
        """질문에 답을 쓴다. 답을 낼 수 없거나 서버가 받지 않으면, 근거를 다시 읽고 한 번 더 쓴다.

        서버가 받지 않는 경우는 주로 "글을 쓰는 사이에 참고한 카드가 바뀌었다"이다. 그래서 다시 쓸 때는
        근거부터 새로 읽는다.
        """
        request = self._server.request(lease)
        feedback = ""
        for attempt in range(MAX_REPAIRS + 1):
            context = answer.gather(self._server, lease, request["question"])
            user = answer.build_user_prompt(request, context) + feedback
            output = self._llm.structured(answer.SYSTEM_PROMPT, user, answer.OUTPUT_SCHEMA)
            try:
                draft = answer.to_draft(output, context)
                self._server.submit(lease, {"draft": draft})
                return
            except (answer.AnswerInvalid, ServerRejected) as error:
                if attempt >= MAX_REPAIRS:
                    raise ServerRejected(str(error)) from error
                log.info("답을 다시 쓴다: %s", error)
                feedback = (f"\n\n앞서 쓴 답은 이 이유로 받지 않았습니다: {error}\n"
                            "이 문제를 고쳐서 처음부터 다시 답해 주세요.")

    def _report(self, lease: Lease, reason: str, retryable: bool) -> None:
        log.warning("작업 %s 실패(%s): %s", lease.job_id, "다시 시도" if retryable else "끝", reason)
        try:
            self._server.fail(lease, reason, retryable)
        except (LeaseLost, ServerError) as error:
            log.warning("실패를 알리지 못했다: %s", error)


def main() -> None:
    parser = argparse.ArgumentParser(description="Contexa AI 인턴")
    parser.add_argument("--once", action="store_true", help="기다리는 일을 한 번 훑고 끝낸다")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    config = Config.from_env()
    worker = Worker(ServerClient(config.server_url, config.worker_token, config.worker_id), make_llm(config.model))
    log.info("일꾼 %s 시작. 서버 %s, 모델 %s", config.worker_id, config.server_url, config.model)
    while True:
        try:
            worked = worker.run_once()
        except ServerError as error:
            log.warning("서버와 통신하지 못했다: %s", error)
            worked = False
        if not worked:
            if args.once:
                return
            time.sleep(config.poll_seconds)


if __name__ == "__main__":
    main()
