# Contexa AI 인턴

서버에서 일을 가져와 처리하고 결과를 제출하는 Python 일꾼입니다.

| 작업 | 하는 일 | 모델 사용 |
|---|---|---|
| `PARSE` | 문서를 발언으로 자른다 | 아니오 (규칙으로 자름) |
| `EXTRACT` | 발언에서 카드·줄 후보를 뽑는다. 긴 문서는 겹치는 창으로 나눠 동시에 뽑고 합친다 | 예 |
| `GENERATE` | 질문에 답을 쓴다. 검색어를 계획하고(다른 언어 검색어 포함) 근거를 모은 뒤, 모자라면 한 번 더 찾고 쓴다. 인용을 검사해서 제출한다. 질문을 쓴 언어로 답하고, 근거 발언은 번역하지 않는다 | 예 |

일꾼이 낸 것은 모두 "AI 후보"로 저장됩니다. 확인은 사람이 검토함에서 합니다.
일꾼은 데이터베이스에 접속하지 않고 서버의 `/internal` 주소만 부릅니다.

## 작업 안의 단계 (LangGraph)

`EXTRACT`와 `GENERATE`는 작업 하나 안의 단계를 LangGraph로 잇습니다. 체크포인터와 `interrupt`는 쓰지 않습니다.
실패하면 서버에 알리고, 다시 하는 것은 서버의 작업 임대·재시도가, 사람의 판단은 검토함이 맡습니다.

```
GENERATE  load ─▶ plan ─▶ retrieve ─▶ judge ─(모자라면 한 번 더)─▶ retrieve
                                        └─(충분하면)─▶ write ─▶ submit ─(서버가 받지 않으면)─▶ retrieve(처음부터)
EXTRACT   prepare ─(창마다 Send)─▶ window ×N ─▶ merge ─▶ submit ─(받지 않으면 문제의 창만)─▶ window
```

- 질의 계획(`plan.v1`): 질문 원문은 언제나 첫 검색어입니다. 모델이 검색어를 더 내고(질문을 프로젝트의 다른 언어로 옮긴 것 포함),
  현황의 안건 중 관련된 것을 고릅니다. 고른 안건은 지난 제안·바뀐 결정까지 따라가 읽습니다(state의 `topic_ids`).
  검색이 글자 일치라 다른 언어로 적힌 발언은 원문으로 찾지 못하기 때문입니다.
- 근거 판단(`judge.v1`): 모은 근거로 답할 수 있는지 보고, 모자라면 검색어·안건을 더 내 한 번 더 모읍니다(최대 2회).
- 창 나누기: 발언 200개씩, 앞 창과 20개 겹치게 자릅니다. 다른 창에서 나온 같은 카드(종류가 같고 글이 같거나 근거 발언이 똑같은 것)는
  하나로 합칩니다. 인물·기관은 이름이 같을 때만 합칩니다. 창들은 서로의 결과를 보지 못하므로, 겹치지 않는 창 사이의 줄은 생기지 않습니다.

## 실행

서버(`server/`)가 떠 있어야 합니다.

```bash
cd ai
python3 -m venv .venv && source .venv/bin/activate
pip install -e .                      # LangChain(langchain, langchain-anthropic)과 LangGraph를 설치한다
# 모델 API 키는 저장소 최상위 .env에 ANTHROPIC_API_KEY=... 로 적는다(git이 무시한다).
# 일꾼이 켜질 때 읽고, 이미 있는 환경 변수는 덮어쓰지 않는다. 셸 설정 파일에는 export하지 않는다
python -m contexa_ai.worker           # 계속 돌면서 일을 기다린다
python -m contexa_ai.worker --once    # 기다리는 일을 한 번 훑고 끝낸다
```

| 환경 변수 | 기본값 | 뜻 |
|---|---|---|
| `CONTEXA_SERVER_URL` | `http://127.0.0.1:8090` | 서버 주소 |
| `CONTEXA_WORKER_TOKEN` | `dev-worker-token` | 서버의 `contexa.worker.token`과 같은 값 |
| `CONTEXA_MODEL` | `anthropic:claude-opus-5-5` | `공급자:모델`. 공급자를 빼면 `anthropic`으로 본다 |
| `CONTEXA_POLL_SECONDS` | `2` | 일이 없을 때 다시 물어보는 간격 |
| `CONTEXA_LANGUAGES` | `ko,en,ar` | 프로젝트가 쓰는 언어. 질의 계획이 이 언어들로 검색어를 옮긴다 |
| `CONTEXA_EXTRACT_CONCURRENCY` | `2` | 긴 문서의 창을 동시에 몇 개까지 뽑을지 |
| `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`, `LANGFUSE_HOST` | 없음, 없음, `http://localhost:3000` | 셋 다 있으면 Langfuse로 추적을 보낸다(아래) |

## 호출 기록

모델을 부를 때마다 로그에 JSON 한 줄이 남습니다. 회의 원문, 프롬프트 본문, 모델 답은 적지 않습니다.

```json
{"event": "llm_call", "job_id": "…", "job_type": "EXTRACT", "project_id": "…", "prompt": "extract.v1",
 "provider": "anthropic", "model": "claude-opus-5-5", "outcome": "ok", "input_tokens": 5120, "output_tokens": 830, "latency_ms": 41250}
```

### Langfuse로 추적 보기 (선택)

[Langfuse](https://langfuse.com)를 이 컴퓨터에 띄우면 작업 하나가 trace 하나로 보입니다. 모델 호출은 그 아래 generation으로 붙고,
토큰·지연·모델·프롬프트 버전, `project:<id>` 꼬리표가 함께 남습니다. 추적은 업무 기록이 아니고, 외부 서비스로 보내지 않습니다.
Postgres·ClickHouse·Redis·MinIO를 함께 띄우므로 메모리를 몇 GB 씁니다. 필요할 때만 켭니다.

```bash
cp observability/langfuse.env.example observability/.env      # 저장소 최상위에서. 비밀값을 채운다
docker compose -f observability/langfuse.compose.yml --env-file observability/.env up -d
# http://localhost:3000 에 .env의 LANGFUSE_INIT_USER_EMAIL / PASSWORD로 로그인

cd ai && pip install -e '.[observability]'
set -a && source ../observability/.env && set +a                # LANGFUSE_PUBLIC_KEY·SECRET_KEY를 일꾼에도 넣는다
python -m contexa_ai.worker                                    # 시작 로그에 "Langfuse 켜짐"이 나온다
```

Langfuse가 꺼져 있는데 키만 넣어 두면, 작업은 그대로 끝나고 보내지 못했다는 경고만 남습니다.
LangSmith는 쓰지 않습니다. 무료 플랜이 클라우드 전용이라 회의 내용이 밖으로 나갑니다.

## 프롬프트

지시문은 `contexa_ai/prompts/이름.vN.md`에 있습니다. 내용을 바꿀 때는 파일을 고치지 않고 N을 올린 새 파일을 만듭니다.
일꾼은 가장 높은 판을 쓰고, 호출 기록에 `extract.v1`처럼 판을 남깁니다. 평가 결과와 비교할 때 어떤 글이었는지 다시 볼 수 있습니다.

## 테스트

모델·서버·Langfuse 없이 돕니다. `pip install -e .`로 의존성을 설치한 가상환경에서 돌립니다.

```bash
cd ai
python -m unittest discover -s tests -t .
```

## 파일

| 파일 | 내용 |
|---|---|
| `contexa_ai/worker.py` | 일을 가져와 종류에 맞게 처리하는 루프. 작업마다 추적 범위를 연다 |
| `contexa_ai/parse.py` | 문서를 발언으로 자르는 규칙 |
| `contexa_ai/extract.py` | 모델에게 주는 자료, 모델의 답을 후보로 바꾸는 규칙 |
| `contexa_ai/extract_graph.py` | EXTRACT 단계(창 나누기·창마다 뽑기·합치기·고치기)를 잇는 LangGraph |
| `contexa_ai/answer.py` | 근거 합치기, 답을 쓰게 하는 자료, 인용 검사 |
| `contexa_ai/answer_graph.py` | GENERATE 단계(질의 계획·근거 모으기·충분성 판단·쓰기·내기)를 잇는 LangGraph |
| `contexa_ai/prompts.py`, `contexa_ai/prompts/` | 버전이 붙은 지시문과 그 읽기 |
| `contexa_ai/llm.py` | 모델을 부르는 유일한 곳. LangChain 어댑터 |
| `contexa_ai/tracing.py` | 작업 맥락, 호출 기록 한 줄, Langfuse 추적(선택) |
| `contexa_ai/server.py` | 서버의 뒷문을 부르는 클라이언트 |

## 다른 모델로 바꾸기

`CONTEXA_MODEL`만 바꿉니다. 모델 연결은 LangChain의 `init_chat_model`이 맡습니다.

```bash
export CONTEXA_MODEL=anthropic:claude-sonnet-5-5
pip install langchain-openai && export CONTEXA_MODEL=openai:<모델 이름>   # 다른 공급자는 그 LangChain 패키지를 설치한다
```

나머지 파일은 `structured(system, user, schema)`만 알고 모델 회사를 모릅니다. Claude에만 있는 옵션(effort, 거절 시 대체 모델)은
`llm.py`가 공급자가 anthropic일 때만 붙입니다. 공급자마다 받아들이는 JSON 스키마의 범위가 다르므로, 바꾼 뒤에는 평가셋으로 다시 잽니다.
