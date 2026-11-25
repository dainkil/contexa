"""정답 라벨이 합성 문서와 맞물리는지. 발언 번호를 하나 잘못 적으면 평가 숫자 전체가 틀어지므로 먼저 막는다."""

import json
import unittest
from pathlib import Path

from contexa_ai import extract

from eval import scoring
from eval.common import languages_of, load_corpus, load_labels

ONTOLOGY = json.loads((Path(__file__).parent / "ontology.json").read_text(encoding="utf-8"))


class LabelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _, documents = load_corpus()
        cls.documents = {document.id: document for document in documents}
        cls.spans = {span.id: span for document in documents for span in document.spans}
        cls.extract = load_labels("extract")
        cls.retrieval = load_labels("retrieval")

    def test_뽑기_라벨의_근거_발언이_모두_있다(self):
        for meeting in self.extract["meetings"]:
            count = len(self.documents[meeting["document"]].spans)
            numbers = [n for card in meeting["cards"] for n in card["evidence"]]
            numbers += [n for link in meeting["links"] for n in link["evidence"]] + meeting["not_decided"]
            with self.subTest(meeting=meeting["document"]):
                self.assertTrue(all(1 <= n <= count for n in numbers), numbers)
                self.assertTrue(all(card["evidence"] for card in meeting["cards"]))

    def test_카드_id가_겹치지_않고_종류가_올바르다(self):
        ids = [card["id"] for meeting in self.extract["meetings"] for card in meeting["cards"]]
        self.assertEqual(len(ids), len(set(ids)))
        for meeting in self.extract["meetings"]:
            for card in meeting["cards"]:
                with self.subTest(card=card["id"]):
                    self.assertIn(card["type"], scoring.CARD_TYPES)
                    if card["type"] == "DECISION_EVENT":
                        self.assertTrue(set(card["kinds"]) <= set(extract.EVENT_KINDS))
                    if card["type"] == "ENTITY":
                        self.assertIn(card["kind"], extract.ENTITY_KINDS)

    def test_줄의_양_끝은_이_회의의_카드_이미_있는_카드_이_회의의_발언_중_하나다(self):
        meetings = self.extract["meetings"]
        for index, meeting in enumerate(meetings):
            known = {card["id"] for card in meeting["cards"]}
            known |= {card["id"] for card in scoring.existing_cards(meetings, index)}
            known |= {span.id for span in self.documents[meeting["document"]].spans}
            for link in meeting["links"]:
                with self.subTest(link=link):
                    self.assertIn(link["from"], known)
                    self.assertIn(link["to"], known)

    def test_정답을_답으로_넣으면_일꾼이_버리는_것_없이_모두_맞는다(self):
        # 정답 줄이 규칙표에 맞는지, 채점이 정답을 정답으로 세는지를 함께 확인한다
        meetings = self.extract["meetings"]
        results = []
        for index, meeting in enumerate(meetings):
            existing = scoring.existing_cards(meetings, index)
            spans = scoring.spans_of(self.documents[meeting["document"]])
            state = scoring.state_of(existing)
            output = scoring.oracle_output(meeting, existing)
            extraction = extract.to_candidates(output, spans, state, ONTOLOGY)
            self.assertEqual(extraction.dropped, [], meeting["document"])
            results.append(scoring.score_meeting(meeting, existing, output, extraction, len(spans)))
        summary = scoring.summarize(results)
        self.assertEqual(summary["cards"]["ALL"]["precision"], 1.0)
        self.assertEqual(summary["cards"]["ALL"]["recall"], 1.0)
        self.assertEqual(summary["links"]["ALL"]["precision"], 1.0)
        self.assertEqual(summary["links"]["ALL"]["recall"], 1.0)
        self.assertEqual(summary["cross_links"]["recall"], 1.0)
        self.assertEqual(summary["misclassified_as_decided"], 0)
        self.assertEqual(summary["duplicates"], 0)

    def test_확정되지_않은_발언에는_확정_사건_정답이_없다(self):
        for meeting in self.extract["meetings"]:
            undecided = set(meeting["not_decided"])
            for card in meeting["cards"]:
                if card["type"] == "DECISION_EVENT" and set(card["kinds"]) & scoring.DECIDED:
                    with self.subTest(card=card["id"]):
                        self.assertFalse(set(card["evidence"]) <= undecided)

    def test_검색_라벨의_발언이_모두_있고_언어마다_질문이_있다(self):
        queries = self.retrieval["queries"]
        self.assertEqual(len({query["id"] for query in queries}), len(queries))
        for query in queries:
            with self.subTest(query=query["id"]):
                self.assertTrue(query["relevant"])
                for span_id in query["relevant"]:
                    self.assertIn(span_id, self.spans)
                self.assertTrue(set(query["langs"]) <= {"ko", "en", "ar"})
        groups = {"mixed" if len(q["langs"]) > 1 else q["langs"][0] for q in queries}
        self.assertEqual(groups, {"ko", "en", "ar", "mixed"})

    def test_검색_라벨에_다른_언어로_찾아야_하는_발언이_들어_있다(self):
        cross = [(query["id"], span_id) for query in self.retrieval["queries"] for span_id in query["relevant"]
                 if not set(query["langs"]) & languages_of(self.spans[span_id].text)]
        self.assertGreaterEqual(len(cross), 10, cross)


if __name__ == "__main__":
    unittest.main()
