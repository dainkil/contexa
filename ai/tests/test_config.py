import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from contexa_ai.config import ENV_FILE, load_env_file


class EnvFileTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / ".env"

    def test_이름과_값을_환경_변수로_올리고_주석과_따옴표를_처리한다(self):
        self.path.write_text('# 모델 키\nCONTEXA_T_A=abc\nexport CONTEXA_T_B="x y"\n\nCONTEXA_T_C=\'q\'\n잘못된 줄\n',
                             encoding="utf-8")
        with mock.patch.dict(os.environ, {}, clear=False):
            loaded = load_env_file(self.path)
            self.assertEqual(loaded, ["CONTEXA_T_A", "CONTEXA_T_B", "CONTEXA_T_C"])
            self.assertEqual((os.environ["CONTEXA_T_A"], os.environ["CONTEXA_T_B"], os.environ["CONTEXA_T_C"]),
                             ("abc", "x y", "q"))

    def test_이미_있는_환경_변수는_덮어쓰지_않는다(self):
        self.path.write_text("CONTEXA_T_A=from-file\n", encoding="utf-8")
        with mock.patch.dict(os.environ, {"CONTEXA_T_A": "from-shell"}):
            self.assertEqual(load_env_file(self.path), [])
            self.assertEqual(os.environ["CONTEXA_T_A"], "from-shell")

    def test_파일이_없으면_아무것도_하지_않는다(self):
        self.assertEqual(load_env_file(self.path), [])

    def test_기본_위치는_레포_루트의_env다(self):
        self.assertEqual(ENV_FILE, Path(__file__).resolve().parents[2] / ".env")


if __name__ == "__main__":
    unittest.main()
