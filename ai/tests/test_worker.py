import threading
import time
import unittest
from unittest import mock

from contexa_ai import worker as worker_module
from contexa_ai.llm import LLMRefused, LLMTransient
from contexa_ai.server import Lease, LeaseLost, ServerError, ServerRejected
from contexa_ai.worker import Stopping, Worker, idle_seconds
from tests.test_extract import ONTOLOGY, SPANS, STATE, card, link


class FakeServer:
    """서버 흉내. 일 하나를 내주고, 일꾼이 낸 결과와 실패를 기억한다."""

    def __init__(self, job_type, reject_first=None, lose_lease=False):
        self._lease = Lease("job-1", job_type, "project-1", "attempt-1", "lease-token")
        self._reject_first = reject_first
        self._lose_lease = lose_lease
        self.submitted = []
        self.failures = []
        self.heartbeats = 0

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

    def heartbeat(self, lease):
        self.heartbeats += 1
        return "2026-11-25T00:10:00Z"


class FakeLLM:
    """모델 흉내. 정해 둔 답을 차례로 돌려주고, 받은 자료를 기억한다."""

    def __init__(self, *answers):
        self._answers = list(answers)
        self.prompts = []

    def structured(self, system, user, schema):
        self.prompts.append(user)
        answer = self._answers.pop(0)
        if isinstance(answer, BaseException):
            raise answer
        return answer


class SlowLLM(FakeLLM):
    """답하는 데 시간이 걸리는 모델."""

    def __init__(self, seconds, *answers):
        super().__init__(*answers)
        self._seconds = seconds

    def structured(self, system, user, schema):
        time.sleep(self._seconds)
        return super().structured(system, user, schema)


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

    def test_모르는_예외는_끝난_실패로_알리고_일꾼은_죽지_않는다(self):
        # 예: 모델 응답 모양이 예상과 달라 생긴 코드 오류. 전에는 일꾼 프로세스가 그대로 끝났다
        server = FakeServer("EXTRACT")

        self.assertTrue(Worker(server, FakeLLM(KeyError("cards"))).run_once())

        self.assertEqual(server.failures, [("일꾼 내부 오류(KeyError): 'cards'", False)])

    def test_서버와_통신하지_못하면_실패를_알리지_않고_위로_올린다(self):
        # 실패를 알리는 요청도 닿지 않는다. 임대가 끝나면 서버가 다른 일꾼에게 다시 준다
        class Unreachable(FakeServer):
            def spans(self, lease):
                raise ServerError("연결이 끊겼다")

        server = Unreachable("EXTRACT")

        with self.assertRaises(ServerError):
            Worker(server, FakeLLM(GOOD)).run_once()
        self.assertEqual(server.failures, [])

    def test_오래_걸리는_작업_중에는_임대를_늘리고_한_번_닿지_않아도_계속_알린다(self):
        class Flaky(FakeServer):
            def heartbeat(self, lease):
                self.heartbeats += 1
                if self.heartbeats == 1:
                    raise ServerError("연결이 잠깐 끊겼다")
                return "2026-11-25T00:10:00Z"

        server = Flaky("EXTRACT")

        Worker(server, SlowLLM(0.3, GOOD), heartbeat_seconds=0.02).run_once()

        self.assertGreaterEqual(server.heartbeats, 2)
        self.assertEqual(len(server.submitted), 1)
        self.assertEqual(server.failures, [])

    def test_빨리_끝난_작업은_임대를_늘리지_않는다(self):
        server = FakeServer("EXTRACT")

        Worker(server, FakeLLM(GOOD), heartbeat_seconds=60).run_once()

        self.assertEqual(server.heartbeats, 0)

    def test_임대를_잃으면_다음_모델_호출_전에_멈추고_결과도_실패도_내지_않는다(self):
        class Expired(FakeServer):
            def heartbeat(self, lease):
                self.heartbeats += 1
                raise LeaseLost("한 시도의 최대 작업 시간을 넘겼다")

        server = Expired("EXTRACT")
        llm = SlowLLM(0.3, GOOD, GOOD)

        with mock.patch.object(worker_module, "WINDOW", 1):  # 발언 2개 → 창 2개
            Worker(server, llm, heartbeat_seconds=0.02).run_once()

        self.assertEqual(server.heartbeats, 1)  # 잃은 뒤로는 더 알리지 않는다
        self.assertEqual(len(llm.prompts), 1)   # 두 번째 창은 모델에게 보내지 않는다
        self.assertEqual(server.submitted, [])
        self.assertEqual(server.failures, [])

    def test_꺼지라는_신호를_받으면_하던_일을_다시_해_볼_실패로_알리고_멈춘다(self):
        server = FakeServer("EXTRACT")

        with self.assertRaises(Stopping):
            Worker(server, FakeLLM(Stopping("SIGTERM"))).run_once()

        self.assertEqual(server.failures, [("일꾼이 작업 중에 꺼졌다", True)])
        self.assertEqual(server.submitted, [])
        self.assertFalse(any(t.name.startswith("heartbeat-") for t in threading.enumerate()))

    def test_작업을_가져온_직후에_꺼져도_다시_해_볼_실패로_알린다(self):
        # 처리에 들어가기 전(로그·추적을 여는 사이)에 신호가 와도 임대가 끝날 때까지 묶이지 않는다
        server = FakeServer("EXTRACT")

        with mock.patch.object(Worker, "_handle", side_effect=Stopping("SIGTERM")):
            with self.assertRaises(Stopping):
                Worker(server, FakeLLM()).run_once()

        self.assertEqual(server.failures, [("일꾼이 작업 중에 꺼졌다", True)])

    def test_서버에_닿지_않으면_쉬는_시간을_두_배씩_늘리고_상한을_둔다(self):
        self.assertEqual(idle_seconds(2, 0), 2)
        self.assertEqual(idle_seconds(2, 1), 4)
        self.assertEqual(idle_seconds(2, 3), 16)
        self.assertEqual(idle_seconds(2, 50), worker_module.MAX_IDLE_SECONDS)

    def test_모르는_종류의_일은_끝난_실패로_알린다(self):
        server = FakeServer("SUMMARIZE")

        Worker(server, FakeLLM()).run_once()

        self.assertEqual(len(server.failures), 1)
        self.assertFalse(server.failures[0][1])


if __name__ == "__main__":
    unittest.main()
