import unittest

from contexa_ai.llm import LLMRefused, LLMTransient
from contexa_ai.server import Lease, LeaseLost, ServerRejected
from contexa_ai.worker import Worker
from tests.test_extract import ONTOLOGY, SPANS, STATE, card, link


class FakeServer:
    """서버 흉내. 일 하나를 내주고, 일꾼이 낸 결과와 실패를 기억한다."""

    def __init__(self, job_type, reject_first=None, lose_lease=False):
        self._lease = Lease("job-1", job_type, "project-1", "attempt-1", "lease-token")
        self._reject_first = reject_first
        self._lose_lease = lose_lease
        self.submitted = []
        self.failures = []

    def claim(self):
        lease, self._lease = self._lease, None
        return lease

    def ontology(self):
        return ONTOLOGY

    def content(self, lease):
        return {"document": {"title": "3차 회의", "kind": "TRANSCRIPT"},
                "content": "민지: 예산 승인 전까지 인쇄는 보류입니다.\n준호: 네."}

    def spans(self, lease):
        return SPANS

    def state(self, lease):
        return STATE

    def submit(self, lease, result):
        if self._lose_lease:
            raise LeaseLost("임대 시간이 지났다")
        if self._reject_first:
            reason, self._reject_first = self._reject_first, None
            raise ServerRejected(reason)
        self.submitted.append(result)

    def fail(self, lease, reason, retryable):
        self.failures.append((reason, retryable))


class FakeLLM:
    """모델 흉내. 정해 둔 답을 차례로 돌려주고, 받은 자료를 기억한다."""

    def __init__(self, *answers):
        self._answers = list(answers)
        self.prompts = []

    def structured(self, system, user, schema):
        self.prompts.append(user)
        answer = self._answers.pop(0)
        if isinstance(answer, Exception):
            raise answer
        return answer


GOOD = {"cards": [card("c1", "TASK", "인쇄 발주", [1]), card("c2", "CONDITION", "예산 승인", [1])],
        "links": [link("REQUIRES", "c1", "c2", [1])]}


class WorkerTest(unittest.TestCase):
    def test_가져올_일이_없으면_아무것도_하지_않는다(self):
        server = FakeServer("PARSE")
        server.claim()
        self.assertFalse(Worker(server, FakeLLM()).run_once())

    def test_자르기는_모델_없이_문서를_발언으로_잘라_낸다(self):
        server = FakeServer("PARSE")
        llm = FakeLLM()

        self.assertTrue(Worker(server, llm).run_once())

        self.assertEqual(server.submitted, [{"spans": [
            {"speakerLabel": "민지", "text": "예산 승인 전까지 인쇄는 보류입니다."},
            {"speakerLabel": "준호", "text": "네."}]}])
        self.assertEqual(llm.prompts, [])

    def test_뽑기는_모델의_답을_후보로_바꿔_낸다(self):
        server = FakeServer("EXTRACT")

        Worker(server, FakeLLM(GOOD)).run_once()

        candidates = server.submitted[0]["candidates"]
        self.assertEqual([node["key"] for node in candidates["nodes"]], ["w0:c1", "w0:c2"])
        self.assertIn({"rel": "REQUIRES", "srcKey": "w0:c1", "dstKey": "w0:c2", "evidenceSpanIds": ["span-1"]},
                      candidates["edges"])
        self.assertEqual(server.failures, [])

    def test_서버가_거절하면_이유를_모델에게_알려_주고_한_번_다시_뽑는다(self):
        server = FakeServer("EXTRACT", reject_first="허용되지 않은 줄: CONDITION -REQUIRES-> TASK")
        llm = FakeLLM(GOOD, GOOD)

        Worker(server, llm).run_once()

        self.assertEqual(len(llm.prompts), 2)
        self.assertIn("허용되지 않은 줄: CONDITION -REQUIRES-> TASK", llm.prompts[1])
        self.assertEqual([node["key"] for node in server.submitted[0]["candidates"]["nodes"]], ["r:c1", "r:c2"])
        self.assertEqual(server.failures, [])

    def test_두_번째도_거절되면_다시_시도하지_않는_실패로_알린다(self):
        server = FakeServer("EXTRACT", reject_first="근거 발언이 없는 줄 후보다")
        server.submit = lambda lease, result: (_ for _ in ()).throw(ServerRejected("근거 발언이 없는 줄 후보다"))

        Worker(server, FakeLLM(GOOD, GOOD)).run_once()

        self.assertEqual(server.failures, [("근거 발언이 없는 줄 후보다", False)])

    def test_모델의_잠깐의_실패는_다시_시도할_실패로_거절은_끝난_실패로_알린다(self):
        busy = FakeServer("EXTRACT")
        Worker(busy, FakeLLM(LLMTransient("요청 제한"))).run_once()
        self.assertEqual(busy.failures[0][1], True)

        refused = FakeServer("EXTRACT")
        Worker(refused, FakeLLM(LLMRefused("거절"))).run_once()
        self.assertEqual(refused.failures, [("거절", False)])

    def test_임대증을_잃으면_결과도_실패도_내지_않고_내려놓는다(self):
        server = FakeServer("EXTRACT", lose_lease=True)

        self.assertTrue(Worker(server, FakeLLM(GOOD)).run_once())

        self.assertEqual(server.submitted, [])
        self.assertEqual(server.failures, [])

    def test_모르는_종류의_일은_끝난_실패로_알린다(self):
        server = FakeServer("SUMMARIZE")

        Worker(server, FakeLLM()).run_once()

        self.assertEqual(len(server.failures), 1)
        self.assertFalse(server.failures[0][1])


if __name__ == "__main__":
    unittest.main()
