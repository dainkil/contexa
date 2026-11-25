import json
import os
import unittest
from unittest import mock

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from contexa_ai import prompts, tracing
from contexa_ai.llm import ChatModelLLM, LLMError, LLMRefused, LLMTransient, parse_model, provider_options

SCHEMA = {"type": "object", "additionalProperties": False, "required": ["a"], "properties": {"a": {"type": "string"}}}


def reply(parsed=None, *, finish="end_turn", normalized="stop", text='{"a": "x"}', parsing_error=None, extra=None):
    metadata = {"finish_reason": finish, "normalized_finish_reason": normalized, **(extra or {})}
    raw = AIMessage(content=text, response_metadata=metadata,
                    usage_metadata={"input_tokens": 120, "output_tokens": 30, "total_tokens": 150})
    return {"raw": raw, "parsed": parsed, "parsing_error": parsing_error}


class FakeChat:
    """LangChain 채팅 모델 흉내. with_structured_output에 넘긴 값과 invoke에 넘긴 값을 기억한다."""

    def __init__(self, outcome):
        self._outcome = outcome
        self.structured_args = None
        self.messages = None
        self.config = None

    def with_structured_output(self, schema, **kwargs):
        self.structured_args = (schema, kwargs)
        return self

    def invoke(self, messages, config=None):
        self.messages, self.config = messages, config
        if isinstance(self._outcome, Exception):
            raise self._outcome
        return self._outcome


class RateLimitError(Exception):
    """공급자 SDK의 요청 제한 예외와 같은 이름."""


class StatusError(Exception):
    def __init__(self, status_code, message="잘못된 요청"):
        super().__init__(message)
        self.status_code = status_code
        self.message = message


class ParseModelTest(unittest.TestCase):
    def test_provider_prefix_is_optional_and_defaults_to_anthropic(self):
        self.assertEqual(parse_model("claude-opus-5-5"), ("anthropic", "claude-opus-5-5"))
        self.assertEqual(parse_model("anthropic:claude-opus-5-5"), ("anthropic", "claude-opus-5-5"))
        self.assertEqual(parse_model(" openai:gpt-x "), ("openai", "gpt-x"))

    def test_rejects_half_spec(self):
        for spec in (":claude-opus-5-5", "anthropic:"):
            with self.assertRaises(ValueError):
                parse_model(spec)

    def test_claude_only_options_are_not_sent_to_other_providers(self):
        options = provider_options("anthropic")
        self.assertEqual(options["output_config"], {"effort": "high"})
        self.assertEqual(options["betas"], ["server-side-fallback-2026-07-01"])
        self.assertEqual(options["model_kwargs"], {"fallbacks": "default"})
        self.assertEqual(provider_options("openai"), {})


class StructuredTest(unittest.TestCase):
    def call(self, outcome, system="지시"):
        chat = FakeChat(outcome)
        llm = ChatModelLLM("anthropic:claude-opus-5-5", chat_model=chat)
        return llm, chat, lambda: llm.structured(system, "자료", SCHEMA)

    def test_returns_parsed_json_and_asks_for_native_json_schema(self):
        _, chat, run = self.call(reply({"a": "x"}))
        self.assertEqual(run(), {"a": "x"})
        schema, kwargs = chat.structured_args
        self.assertEqual(kwargs, {"method": "json_schema", "include_raw": True})
        # LangChain이 스키마 이름을 title에서 가져가므로 붙인다. 원래 스키마는 건드리지 않는다.
        self.assertEqual(schema["title"], "output")
        self.assertNotIn("title", SCHEMA)
        self.assertEqual([type(m) for m in chat.messages], [SystemMessage, HumanMessage])
        self.assertEqual([m.content for m in chat.messages], ["지시", "자료"])

    def test_logs_one_line_with_prompt_version_tokens_and_job_but_no_text(self):
        prompt = prompts.Prompt("비밀스러운 지시문", "extract", 3)
        _, chat, run = self.call(reply({"a": "회의 원문 내용"}), system=prompt)
        with tracing.job("job-1", "EXTRACT", "project-1"), self.assertLogs("contexa.llm", "INFO") as logs:
            run()
        record = json.loads(logs.records[0].getMessage())
        self.assertEqual(record["prompt"], "extract.v3")
        self.assertEqual((record["job_id"], record["job_type"], record["project_id"]), ("job-1", "EXTRACT", "project-1"))
        self.assertEqual((record["provider"], record["model"]), ("anthropic", "claude-opus-5-5"))
        self.assertEqual((record["input_tokens"], record["output_tokens"], record["outcome"]), (120, 30, "ok"))
        self.assertIsInstance(record["latency_ms"], int)
        line = logs.records[0].getMessage()
        self.assertNotIn("비밀스러운", line)
        self.assertNotIn("회의 원문", line)
        self.assertEqual(chat.config["metadata"]["prompt"], "extract.v3")
        self.assertEqual(chat.config["metadata"]["job_id"], "job-1")

    def test_refusal_becomes_llm_refused_with_category(self):
        _, _, run = self.call(reply(None, finish="refusal", normalized="refusal", text="",
                                    extra={"stop_reason": "refusal", "stop_details": {"category": "cyber"}}))
        with self.assertRaises(LLMRefused) as caught:
            run()
        self.assertIn("cyber", str(caught.exception))

    def test_truncated_answer_is_a_final_failure(self):
        _, _, run = self.call(reply(None, finish="max_tokens", normalized="length"))
        with self.assertRaises(LLMError) as caught:
            run()
        self.assertNotIsInstance(caught.exception, LLMTransient)
        self.assertIn("길이 제한", str(caught.exception))

    def test_unparseable_or_empty_answer(self):
        _, _, run = self.call(reply(None, text="이건 JSON이 아니다", parsing_error=ValueError("bad")))
        with self.assertRaisesRegex(LLMError, "JSON이 아니다"):
            run()
        _, _, run = self.call(reply(None, text="", parsing_error=ValueError("empty")))
        with self.assertRaisesRegex(LLMError, "글자 내용이 없다"):
            run()

    def test_provider_errors_are_sorted_into_transient_final_or_unknown(self):
        cases = [(RateLimitError("too many"), LLMTransient), (StatusError(503), LLMTransient),
                 (StatusError(429), LLMTransient), (TimeoutError("slow"), LLMTransient)]
        for error, expected in cases:
            _, _, run = self.call(error)
            with self.assertRaises(expected):
                run()
        _, _, run = self.call(StatusError(400))
        with self.assertRaises(LLMError) as caught:
            run()
        self.assertNotIsInstance(caught.exception, LLMTransient)
        self.assertIn("400", str(caught.exception))
        _, _, run = self.call(KeyError("버그"))
        with self.assertRaises(KeyError):
            run()


class RealChatModelWiringTest(unittest.TestCase):
    """실제 LangChain 모델을 만들되 부르지는 않는다. 요청에 Claude 옵션이 빠짐없이 실리는지 본다."""

    def test_anthropic_request_carries_effort_beta_fallback_and_schema(self):
        with mock.patch.dict(os.environ, {"ANTHROPIC_API_KEY": os.environ.get("ANTHROPIC_API_KEY", "sk-test")}):
            llm = ChatModelLLM("claude-opus-5-5")
        chat = llm._chat
        self.assertEqual(type(chat).__name__, "ChatAnthropic")
        self.assertEqual((chat.model, chat.max_tokens), ("claude-opus-5-5", 16000))
        # 응답을 끝없이 기다리지 않는다. 그 뒤의 다시 시도는 server 작업 큐가 간격을 두고 맡는다
        self.assertEqual((chat.default_request_timeout, chat.max_retries), (600.0, 2))
        payload = chat._get_request_payload(
            [SystemMessage(content="지시"), HumanMessage(content="자료")],
            output_config={"format": {"type": "json_schema", "schema": SCHEMA}})
        self.assertEqual(payload["output_config"]["effort"], "high")
        self.assertEqual(payload["output_config"]["format"]["type"], "json_schema")
        self.assertEqual(payload["betas"], ["server-side-fallback-2026-07-01"])
        self.assertEqual(payload["fallbacks"], "default")
        self.assertNotIn("thinking", payload)


if __name__ == "__main__":
    unittest.main()
