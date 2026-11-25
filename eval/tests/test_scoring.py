"""채점 규칙: 무엇을 맞음·틀림·놓침으로 세는지."""

import json
import unittest
from pathlib import Path

from contexa_ai import extract

from eval import scoring
from eval.common import Document, Span, languages_of, normalize
from eval.retrieval import score as score_retrieval

ONTOLOGY = json.loads((Path(__file__).parent / "ontology.json").read_text(encoding="utf-8"))

DOC = Document("m9", "9차 회의", "TRANSCRIPT", [
    Span("m9:1", "m9", 1, "민지", "행사를 온라인으로 하면 어떨까요?"),
    Span("m9:2", "m9", 2, "지훈", "날짜는 12월 5일로 옮깁니다."),
    Span("m9:3", "m9", 3, "민지", "포스터는 Sara가 맡아 주세요."),
])
EXISTING = [
    {"id": "m1.date", "type": "DECISION_ITEM", "text": "행사 날짜", "evidence": [1]},
    {"id": "m1.nov28", "type": "DECISION_EVENT", "kinds": ["DECISION"], "text": "11월 28일", "evidence": [1]},
    {"id": "m1.sara", "type": "ENTITY", "kind": "PERSON", "names": ["Sara", "사라"], "evidence": [1]},
]
MEETING = {
    "document": "m9",
    "not_decided": [1],
    "cards": [
        {"id": "m9.online", "type": "DECISION_EVENT", "kinds": ["PROPOSAL"], "text": "온라인 제안", "evidence": [1]},
        {"id": "m9.dec5", "type": "DECISION_EVENT", "kinds": ["CHANGE", "DECISION"], "text": "12월 5일", "evidence": [2]},
        {"id": "m9.poster", "type": "TASK", "text": "포스터", "evidence": [3]},
        {"id": "m9.view", "type": "DECISION_EVENT", "kinds": ["DISCUSSION"], "text": "의견", "evidence": [3],
         "optional": True},
    ],
    "links": [
        {"rel": "HAS_EVENT", "from": "m1.date", "to": "m9.dec5", "evidence": [2]},
        {"rel": "SUPERSEDES", "from": "m9.dec5", "to": "m1.nov28", "evidence": [2]},
        {"rel": "ASSIGNED_TO", "from": "m9.poster", "to": "m1.sara", "evidence": [3]},
    ],
}


def card(key, type_, text, evidence, kind="NONE", entity_kind="NONE"):
    return {"key": key, "type": type_, "text": text, "event_kind": kind, "entity_kind": entity_kind,
            "occurred_at": "", "evidence": evidence}


def link(rel, src, dst, evidence):
    return {"rel": rel, "from": src, "to": dst, "evidence": evidence}


def score(output):
    spans = scoring.spans_of(DOC)
    state = scoring.state_of(EXISTING)
    extraction = extract.to_candidates(output, spans, state, ONTOLOGY)
    return scoring.score_meeting(MEETING, EXISTING, output, extraction, len(spans))


class ScoringTest(unittest.TestCase):
    def test_근거가_겹치고_종류가_같으면_글이_달라도_맞다(self):
        result = score({"cards": [card("a", "DECISION_EVENT", "온라인으로 하자는 의견", [1], "PROPOSAL"),
                                  card("b", "DECISION_EVENT", "행사일 변경", [2], "CHANGE"),
                                  card("c", "TASK", "포스터 담당", [3])],
                        "links": [link("HAS_EVENT", "x1", "b", [2]), link("SUPERSEDES", "b", "x2", [2]),
                                  link("ASSIGNED_TO", "c", "x3", [3])]})
        self.assertEqual(result["cards"]["DECISION_EVENT"], {"tp": 2, "fp": 0, "fn": 0})
        self.assertEqual(result["links"]["SUPERSEDES"], {"tp": 1, "fp": 0, "fn": 0})
        self.assertEqual(result["cross_links"], {"tp": 3, "fp": 0, "fn": 0})
        self.assertEqual(result["event_kind"], {"correct": 2, "total": 2})

    def test_제안을_확정으로_뽑으면_오분류로_센다(self):
        result = score({"cards": [card("a", "DECISION_EVENT", "온라인 개최", [1], "DECISION")], "links": []})
        self.assertEqual(len(result["misclassified"]), 1)
        # 근거는 맞으므로 카드는 짝지어지지만 종류는 틀렸다
        self.assertEqual(result["cards"]["DECISION_EVENT"]["tp"], 1)
        self.assertEqual(result["event_kind"], {"correct": 0, "total": 1})

    def test_이미_있는_사람을_새로_만들고_그쪽으로_담당을_걸면_중복이고_줄도_틀린다(self):
        result = score({"cards": [card("c", "TASK", "포스터", [3]), card("p", "ENTITY", "사라", [3], entity_kind="PERSON")],
                        "links": [link("ASSIGNED_TO", "c", "p", [3])]})
        self.assertEqual(result["duplicates"], ["[ENTITY] 사라"])
        self.assertEqual(result["links"]["ASSIGNED_TO"], {"tp": 0, "fp": 1, "fn": 1})

    def test_optional_정답은_맞혀도_틀려도_세지_않는다(self):
        result = score({"cards": [card("c", "TASK", "포스터", [3]), card("v", "DECISION_EVENT", "의견", [3], "DISCUSSION")],
                        "links": []})
        self.assertEqual(result["cards"]["DECISION_EVENT"], {"tp": 0, "fp": 0, "fn": 2})
        self.assertEqual(result["cards"]["TASK"], {"tp": 1, "fp": 0, "fn": 0})

    def test_근거가_정답_밖까지_걸치면_짝은_되지만_근거_정확에서는_틀린다(self):
        result = score({"cards": [card("c", "TASK", "포스터", [2, 3])], "links": []})
        self.assertEqual(result["cards"]["TASK"]["tp"], 1)
        self.assertEqual(result["evidence"], {"correct": 0, "total": 1})

    def test_정답에_없는_카드와_줄은_틀림이다(self):
        result = score({"cards": [card("t", "TOPIC", "행사", [1]), card("c", "TASK", "포스터", [3])],
                        "links": [link("ASSIGNED_TO", "c", "x3", [3]), link("REQUIRES", "c", "c", [3])]})
        self.assertEqual(result["cards"]["TOPIC"], {"tp": 0, "fp": 1, "fn": 0})
        self.assertEqual(result["links"]["ASSIGNED_TO"]["tp"], 1)


class CommonTest(unittest.TestCase):
    def test_언어_판별은_이름_하나가_섞인_것은_언어로_치지_않는다(self):
        self.assertEqual(languages_of("그럼 인쇄 발주는 Sara가 맡아 주세요."), {"ko"})
        self.assertEqual(languages_of("아랍어권 학생 invitation list는 제가 정리할게요."), {"ko", "en"})
        self.assertEqual(languages_of("سأراجع النص العربي للدعوة قبل الطباعة."), {"ar"})
        self.assertEqual(languages_of("OK, then the invitations go out on Monday."), {"en"})

    def test_정규화는_아랍어_모음_기호와_알리프_변형을_없앤다(self):
        self.assertEqual(normalize("أَحْمَد"), normalize("احمد"))
        self.assertEqual(normalize("  Ahmed   HASSAN "), "ahmed hassan")


class RetrievalScoreTest(unittest.TestCase):
    def test_질문마다_찾은_정답의_비율을_평균하고_같은_언어와_다른_언어를_나눠_센다(self):
        spans = {span.id: span for span in DOC.spans}
        spans["e:1"] = Span("e:1", "e", 1, "Sara", "I will design the poster.")
        queries = [{"id": "q1", "langs": ["ko"], "query": "포스터", "relevant": ["m9:3", "e:1"]},
                   {"id": "q2", "langs": ["en"], "query": "date", "relevant": ["m9:2"]}]
        result = score_retrieval(queries, {"q1": ["m9:3", "m9:1"], "q2": []}, spans, 8)
        self.assertEqual(result["groups"]["ko"]["recall@8"], 0.5)
        self.assertEqual(result["groups"]["ALL"]["recall@8"], 0.25)
        self.assertEqual(result["groups"]["ALL"]["hit@8"], 0.5)
        self.assertEqual(result["same_language"], {"found": 1, "total": 1, "rate": 1.0})
        self.assertEqual(result["cross_language"], {"found": 0, "total": 2, "rate": 0.0})


if __name__ == "__main__":
    unittest.main()
