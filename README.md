# contexa

회의록과 문서에서 결정·조건·할 일을 뽑아, 사람이 근거를 보고 확인한 것만 "지금의 결정"으로 쌓는 프로젝트 맥락 도구.

| 폴더 | 무엇 | 실행 방법 |
|---|---|---|
| `server/` | 업무 서버(Java 21 · Spring Boot). 권한, 결정 그래프, 검토, 작업 큐 | `docker compose up -d db` 뒤 `cd server && ./gradlew bootRun` (127.0.0.1:8090) |
| `ai/` | AI 일꾼(Python). 문서를 발언으로 자르고, 후보를 뽑고, 답변을 쓴다 | [`ai/README.md`](ai/README.md) |
| `android/` | Android 앱(Kotlin · Jetpack Compose). 자료함, 검토함, 질문, 맥락 | [`android/README.md`](android/README.md) |
| `docs/` | 기획과 설계 문서 | 구조는 [`docs/contexa_architecture.md`](docs/contexa_architecture.md), 모듈과 API는 [`docs/contexa_module_map.md`](docs/contexa_module_map.md) |

server → ai 일꾼 → 앱 순서로 켠다. 일꾼이 꺼져 있으면 올린 문서와 질문은 처리 대기에 머문다.
