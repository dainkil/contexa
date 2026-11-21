# Contexa 아키텍처

> 작성일: 2026-11-15 · 고친 날: 2026-11-21(클라이언트를 Android 앱으로, 구현 상태 갱신) · 대상: 개발팀 · 근거: 제안서 부록 A, 모듈 지도 v2, `server/` 코드를 대조해 정리

Java 모듈형 모놀리스 하나가 업무 기록과 규칙을 모두 갖고, Python AI 처리부가 LangGraph로 분석·생성을 돌려 후보만 제출하는 구조다. 저장소는 PostgreSQL 하나다.

## 1. 먼저 답부터

| 질문 | 답 |
|---|---|
| LLM 위키를 쓰나? | 쓴다. 다만 제품이 아니라 방식이다. 확인된 결정과 원문으로 안건별 페이지 갱신안을 LLM이 만들고, 사람이 검토해 버전으로 쌓는다. 서버에서는 `generation-wiki` 모듈이 맡고, 위키 페이지는 `ARTIFACT` 노드로 저장된다. |
| LangChain · LangGraph · LangSmith는? | 셋 다 Python AI 처리부 안에서만 쓴다. LangChain은 모델·임베딩 연결, LangGraph는 단계 제어와 복구, LangSmith는 추적·평가다. Java 서버는 이 셋을 모른다. |
| 지금 어디까지 만들었나? | (2026-11-21 기준) Java 13개 모듈 전부, Python AI 일꾼(자르기·후보 뽑기·답변), Android 앱(자료함·검토함·질문·맥락)이 동작한다. LangChain 계열, 임베딩 검색, 위키, 파일 저장소는 아직 없다. 자세한 것은 8장. |

## 2. 전체 구성

Java 서버의 모듈은 모두 구현됐다. Python 쪽의 LangGraph·LangChain·`embed_api`, CLOVA Studio, LangSmith, 체크포인트, Object Storage는 설계이고 아직 만들지 않았다. 지금 AI 처리부는 표준 라이브러리와 Claude API로 만든 일꾼 하나다(8장).

```mermaid
flowchart TB
    subgraph CLIENT["클라이언트"]
        WEB["Android 앱 · Kotlin · Compose<br/>ContextApi (읽기) · ReviewApi (쓰기)"]
        AGENT["외부 AI 에이전트<br/>MCP 도구: 조회 6개 + 후보 제안 1개"]
    end

    subgraph JAVA["Java 업무 서버 · Spring Boot 4 · Java 21 · :8090"]
        direction TB
        API["8층 api<br/>WebController · InternalJobController · McpToolController"]
        PL["7층 pipeline<br/>AI 결과를 종류별로 분배"]
        GEN["6층 generation-wiki<br/>답변 · 브리핑 · 위키 · 초안"]
        GW["5층 context-gateway<br/>읽기의 유일한 창구"]
        RV["5층 review-impact<br/>쓰기의 유일한 창구"]
        RET["4층 retrieval"]
        ENT["4층 entity-review"]
        DT["4층 decision-task"]
        GR["3층 decision-graph"]
        DOC["2층 document-evidence"]
        IAM["1층 identity-access"]
        JOB["1층 job-operation"]
        CON["0층 shared-contract"]
        API --> PL --> GEN --> GW
        API --> RV
        PL --> RV
        GW --> RET & ENT & DT
        RV --> ENT & DT
        RET --> DOC
        ENT --> GR
        DT --> GR
        GR --> DOC --> IAM
        PL --> JOB
        IAM --> CON
        JOB --> CON
    end

    subgraph PY["Python AI 처리부 · FastAPI"]
        direction TB
        WORKER["worker<br/>작업 임대 · 갱신 · 결과 제출"]
        LG["LangGraph<br/>PARSE · EXTRACT · GENERATE 그래프"]
        LC["LangChain<br/>챗 모델 · 임베딩 · 구조화 출력 어댑터"]
        EMB["embed_api<br/>POST /embed"]
        WORKER --> LG --> LC
        EMB --> LC
    end

    subgraph EXT["외부 서비스"]
        MODEL["CLOVA Studio<br/>생성 · 임베딩 모델 (우선 검토)"]
        LS["LangSmith<br/>추적 · 평가"]
    end

    DB[("PostgreSQL 16 + pgvector · :5434<br/>업무 원장 · graph_edge")]
    CP[("LangGraph 체크포인트<br/>독립 스키마 · 실행 복구 상태")]
    OBJ[("비공개 Object Storage<br/>원본 파일 · 선택적 녹음")]

    WEB -->|"HTTPS /api/v1"| API
    AGENT -->|"MCP"| API
    WORKER <-->|"/internal/v1/jobs/*"| API
    RET -->|"POST /embed"| EMB
    LC --> MODEL
    LG -.-> LS
    LG --> CP
    JAVA -->|"JDBC (Java만)"| DB
    DOC --> OBJ
```

모듈별 역할:

| 층 | 모듈 | 맡는 일 | 상태 |
|---|---|---|---|
| 8 | `api` | 요청을 알맞은 모듈로 넘긴다. 번역만 한다 | 미착수 |
| 7 | `pipeline` | AI 결과를 종류별로 분배 (PARSE → EXTRACT → GENERATE) | 미착수 |
| 6 | `generation-wiki` | 답변·브리핑·위키·초안의 요청과 버전 저장. 저장 직전 `verifyCurrent` | 미착수 |
| 5 | `context-gateway` | 읽기의 유일한 창구. 항상 `ContextPacket` 반환 | 미착수 |
| 5 | `review-impact` | 쓰기의 유일한 창구. 후보 접수, 확인, 영향 추적 | 미착수 |
| 4 | `retrieval` | 벡터 + 이름·별칭 검색 | 미착수 |
| 4 | `entity-review` | 인물·기관, 언어별 표기 | 미착수 |
| 4 | `decision-task` | 안건·결정·조건·업무, 현재 결정 계산 | 미착수 |
| 3 | `decision-graph` | 줄 추가, 상태 전이, 근거(BASIS)·영향(IMPACT) 탐색, 낙관적 잠금 | **구현됨** |
| 2 | `document-evidence` | 문서 버전, 발언·문단 단위 원문 구간 | 미착수 |
| 1 | `identity-access` | 인증, 자료 열람 권한, 에이전트 토큰 | 미착수 |
| 1 | `job-operation` | 영속 작업 큐: 임대, 재시도, 중복 방지 | 미착수 |
| 0 | `shared-contract` | `NodeType`, `RelType`, `ReviewStatus`, `Edge`, `Ontology.isAllowed` | **구현됨** |

위층은 아래층만 호출하고, 같은 층끼리는 호출하지 않는다. 정확한 의존 표는 [모듈 지도](contexa_module_map.md) 부록 B.1을 따른다.

## 3. 구조를 고정하는 규칙 다섯 가지

1. **읽기는 `context-gateway`로만.** 화면, Q&A, 외부 AI가 같은 답과 같은 확인 상태를 받는다.
2. **쓰기는 `review-impact`로만.** 확인, 정정, 보류, 담당 수락, 업무 상태 변경이 모두 여기를 거친다.
3. **AI는 `CANDIDATE`만 낸다.** `CONFIRMED`는 근거를 본 사람만 찍을 수 있고, 근거 없는 확인은 거절된다.
4. **관계의 상태는 `graph_edge`에만 적는다.** 방향은 항상 기대는 쪽 → 기댈 대상이다. 앞으로 따라가면 근거, 거꾸로 따라가면 영향이다.
5. **근거를 볼 수 없으면 카드와 줄도 보이지 않는다.** 작업(JOB)은 요청한 사용자의 권한으로 조회한다.

## 4. Python AI 처리부 안: LangGraph 그래프 세 개

작업 종류마다 그래프 하나다. 노드 구성은 모듈 지도 부록 B.9의 함수와 제안서 A.5 흐름을 그래프로 옮긴 **안**이며, 아직 코드로 확정된 것은 아니다.

### 4.1 PARSE · 문서 자르기

발언 번호가 먼저 있어야 다음 단계가 근거를 적을 수 있다.

```mermaid
flowchart LR
    A["원본 받기<br/>GET /jobs/{id}/content<br/>(Java API)"] --> B["발언·문단 단위로 자르기<br/>parse.extract_spans"]
    B --> C["청크 임베딩<br/>parse.embed_chunks<br/>(LangChain)"]
    C --> D["제출<br/>POST /jobs/{id}/result<br/>(Java API)"]
```

### 4.2 EXTRACT · 카드와 줄 후보 뽑기

기존 카드를 먼저 받아 중복 생성을 막는다.

```mermaid
flowchart LR
    A["기존 안건·결정·인물 조회<br/>gateway_client.current_state<br/>(Java API)"] --> B["후보 추출, 구조화 출력<br/>extract.extract_graph_candidates<br/>(LangChain)"]
    B --> C["온톨로지·근거 번호 검사"]
    C -->|"실패 시 재시도"| B
    C --> D["후보 제출<br/>review-impact.submitCandidates<br/>(Java API)"]
```

### 4.3 GENERATE · 답변, 브리핑, 위키 갱신안, 초안

근거가 부족하면 지어내지 않고 확인 필요로 답한다.

```mermaid
flowchart LR
    A["근거 검색<br/>gateway_client.search_evidence<br/>(Java API)"] --> B["근거·조건 따라가기<br/>gateway_client.trace<br/>(Java API)"]
    B --> C["근거 기반 생성<br/>answer.generate<br/>(LangChain)"]
    C --> D["인용 검증<br/>verify.check_citations"]
    D -->|"한도 안에서 재검색"| A
    D --> E["초안 + 참고한 카드·줄 제출<br/>generation-wiki.publishArtifact<br/>(Java API)"]
```

제출 후 Java가 `verifyCurrent`로 그사이 바뀐 카드가 없는지 확인하고, 바뀌었으면 저장하지 않고 재생성을 요청한다.

### 4.4 세 도구가 맡는 곳

| 도구 | 맡는 곳 | 주의 |
|---|---|---|
| LangChain | 모델 호출 세 지점: 임베딩, 구조화된 후보 추출, 생성. 모델 제공업체를 바꿀 때 이 어댑터만 바꾼다 | 임베딩 모델·차원·전처리 버전은 벡터와 함께 기록한다 |
| LangGraph | 단계 순서, 검증 실패 시 재시도, 작업 복구 | 중단·재개는 실행 편의일 뿐이다. 사람 검토는 Java DB의 검토 항목이 기준이고, 재개할 때 서버가 버전과 권한을 다시 확인한다 |
| LangSmith | 세 그래프의 실행 추적과 고정 평가 세트(언어별 질의 30개 이상, 이름 언급 50개 이상) 비교 | 추적 로그는 업무 기록이 아니며, 외부 전송이 허용된 정보만 보낸다 |

## 5. 흐름 1 · 자료를 올리고 사람이 확인하기까지

```mermaid
sequenceDiagram
    actor U as 사용자
    participant W as Android 앱
    participant J as Java 업무 서버
    participant D as PostgreSQL
    participant P as Python LangGraph
    participant M as CLOVA Studio
    U->>W: 전사문 업로드
    W->>J: POST /documents
    J->>D: 문서 버전과 PARSE 작업 저장
    P->>J: 작업 임대
    P->>P: 발언 단위로 자르기
    P->>M: 임베딩 요청
    P->>J: 발언과 임베딩 제출
    J->>D: 발언 번호 부여, 색인, EXTRACT 작업 등록
    P->>J: 작업 임대, 기존 카드 조회
    P->>M: 카드와 줄 후보 추출
    P->>J: 후보 제출
    J->>D: CANDIDATE로 저장, 검토 항목 생성
    J-->>W: AI 후보 표시와 함께 노출
    U->>W: 근거를 보고 확인
    W->>J: POST /reviews
    J->>D: CONFIRMED 저장, 현재 결정 재계산, 영향 목록 생성
```

## 6. 흐름 2 · 질문에 답하기

```mermaid
sequenceDiagram
    actor U as 사용자
    participant W as Android 앱
    participant J as Java 업무 서버
    participant D as PostgreSQL
    participant P as Python LangGraph
    participant M as CLOVA Studio
    U->>W: 인쇄 진행해도 돼?
    W->>J: POST /generations
    J->>D: GENERATE 작업 등록
    P->>J: 작업 임대
    P->>J: 근거 검색과 근거 따라가기
    J-->>P: ContextPacket, 요청자 권한으로 걸러짐
    P->>M: 근거 기반 답변 생성
    P->>P: 인용 검증
    P->>J: 초안과 참고한 카드, 줄 제출
    J->>J: verifyCurrent
    alt 참고한 카드가 그대로
        J->>D: ARTIFACT 저장, USES 줄 기록
        J-->>W: 근거와 확인 상태가 붙은 답변
    else 그사이 바뀜
        J->>D: 저장하지 않고 재생성 작업 등록
    end
```

외부 AI가 MCP로 같은 질문을 해도 `context-gateway`의 같은 함수를 거치므로 같은 근거와 같은 확인 상태를 받는다.

## 7. LLM 위키가 이 구조에서 놓이는 자리

- **무엇인가.** 안건별 현재 상태, 배경, 변경 과정, 미해결 사항을 누적 정리한 페이지다(FR-060, FR-061). 새 자료나 확인·정정이 생기면 LLM이 갱신안을 만들고, 기존 버전과 비교해 사람이 반영한다.
- **어떻게 저장되나.** 위키 페이지도 답변·초안과 같은 `ARTIFACT` 노드다. 만들 때 참고한 카드마다 `USES` 줄이 그어지고, 그 카드가 바뀌면 영향 탐색에 걸려 다음에 열 때 재확인 필요로 표시된다.
- **지키는 선.** 위키는 원본에서 다시 만들 수 있는 파생 자료다. 위키만으로 사실을 확정하지 않고, 주요 사실은 원문과 검토 기록으로 확인한다. 공용 위키에는 프로젝트 공용 자료만 쓴다.

## 8. 기술 스택과 현재 상태

| 영역 | 기술 | 맡는 일 | 상태 |
|---|---|---|---|
| 업무 서버 | Java 21 · Spring Boot 4 · JdbcClient · Flyway | 권한, 상태 전이, 결정 그래프, 작업 큐, 감사 | 13개 모듈 구현 |
| 데이터 | PostgreSQL 16 · pgvector | 업무 원장, 그래프, 임베딩 검색 | 원장·그래프 구현. 임베딩 검색은 없고 글자 일치로 찾는다 |
| AI 처리부 | Python · FastAPI | 작업 워커, `/embed` | 일꾼 구현(표준 라이브러리 + `anthropic`). FastAPI·`/embed`는 미착수 |
| 모델 연결 | LangChain | 챗 모델, 임베딩, 구조화 출력 어댑터 | 미착수 |
| 처리 흐름 | LangGraph | PARSE·EXTRACT·GENERATE 단계 제어와 복구 | 미착수 |
| 평가·추적 | LangSmith | 언어별 평가, 프롬프트·모델 비교, 지연·오류 추적 | 미착수 |
| 생성 모델 | CLOVA Studio 우선 검토 | 후보 추출, 질의응답, 브리핑·초안·위키 | 지금은 Claude API로 후보 추출과 답변을 만든다. CLOVA 실호출은 검증 전 |
| 지식 축적 | LLM 기반 누적 위키 | 안건별 페이지 갱신안과 버전 | 미착수 |
| 클라이언트 | Android · Kotlin · Jetpack Compose | 자료함, 검토함, 질문·답변, 결정 현황·이력, 업무, 위키, 초안 | 자료함·원문 뷰어, 검토함, 질문·답변·브리핑, 맥락(결정·이력·근거·할 일) 구현. 이름 검토, 위키, 초안 편집은 미착수. React 웹은 2026-11-21에 지웠다 |
| 외부 연결 | MCP | 게이트웨이 조회 6개 + 후보 제안 | `McpController` 구현 |
| 파일 | 비공개 Object Storage | 원본 파일, 선택적 녹음 | 미착수 |

## 9. 팀에서 정해야 할 것

제안서(부록 A)와 모듈 지도(v2)가 서로 다르게 말하거나, 한쪽에만 적혀 있는 부분이다.

1. **LangGraph 체크포인트를 어디에 두나.** 제안서는 독립 체크포인트 스키마를 두고, 모듈 지도는 Python이 DB에 접속하지 않는다고 한다. 체크포인트 전용 스키마와 계정만 Python에 열어 줄지, 체크포인트 없이 작업 재시도로만 복구할지 정해야 한다.
2. **모듈 지도에 LangChain 계열이 없다.** 부록 B.9의 Python 설계는 일반 함수 목록이고 그래프 구성이 적혀 있지 않다. 4장의 그래프 세 개는 두 문서를 합쳐 그린 안이므로, AI 담당이 노드와 재시도 한도를 확정해야 한다.
3. **위키의 서버 쪽 설계가 얇다.** 모듈 이름은 `generation-wiki`지만 공개 함수에는 위키 전용 동작(안건별 페이지, 갱신안 비교, 반영)이 아직 없다. `GenKind`에 위키를 넣는 정도로 끝나는지 확인이 필요하다.
4. **의존 관계 저장 방식.** 제안서는 `resource_revision`·`dependency` 테이블을, 모듈 지도는 `graph_edge`의 `USES` 줄을 쓴다. 날짜가 더 늦은 모듈 지도가 대체한 것으로 읽었지만 명시된 적은 없다.
