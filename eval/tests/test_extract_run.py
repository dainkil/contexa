import json
import logging
import unittest
from pathlib import Path

from contexa_ai import tracing

from eval import extract
from eval.extract import count_tokens

ONTOLOGY = json.loads((Path(__file__).parent / "ontology.json").read_text(encoding="utf-8"))


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


class OracleRunTest(unittest.TestCase):
    """정답을 답으로 넣으면 일꾼과 같은 흐름(extract_graph)을 거쳐도 만점이어야 한다. 서버와 모델 없이 돈다."""

    def assert_perfect(self, result):
        summary = result["summary"]
        self.assertEqual((summary["cards"]["ALL"]["precision"], summary["cards"]["ALL"]["recall"]), (1.0, 1.0))
        self.assertEqual((summary["links"]["ALL"]["precision"], summary["links"]["ALL"]["recall"]), (1.0, 1.0))
        self.assertEqual(summary["duplicates"], 0)

    def test_창_하나(self):
        result = extract.run(None, ONTOLOGY)
        self.assert_perfect(result)
        self.assertEqual([meeting["windows"] for meeting in result["meetings"]], [1, 1, 1, 1])

    def test_작은_창으로_나눠도_합치기가_같은_카드를_하나로_모은다(self):
        result = extract.run(None, ONTOLOGY, window=3, overlap=1)
        self.assert_perfect(result)
        self.assertGreater(sum(meeting["windows"] for meeting in result["meetings"]), 4)


if __name__ == "__main__":
    unittest.main()
