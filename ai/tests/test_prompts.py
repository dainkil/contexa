import unittest

from contexa_ai import answer, extract, prompts


class PromptsTest(unittest.TestCase):
    def test_modules_use_versioned_prompt_files(self):
        self.assertIsInstance(extract.SYSTEM_PROMPT, prompts.Prompt)
        self.assertEqual(extract.SYSTEM_PROMPT.ref, f"extract.v{max(prompts.available('extract'))}")
        self.assertEqual(answer.SYSTEM_PROMPT.ref, f"answer.v{max(prompts.available('answer'))}")

    def test_prompt_is_still_a_plain_string(self):
        prompt = prompts.load("extract", 1)
        self.assertIsInstance(prompt, str)
        self.assertIn("결정 그래프", prompt)
        self.assertTrue(prompt.endswith("\n"))
        self.assertEqual((prompt.name, prompt.version, prompt.ref), ("extract", 1, "extract.v1"))

    def test_missing_name_or_version_is_an_error(self):
        with self.assertRaises(FileNotFoundError):
            prompts.load("nothing")
        with self.assertRaises(FileNotFoundError):
            prompts.load("extract", 999)


if __name__ == "__main__":
    unittest.main()
