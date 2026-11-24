"""모델을 부르는 곳. 모델 연결 코드는 이 파일에만 둔다.

다른 파일은 `LLM.structured(system, user, schema)` 하나만 안다. 모델 연결은 LangChain으로 한다.
`CONTEXA_MODEL=공급자:모델`(예: anthropic:claude-opus-5-5)만 바꾸면 다른 모델로 바뀐다. 공급자를 적지 않으면
anthropic으로 본다. anthropic 말고 다른 공급자는 그 LangChain 패키지(langchain-openai 등)를 따로 설치한다.
어느 모델이 답했든 서버 검증은 똑같다. 다만 공급자마다 받아들이는 JSON 스키마의 범위가 달라서, 바꾼 뒤에는
평가셋으로 다시 잰다.

LangChain 객체는 이 파일 밖으로 내보내지 않는다. 호출마다 tracing.py로 로그 한 줄과 (켜져 있으면) Langfuse 추적을 남긴다.
"""

from __future__ import annotations

import time
from typing import Any, Protocol

from . import tracing

DEFAULT_PROVIDER = "anthropic"
MAX_TOKENS = 16000

# 잠깐의 문제로 보는 예외 이름. 공급자 SDK들(anthropic, openai 등)이 같은 이름을 쓴다.
_TRANSIENT = {"RateLimitError", "APIConnectionError", "APITimeoutError", "InternalServerError", "OverloadedError"}


class LLMError(Exception):
    """모델 호출이 실패했다. 다시 해도 소용없는 경우다."""


class LLMTransient(LLMError):
    """잠깐의 문제(요청 제한, 서버 오류, 연결 끊김). 나중에 다시 해 볼 만하다."""


class LLMRefused(LLMError):
    """모델이 이 요청에 답하기를 거절했다."""


class LLM(Protocol):
    def structured(self, system: str, user: str, schema: dict[str, Any]) -> dict[str, Any]:
        """지시(system)와 자료(user)를 주고, schema에 맞는 JSON을 받는다."""
        ...


def parse_model(spec: str) -> tuple[str, str]:
    """'공급자:모델'을 (공급자, 모델)로 나눈다. 공급자가 없으면 anthropic이다."""
    spec = spec.strip()
    provider, sep, name = spec.partition(":")
    if not sep:
        return DEFAULT_PROVIDER, spec
    if not provider or not name:
        raise ValueError(f"모델은 '공급자:모델' 모양이어야 한다: {spec!r}")
    return provider, name


def provider_options(provider: str) -> dict[str, Any]:
    """공급자에만 있는 옵션. 다른 공급자로 바꾸면 이 옵션은 붙지 않는다."""
    if provider == "anthropic":
        return {
            # 부정·조건을 놓치면 안 되는 일이라 effort를 높게 둔다. reasoning_effort가 아니라 output_config로 넘긴다.
            # reasoning_effort로 넘기면 LangChain이 요약된 thinking 블록을 따로 켠다.
            "output_config": {"effort": "high"},
            # 안전 분류기가 요청을 거절하면 서버가 같은 요청을 대체 모델로 한 번 더 돌린다.
            "betas": ["server-side-fallback-2026-07-01"],
            "model_kwargs": {"fallbacks": "default"},
        }
    return {}


class ChatModelLLM:
    """LangChain 채팅 모델로 구조화 출력을 받는다. 인증은 각 SDK가 환경에서 찾는다(ANTHROPIC_API_KEY 등)."""

    def __init__(self, spec: str, chat_model: Any = None):
        self.provider, self.model = parse_model(spec)
        if chat_model is None:
            from langchain.chat_models import init_chat_model  # 이 클래스를 쓸 때만 필요하다

            chat_model = init_chat_model(self.model, model_provider=self.provider, max_tokens=MAX_TOKENS,
                                         **provider_options(self.provider))
        self._chat = chat_model

    def structured(self, system: str, user: str, schema: dict[str, Any]) -> dict[str, Any]:
        from langchain_core.messages import HumanMessage, SystemMessage

        prompt = getattr(system, "ref", None)
        # LangChain은 JSON 스키마의 이름을 title에서 가져온다. 모델에게 가는 뜻은 바뀌지 않는다.
        named = schema if "title" in schema else {"title": "output", **schema}
        runnable = self._chat.with_structured_output(named, method="json_schema", include_raw=True)
        started = time.monotonic()

        def record(outcome: str, raw: Any = None) -> None:
            usage = getattr(raw, "usage_metadata", None) or {}
            tracing.log_call(prompt=prompt, provider=self.provider, model=self.model, outcome=outcome,
                             latency_ms=int((time.monotonic() - started) * 1000),
                             input_tokens=usage.get("input_tokens"), output_tokens=usage.get("output_tokens"))

        try:
            result = runnable.invoke([SystemMessage(content=str(system)), HumanMessage(content=user)],
                                     config=tracing.run_config(prompt, f"{self.provider}:{self.model}"))
        except Exception as error:
            mapped = _classify(error)
            record(f"error:{type(error).__name__}")
            if mapped is None:
                raise
            raise mapped from error

        raw = result["raw"]
        metadata = getattr(raw, "response_metadata", None) or {}
        finish = metadata.get("normalized_finish_reason") or metadata.get("finish_reason")
        if finish == "refusal":
            record("refusal", raw)
            category = (metadata.get("stop_details") or {}).get("category")
            raise LLMRefused(f"모델이 답하기를 거절했다(분류: {category})")
        if finish in ("length", "max_tokens", "context_exceeded"):
            record("length", raw)
            raise LLMError("응답이 길이 제한에 걸려 잘렸다. 문서를 더 작게 나눠야 한다")
        parsed = result.get("parsed")
        if result.get("parsing_error") is not None or parsed is None:
            record("invalid_json", raw)
            if not getattr(raw, "text", ""):
                raise LLMError("모델 응답에 글자 내용이 없다")
            raise LLMError("모델 응답이 JSON이 아니다")
        if not isinstance(parsed, dict):
            record("invalid_json", raw)
            raise LLMError("모델 응답이 JSON 객체가 아니다")
        record("ok", raw)
        return parsed


def _classify(error: Exception) -> LLMError | None:
    """공급자 SDK의 예외를 세 가지(잠깐의 문제·끝난 실패·모름)로 나눈다. 모르는 예외는 None."""
    if any(cls.__name__ in _TRANSIENT for cls in type(error).__mro__) or isinstance(error, (TimeoutError, ConnectionError)):
        return LLMTransient(str(error))
    status = getattr(error, "status_code", None)
    if isinstance(status, int):
        if status == 429 or status >= 500:
            return LLMTransient(f"모델 서버 오류 {status}")
        return LLMError(f"모델 호출 실패 {status}: {getattr(error, 'message', error)}")
    return None


def make_llm(model: str) -> LLM:
    return ChatModelLLM(model)
