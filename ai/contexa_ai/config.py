"""환경 변수로 받는 설정. 레포 루트의 `.env`(git이 무시한다)에 적은 값도 환경 변수로 읽는다."""

from __future__ import annotations

import os
import socket
from dataclasses import dataclass
from pathlib import Path

# 레포 루트의 .env. ai를 레포 안에서 설치했을 때(pip install -e)의 위치다. 없으면 읽지 않는다.
ENV_FILE = Path(__file__).resolve().parents[2] / ".env"


def load_env_file(path: Path = ENV_FILE) -> list[str]:
    """`.env`의 `이름=값` 줄을 환경 변수로 올린다. 이미 있는 환경 변수는 덮어쓰지 않는다.

    모델 API 키(ANTHROPIC_API_KEY 등)를 셸에 내보내지 않고 이 프로젝트에서만 쓰기 위해서다.
    돌려주는 것은 새로 올린 이름뿐이고 값은 돌려주지도 기록하지도 않는다.
    """
    if not path.is_file():
        return []
    loaded = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].lstrip()
        name, sep, value = line.partition("=")
        name, value = name.strip(), value.strip()
        if not sep or not name.isidentifier():
            continue
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        if name not in os.environ:
            os.environ[name] = value
            loaded.append(name)
    return loaded


# 기본으로 다루는 언어. CONTEXA_LANGUAGES로 늘리거나 줄인다
DEFAULT_LANGUAGES: tuple[str, ...] = ("ko", "en", "ar")


@dataclass(frozen=True)
class Config:
    server_url: str
    worker_token: str
    worker_id: str
    model: str
    poll_seconds: float
    # 프로젝트에서 쓰는 언어. 질문을 다른 언어로도 바꿔 찾을 때 쓴다(answer_graph). 코드에 박지 않고 여기서 늘린다.
    languages: tuple[str, ...] = DEFAULT_LANGUAGES
    # 긴 문서를 창으로 나눠 뽑을 때 동시에 부르는 모델 수(extract_graph)
    extract_concurrency: int = 2

    @staticmethod
    def from_env() -> "Config":
        """환경 변수로 설정을 만든다. 그 전에 레포 루트의 .env를 읽어 둔다(모델 SDK도 같은 환경 변수를 읽는다)."""
        load_env_file()
        return Config(
            server_url=os.environ.get("CONTEXA_SERVER_URL", "http://127.0.0.1:8090").rstrip("/"),
            # 서버의 contexa.worker.token과 같은 값이어야 한다. 기본값은 내 컴퓨터 개발용이다.
            worker_token=os.environ.get("CONTEXA_WORKER_TOKEN", "dev-worker-token"),
            worker_id=os.environ.get("CONTEXA_WORKER_ID", f"{socket.gethostname()}-{os.getpid()}"),
            # "공급자:모델". 공급자를 적지 않으면 anthropic으로 본다(llm.parse_model).
            model=os.environ.get("CONTEXA_MODEL", "anthropic:claude-opus-5-5"),
            poll_seconds=float(os.environ.get("CONTEXA_POLL_SECONDS", "2")),
            languages=parse_languages(os.environ.get("CONTEXA_LANGUAGES", ",".join(DEFAULT_LANGUAGES))),
            extract_concurrency=max(1, int(os.environ.get("CONTEXA_EXTRACT_CONCURRENCY", "2"))),
        )


def parse_languages(value: str) -> tuple[str, ...]:
    """쉼표로 나눈 언어 코드를 읽는다. 예: "ko, en,ar" → ("ko", "en", "ar"). 빈 값과 겹치는 값은 뺀다."""
    return tuple(dict.fromkeys(code.strip().lower() for code in value.split(",") if code.strip()))
