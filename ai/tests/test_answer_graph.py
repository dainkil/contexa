import unittest

from contexa_ai import answer, answer_graph
from contexa_ai.server import ServerError, ServerRejected
from contexa_ai.worker import Stopping
from tests.test_answer import (BUDGET, GOOD, SEARCH, SOURCE22, SPAN22, SPAN23, STATE, TRACE, fact,
                               packet)
from tests.test_worker import FakeLLM

VENUE = {"type": "TOPIC", "id": "topic-venue", "revision": 1}
BUDGET_TOPIC = {"type": "TOPIC", "id": "topic-budget", "revision": 1}
GONE = {"type": "TOPIC", "id": "topic-gone", "revision": 1}
SPAN31 = {"type": "SPAN", "id": "span-31", "revision": 1}
SOURCE31 = {"span": SPAN31, "documentId": "doc-2", "seq": 4, "speaker": "Ahmed",
            "text": "القاعة قيد الصيانة، لذلك نغيّر المكان."}
ARABIC_HIT = packet([], [], [SOURCE31])
WITH_TOPICS = packet(
    [fact(VENUE, "행사 장소", "CONFIRMED", {}, []), fact(BUDGET_TOPIC, "예산", "CONFIRMED", {}, []),
     fact(GONE, "지워진 안건", "CONFIRMED", {}, [])],
    [], [], STATE["open"])
VENUE_TRACE = packet([fact(VENUE, "행사 장소", "CONFIRMED", {}, [])], [], [SOURCE22])
EMPTY = packet([], [], [])
QUESTION = "인쇄 진행해도 돼?"


class FakeSource:
    """근거를 읽는 곳 흉내. 검색어마다 정해 둔 답변서를 준다."""

    def __init__(self, hits=None, state=WITH_TOPICS, reject=()):
        self._hits = hits or {QUESTION: SEARCH}
        self._state = state
        self._reject = list(reject)
        self.searched = []
        self.traced = []
        self.states = 0
        self.submitted = []

    def request(self):
        return {"artifactId": "a-1", "kind": "ANSWER", "question": QUESTION, "lang": "ko"}

    def state(self):
        self.states += 1
        return self._state

    def search(self, query, k):
        self.searched.append(query)
        return self._hits.get(query, EMPTY)

    def trace(self, node, direction, depth):
        self.traced.append((node["id"], direction, depth))
        if node["id"] in (BUDGET["id"], GONE["id"]):
            raise ServerError("404: 카드가 없다")
        return VENUE_TRACE if node["type"] == "TOPIC" else TRACE

    def submit(self, draft):
        if self._reject:
            raise ServerRejected(self._reject.pop(0))
        self.submitted.append(draft)


def plan(queries=(), topics=()):
    return {"queries": list(queries), "topics": list(topics)}


def judge(enough, queries=(), topics=()):
    return {"enough": enough, "missing": "" if enough else "이유가 없다", "queries": list(queries),
            "topics": list(topics)}


class Plan(unittest.TestCase):
    def test_더_찾을_것이_없으면_예전처럼_질문_하나로_한_번_모은다(self):
        source, llm = FakeSource(state=STATE), FakeLLM(GOOD)

        draft = answer_graph.run(source, llm)

        self.assertEqual(source.searched, [QUESTION])
        self.assertEqual(draft["citations"], [SPAN22, SPAN23])
        self.assertEqual((len(llm.plans), len(llm.judges), len(llm.prompts)), (1, 1, 1))
        # 모델이 본 근거가 예전 gather와 같다
        old = answer.build_user_prompt(source.request(), answer.gather(_JobServer(), None, QUESTION))
        self.assertEqual(llm.prompts[0], old)

    def test_계획한_검색어와_안건으로_더_모은다(self):
        source = FakeSource({QUESTION: SEARCH, "طباعة": ARABIC_HIT})
        llm = FakeLLM(GOOD, plan=plan(["طباعة", "인쇄 진행해도 돼?", "  طباعة ", "", "print"], ["t1", "t9", "t3"]))

        answer_graph.run(source, llm)

        # 질문 원문이 첫째고, 겹치는 검색어·빈 검색어는 뺀다
        self.assertEqual(source.searched, [QUESTION, "طباعة", "print"])
        # 목록에 없는 이름표(t9)는 버린다. 보이지 않게 된 안건(t3)은 건너뛴다
        topic_traces = [call for call in source.traced if call[0].startswith("topic-")]
        self.assertEqual(topic_traces, [("topic-venue", "BASIS", 3), ("topic-gone", "BASIS", 3)])
        self.assertIn("ال", llm.prompts[0])  # 다른 언어 검색으로 찾은 발언을 모델이 본다
        self.assertIn("t1: 행사 장소", llm.plans[0])
        self.assertIn("프로젝트 언어: 한국어(ko), 영어(en), 아랍어(ar)", llm.plans[0])

    def test_검색어_수에는_상한이_있다(self):
        source = FakeSource()
        llm = FakeLLM(GOOD, plan=plan([f"q{n}" for n in range(10)]))

        answer_graph.run(source, llm)

        self.assertEqual(len(source.searched), answer_graph.MAX_QUERIES)

    def test_언어를_설정에서_받는다(self):
        llm = FakeLLM(GOOD)

        answer_graph.run(FakeSource(), llm, languages=("ko", "ja"))

        self.assertIn("프로젝트 언어: 한국어(ko), ja(ja)", llm.plans[0])


class Judge(unittest.TestCase):
    def test_모자라면_새_검색어와_안건으로_한_번_더_모은다(self):
        source = FakeSource({QUESTION: SEARCH, "قاعة": ARABIC_HIT})
        llm = FakeLLM(GOOD, judge=judge(False, ["قاعة", QUESTION], ["t1"]))

        answer_graph.run(source, llm)

        self.assertEqual(source.searched, [QUESTION, "قاعة"])
        self.assertIn(("topic-venue", "BASIS", 3), source.traced)
        self.assertEqual(len(llm.judges), 1)  # 두 번째로 모은 뒤에는 판단하지 않고 쓴다(MAX_ROUNDS)
        self.assertIn("이미 찾은 검색어: \"인쇄 진행해도 돼?\"", llm.judges[0])
        self.assertIn("<sources>", llm.judges[0])

    def test_모자라도_새로_찾을_것이_없으면_바로_쓴다(self):
        source = FakeSource()
        llm = FakeLLM(GOOD, judge=judge(False, [QUESTION], []))

        answer_graph.run(source, llm)

        self.assertEqual(source.searched, [QUESTION])
        self.assertEqual(len(llm.prompts), 1)

    def test_이미_읽은_안건은_다시_고르지_않는다(self):
        source = FakeSource()
        llm = FakeLLM(GOOD, plan=plan([], ["t1"]), judge=judge(False, [], ["t1"]))

        answer_graph.run(source, llm)

        self.assertEqual([call for call in source.traced if call[0] == "topic-venue"], [("topic-venue", "BASIS", 3)])
        self.assertIn("t1: 행사 장소 (읽음)", llm.judges[0])


class Repair(unittest.TestCase):
    def test_서버가_받지_않으면_같은_검색어와_안건으로_처음부터_다시_읽고_판단_없이_다시_쓴다(self):
        source = FakeSource({QUESTION: SEARCH, "طباعة": ARABIC_HIT}, reject=["참고한 카드가 바뀌었다"])
        llm = FakeLLM(GOOD, GOOD, plan=plan(["طباعة"], ["t1"]))

        answer_graph.run(source, llm)

        self.assertEqual(source.searched, [QUESTION, "طباعة", QUESTION, "طباعة"])
        self.assertEqual([call for call in source.traced if call[0] == "topic-venue"],
                         [("topic-venue", "BASIS", 3)] * 2)
        self.assertEqual(source.states, 2)
        self.assertEqual((len(llm.plans), len(llm.judges), len(llm.prompts)), (1, 1, 2))
        self.assertIn("참고한 카드가 바뀌었다", llm.prompts[1])
        self.assertEqual(len(source.submitted), 1)

    def test_낼_수_없는_답은_같은_근거로_다시_쓰고_두_번째도_안_되면_거절로_올린다(self):
        source = FakeSource()
        bad = {**GOOD, "answer": "인쇄는 보류입니다."}
        llm = FakeLLM(bad, bad)

        with self.assertRaisesRegex(ServerRejected, "인용이 하나도 없다"):
            answer_graph.run(source, llm)
        self.assertEqual(source.searched, [QUESTION])  # 근거를 다시 읽지 않는다
        self.assertEqual(len(llm.prompts), 2)


class Gather(unittest.TestCase):
    def test_모델_없이_모으면_질문_하나로_한_번만_찾는다(self):
        source = FakeSource()

        state = answer_graph.gather(source, None)

        self.assertEqual(source.searched, [QUESTION])
        self.assertEqual(len(state["rounds_log"]), 1)
        self.assertEqual(state["rounds_log"][0]["sources"], ["span-22", "span-23"])
        self.assertEqual(source.submitted, [])

    def test_회차마다_찾은_발언을_남긴다(self):
        source = FakeSource({QUESTION: SEARCH, "قاعة": ARABIC_HIT})
        llm = FakeLLM(judge=judge(False, ["قاعة"]))

        state = answer_graph.gather(source, llm)

        self.assertEqual([entry["round"] for entry in state["rounds_log"]], [1, 2])
        self.assertEqual(state["rounds_log"][1]["queries"], ["قاعة"])
        self.assertIn("span-31", state["rounds_log"][1]["sources"])
        self.assertEqual(llm.prompts, [])  # 답은 쓰지 않는다


class Check(unittest.TestCase):
    def test_모델을_부르기_전마다_확인하고_멈추라면_멈춘다(self):
        calls = []

        def check():
            calls.append(1)
            if len(calls) == 2:
                raise Stopping("SIGTERM")

        llm = FakeLLM(GOOD)
        with self.assertRaises(Stopping):
            answer_graph.run(FakeSource(), llm, check=check)
        self.assertEqual((len(llm.plans), len(llm.judges)), (1, 0))


class _JobServer:
    """예전 answer.gather(server, lease, question)에 FakeSource와 같은 답변서를 준다."""

    def search(self, lease, query, k=8):
        return SEARCH if query == QUESTION else EMPTY

    def trace(self, lease, node, direction="BASIS", depth=2):
        if node["id"] == BUDGET["id"]:
            raise ServerError("404")
        return TRACE

    def state(self, lease):
        return STATE


if __name__ == "__main__":
    unittest.main()
