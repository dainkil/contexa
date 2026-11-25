import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from contexa_ai.server import Lease, LeaseLost, ServerClient, ServerError, ServerRejected

LEASE = Lease("job-1", "EXTRACT", "project-1", "attempt-1", "lease-token")


class _Handler(BaseHTTPRequestHandler):
    """정해 둔 상태 코드와 본문으로 답하고, 받은 요청을 기억한다."""

    def do_POST(self):
        server = self.server
        server.requests.append((self.path, dict(self.headers)))
        status, body = server.reply
        raw = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, *args):
        pass


class ServerClientTest(unittest.TestCase):
    def setUp(self):
        self.http = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        self.http.requests = []
        self.http.reply = (200, {})
        threading.Thread(target=self.http.serve_forever, daemon=True).start()
        self.client = ServerClient(f"http://127.0.0.1:{self.http.server_port}", "worker-token", "worker-1", timeout=5)

    def tearDown(self):
        self.stop()

    def stop(self):
        if self.http is not None:
            self.http.shutdown()
            self.http.server_close()
            self.http = None

    def reply(self, status, body):
        self.http.reply = (status, body)

    def test_임대를_늘리면_임대증을_헤더로_보내고_새_만료_시각을_받는다(self):
        self.reply(200, {"leaseExpiresAt": "2026-11-25T00:10:00Z"})

        self.assertEqual(self.client.heartbeat(LEASE), "2026-11-25T00:10:00Z")

        path, headers = self.http.requests[0]
        self.assertEqual(path, "/internal/v1/jobs/job-1/heartbeat")
        self.assertEqual(headers["X-Worker-Token"], "worker-token")
        self.assertEqual(headers["X-Attempt-Id"], "attempt-1")
        self.assertEqual(headers["X-Lease-Token"], "lease-token")

    def test_409는_LEASE_LOST일_때만_작업을_내려놓는다(self):
        self.reply(409, {"error": "LEASE_LOST", "message": "임대가 끝났다"})
        with self.assertRaisesRegex(LeaseLost, "임대가 끝났다"):
            self.client.heartbeat(LEASE)

        # 결과가 지금 상태와 맞지 않는다(예: 이미 처리된 문서). 내려놓지 않고 고쳐 내거나 실패로 알린다
        self.reply(409, {"error": "NOT_POSSIBLE", "message": "이미 처리된 문서 판이다"})
        with self.assertRaisesRegex(ServerRejected, "이미 처리된 문서 판이다"):
            self.client.submit(LEASE, {"spans": []})

    def test_400은_거절이고_그_밖의_실패는_서버_오류다(self):
        self.reply(400, {"error": "BAD_REQUEST", "message": "근거 발언이 없는 줄 후보다"})
        with self.assertRaisesRegex(ServerRejected, "근거 발언이 없는 줄 후보다"):
            self.client.submit(LEASE, {"candidates": {}})

        self.reply(500, {"error": "INTERNAL", "message": "server 안에서 예상하지 못한 오류가 났다"})
        with self.assertRaisesRegex(ServerError, "500"):
            self.client.submit(LEASE, {"candidates": {}})

        self.reply(502, b"<html>Bad Gateway</html>")  # 앞단 프록시가 JSON이 아닌 본문을 줄 수 있다
        with self.assertRaisesRegex(ServerError, "502"):
            self.client.submit(LEASE, {"candidates": {}})

    def test_서버가_꺼져_있으면_서버_오류다(self):
        port = self.http.server_port
        self.stop()
        client = ServerClient(f"http://127.0.0.1:{port}", "worker-token", "worker-1", timeout=1)

        with self.assertRaises(ServerError):
            client.heartbeat(LEASE)


if __name__ == "__main__":
    unittest.main()
