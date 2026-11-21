import unittest

from contexa_ai import answer
from contexa_ai.llm import LLMRefused
from contexa_ai.server import Lease, ServerError, ServerRejected
from contexa_ai.worker import Worker
from tests.test_worker import FakeLLM

PRINT = {"type": "TASK", "id": "task-print", "revision": 4}
BUDGET = {"type": "CONDITION", "id": "cond-budget", "revision": 2}
ARABIC = {"type": "TASK", "id": "task-arabic", "revision": 1}
SPAN22 = {"type": "SPAN", "id": "span-22", "revision": 1}
SPAN23 = {"type": "SPAN", "id": "span-23", "revision": 1}


def fact(node, label, status, states, evidence):
    return {"node": node, "label": label, "status": status, "states": states,
            "evidence": [{"span": span, "edgeId": "e", "status": edge_status} for span, edge_status in evidence]}


def packet(facts, relations, sources, open_issues=()):
    return {"facts": facts, "relations": relations, "sources": sources, "open": list(open_issues)}


SOURCE22 = {"span": SPAN22, "documentId": "doc", "seq": 1, "speaker": "민지", "text": "예산 승인 전까지 인쇄는 보류입니다."}
SOURCE23 = {"span": SPAN23, "documentId": "doc", "seq": 2, "speaker": None, "text": "인쇄 발주는 그다음입니다."}
REQUIRES = {"id": "edge-req", "version": 2, "rel": "REQUIRES", "src": PRINT, "dst": BUDGET, "status": "CONFIRMED"}
NEEDS_ARABIC = {"id": "edge-arabic", "version": 1, "rel": "REQUIRES", "src": PRINT, "dst": ARABIC, "status": "CANDIDATE"}

SEARCH = packet(
    [fact(BUDGET, "인쇄 예산 승인", "CONFIRMED", {"state": "UNMET"}, [(SPAN22, "CONFIRMED")]),
     fact(PRINT, "인쇄 발주", "CONFIRMED", {"workStatus": "PLANNED"}, [(SPAN23, "CONFIRMED")])],
    [], [SOURCE22, SOURCE23])
TRACE = packet(
    [fact(PRINT, "인쇄 발주", "CONFIRMED", {"workStatus": "PLANNED"}, [(SPAN23, "CONFIRMED")]),
     fact(BUDGET, "인쇄 예산 승인", "CONFIRMED", {"state": "UNMET"}, [(SPAN22, "CONFIRMED")]),
     fact(ARABIC, "아랍어 문구 검토", "CANDIDATE", {"workStatus": "PLANNED"}, [(SPAN23, "CANDIDATE")])],
    [REQUIRES, NEEDS_ARABIC], [SOURCE22, SOURCE23],
    [{"kind": "UNMET_CONDITION", "node": BUDGET, "edgeId": None}])
STATE = packet([], [], [], [{"kind": "UNMET_CONDITION", "node": BUDGET, "edgeId": None}])


class FakeServer:
    """안내 데스크 흉내. 정해 둔 답변서를 돌려주고, 일꾼이 낸 글과 실패를 기억한다."""

    def __init__(self, reject=()):
        self._lease = Lease("job-1", "GENERATE", "project-1", "attempt-1", "lease-token")
        self._reject = list(reject)
        self.searched = []
        self.traced = []
        self.submitted = []
        self.failures = []

    def claim(self):
        lease, self._lease = self._lease, None
        return lease

    def request(self, lease):
        return {"artifactId": "a-1", "kind": "ANSWER", "question": "인쇄 진행해도 돼?", "lang": "ko"}

    def search(self, lease, query, k=8):
        self.searched.append(query)
        return SEARCH

    def trace(self, lease, node, direction="BASIS", depth=2):
        self.traced.append(node["id"])
        if node["id"] == BUDGET["id"]:
            raise ServerError("404: 카드가 없다")
        return TRACE

    def state(self, lease):
        return STATE

    def submit(self, lease, result):
        if self._reject:
            raise ServerRejected(self._reject.pop(0))
        self.submitted.append(result)

    def fail(self, lease, reason, retryable):
        self.failures.append((reason, retryable))


GOOD = {"answer": "예산 승인 전이라 인쇄는 보류입니다 [s1]. 인쇄 발주는 그다음입니다 [s2][s1].",
        "used_facts": ["f1", "f2"], "used_relations": ["r1"], "cited_sources": ["s1", "s2"]}


class Gather(unittest.TestCase):
    def test_근거를_모아_카드_줄_발언에_이름표를_붙인다(self):
        server = FakeServer()
        context = answer.gather(server, server.claim(), "인쇄 진행해도 돼?")

        self.assertEqual(server.searched, ["인쇄 진행해도 돼?"])
        # 검색에 걸린 카드마다 줄을 따라가 본다. 그사이 보이지 않게 된 카드는 건너뛴다
        self.assertEqual(server.traced, ["cond-budget", "task-print"])
        self.assertEqual([f["label"] for f in context.facts.values()], ["인쇄 예산 승인", "인쇄 발주", "아랍어 문구 검토"])
        self.assertEqual(list(context.relations), ["r1", "r2"])
        self.assertEqual(context.sources["s1"]["text"], "예산 승인 전까지 인쇄는 보류입니다.")
        # 같은 것이 여러 답변서에 나와도 한 번만 담는다
        self.assertEqual(len(context.open), 1)

    def test_모델에게는_이름표와_확인_상태를_보여_주고_실제_id는_보여_주지_않는다(self):
        server = FakeServer()
        context = answer.gather(server, server.claim(), "인쇄 진행해도 돼?")
        prompt = answer.build_user_prompt(server.request(None), context)

        self.assertIn("f1: [CONDITION] 인쇄 예산 승인 — 확인됨; state=UNMET; 근거: s1", prompt)
        self.assertIn("f3: [TASK] 아랍어 문구 검토 — AI 후보(사람이 아직 확인하지 않음); workStatus=PLANNED; 근거: s2(AI 후보)", prompt)
        self.assertIn("r1: f2 -REQUIRES-> f1 (확인됨)", prompt)
        self.assertIn("r2: f2 -REQUIRES-> f3 (AI 후보(사람이 아직 확인하지 않음))", prompt)
        self.assertIn("s1: [민지] 예산 승인 전까지 인쇄는 보류입니다.", prompt)
        self.assertIn("- 아직 충족되지 않은 조건: f1", prompt)
        self.assertIn("답할 언어: 한국어", prompt)
        self.assertIn("질문: 인쇄 진행해도 돼?", prompt)
        for real_id in ("task-print", "cond-budget", "span-22", "edge-req"):
            self.assertNotIn(real_id, prompt)


class ToDraft(unittest.TestCase):

    def test_언어를_정하지_않은_질문에는_답할_언어를_적지_않는다(self):
        server = FakeServer()
        context = answer.gather(server, server.claim(), "When do we print?")
        request = {"artifactId": "a-1", "kind": "ANSWER", "question": "When do we print?", "lang": None}
        prompt = answer.build_user_prompt(request, context)

        self.assertNotIn("답할 언어", prompt)
        self.assertIn("질문: When do we print?", prompt)
        self.assertIn("질문을 쓴 언어로 답합니다", answer.SYSTEM_PROMPT)
        self.assertIn("근거 발언은 번역하지 않습니다", answer.SYSTEM_PROMPT)

    def setUp(self):
        server = FakeServer()
        self.context = answer.gather(server, server.claim(), "인쇄 진행해도 돼?")

    def test_인용은_등장한_순서대로_번호가_되고_참고한_것은_읽은_버전과_함께_낸다(self):
        draft = answer.to_draft(GOOD, self.context)

        self.assertEqual(draft["content"], "예산 승인 전이라 인쇄는 보류입니다 [1]. 인쇄 발주는 그다음입니다 [2][1].")
        self.assertEqual(draft["citations"], [SPAN22, SPAN23])
        self.assertEqual(draft["usedEdges"], [{"id": "edge-req", "version": 2}])
        self.assertCountEqual(draft["usedNodes"], [PRINT, BUDGET, SPAN22, SPAN23])

    def test_줄을_썼으면_그_양끝_카드도_쓴_것으로_친다(self):
        draft = answer.to_draft({"answer": "아랍어 문구 검토가 먼저일 수 있습니다(미확인).", "used_facts": [],
                                 "used_relations": ["r2", "r99"], "cited_sources": []}, self.context)

        self.assertCountEqual(draft["usedNodes"], [PRINT, ARABIC])
        self.assertEqual(draft["usedEdges"], [{"id": "edge-arabic", "version": 1}])
        self.assertEqual(draft["citations"], [])

    def test_보여_주지_않은_발언을_인용했거나_본문이_비었으면_거절한다(self):
        with self.assertRaisesRegex(answer.AnswerInvalid, r"\[s9\]"):
            answer.to_draft({**GOOD, "answer": "인쇄는 승인됐습니다 [s9]."}, self.context)
        with self.assertRaises(answer.AnswerInvalid):
            answer.to_draft({**GOOD, "answer": "  "}, self.context)

    def test_없는_이름표는_무시한다(self):
        draft = answer.to_draft({"answer": "기록에서 찾을 수 없습니다.", "used_facts": ["f42"], "used_relations": [],
                                 "cited_sources": ["s42"]}, self.context)

        self.assertEqual(draft, {"content": "기록에서 찾을 수 없습니다.", "usedNodes": [], "usedEdges": [], "citations": []})


class GenerateJob(unittest.TestCase):
    def test_답을_써서_낸다(self):
        server = FakeServer()
        llm = FakeLLM(GOOD)

        Worker(server, llm).run_once()

        self.assertEqual(server.submitted[0]["draft"]["citations"], [SPAN22, SPAN23])
        self.assertEqual(server.failures, [])
        self.assertEqual(len(llm.prompts), 1)

    def test_없는_발언을_인용하면_이유를_알려_주고_한_번_다시_쓰게_한다(self):
        server = FakeServer()
        llm = FakeLLM({**GOOD, "answer": "승인됐습니다 [s9]."}, GOOD)

        Worker(server, llm).run_once()

        self.assertIn("<sources>에 없는 발언을 인용했다: [s9]", llm.prompts[1])
        self.assertEqual(len(server.submitted), 1)
        self.assertEqual(server.failures, [])

    def test_서버가_낡은_근거라고_거절하면_근거부터_다시_읽고_다시_쓴다(self):
        server = FakeServer(reject=["글을 쓰는 사이에 참고한 카드·발언·줄 1개가 바뀌었다"])
        llm = FakeLLM(GOOD, GOOD)

        Worker(server, llm).run_once()

        self.assertEqual(len(server.searched), 2)
        self.assertEqual(len(server.submitted), 1)
        self.assertEqual(server.failures, [])

    def test_두_번_다_안_되면_끝난_실패로_알린다(self):
        server = FakeServer(reject=["바뀌었다", "또 바뀌었다"])

        Worker(server, FakeLLM(GOOD, GOOD)).run_once()

        self.assertEqual(server.submitted, [])
        self.assertEqual(server.failures, [("또 바뀌었다", False)])

    def test_모델이_거절하면_끝난_실패로_알린다(self):
        server = FakeServer()

        Worker(server, FakeLLM(LLMRefused("거절"))).run_once()

        self.assertEqual(server.failures, [("거절", False)])


if __name__ == "__main__":
    unittest.main()
