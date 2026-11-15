import unittest

from contexa_ai import extract

ONTOLOGY = {"version": "v1", "rules": [
    {"rel": "HAS_EVENT", "sources": ["DECISION_ITEM"], "targets": ["DECISION_EVENT"]},
    {"rel": "REQUIRES", "sources": ["DECISION_ITEM", "TASK"], "targets": ["CONDITION"]},
    {"rel": "REQUIRES", "sources": ["TASK"], "targets": ["TASK"]},
    {"rel": "EVIDENCED_BY", "sources": ["DECISION_EVENT", "CONDITION", "TASK"], "targets": ["SPAN"]},
    {"rel": "ASSIGNED_TO", "sources": ["TASK"], "targets": ["ENTITY"]},
    {"rel": "MENTIONS", "sources": ["SPAN"], "targets": ["ENTITY"]},
    {"rel": "SUPERSEDES", "sources": ["DECISION_EVENT"], "targets": ["DECISION_EVENT"]},
]}
SPANS = [
    {"seq": 1, "speakerLabel": "민지", "text": "예산 승인 전까지 인쇄는 보류입니다.", "ref": {"type": "SPAN", "id": "span-1", "revision": 1}},
    {"seq": 2, "speakerLabel": None, "text": "아랍어 문구는 아흐마드 씨가 봐 주세요.", "ref": {"type": "SPAN", "id": "span-2", "revision": 1}},
]
STATE = {"facts": [
    {"node": {"type": "DECISION_ITEM", "id": "item-1", "revision": 3}, "label": "개최 방식", "status": "CONFIRMED",
     "states": {"decisionState": "DECIDED"}},
    {"node": {"type": "ENTITY", "id": "ent-1", "revision": 2}, "label": "Ahmed Hassan", "status": "CANDIDATE",
     "states": {"kind": "PERSON"}},
]}


def card(key, type_, text, evidence, event_kind="NONE", entity_kind="NONE", occurred_at=""):
    return {"key": key, "type": type_, "text": text, "event_kind": event_kind, "entity_kind": entity_kind,
            "occurred_at": occurred_at, "evidence": evidence}


def link(rel, src, dst, evidence):
    return {"rel": rel, "from": src, "to": dst, "evidence": evidence}


class ToCandidates(unittest.TestCase):
    def run_it(self, cards, links):
        return extract.to_candidates({"cards": cards, "links": links}, SPANS, STATE, ONTOLOGY)

    def test_카드의_근거는_근거_줄로_바뀌고_번호는_실제_id가_된다(self):
        result = self.run_it(
            [card("c1", "TASK", "인쇄 발주", [1]), card("c2", "CONDITION", "예산 승인", [1])],
            [link("REQUIRES", "c1", "c2", [1])])

        self.assertEqual(result.dropped, [])
        self.assertEqual(result.candidates["nodes"], [
            {"key": "c1", "type": "TASK", "fields": {"title": "인쇄 발주"}},
            {"key": "c2", "type": "CONDITION", "fields": {"description": "예산 승인"}},
        ])
        self.assertIn({"rel": "EVIDENCED_BY", "srcKey": "c1", "dstRef": SPANS[0]["ref"], "evidenceSpanIds": ["span-1"]},
                      result.candidates["edges"])
        self.assertIn({"rel": "REQUIRES", "srcKey": "c1", "dstKey": "c2", "evidenceSpanIds": ["span-1"]},
                      result.candidates["edges"])

    def test_이미_있는_카드와_발언을_이름표로_가리킨다(self):
        result = self.run_it(
            [card("c1", "DECISION_EVENT", "대면 개최 확정", [1], event_kind="DECISION"),
             card("c2", "TASK", "아랍어 문구 검토", [2])],
            [link("HAS_EVENT", "x1", "c1", [1]), link("ASSIGNED_TO", "c2", "x2", [2]), link("MENTIONS", "s2", "x2", [2])])

        self.assertEqual(result.dropped, [])
        edges = result.candidates["edges"]
        self.assertIn({"rel": "HAS_EVENT", "srcRef": STATE["facts"][0]["node"], "dstKey": "c1",
                       "evidenceSpanIds": ["span-1"]}, edges)
        self.assertIn({"rel": "MENTIONS", "srcRef": SPANS[1]["ref"], "dstRef": STATE["facts"][1]["node"],
                       "evidenceSpanIds": ["span-2"]}, edges)

    def test_새_인물_카드에는_언급_줄이_함께_생긴다(self):
        result = self.run_it([card("p1", "ENTITY", "아흐마드", [2], entity_kind="PERSON")], [])

        self.assertEqual(result.candidates["nodes"][0]["fields"], {"name": "아흐마드", "kind": "PERSON"})
        self.assertEqual(result.candidates["edges"], [
            {"rel": "MENTIONS", "srcRef": SPANS[1]["ref"], "dstKey": "p1", "evidenceSpanIds": ["span-2"]}])

    def test_근거_없는_카드와_규칙에_없는_줄은_버리고_이유를_남긴다(self):
        result = self.run_it(
            [card("c1", "TASK", "인쇄 발주", [1]),
             card("c2", "CONDITION", "근거 없는 조건", []),
             card("c3", "TASK", "없는 발언을 근거로", [99]),
             card("c4", "DECISION_EVENT", "유형 없는 사건", [1]),
             card("c1", "TASK", "key가 겹침", [1])],
            [link("REQUIRES", "c1", "c2", [1]),            # 버려진 카드를 가리킴
             link("REQUIRES", "x1", "c1", [1]),            # 방향이 반대
             link("USES", "c1", "x1", [1]),                # 낼 수 없는 줄
             link("REQUIRES", "c1", "c1", [1]),            # 자기 자신
             link("ASSIGNED_TO", "c1", "x2", [])])         # 근거 없음

        self.assertEqual([node["key"] for node in result.candidates["nodes"]], ["c1"])
        self.assertEqual([edge["rel"] for edge in result.candidates["edges"]], ["EVIDENCED_BY"])
        self.assertEqual(len(result.dropped), 9)

    def test_시간대가_없는_시각은_저장하지_않는다(self):
        result = self.run_it(
            [card("e1", "DECISION_EVENT", "대면 확정", [1], event_kind="DECISION", occurred_at="2026-10-20T10:00:00+09:00"),
             card("e2", "DECISION_EVENT", "날짜 변경", [1], event_kind="CHANGE", occurred_at="2026-11-03"),
             card("e3", "DECISION_EVENT", "제안", [1], event_kind="PROPOSAL", occurred_at="다음 주")],
            [])

        fields = [node["fields"] for node in result.candidates["nodes"]]
        self.assertEqual(fields[0]["occurredAt"], "2026-10-20T10:00:00+09:00")
        self.assertNotIn("occurredAt", fields[1])
        self.assertNotIn("occurredAt", fields[2])

    def test_같은_줄은_한_번만_낸다(self):
        result = self.run_it(
            [card("c1", "TASK", "인쇄 발주", [1, 1]), card("c2", "CONDITION", "예산 승인", [1])],
            [link("REQUIRES", "c1", "c2", [1]), link("REQUIRES", "c1", "c2", [1])])

        self.assertEqual([edge["rel"] for edge in result.candidates["edges"]].count("REQUIRES"), 1)
        self.assertEqual([edge["rel"] for edge in result.candidates["edges"]].count("EVIDENCED_BY"), 2)


class BuildPrompt(unittest.TestCase):
    def test_발언에는_번호를_기존_카드에는_이름표를_붙여_보여_준다(self):
        prompt = extract.build_user_prompt(
            {"document": {"title": "3차 회의", "kind": "TRANSCRIPT"}}, SPANS, STATE, ONTOLOGY)

        self.assertIn("s1: [민지] 예산 승인 전까지 인쇄는 보류입니다.", prompt)
        self.assertIn("x1: [DECISION_ITEM] 개최 방식 (확인됨, decisionState=DECIDED)", prompt)
        self.assertIn("x2: [ENTITY] Ahmed Hassan (AI 후보, kind=PERSON)", prompt)
        self.assertIn("- REQUIRES: TASK → TASK", prompt)
        self.assertNotIn("EVIDENCED_BY", prompt)
        # 모델이 실제 id를 보지 않는다
        self.assertNotIn("span-1", prompt)
        self.assertNotIn("item-1", prompt)

    def test_출력_양식은_모든_칸이_필수이고_다른_칸을_허용하지_않는다(self):
        cards = extract.OUTPUT_SCHEMA["properties"]["cards"]["items"]
        self.assertFalse(cards["additionalProperties"])
        self.assertEqual(sorted(cards["required"]), sorted(cards["properties"]))


if __name__ == "__main__":
    unittest.main()
