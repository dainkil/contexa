import threading
import time
import unittest

from contexa_ai import extract_graph, tracing
from contexa_ai.llm import LLMRefused
from contexa_ai.server import ServerRejected
from contexa_ai.worker import Stopping
from tests.test_extract import ONTOLOGY, STATE, card, link

DOCUMENT = {"document": {"title": "긴 회의", "kind": "TRANSCRIPT"}}


def spans(count):
    return [{"seq": n, "speakerLabel": "민지", "text": f"발언 {n}",
             "ref": {"type": "SPAN", "id": f"00000000-0000-0000-0000-{n:012d}", "revision": 1}}
            for n in range(1, count + 1)]


def window_of(user):
    """모델에게 보여 준 발언 번호로 어느 창인지 알아낸다."""
    return [int(line.split(":")[0][1:]) for line in user.splitlines() if line.startswith("s") and ":" in line
            and line.split(":")[0][1:].isdigit()]


class ScriptedLLM:
    """창마다(첫 발언 번호로 구분) 정해 둔 답을 준다. 함수를 주면 그 자리에서 부른다."""

    def __init__(self, by_first_seq):
        self._by_first = by_first_seq
        self.calls = []
        self._lock = threading.Lock()

    def structured(self, system, user, schema):
        seqs = window_of(user)
        with self._lock:
            self.calls.append((seqs[0], user))
        answer = self._by_first[seqs[0]]
        if callable(answer):
            answer = answer(user)
        if isinstance(answer, BaseException):
            raise answer
        return answer


class Windows(unittest.TestCase):
    def test_발언을_겹치게_자른다(self):
        seqs = [[span["seq"] for span in window] for window in extract_graph.windows_of(spans(5), 2, 1)]
        self.assertEqual(seqs, [[1, 2], [2, 3], [3, 4], [4, 5]])

    def test_짧은_문서는_창_하나고_빈_문서는_창이_없다(self):
        self.assertEqual(len(extract_graph.windows_of(spans(3), 200, 20)), 1)
        self.assertEqual(extract_graph.windows_of([], 200, 20), [])

    def test_겹침이_창보다_커도_앞으로_간다(self):
        self.assertEqual(len(extract_graph.windows_of(spans(3), 1, 20)), 3)


class Merge(unittest.TestCase):
    def run_windows(self, answers, count=4, window=3, overlap=1, **kwargs):
        llm = ScriptedLLM(answers)
        result = extract_graph.run(llm, DOCUMENT, spans(count), STATE, ONTOLOGY, window=window, overlap=overlap,
                                   **kwargs)
        return result, llm

    def test_겹치는_곳에서_두_번_나온_카드는_하나로_합치고_줄은_합친_카드로_잇는다(self):
        # 창 0: 발언 1~3, 창 1: 발언 3~4. 발언 3의 할 일이 두 창에 다 나온다(띄어쓰기·대소문자가 조금 다르다)
        result, _ = self.run_windows({
            1: {"cards": [card("c1", "TASK", "Print  order", [3]), card("c2", "CONDITION", "예산 승인", [1])],
                "links": [link("REQUIRES", "c1", "c2", [1])]},
            3: {"cards": [card("c1", "TASK", "print order", [3, 4]), card("c2", "CONDITION", "예산 승인", [4])],
                "links": [link("REQUIRES", "c1", "c2", [4])]},
        })

        nodes = result.extraction.candidates["nodes"]
        self.assertEqual([node["key"] for node in nodes], ["w0:c1", "w0:c2"])
        requires = [edge for edge in result.extraction.candidates["edges"] if edge["rel"] == "REQUIRES"]
        self.assertEqual(len(requires), 1)
        self.assertEqual(requires[0]["srcKey"], "w0:c1")
        self.assertEqual(requires[0]["evidenceSpanIds"], [spans(4)[0]["ref"]["id"], spans(4)[3]["ref"]["id"]])
        evidenced = {edge["dstRef"]["id"] for edge in result.extraction.candidates["edges"]
                     if edge["rel"] == "EVIDENCED_BY" and edge["srcKey"] == "w0:c1"}
        self.assertEqual(evidenced, {spans(4)[2]["ref"]["id"], spans(4)[3]["ref"]["id"]})
        # 평가가 채점에 쓰는 원래 답에도 합친 근거가 남는다
        self.assertEqual({c["key"]: c["evidence"] for c in result.output["cards"]}["w0:c1"], [3, 4])
        self.assertEqual(result.windows, 2)

    def test_글이_달라도_근거_발언이_똑같으면_합친다(self):
        result, _ = self.run_windows({
            1: {"cards": [card("c1", "CONDITION", "예산 승인", [3])], "links": []},
            3: {"cards": [card("c9", "CONDITION", "예산이 승인되면", [3])], "links": []},
        })

        self.assertEqual([node["key"] for node in result.extraction.candidates["nodes"]], ["w0:c1"])

    def test_인물은_이름이_같을_때만_합친다(self):
        result, _ = self.run_windows({
            1: {"cards": [card("p1", "ENTITY", "أحمد", [3], entity_kind="PERSON"),
                          card("p2", "ENTITY", "Ahmed", [3], entity_kind="PERSON")], "links": []},
            # 모음 기호가 붙은 같은 이름은 합치고, 근거만 같은 다른 이름은 합치지 않는다
            3: {"cards": [card("p1", "ENTITY", "أَحْمَد", [3], entity_kind="PERSON"),
                          card("p2", "ENTITY", "아흐마드", [3], entity_kind="PERSON")], "links": []},
        })

        names = [node["fields"]["name"] for node in result.extraction.candidates["nodes"]]
        self.assertEqual(names, ["أحمد", "Ahmed", "아흐마드"])

    def test_같은_창_안의_카드끼리는_합치지_않는다(self):
        result, _ = self.run_windows({
            1: {"cards": [card("c1", "TASK", "인쇄 발주", [1]), card("c2", "TASK", "인쇄 발주", [2])], "links": []},
            3: {"cards": [], "links": []},
        })

        self.assertEqual(len(result.extraction.candidates["nodes"]), 2)

    def test_근거가_같은_카드가_여럿이면_어느_것과도_합치지_않는다(self):
        result, _ = self.run_windows({
            1: {"cards": [card("c1", "TASK", "인쇄 발주", [3]), card("c2", "TASK", "포스터 수정", [3])], "links": []},
            3: {"cards": [card("c1", "TASK", "인쇄물 준비", [3])], "links": []},
        })

        self.assertEqual(len(result.extraction.candidates["nodes"]), 3)


class Concurrency(unittest.TestCase):
    def test_창들은_동시에_돌고_작업_맥락을_그대로_가진다(self):
        barrier = threading.Barrier(2, timeout=5)
        seen = []

        def meet(user):
            barrier.wait()  # 차례로 돌면 여기서 기다리다 실패한다
            seen.append(tracing.current().job_id)
            return {"cards": [], "links": []}

        with tracing.job("job-7", "EXTRACT", "project-1"):
            extract_graph.run(ScriptedLLM({1: meet, 3: meet}), DOCUMENT, spans(4), STATE, ONTOLOGY,
                              window=3, overlap=1, concurrency=2)

        self.assertEqual(seen, ["job-7", "job-7"])

    def test_한_창이_실패하면_그_실패가_올라오고_다른_창의_결과는_내지_않는다(self):
        def slow(user):
            time.sleep(0.2)
            return {"cards": [], "links": []}

        submitted = []
        with self.assertRaisesRegex(LLMRefused, "거절"):
            extract_graph.run(ScriptedLLM({1: LLMRefused("거절"), 3: slow}), DOCUMENT, spans(4), STATE, ONTOLOGY,
                              window=3, overlap=1, concurrency=2, submit=submitted.append)
        self.assertEqual(submitted, [])

    def test_꺼지라는_신호도_그대로_올라온다(self):
        with self.assertRaises(Stopping):
            extract_graph.run(ScriptedLLM({1: Stopping("SIGTERM"), 3: {"cards": [], "links": []}}), DOCUMENT,
                              spans(4), STATE, ONTOLOGY, window=3, overlap=1, concurrency=2)

    def test_멈추라고_하면_아직_시작하지_않은_창은_모델을_부르지_않는다(self):
        stop = threading.Event()

        def first(user):
            stop.set()
            return {"cards": [], "links": []}

        def check():
            if stop.is_set():
                raise Stopping("SIGTERM")

        llm = ScriptedLLM({1: first, 3: {"cards": [], "links": []}, 5: {"cards": [], "links": []}})
        with self.assertRaises(Stopping):
            extract_graph.run(llm, DOCUMENT, spans(6), STATE, ONTOLOGY, window=3, overlap=1, check=check)
        self.assertEqual([seq for seq, _ in llm.calls], [1])


class Repair(unittest.TestCase):
    def test_거절_이유에_나온_창만_이유를_알려_주고_다시_뽑는다(self):
        answers = {1: {"cards": [card("c1", "TASK", "인쇄 발주", [1])], "links": []},
                   3: {"cards": [card("c1", "TASK", "포스터 수정", [4])], "links": []}}
        submitted = []

        def submit(candidates):
            if not submitted:
                submitted.append(candidates)
                raise ServerRejected("같은 key가 두 번 나왔다: w1:c1")
            submitted.append(candidates)

        llm = ScriptedLLM(answers)
        result = extract_graph.run(llm, DOCUMENT, spans(4), STATE, ONTOLOGY, window=3, overlap=1, submit=submit)

        self.assertEqual([seq for seq, _ in llm.calls], [1, 3, 3])
        self.assertIn("같은 key가 두 번 나왔다: w1:c1", llm.calls[2][1])
        self.assertEqual([node["key"] for node in submitted[1]["nodes"]], ["w0:c1", "r1:c1"])
        self.assertEqual(result.repairs, 1)

    def test_두_번째도_거절되면_그대로_올린다(self):
        def submit(candidates):
            raise ServerRejected("자료실에 없는 발언을 근거로 댔다: x")

        llm = ScriptedLLM({1: {"cards": [], "links": []}})
        with self.assertRaisesRegex(ServerRejected, "자료실에 없는"):
            extract_graph.run(llm, DOCUMENT, spans(2), STATE, ONTOLOGY, submit=submit)
        self.assertEqual(len(llm.calls), 2)

    def test_문제의_창을_거절_이유로_찾는다(self):
        windows = extract_graph.windows_of(spans(5), 3, 1)  # 발언 1~3, 3~5
        results = {}
        span4 = spans(5)[3]["ref"]["id"]
        span3 = spans(5)[2]["ref"]["id"]
        self.assertEqual(extract_graph.windows_to_redo("줄이 가리키는 카드 후보가 없다: r1:c2", windows, results), [1])
        self.assertEqual(extract_graph.windows_to_redo(f"읽을 수 없는 문서의 발언을 근거로 댈 수 없다: {span4}",
                                                       windows, results), [1])
        self.assertEqual(extract_graph.windows_to_redo(f"자료실에 없는 발언을 근거로 댔다: {span3}", windows, results),
                         [0, 1])
        self.assertEqual(extract_graph.windows_to_redo("규칙표에 없는 줄이다", windows, results), [0, 1])


if __name__ == "__main__":
    unittest.main()
