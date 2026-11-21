# Contexa 엔지니어링 노트

작성 2026-11-21 · 기준 커밋 `8bb36c3`(`feat/android-app`) · 코드는 고치지 않고 읽기만 했다.

여기서 엔지니어링은 "돌아가는 코드"가 아니라, 제약 속에서 트레이드오프를 판단해 시간이 지나도 동작하고 고칠 수 있게 만든 부분을 말한다.

**읽는 법**
- **확인**: 코드·주석·문서·커밋에 그 이유가 적혀 있다.
- **(추정)**: 적힌 이유가 없어서 내가 코드에서 읽어 낸 의도다.
- 경로는 저장소 최상위 기준이다. `S/` = `server/src/main/java/com/contexa/server/`, `A/` = `android/app/src/main/java/com/contexa/android/`.
- `android/`는 이 저장소의 작업 세션에서 Claude와 함께 쓴 코드다. 그 부분의 "왜"는 세션 중에 정한 것이고, 본인이 직접 내린 결정과 구분해서 읽어야 한다.

---

## 1. 요약: 가장 엔지니어링다운 결정 3개

**① 읽는 문 하나, 쓰는 문 하나, 그리고 AI는 후보만 낸다.**
결정 정보를 읽는 곳은 `ContextGateway` 하나, 바꾸는 곳은 `ReviewImpact` 하나다. AI가 낸 카드와 줄은 모두 `CANDIDATE`로 저장되고, 확인·정정은 사람(OWNER·REVIEWER)만 할 수 있다. 권한 검사와 "무엇이 사실인가"의 기준이 한 곳에 모여서, 화면·Q&A·외부 에이전트(MCP)가 서로 다른 답을 볼 수 없다.
근거: `S/gateway/ContextGateway.java:57`, `S/review/ReviewImpact.java:55`, `S/review/ReviewImpact.java:614-622`(사람이 아니면 거절), `S/review/ReviewImpact.java:214`(후보로 저장).

**② "두 번 눌림 · 동시에 고침 · 일꾼이 죽음"을 각각 다른 장치로 막았다.**
- 동시에 고침 → 낙관적 잠금: 내가 본 버전을 함께 보내고, 다르면 409 `STALE`. `S/graph/DecisionGraph.java:96-129`
- 두 번 눌림 → 멱등 키: 같은 키로 다시 오면 처리하지 않고 현재 상태를 돌려준다. `S/review/ReviewImpact.java:295`, `server/src/main/resources/db/migration/V7__create_job.sql:24`
- 일꾼이 죽음 → 작업 임대: 10분 임대, 만료되면 다른 일꾼이 가져감, 최대 3회. `S/job/JobOperation.java:38`, `:92-95`(`for update skip locked`), `:104`

**③ 설계를 실행 순서대로 따라가 보고 고친 뒤에 만들었다.**
모듈 지도 v2는 초안을 실제 흐름대로 따라가며 찾은 문제 9개와 고친 내용을 표로 남겼다(줄 방향 통일, 자르기·뽑기 분리, AI의 조회 권한 등). 고친 내용이 실제 코드에 있다.
근거: `docs/contexa_module_map.md:152-166`, `S/contract/Caller.java:12`(8번: `onBehalfOf`), 작업 종류 `PARSE`·`EXTRACT`(`V7__create_job.sql:7`, 5번).

---

## 2. 관점별 정리

### 2.1 제약과 트레이드오프

**검색: 임베딩 대신 글자 일치(pg_trgm)**
- 무엇: 질문을 낱말로 나눠 발언을 찾고 트라이그램 유사도를 더한다. 한국어 조사를 떼고도 찾아본다.
- 대안과 이유(확인): 임베딩 검색이 원래 설계다. Claude API에 임베딩이 없어서 글자 일치로 만들었고, CLOVA로 바꿀 때 붙이기로 했다. 대가도 적혀 있다: "발주"와 "주문"처럼 뜻은 같고 글자가 다른 표현은 못 찾는다.
- 근거: `S/retrieval/Retrieval.java:20-30`, `server/src/main/resources/db/migration/V8__create_retrieval_and_artifact.sql:3`, `docs/contexa_module_map.md` B.9 끝("아직 없는 것").

**AI 일꾼: 프레임워크 없이 표준 라이브러리 + SDK 하나**
- 무엇: 서버와의 통신은 `urllib`, 의존성은 `anthropic` 하나. 일꾼은 DB에 붙지 않고 서버의 `/internal` 주소만 부른다.
- 대안과 이유: 설계 문서는 FastAPI·LangChain·LangGraph를 그렸다(`docs/contexa_architecture.md` 2장). 실제 구현은 그것 없이 갔다. "모델을 부르는 데만 쓴다"는 주석은 있지만 프레임워크를 뺀 이유는 적혀 있지 않다. 작은 일꾼에 의존성을 늘리지 않으려는 판단으로 보인다(추정). DB에 붙지 않는 것은 권한·상태 규칙을 서버 한 곳에만 두려는 것이다(확인: `ai/README.md` "일꾼은 데이터베이스에 접속하지 않고").
- 근거: `ai/pyproject.toml`의 `dependencies`, `ai/contexa_ai/server.py:6-8,95`, `S/api/InternalJobController.java:44`.

**규칙표를 한 곳에만 둔다**
- 무엇: "어떤 카드에서 어떤 카드로 어떤 줄을 그을 수 있나"는 Java의 `Ontology`에만 있고, Python 일꾼은 실행할 때 서버에서 읽어 간다.
- 대안과 이유(확인): Python에도 같은 표를 적는 방법이 있었다. "두 곳에 적으면 언젠가 어긋난다"는 이유로 하지 않았다.
- 근거: `S/contract/Ontology.java:62-66`, `S/api/InternalJobController.java:75-76`, `ai/contexa_ai/extract.py:129-145,265`.

**분석을 두 작업으로 나눔(PARSE → EXTRACT)**
- 무엇: 문서를 발언으로 자르는 일과 후보를 뽑는 일이 별도 작업이다.
- 대안과 이유(확인): 한 번에 제출하면 근거로 적을 발언 번호가 아직 없다. 그래서 나눴다.
- 근거: `docs/contexa_module_map.md:162`, `V7__create_job.sql:7`.

**그래프 탐색과 답변 근거에 상한**
- 무엇: 줄 따라가기는 최대 10칸, 답변에 싣는 카드는 150개·따라가기 5번.
- 대안과 이유: 재귀 SQL로 끝까지 따라가는 대신 칸 수를 세며 한 단계씩 넓힌다. 숫자의 근거는 적혀 있지 않다(추정: 응답 크기와 모델 입력 길이를 묶어 두려는 것).
- 근거: `S/graph/DecisionGraph.java:43,188-202`, `ai/contexa_ai/answer.py:21-22`.

**Android: 라이브러리를 줄인 선택들** (세션 중 결정)
- DI 프레임워크 대신 손으로 만든 `AppContainer`. 화면이 열 개 안쪽이라 Hilt의 설정 비용이 더 크다고 봤다. 근거: `A/AppContainer.kt:23-24`.
- 실시간 연결 대신 2초 폴링. 서버에 푸시 통로가 없다. 화면이 보이는 동안만 돈다. 근거: `A/ui/documents/DocumentsViewModel.kt:39`, `A/ui/ask/AskViewModel.kt:120`.
- 기기 배경색을 따르는 동적 색 대신 고정 테마. 어느 기기에서나 같은 색이어야 한다는 이유가 적혀 있다. 근거: `A/ui/theme/Theme.kt` 머리 주석.
- 에뮬레이터의 `10.0.2.2` 대신 `adb reverse`. 에뮬레이터 37.3에서 연결이 시간 초과로 실패했고, 설치 작업에 포트 돌리기를 묶었다. 근거: `android/app/build.gradle.kts:25-27,79-86`.

**클라이언트 교체와 배포 보류**
- React 웹을 만들다(PR #1–#3) 지우고 Android 앱으로 바꿨다. 기능이 앱에서 동작하는 것을 확인한 뒤 한 커밋으로 지웠다. 근거: `da933ac`, `1a34b66`.
- 배포는 계획만 쓰고 실행하지 않았다. 무료 조건, 공개 전에 고칠 것 6가지가 적혀 있다. 근거: `c92cec3`, `docs/contexa_deploy_plan.md:1-20`.
- 왜 웹을 버렸는지는 저장소에 없다(4장 질문).

### 2.2 시간(유지보수성)

**DB가 스스로 규칙을 지킨다**
- 무엇: Flyway 마이그레이션 10개(V1–V10)에 CHECK·UNIQUE·FK·인덱스가 107줄. 상태 값 목록, "자기 자신에게 줄 긋기 금지", "확인됨이면 확인 시각이 있어야 함"을 DB 제약으로 걸었다.
- 대안과 이유(추정): 애플리케이션 코드에서만 검사할 수도 있었다. 코드가 틀려도 잘못된 행이 들어가지 않게 한 것으로 보인다.
- 근거: `server/src/main/resources/db/migration/V1__create_graph_edge.sql:13-25`, `V7__create_job.sql:7-24`, `V10__review_page_and_job_subject_indexes.sql`(화면 요구에 맞춰 인덱스를 나중에 추가).

**테스트**
- 서버: 20파일 4,489줄, `@Test` 198개. 가짜 DB가 아니라 실제 PostgreSQL에 붙고 `@Transactional`로 되돌린다. 모듈마다 테스트 파일이 있다(`ReviewImpactTest` 614줄, `ContextGatewayTest` 555줄, `DecisionTaskTest` 497줄).
- AI 일꾼: 테스트 함수 35개(`ai/tests/`). 모델은 `LLM` 인터페이스 뒤에 있어서 테스트에서 바꿔 끼운다(`ai/contexa_ai/llm.py:26`).
- Android: 14파일, `@Test` 81개. 화면에서 뗀 규칙과 ViewModel, MockWebServer로 API 주소·본문을 확인한다.
- 근거: `server/src/test/java/com/contexa/server/`, `android/app/src/test/`.

**모듈 경계**
- 서버는 13개 패키지가 모듈 지도의 "부서"와 1:1이다(`S/` 아래 `contract`, `graph`, `decision`, `gateway`, `review` 등). 커밋도 모듈 단위다(`5324e61`부터 `45c058d`까지 13개).
- Android는 화면과 규칙을 나눴다. 계산은 순수 함수 파일에 있고 화면은 그 결과만 그린다: `A/ui/documents/Progress.kt`, `A/ui/reviews/ReviewAction.kt`, `A/ui/context/Context.kt`.
- 한계: 경계를 강제하는 장치는 없다(3장 3번).

**설정 분리**
- 일꾼 토큰, 서버 주소, 모델 이름을 환경 변수로 뺐다. 근거: `server/src/main/resources/application.yml`(`CONTEXA_WORKER_TOKEN`), `ai/contexa_ai/config.py:25`.
- 앱의 API 주소는 빌드 종류별로 다르고 `-PapiBaseUrl`로 덮어쓴다. 배포 빌드의 기본값은 일부러 존재하지 않는 주소(`contexa.invalid`)다. 근거: `android/app/build.gradle.kts:28-36`.
- 라이브러리 버전은 한 파일에 모았고 확인한 날짜를 적었다. 근거: `android/gradle/libs.versions.toml:1`.

**고친 이력이 테스트와 함께 남아 있다**
- 문서 열람 범위가 새 판 올리기와 작업 조회에는 적용되지 않던 구멍을 찾아 고치고, 회귀 테스트 파일을 새로 만들었다.
- 근거: `4853ec4`(`JobVisibilityTest.java` 80줄 추가, `DocumentEvidenceTest`에 15줄).

### 2.3 사람(협업)

**문서**
- 설계 문서가 코드보다 먼저 있고(`8e7d1e1`), 모듈 지도는 "개발을 몰라도 읽을 수 있게" 본문과 부록(시그니처)을 나눴다. 근거: `docs/contexa_module_map.md:3-5`.
- 폴더마다 실행 방법이 있다: `README.md`, `ai/README.md`, `android/README.md`.
- 문서가 코드와 어긋났을 때 고친 커밋이 있다(`dbd3520`: 낡은 "미착수" 상태표 갱신).

**커밋 규칙**
- 42개 커밋 거의 전부가 `type(scope): 한국어 설명` 꼴이다(`feat(graph)`, `fix(server)`, `chore(web)`, `docs(deploy)`). 기능 브랜치와 PR 4개(#1–#4)를 썼다.
- 규칙을 적어 둔 문서나 검사하는 훅은 없다. 지켜진 것은 습관이다(확인: 저장소에 `CONTRIBUTING`·`commitlint`·훅 없음).

**타입과 주석**
- 앱의 응답 타입은 서버 record를 손으로 옮긴 것이고, 파일 머리에 원본 위치와 "Java record가 바뀌면 여기도 같이 고친다"가 적혀 있다. 근거: `A/api/Models.kt:5-10`.
- 주석이 "무엇"보다 "왜"를 적는다. 예: `S/api/ApiExceptionHandler.java:28`("없는 것과 볼 수 없는 것을 구분해서 알려주지 않는다"), `A/api/Http.kt:29-30`.

**없는 것**: CI, 린터, 포매터(3장 1번).

### 2.4 예측 가능성과 측정

**오류가 한 가지 모양으로 나온다**
- 서버: 안쪽 예외를 한 곳에서 상태 코드와 `{error, message}`로 바꾼다. "다른 사람이 먼저 바꿈"(`STALE`)과 "지금 상태에서는 불가"(`NOT_POSSIBLE`)를 같은 409 안에서 구분한다. 근거: `S/api/ApiExceptionHandler.java:28-75`.
- 앱: 그 코드를 사용자 문장으로 바꾸고, `STALE`이면 화면이 새로 읽는다. 토큰을 보냈는데 401이면 로그인 화면으로 보낸다. 근거: `A/api/ApiError.kt:31-46`, `A/api/Http.kt:47`.

**AI 출력은 믿지 않고 검사한다**
- 모델이 보여 주지 않은 발언을 인용하면 그 답을 버리고 다시 쓰게 한다. 근거: `ai/contexa_ai/answer.py:176-179`.
- 허용되지 않은 줄 조합은 일꾼에서 한 번, 서버에서 한 번 걸러진다. 근거: `ai/contexa_ai/extract.py:157,265`, `S/contract/Ontology.java:73`.
- 답변이 참고한 카드가 그사이 바뀌었으면 저장하지 않고, 저장된 뒤에 바뀌면 열 때 알려 준다. 근거: `S/generation/GenerationWiki.java:147-149,215`.
- 출력이 잘린 경우(`max_tokens`)를 따로 처리한다. 근거: `ai/contexa_ai/llm.py:65`.

**실패를 분류한다**
- 일꾼은 "잠시 실패(다시 시도)"와 "끝난 실패"를 구분해 서버에 알린다. 서버는 시도 횟수가 남았을 때만 대기열로 되돌린다. 근거: `ai/contexa_ai/worker.py:56-58`, `S/job/JobOperation.java:152-162`.
- 임대증은 해시로만 저장하고 상수 시간 비교를 쓴다. 다른 시도의 임대증이나 만료된 임대는 거절한다. 근거: `S/job/JobOperation.java:227-232`.
- 서버 호출 타임아웃 60초. 근거: `ai/contexa_ai/server.py:37`.

**입력 검증과 상한**
- 질문 2000자(서버와 앱 양쪽), 답변 대기 90번(3분) 뒤 멈춤, 텍스트 파일 5MB, 같은 내용의 판은 거절(내용 해시). 근거: `A/ui/ask/Ask.kt:9,12`, `A/ui/documents/TextFile.kt:19`, `S/document/DocumentEvidence.java:91-95`.

**비밀 다루기**
- 비밀번호는 PBKDF2 600,000회, 토큰은 SHA-256 해시로만 저장, 사용자 토큰 12시간. 근거: `S/identity/IdentityAccess.java:36,44-46,397-413`.
- 앱은 토큰을 Android Keystore 키로 암호화해 저장하고, 배포 빌드는 HTTPS만 허용한다(평문 HTTP는 개발 빌드의 127.0.0.1에만). 근거: `A/session/TokenCipher.kt:20-24`, `android/app/src/main/res/xml/network_security_config.xml`, `android/app/src/debug/res/xml/network_security_config.xml`.
- 서버는 기본으로 `127.0.0.1`에만 붙는다. 근거: `server/src/main/resources/application.yml:4`.

**없는 것**: 로그, 지표, 벤치마크(3장 2번).

---

## 3. 빈 곳: "그냥 돌아가니까"에 머문 부분 (우선순위 순)

**1. 자동으로 확인해 주는 것이 없다 (CI·린터·포매터)**
`.github/`가 없다. 테스트 279개(서버 198 + 앱 81)와 ai 테스트 35개는 사람이 손으로 돌려야 돌아간다. PR 4개 모두 검사 없이 머지됐거나 머지될 것이다. 테스트를 이만큼 써 놓고 자동 실행을 안 붙인 것이 가장 값싼 개선이다: PR마다 `server ./gradlew test`(PostgreSQL 서비스 컨테이너), `ai pytest`, `android :app:testDebugUnitTest`.

**2. 고장 났을 때 볼 것이 없다 (관측)**
서버 전체에 로그 호출이 한 곳이다(`S/gateway/ContextGateway.java:604`, 그것도 debug). 작업이 왜 실패했는지는 `job.last_error` 열 하나에 기대고, 누가 무엇을 확인했는지의 감사 조회, 처리 시간·실패율 같은 숫자가 없다. "측정" 관점에서는 사실상 비어 있다. 작업 시작·끝·실패, 검토 판단, 권한 거절에 구조화된 로그부터 넣는 것이 순서다.

**3. 경계와 의존성이 약속으로만 지켜진다**
- 13개 모듈은 한 Gradle 모듈 안의 패키지다. "화면은 게이트웨이로만 읽는다" 같은 규칙을 어겨도 빌드가 통과한다. 의존 방향을 검사하는 테스트(ArchUnit 등)가 없다.
- `spring-boot-starter-data-jpa`가 들어 있는데 `@Entity`가 0개다. 실제 접근은 전부 `JdbcClient`(9개 파일)다. `ddl-auto: validate`도 검사할 대상이 없다. 근거: `server/build.gradle`, `server/src/main/resources/application.yml`.
- `application.yml:3`의 주석 "아직 로그인이 없으므로"는 로그인이 생긴 뒤에도 남아 있다.
- 앱의 응답 타입은 서버 record를 손으로 옮긴 것이라, 서버가 바뀌면 조용히 어긋난다(`ignoreUnknownKeys`라 빠진 칸은 오류도 안 난다). 서버 응답 예시를 고정해 두고 양쪽 테스트가 같이 읽는 방법이 있다.

**4. 공개하면 바로 뚫리는 기본값이 "문서에만" 적혀 있다**
`POST /dev/seed`가 기본으로 켜져 있고, 일꾼 토큰 기본값은 `dev-worker-token`, 로그인 시도 제한과 로그아웃이 없다. 문제는 정확히 알고 적어 뒀지만(`docs/contexa_deploy_plan.md:11-20`) 코드에는 막는 장치가 없다. 배포를 보류했으니 지금은 괜찮지만, 기본값을 안전한 쪽으로 뒤집고 개발 프로필에서만 켜는 것이 맞는 방향이다.

**5. 실제 경로로 한 번도 확인하지 못한 것들**
- 서버 테스트는 켜 둔 개발 DB(5434)에 기댄다. DB가 꺼져 있으면 전부 실패하고, 개발 데이터와 같은 DB를 쓴다.
- Android는 UI·계측 테스트가 없다. 화면은 에뮬레이터에서 손으로 눌러 확인했다.
- 앱 작업 중 실제 모델이 만든 후보·답변으로는 확인하지 못했다(API 키 없이 정해 둔 답으로 화면만 확인). 담당 수락, 구성원 보기 전용, 어두운 모드도 실제로 눌러 보지 않았다.
- 2초 폴링은 문서 수·사용자 수가 늘면 그대로 요청 수가 된다. 지금 규모에서는 문제없지만 잰 적은 없다.

---

## 4. 나에게 물어볼 질문 (코드만으로는 이유를 알 수 없는 것)

1. **JPA 스타터를 넣고 JdbcClient만 쓴 이유는?** 처음엔 JPA로 가려다 바꾼 것인가, 그냥 남은 것인가. 그래프·낙관적 잠금·`skip locked` 때문에 SQL을 직접 쓰기로 했다면 그게 이 프로젝트의 큰 결정 중 하나인데 어디에도 적혀 있지 않다.
2. **왜 모듈형 모놀리스인가?** 서버를 나누지 않고 패키지로만 나눈 이유(팀 규모? 배포 단순성?), 그리고 경계를 강제하지 않은 것은 의도인가 미룬 것인가.
3. **숫자들의 근거는?** 임대 10분, 최대 3회, 탐색 깊이 10, PBKDF2 600,000회, 사용자 토큰 12시간, 에이전트 토큰 30일, 답변 근거 150개. 재 본 값인가, 권고치인가, 감으로 잡은 값인가.
4. **React 웹을 버리고 Android로 간 이유는?** PR 3개를 머지한 다음 날 지웠다. 사용 장면(회의 직후 휴대폰?)인가, 과제 조건인가, 배우고 싶은 것이었나. 웹과 앱을 둘 다 두지 않은 이유도.
5. **설계는 CLOVA·LangGraph·FastAPI인데 구현은 Claude·순수 Python인 이유는?** 임시 선택인가, 방향을 바꾼 것인가. 바꾼 것이라면 `docs/contexa_architecture.md`의 그림과 9장 "정해야 할 것"을 닫아야 한다.
6. **2026-11-15 하루에 모듈 13개가 각각 커밋된 경위는?** 한꺼번에 만든 것을 모듈별로 나눠 커밋한 것인가. 그렇다면 실제 개발 순서와 그 과정에서 고친 것들(모듈 지도 8장의 9개 말고)이 어디에 남아 있나.
7. **테스트가 개발 DB를 같이 쓰는 것은 의도인가?** Testcontainers나 테스트 전용 DB를 쓰지 않은 이유(속도? 설정 비용?).
8. **검토를 "카드 묶음" 단위로 한 이유는?** 줄 하나하나가 아니라 카드와 딸린 줄을 한 번에 판단하게 한 것은 사용성 판단으로 보이는데, 어떤 대안을 놓고 고른 것인가.
9. **"근거를 볼 수 있는 사람에게만 카드를 보여 준다"의 비용을 어떻게 봤나?** 읽을 때마다 열람 범위를 계산하는데, 느려질 것을 알고 정확성을 고른 것인가.
10. **`docs/contexa_proposal.md`는 제출한 문서인가?** 그래서 고치지 않은 것인지, 지금 구현과 어긋난 부분을 어디까지 맞출지.
