import json
import os
import unittest
from unittest import mock

from contexa_ai import tracing


class TracingTest(unittest.TestCase):
    def setUp(self):
        tracing.reset_for_tests()
        self.addCleanup(tracing.reset_for_tests)

    def test_job_context_is_set_only_inside_the_block(self):
        self.assertIsNone(tracing.current())
        with tracing.job("job-1", "GENERATE", "project-1") as context:
            self.assertEqual(tracing.current(), context)
            self.assertEqual(context.project_id, "project-1")
        self.assertIsNone(tracing.current())

    def test_log_line_outside_a_job_has_empty_job_fields(self):
        with self.assertLogs("contexa.llm", "INFO") as logs:
            tracing.log_call(prompt=None, provider="anthropic", model="m", outcome="ok", latency_ms=5)
        record = json.loads(logs.records[0].getMessage())
        self.assertEqual(record["event"], "llm_call")
        self.assertIsNone(record["job_id"])
        self.assertIsNone(record["input_tokens"])

    def test_langfuse_stays_off_without_keys(self):
        with mock.patch.dict(os.environ, {"LANGFUSE_PUBLIC_KEY": "", "LANGFUSE_SECRET_KEY": ""}):
            self.assertFalse(tracing.langfuse_enabled())
            with tracing.job("job-1", "EXTRACT", "project-1"):
                config = tracing.run_config("extract.v1", "anthropic:m")
        self.assertNotIn("callbacks", config)
        self.assertEqual(config["metadata"]["job_id"], "job-1")
        self.assertEqual(config["metadata"]["prompt"], "extract.v1")
        self.assertEqual(config["run_name"], "extract.v1")


if __name__ == "__main__":
    unittest.main()
