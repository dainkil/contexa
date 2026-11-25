import logging
import unittest

from contexa_ai import tracing

from eval.extract import count_tokens


class TokenCountTest(unittest.TestCase):
    def test_로그_수준이_WARNING이어도_호출_기록의_토큰을_모은다(self):
        # 전에는 basicConfig(WARNING) 때문에 INFO 기록이 버려져 토큰이 0으로 나왔다
        logging.getLogger().setLevel(logging.WARNING)
        with count_tokens() as counter:
            tracing.log_call(prompt="extract.v1", provider="anthropic", model="m", outcome="ok", latency_ms=5,
                             input_tokens=100, output_tokens=40)
            tracing.log_call(prompt="extract.v1", provider="anthropic", model="m", outcome="ok", latency_ms=5,
                             input_tokens=50, output_tokens=None)
        self.assertEqual((counter.calls, counter.input_tokens, counter.output_tokens), (2, 150, 40))
        self.assertFalse(logging.getLogger("contexa.llm").handlers)


if __name__ == "__main__":
    unittest.main()
