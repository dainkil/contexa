"""환경 변수로 받는 설정."""

from __future__ import annotations

import os
import socket
from dataclasses import dataclass


@dataclass(frozen=True)
class Config:
    server_url: str
    worker_token: str
    worker_id: str
    model: str
    poll_seconds: float

    @staticmethod
    def from_env() -> "Config":
        return Config(
            server_url=os.environ.get("CONTEXA_SERVER_URL", "http://127.0.0.1:8090").rstrip("/"),
            # 서버의 contexa.worker.token과 같은 값이어야 한다. 기본값은 내 컴퓨터 개발용이다.
            worker_token=os.environ.get("CONTEXA_WORKER_TOKEN", "dev-worker-token"),
            worker_id=os.environ.get("CONTEXA_WORKER_ID", f"{socket.gethostname()}-{os.getpid()}"),
            model=os.environ.get("CONTEXA_MODEL", "claude-opus-5-5"),
            poll_seconds=float(os.environ.get("CONTEXA_POLL_SECONDS", "2")),
        )
