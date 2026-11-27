"""일꾼: 서버에서 일을 가져와 처리하고 결과를 낸다.

    python -m contexa_ai.worker          # 계속 돌면서 일을 기다린다
    python -m contexa_ai.worker --once   # 기다리는 일을 한 번 훑고 끝낸다

할 줄 아는 일: PARSE(문서를 발언으로 자르기), EXTRACT(카드·줄 후보 뽑기), GENERATE(질문에 답 쓰기)
EXTRACT와 GENERATE의 단계는 extract_graph.py, answer_graph.py가 LangGraph로 잇는다. 일꾼은 작업을 가져오고,
임대를 늘리고, 결과나 실패를 서버에 알린다.
"""

from __future__ import annotations

import argparse
import logging
import signal
import threading
import time
from typing import Any

from . import answer_graph, extract_graph, parse, tracing
from .config import DEFAULT_LANGUAGES, Config
from .llm import LLM, LLMError, LLMRefused, LLMTransient, make_llm
from .server import Lease, LeaseLost, ServerClient, ServerError, ServerRejected

log = logging.getLogger("contexa.worker")

# 일하는 중이라고 서버에 알리는 간격(초). 서버의 임대는 10분이라 그 안에 여러 번 알린다
HEARTBEAT_SECONDS = 120.0
# 서버에 닿지 않을 때 다음 시도까지 기다리는 시간의 상한(초)
MAX_IDLE_SECONDS = 300.0


class Stopping(BaseException):
    """일꾼을 끄라는 신호(SIGTERM·SIGINT)를 받았다. 하던 일은 다시 해 볼 만한 실패로 알리고 끝낸다.

    BaseException이라 작업 처리의 `except Exception`에 잡히지 않고 맨 위까지 올라간다.
    """


# 꺼지라는 신호를 받았다는 표시. 신호는 주 스레드에서만 예외가 되므로, 긴 문서의 창을 동시에 뽑는 다른 스레드는
# 모델을 부르기 전에 이 표시를 보고 멈춘다. 이미 모델을 부르는 중인 창은 그 호출이 끝날 때까지 기다린다.
STOP = threading.Event()


def _short(error: Exception, limit: int = 300) -> str:
    """실패 이유로 서버에 남길 만큼만. 긴 메시지는 자른다."""
    text = " ".join(str(error).split())
    return text if len(text) <= limit else text[:limit] + "…"


class _Keepalive:
    """작업하는 동안 따로 도는 스레드가 일정한 간격으로 임대를 늘린다.

    모델 호출 하나가 몇 분씩 걸리거나 긴 문서를 여러 창으로 나눠 뽑으면 10분 임대를 넘길 수 있다. 그러면 서버가
    같은 일을 다른 일꾼에게 다시 내줘 같은 문서를 두 번 뽑게 된다(모델 비용도 두 번). 임대를 잃으면 `lost`에
    이유를 적고 멈춘다. 일꾼은 다음 모델 호출 전에 이 값을 본다.
    """

    def __init__(self, server: ServerClient, lease: Lease, interval: float):
        self._server = server
        self._lease = lease
        self._interval = interval
        self._stop = threading.Event()
        self.lost: str | None = None
        self._thread = threading.Thread(target=self._run, name=f"heartbeat-{lease.job_id}", daemon=True)

    def __enter__(self) -> "_Keepalive":
        self._thread.start()
        return self

    def __exit__(self, *exc: object) -> None:
        self._stop.set()
        self._thread.join(timeout=1.0)

    def _run(self) -> None:
        while not self._stop.wait(self._interval):
            try:
                self._server.heartbeat(self._lease)
            except LeaseLost as error:
                self.lost = str(error) or "임대를 잃었다"
                log.warning("작업 %s 의 임대를 늘리지 못했다: %s", self._lease.job_id, self.lost)
                return
            except Exception as error:
                # 서버에 잠깐 닿지 않아도 다음 차례에 다시 알린다. 임대가 끝나기 전에 여러 번 기회가 있다
                log.warning("작업 %s 의 임대를 늘리지 못했다(다음에 다시): %s", self._lease.job_id, error)


class Worker:
    def __init__(self, server: ServerClient, llm: LLM, heartbeat_seconds: float = HEARTBEAT_SECONDS,
                 languages: tuple[str, ...] = DEFAULT_LANGUAGES, extract_concurrency: int = 1):
        self._server = server
        self._llm = llm
        self._ontology: dict[str, Any] | None = None
        self._heartbeat_seconds = heartbeat_seconds
        self._keepalive: _Keepalive | None = None
        self._languages = languages
        self._extract_concurrency = extract_concurrency

    def run_once(self) -> bool:
        """일을 하나 가져와 처리한다. 가져올 일이 없으면 False."""
        lease = self._server.claim()
        if lease is None:
            return False
        # 임대증을 받은 뒤로는 어디서 꺼지든 바로 다시 맡길 수 있게 알린다. 알리지 않으면 임대가 끝날 때(10분)까지 묶인다
        try:
            log.info("작업 %s (%s, 프로젝트 %s) 시작", lease.job_id, lease.job_type, lease.project_id)
            with tracing.job(lease.job_id, lease.job_type, lease.project_id):
                self._handle(lease)
        except Stopping:
            self._report(lease, "일꾼이 작업 중에 꺼졌다", retryable=True)
            raise
        return True

    def _handle(self, lease: Lease) -> None:
        with _Keepalive(self._server, lease, self._heartbeat_seconds) as keepalive:
            self._keepalive = keepalive
            try:
                self._process(lease)
            finally:
                self._keepalive = None

    def _check_lease(self) -> None:
        """모델을 부르기 전마다 본다. 꺼지라는 신호를 받았거나 임대를 잃었으면 더 일하지 않는다.

        임대를 잃으면 결과를 내도 받아 주지 않으니 모델 호출 비용만 든다.
        """
        if STOP.is_set():
            raise Stopping("꺼지라는 신호를 받았다")
        if self._keepalive is not None and self._keepalive.lost:
            raise LeaseLost(self._keepalive.lost)

    def _process(self, lease: Lease) -> None:
        try:
            if lease.job_type == "PARSE":
                self._parse(lease)
            elif lease.job_type == "EXTRACT":
                self._extract(lease)
            elif lease.job_type == "GENERATE":
                self._generate(lease)
            else:
                self._server.fail(lease, f"이 일꾼은 {lease.job_type} 작업을 할 줄 모른다", retryable=False)
                return
            log.info("작업 %s 완료", lease.job_id)
        except LeaseLost as error:
            # 다른 일꾼이 이미 가져갔거나 취소된 일이다. 결과도 실패도 낼 수 없으니 내려놓는다.
            log.warning("작업 %s 을 내려놓는다: %s", lease.job_id, error)
        except LLMTransient as error:
            self._report(lease, f"모델 호출이 잠시 실패했다: {error}", retryable=True)
        except (LLMRefused, LLMError, ServerRejected) as error:
            self._report(lease, str(error), retryable=False)
        except ServerError:
            # 서버에 닿지 않으면 실패도 알릴 수 없다. 임대가 끝나면 서버가 다시 내준다
            raise
        # Stopping(꺼지라는 신호)은 BaseException이라 여기서 잡히지 않는다. run_once가 알리고 끝낸다
        except Exception as error:
            # 분류하지 못한 오류(예: 모델 응답 모양이 달라 생긴 KeyError 같은 코드 오류). 되풀이될 테니 다시 시도하지 않는 실패로 알리고,
            # 일꾼은 다음 일로 넘어간다. 사람은 자료함의 "다시 시도"로 다시 맡길 수 있다
            log.exception("작업 %s 에서 분류하지 못한 오류", lease.job_id)
            self._report(lease, f"일꾼 내부 오류({type(error).__name__}): {_short(error)}", retryable=False)

    def _parse(self, lease: Lease) -> None:
        content = self._server.content(lease)
        spans = parse.split_document(content["document"]["kind"], content["content"])
        if not spans:
            raise LLMError("문서에서 발언을 하나도 찾지 못했다")
        self._server.submit(lease, {"spans": spans})

    def _extract(self, lease: Lease) -> None:
        """카드·줄 후보를 뽑아 낸다. 긴 문서는 창으로 나눠 뽑고 합친다(extract_graph)."""
        if self._ontology is None:
            self._ontology = self._server.ontology()
        document = self._server.content(lease)
        spans = self._server.spans(lease)
        state = self._server.state(lease)
        result = extract_graph.run(
            self._llm, document, spans, state, self._ontology, check=self._check_lease,
            submit=lambda candidates: self._server.submit(lease, {"candidates": candidates}),
            concurrency=self._extract_concurrency)
        log.info("후보를 냈다: 창 %d개, 다시 뽑기 %d번", result.windows, result.repairs)

    def _generate(self, lease: Lease) -> None:
        """질문에 답을 쓴다(answer_graph): 검색어 계획 → 근거 모으기 → 모자라면 더 찾기 → 쓰기 → 내기.

        답을 낼 수 없거나 서버가 받지 않으면(주로 그사이 참고한 카드가 바뀌었다) 한 번 더 쓴다.
        """
        answer_graph.run(answer_graph.JobSource(self._server, lease), self._llm, check=self._check_lease,
                         languages=self._languages)

    def _report(self, lease: Lease, reason: str, retryable: bool) -> None:
        log.warning("작업 %s 실패(%s): %s", lease.job_id, "다시 시도" if retryable else "끝", reason)
        try:
            self._server.fail(lease, reason, retryable)
        except (LeaseLost, ServerError) as error:
            log.warning("실패를 알리지 못했다: %s", error)


def idle_seconds(poll_seconds: float, failures: int) -> float:
    """다음 일을 찾기 전에 쉴 시간. 서버에 닿지 않은 횟수만큼 두 배씩 늘리고 상한을 둔다."""
    if failures <= 0:
        return poll_seconds
    return min(poll_seconds * (2 ** min(failures, 8)), MAX_IDLE_SECONDS)


def _stop_on_signal(signum: int, frame: object) -> None:
    # 두 번째 신호는 기다리지 않고 바로 끄도록 원래 동작으로 되돌린다
    signal.signal(signum, signal.SIG_DFL)
    STOP.set()
    raise Stopping(signal.Signals(signum).name)


def main() -> None:
    parser = argparse.ArgumentParser(description="Contexa AI 인턴")
    parser.add_argument("--once", action="store_true", help="기다리는 일을 한 번 훑고 끝낸다")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    config = Config.from_env()
    worker = Worker(ServerClient(config.server_url, config.worker_token, config.worker_id), make_llm(config.model),
                    languages=config.languages, extract_concurrency=config.extract_concurrency)
    log.info("일꾼 %s 시작. 서버 %s, 모델 %s, 언어 %s, 창 동시 %d개, Langfuse %s", config.worker_id, config.server_url,
             config.model, ",".join(config.languages), config.extract_concurrency,
             "켜짐" if tracing.langfuse_enabled() else "꺼짐")
    signal.signal(signal.SIGTERM, _stop_on_signal)
    signal.signal(signal.SIGINT, _stop_on_signal)
    failures = 0
    try:
        while True:
            try:
                worked = worker.run_once()
                failures = 0
            except ServerError as error:
                failures += 1
                log.warning("서버와 통신하지 못했다(%d번 연속): %s", failures, error)
                worked = False
            except Exception:
                # 작업 처리 밖에서 난 오류. 일꾼은 죽지 않고 잠시 쉬었다가 다시 일을 찾는다
                log.exception("예상하지 못한 오류")
                worked = False
            if not worked:
                if args.once:
                    return
                time.sleep(idle_seconds(config.poll_seconds, failures))
    except Stopping as stop:
        log.info("%s 신호를 받아 일꾼을 끈다", stop)


if __name__ == "__main__":
    main()
