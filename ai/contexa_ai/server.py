"""서버(Java)의 뒷문을 부르는 클라이언트. 표준 라이브러리만 쓴다."""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any


class ServerRejected(Exception):
    """서버가 결과의 내용을 받아들이지 않았다(400). 이유를 보고 고쳐서 같은 임대증으로 다시 낼 수 있다."""


class LeaseLost(Exception):
    """임대증이 더 이상 유효하지 않다(409). 이 작업은 내려놓아야 한다."""


class ServerError(Exception):
    """그 밖의 실패: 서버가 꺼져 있거나, 일꾼 토큰이 틀렸거나, 서버 쪽 오류."""


@dataclass(frozen=True)
class Lease:
    """일을 가져가며 받은 임대증."""

    job_id: str
    job_type: str
    project_id: str
    attempt_id: str
    token: str


class ServerClient:
    def __init__(self, base_url: str, worker_token: str, worker_id: str, timeout: float = 60.0):
        self._base = base_url.rstrip("/") + "/internal/v1/jobs"
        self._worker_token = worker_token
        self._worker_id = worker_id
        self._timeout = timeout

    def claim(self) -> Lease | None:
        """가장 오래 기다린 일을 가져간다. 없으면 None."""
        body = self._call("POST", "/claim", {"workerId": self._worker_id})
        if body is None:
            return None
        job = body["job"]
        return Lease(job["id"], job["type"], job["projectId"], body["attemptId"], body["leaseToken"])

    def ontology(self) -> dict[str, Any]:
        return self._call("GET", "/ontology")

    def content(self, lease: Lease) -> dict[str, Any]:
        return self._call("GET", f"/{lease.job_id}/content", lease=lease)

    def spans(self, lease: Lease) -> list[dict[str, Any]]:
        return self._call("GET", f"/{lease.job_id}/spans", lease=lease)

    def state(self, lease: Lease) -> dict[str, Any]:
        """이 프로젝트에 이미 있는 카드. 작업을 시킨 사용자가 볼 수 있는 만큼만 온다."""
        return self._call("GET", f"/{lease.job_id}/context/state", lease=lease)

    def request(self, lease: Lease) -> dict[str, Any]:
        """글쓰기 작업의 주문서: 질문, 글의 종류, 언어."""
        return self._call("GET", f"/{lease.job_id}/request", lease=lease)

    def search(self, lease: Lease, query: str, k: int = 8) -> dict[str, Any]:
        """질문과 가까운 발언과, 그 발언에 기댄 카드. 질문은 주소가 아니라 본문으로 보낸다."""
        return self._call("POST", f"/{lease.job_id}/context/search", {"query": query, "k": k}, lease=lease)

    def trace(self, lease: Lease, node: dict[str, Any], direction: str = "BASIS", depth: int = 2) -> dict[str, Any]:
        """한 카드에서 근거 쪽(BASIS)이나 영향 쪽(IMPACT)으로 줄을 따라간다."""
        query = urllib.parse.urlencode({"type": node["type"], "id": node["id"], "direction": direction, "depth": depth})
        return self._call("GET", f"/{lease.job_id}/context/trace?{query}", lease=lease)

    def submit(self, lease: Lease, result: dict[str, Any]) -> dict[str, Any]:
        return self._call("POST", f"/{lease.job_id}/result", result, lease=lease)

    def fail(self, lease: Lease, reason: str, retryable: bool) -> dict[str, Any]:
        return self._call("POST", f"/{lease.job_id}/failure", {"reason": reason, "retryable": retryable}, lease=lease)

    def _call(self, method: str, path: str, body: Any = None, lease: Lease | None = None) -> Any:
        headers = {"X-Worker-Token": self._worker_token, "Accept": "application/json"}
        if lease is not None:
            # 임대 토큰은 주소가 아니라 헤더로 보낸다. 주소는 로그에 남기 쉽다.
            headers["X-Attempt-Id"] = lease.attempt_id
            headers["X-Lease-Token"] = lease.token
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self._base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self._timeout) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            message = _message_of(error)
            if error.code == 400:
                raise ServerRejected(message) from error
            if error.code == 409:
                raise LeaseLost(message) from error
            raise ServerError(f"{error.code}: {message}") from error
        except urllib.error.URLError as error:
            raise ServerError(f"서버에 연결할 수 없다: {error.reason}") from error


def _message_of(error: urllib.error.HTTPError) -> str:
    try:
        return json.loads(error.read()).get("message", "") or error.reason
    except Exception:  # 본문이 JSON이 아닐 수 있다
        return str(error.reason)
