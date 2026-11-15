# Contexa AI 인턴

서버에서 일을 가져와 처리하고 결과를 제출하는 Python 일꾼입니다.

| 작업 | 하는 일 | 모델 사용 |
|---|---|---|
| `PARSE` | 문서를 발언으로 자른다 | 아니오 (규칙으로 자름) |
| `EXTRACT` | 발언에서 카드·줄 후보를 뽑는다 | 예 |
| `GENERATE` | 질문에 답을 쓴다 (근거를 모으고, 인용을 검사해서 제출) | 예 |

일꾼이 낸 것은 모두 "AI 후보"로 저장됩니다. 확인은 사람이 검토함에서 합니다.
일꾼은 데이터베이스에 접속하지 않고 서버의 `/internal` 주소만 부릅니다.

## 실행

서버(`server/`)가 떠 있어야 합니다.

```bash
cd ai
python3 -m venv .venv && source .venv/bin/activate
pip install -e .                      # anthropic SDK를 설치한다
export ANTHROPIC_API_KEY=...          # Claude API 키. 파일에 적지 않는다
python -m contexa_ai.worker           # 계속 돌면서 일을 기다린다
python -m contexa_ai.worker --once    # 기다리는 일을 한 번 훑고 끝낸다
```

| 환경 변수 | 기본값 | 뜻 |
|---|---|---|
| `CONTEXA_SERVER_URL` | `http://127.0.0.1:8090` | 서버 주소 |
| `CONTEXA_WORKER_TOKEN` | `dev-worker-token` | 서버의 `contexa.worker.token`과 같은 값 |
| `CONTEXA_MODEL` | `claude-opus-5-5` | 후보를 뽑고 답을 쓸 때 쓰는 모델 |
| `CONTEXA_POLL_SECONDS` | `2` | 일이 없을 때 다시 물어보는 간격 |

## 테스트

모델과 서버 없이 돕니다. 추가로 설치할 것이 없습니다.

```bash
cd ai
python3 -m unittest discover -s tests -t .
```

## 파일

| 파일 | 내용 |
|---|---|
| `contexa_ai/worker.py` | 일을 가져와 종류에 맞게 처리하는 루프 |
| `contexa_ai/parse.py` | 문서를 발언으로 자르는 규칙 |
| `contexa_ai/extract.py` | 모델에게 주는 지시와 자료, 모델의 답을 후보로 바꾸는 규칙 |
| `contexa_ai/answer.py` | 근거 모으기, 답을 쓰게 하는 지시와 자료, 인용 검사 |
| `contexa_ai/llm.py` | 모델을 부르는 곳. 다른 모델로 바꿀 때는 이 파일만 고친다 |
| `contexa_ai/server.py` | 서버의 뒷문을 부르는 클라이언트 |

## 다른 모델로 바꾸기

`llm.py`에 `structured(system, user, schema)`를 가진 클래스를 하나 더 만들고 `make_llm`에서 고르면 됩니다.
나머지 파일은 모델 회사를 모릅니다.
