"""모델을 부르는 곳. 모델 회사에 묶인 코드는 이 파일에만 둔다.

다른 파일은 `LLM.structured(system, user, schema)` 하나만 안다. 나중에 CLOVA Studio로 바꿀 때는
같은 모양의 클래스를 하나 더 만들어 `make_llm`에서 고르면 된다.
"""

from __future__ import annotations

import json
from typing import Any, Protocol


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


class ClaudeLLM:
    """Claude API. 인증은 SDK가 환경에서 찾는다(ANTHROPIC_API_KEY 등). 키를 코드에 적지 않는다."""

    def __init__(self, model: str):
        import anthropic  # 이 클래스를 쓸 때만 필요하다

        self._anthropic = anthropic
        self._client = anthropic.Anthropic()
        self._model = model

    def structured(self, system: str, user: str, schema: dict[str, Any]) -> dict[str, Any]:
        anthropic = self._anthropic
        try:
            response = self._client.beta.messages.create(
                model=self._model,
                max_tokens=16000,
                system=system,
                messages=[{"role": "user", "content": user}],
                # 응답을 schema에 맞는 JSON으로 고정한다. 부정·조건을 놓치면 안 되는 일이라 effort를 높게 둔다.
                output_config={"format": {"type": "json_schema", "schema": schema}, "effort": "high"},
                # 안전 분류기가 요청을 거절하면 서버가 같은 요청을 대체 모델로 한 번 더 돌린다.
                betas=["server-side-fallback-2026-07-01"],
                fallbacks="default",
            )
        except (anthropic.RateLimitError, anthropic.APIConnectionError) as error:
            raise LLMTransient(str(error)) from error
        except anthropic.APIStatusError as error:
            if error.status_code >= 500:
                raise LLMTransient(f"모델 서버 오류 {error.status_code}") from error
            raise LLMError(f"모델 호출 실패 {error.status_code}: {error.message}") from error

        if response.stop_reason == "refusal":
            category = response.stop_details.category if response.stop_details else None
            raise LLMRefused(f"모델이 답하기를 거절했다(분류: {category})")
        if response.stop_reason == "max_tokens":
            raise LLMError("응답이 길이 제한에 걸려 잘렸다. 문서를 더 작게 나눠야 한다")
        text = next((block.text for block in response.content if block.type == "text"), None)
        if text is None:
            raise LLMError("모델 응답에 글자 내용이 없다")
        try:
            return json.loads(text)
        except json.JSONDecodeError as error:
            raise LLMError("모델 응답이 JSON이 아니다") from error


def make_llm(model: str) -> LLM:
    return ClaudeLLM(model)
