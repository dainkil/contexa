"""모델 호출을 기록한다: 작업 맥락, 호출 로그 한 줄, Langfuse 추적(선택).

- 작업 하나를 처리하는 동안 어느 작업·프로젝트의 일인지를 `contextvars`로 들고 있다. llm.py는 이 값을 읽어
  호출마다 로그를 남긴다. 그래서 `LLM.structured(system, user, schema)`의 모양을 바꾸지 않아도 된다.
- 호출 로그는 JSON 한 줄이다. 회의 원문, 프롬프트 본문, 모델 답은 적지 않는다. 회의 내용이 로그로 새지 않게 하기 위해서다.
- `LANGFUSE_PUBLIC_KEY`·`LANGFUSE_SECRET_KEY`가 있고 `langfuse`가 설치돼 있으면 Langfuse에도 보낸다(`LANGFUSE_HOST`,
  기본 http://localhost:3000). 작업 하나가 trace 하나이고, 모델 호출은 그 아래 generation으로 붙는다. Langfuse는
  이 컴퓨터에 띄운 것만 쓴다. 추적이 실패해도 작업은 계속한다. 추적은 업무 기록이 아니다.
"""

from __future__ import annotations

import json
import logging
import os
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from typing import Any, Iterator

log = logging.getLogger("contexa.llm")


@dataclass(frozen=True)
class JobContext:
    job_id: str
    job_type: str
    project_id: str


_current: ContextVar[JobContext | None] = ContextVar("contexa_job", default=None)


def current() -> JobContext | None:
    """지금 처리 중인 작업. 작업 밖(평가 스크립트 등)에서 부르면 None."""
    return _current.get()


@contextmanager
def job(job_id: str, job_type: str, project_id: str) -> Iterator[JobContext]:
    """이 안에서 일어나는 모델 호출은 모두 이 작업의 것으로 기록된다."""
    context = JobContext(job_id, job_type, project_id)
    token = _current.set(context)
    try:
        with _langfuse_trace(context):
            yield context
    finally:
        _current.reset(token)


def run_config(prompt_ref: str | None, model: str) -> dict[str, Any]:
    """LangChain 호출에 넘길 config: Langfuse 콜백과, 추적에 붙일 꼬리표."""
    context = current()
    metadata: dict[str, Any] = {"prompt": prompt_ref, "model": model}
    if context is not None:
        metadata.update({"job_id": context.job_id, "job_type": context.job_type, "project_id": context.project_id})
    config: dict[str, Any] = {"metadata": metadata, "run_name": prompt_ref or "llm"}
    handler = _langfuse_handler()
    if handler is not None:
        config["callbacks"] = [handler]
    return config


def log_call(*, prompt: str | None, provider: str, model: str, outcome: str, latency_ms: int,
             input_tokens: int | None = None, output_tokens: int | None = None) -> None:
    """모델 호출 한 번을 JSON 한 줄로 남긴다. 원문은 넣지 않는다."""
    context = current()
    record = {
        "event": "llm_call",
        "job_id": context.job_id if context else None,
        "job_type": context.job_type if context else None,
        "project_id": context.project_id if context else None,
        "prompt": prompt,
        "provider": provider,
        "model": model,
        "outcome": outcome,
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "latency_ms": latency_ms,
    }
    log.info(json.dumps(record, ensure_ascii=False))


# ---- Langfuse (선택) ----

_UNSET = object()
_langfuse: Any = _UNSET


def langfuse_enabled() -> bool:
    return _client() is not None


def _client() -> Any:
    """Langfuse 클라이언트. 키가 없거나 설치돼 있지 않으면 None. 처음 한 번만 확인한다."""
    global _langfuse
    if _langfuse is not _UNSET:
        return _langfuse
    _langfuse = None
    if not (os.environ.get("LANGFUSE_PUBLIC_KEY") and os.environ.get("LANGFUSE_SECRET_KEY")):
        return None
    os.environ.setdefault("LANGFUSE_HOST", "http://localhost:3000")
    try:
        from langfuse import get_client
        from langfuse.langchain import CallbackHandler

        _langfuse = (get_client(), CallbackHandler())
    except ImportError:
        log.warning("LANGFUSE_* 키가 있지만 langfuse가 설치돼 있지 않다. pip install -e '.[observability]'")
    except Exception as error:  # 추적을 못 켜도 일은 해야 한다
        log.warning("Langfuse를 켜지 못했다: %s", error)
    return _langfuse


def _langfuse_handler() -> Any:
    client = _client()
    return client[1] if client else None


@contextmanager
def _langfuse_trace(context: JobContext) -> Iterator[None]:
    client = _client()
    if client is None:
        yield
        return
    from langfuse import propagate_attributes

    langfuse = client[0]
    with langfuse.start_as_current_observation(as_type="span", name=f"{context.job_type} 작업"):
        with propagate_attributes(
            trace_name=f"{context.job_type} 작업",
            tags=[context.job_type, f"project:{context.project_id}"],
            metadata={"job_id": context.job_id, "project_id": context.project_id},
        ):
            yield
    try:
        langfuse.flush()
    except Exception as error:  # 보내지 못해도 작업 결과에는 영향이 없다
        log.warning("Langfuse로 보내지 못했다: %s", error)


def reset_for_tests() -> None:
    """테스트에서 환경 변수를 바꾼 뒤 Langfuse 설정을 다시 읽게 한다."""
    global _langfuse
    _langfuse = _UNSET
