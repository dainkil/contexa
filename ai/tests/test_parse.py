import unittest

from contexa_ai.parse import split_document


class SplitTranscript(unittest.TestCase):
    def test_화자와_내용을_나누고_시간_표시는_뗀다(self):
        spans = split_document("TRANSCRIPT", "[00:01:02] 민지: 예산 승인 전까지 인쇄는 보류입니다.\n\nJunho：OK, Friday까지 할게요.")
        self.assertEqual(spans, [
            {"speakerLabel": "민지", "text": "예산 승인 전까지 인쇄는 보류입니다."},
            {"speakerLabel": "Junho", "text": "OK, Friday까지 할게요."},
        ])

    def test_화자_없는_줄은_앞_발언에_이어_붙인다(self):
        spans = split_document("TRANSCRIPT", "민지: 장소는 확보됐습니다.\n그래서 대면으로 확정합니다.")
        self.assertEqual(len(spans), 1)
        self.assertEqual(spans[0]["text"], "장소는 확보됐습니다. 그래서 대면으로 확정합니다.")

    def test_문장_안의_콜론은_화자로_보지_않는다(self):
        spans = split_document("TRANSCRIPT", "회의는 3시에 시작합니다. 안건: 인쇄")
        self.assertEqual(spans, [{"speakerLabel": None, "text": "회의는 3시에 시작합니다. 안건: 인쇄"}])

    def test_아랍어_화자_표기도_받는다(self):
        spans = split_document("TRANSCRIPT", "أحمد: سأراجع النص العربي.")
        self.assertEqual(spans[0]["speakerLabel"], "أحمد")


class SplitParagraphs(unittest.TestCase):
    def test_빈_줄로_나뉜_문단마다_하나(self):
        spans = split_document("DOCUMENT", "제1조 목적\n이 규정은…\n\n제2조 적용\n외부인은 승인이 필요하다.")
        self.assertEqual([span["text"] for span in spans], ["제1조 목적 이 규정은…", "제2조 적용 외부인은 승인이 필요하다."])

    def test_빈_줄이_없으면_줄마다_하나(self):
        self.assertEqual(len(split_document("MEMO", "첫 줄\n둘째 줄")), 2)

    def test_너무_긴_문단은_문장_경계에서_나누고_내용은_그대로_둔다(self):
        sentence = "가나다라마바사아자차카타파하. "
        text = (sentence * 120).strip()
        spans = split_document("DOCUMENT", text)
        self.assertGreater(len(spans), 1)
        self.assertTrue(all(len(span["text"]) <= 1200 for span in spans))
        self.assertEqual(" ".join(span["text"] for span in spans), text)

    def test_빈_문서는_발언이_없다(self):
        self.assertEqual(split_document("MEMO", " \n\n "), [])


if __name__ == "__main__":
    unittest.main()
