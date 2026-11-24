"""버전이 붙은 프롬프트 파일을 읽는다.

프롬프트는 `prompts/이름.vN.md` 파일에 둔다. 내용을 바꿀 때는 파일을 고치지 않고 N을 하나 올린 새 파일을 만든다.
그래야 평가 결과와 호출 로그에 적힌 버전이 어떤 글이었는지 언제든 다시 볼 수 있다.

읽은 프롬프트는 `Prompt`로 돌려준다. `Prompt`는 str이라 지금처럼 글로 쓰면 되고, 어느 파일의 몇 번째 판인지
(`ref`, 예: "extract.v1")를 함께 들고 다닌다. llm.py가 이 값을 호출 로그와 추적에 적는다.
"""

from __future__ import annotations

import re
from importlib import resources

_FILE = re.compile(r"^(?P<name>[a-z_]+)\.v(?P<version>[1-9][0-9]*)\.md$")


class Prompt(str):
    """프롬프트 글과, 그 글이 어느 파일의 몇 번째 판인지."""

    name: str
    version: int

    def __new__(cls, text: str, name: str, version: int) -> "Prompt":
        prompt = super().__new__(cls, text)
        prompt.name = name
        prompt.version = version
        return prompt

    @property
    def ref(self) -> str:
        return f"{self.name}.v{self.version}"


def load(name: str, version: int | None = None) -> Prompt:
    """`name`의 프롬프트를 읽는다. 판을 고르지 않으면 가장 높은 판을 읽는다."""
    found = available(name)
    if not found:
        raise FileNotFoundError(f"프롬프트 파일이 없다: prompts/{name}.vN.md")
    chosen = max(found) if version is None else version
    if chosen not in found:
        raise FileNotFoundError(f"프롬프트 판이 없다: prompts/{name}.v{chosen}.md")
    text = (resources.files(__package__) / "prompts" / f"{name}.v{chosen}.md").read_text(encoding="utf-8")
    return Prompt(text, name, chosen)


def available(name: str) -> list[int]:
    """`name`의 프롬프트 파일이 가진 판 번호들."""
    folder = resources.files(__package__) / "prompts"
    versions = []
    for entry in folder.iterdir():
        match = _FILE.match(entry.name)
        if match and match["name"] == name:
            versions.append(int(match["version"]))
    return sorted(versions)
