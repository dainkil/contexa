import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from contexa_ai.config import DEFAULT_LANGUAGES, ENV_FILE, Config, load_env_file, parse_languages


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


class LanguagesTest(unittest.TestCase):
    def test_쉼표로_나눈_언어_코드를_읽고_빈_값과_겹치는_값은_뺀다(self):
        self.assertEqual(parse_languages(" ko, EN,,ar ,en "), ("ko", "en", "ar"))
        self.assertEqual(parse_languages(""), ())

    def test_설정하지_않으면_기본_언어를_쓰고_설정하면_그것을_쓴다(self):
        with mock.patch("contexa_ai.config.load_env_file"), mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual(Config.from_env().languages, DEFAULT_LANGUAGES)
            self.assertEqual(Config.from_env().extract_concurrency, 2)
        with mock.patch("contexa_ai.config.load_env_file"), \
                mock.patch.dict(os.environ, {"CONTEXA_LANGUAGES": "ko,ja", "CONTEXA_EXTRACT_CONCURRENCY": "0"},
                                clear=True):
            self.assertEqual(Config.from_env().languages, ("ko", "ja"))
            self.assertEqual(Config.from_env().extract_concurrency, 1)


if __name__ == "__main__":
    unittest.main()
