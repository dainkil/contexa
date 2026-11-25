# Contexa 배포 계획 v3 — 초대제 데모 서버

작성 2026-11-25. **서버 배포는 아직 실행하지 않은 계획이다.** 11/21 계획(쿠버네티스·CI 연습)은 git 기록에 있고, 그중 나중에 할 것은 맨 아래에 남겼다.

v3에서 바뀐 것(11/25): Google Play 출시는 보류한다(7절). 포트폴리오를 보는 사람은 스토어가 아니라 GitHub README를 보므로,
공개는 **README 쇼케이스 + GitHub Releases의 서명된 APK + 초대제 데모 서버**로 한다. 서버는 Oracle Cloud 무료 ARM VM으로 정했다.

## 목표와 범위

- 공개 HTTPS 주소 하나에 server·db·일꾼을 올리고, release 앱이 그 주소에 붙는다.
- 쓰는 사람: 나와, 요청해서 허용 목록에 넣은 사람(면접관·검토자). **공개 가입과 공용 데모 계정은 두지 않는다.**
  질문·문서 처리마다 내 API 키로 모델 비용이 나가고, 한 프로젝트에 올린 글은 구성원 모두에게 보이기 때문이다.
- 데이터: 합성·시연 데이터만. 실제 사람의 회의록은 이용 권한이 있을 때만 넣는다.
- 하지 않는 것: Play 출시, 쿠버네티스, CI/CD, 자동 배포, Langfuse(계속 내 컴퓨터에만), 임베딩 검색(5단계 전), MCP 외부 공개(6단계).

## 구성: VM 한 대 + Docker Compose

```
인터넷 ─443─▶ Caddy(자동 HTTPS) ─/api/v1/*─▶ server:8090 ──▶ db:5432 (pgvector, 볼륨)
                                                 ▲
                       ai 일꾼 ─ http://server:8090/internal ┘ ─443─▶ 모델 API
```

- 밖으로 여는 포트는 80·443(Caddy)과 22(SSH, 내 IP만)뿐이다. server·db·일꾼은 Compose 내부 망에만 있다.
- Caddy는 `/api/v1/*`만 server로 넘긴다. `/internal`, `/dev`, `/mcp`, `/`(개발용 페이지)는 밖에서 404다.
- 일꾼은 1개. 작업을 하나씩 처리하므로 모델 호출이 몰리지 않는다.

## 정한 것

| 항목 | 결정 |
| --- | --- |
| 공개 형태 | README 쇼케이스 + GitHub Releases APK + 초대제 데모 서버. Play는 보류(7절) |
| 서버 | Oracle Cloud Always Free ARM VM(Ampere A1). 무료 한도와 남은 자리는 만들 때 콘솔에서 확인한다. 막히면 유료 소형 VPS |
| 주소 | 무료 서브도메인(DuckDNS 등) |
| 가입 | 허용 이메일 목록만(`CONTEXA_ALLOWED_EMAILS`). 요청한 사람만 넣고 계정을 따로 알려 준다 |
| 일꾼 | 켜기 + Console 월 사용 한도 + 사용자당 하루 AI 작업 수(`CONTEXA_AI_DAILY_LIMIT`, 기본 30) |
| 시연 데이터 | 넣기(4절에서 내부 망으로 한 번만) |
| 계정 삭제 | 나 혼자인 프로젝트는 통째로 지우고, 함께 쓰는 프로젝트에서는 개인 메모·그 메모에서만 나온 카드·내 질문을 지우고 나간다. 남는 공유 문서·검토 기록의 이름은 "탈퇴한 사용자" |

## 1. 코드 준비 (로컬에서, 작은 커밋으로)

된 것(11/25, 브랜치 `feat/release-hardening`, server 테스트 238개)

1. **prod 프로필**(`server/src/main/resources/application-prod.yml`, `SPRING_PROFILES_ACTIVE=prod`)
   - `server.address: 0.0.0.0`, `server.forward-headers-strategy: native`, 개발용 페이지와 `/dev/*` 끄기
   - DB 접속 정보·일꾼 토큰·가입 허용 목록은 기본값 없는 환경 변수로 받는다. 빠지면 server가 켜지지 않는다.
   - 일꾼 토큰이 `dev-worker-token`이거나 32자 미만이면 켜지지 않는다(`api/WorkerTokenCheck`).
   - 개발용 `application.yml`과 기존 테스트는 그대로다.
2. **가입 제한**: `contexa.auth.allowed-emails`에 없는 이메일은 403. 개발에서는 비어 있으면 누구나 가입한다(`identity/SignupPolicy`).
3. **로그인 시도 제한**: 15분 안에 같은 이메일로 5번, 같은 주소로 20번 넘게 틀리면 429와 `Retry-After`.
   server 메모리로 센다(인스턴스 1개 전제, `identity/LoginThrottle`).
4. **AI 비용 제한**: 사용자당 24시간에 맡기는 문서 올리기 + 질문 수 `contexa.ai.daily-limit-per-user`. 넘으면 429(`job/AiQuota`).
5. **계정 삭제**: `POST /api/v1/auth/me/delete`(비밀번호 다시 확인, `pipeline/AccountDeletion`·`review/Erasure`). 앱 화면은 아직 없다.
6. **AI 답변 신고**: `POST /api/v1/projects/{id}/generations/{artifactId}/reports`(V13 `artifact_report`, 로그에 신고 줄). 앱 화면은 아직 없다.
7. **앱 release 서명과 아이콘**: 키는 레포 밖에 두고 비밀번호는 환경 변수로 받는다(5절).

남은 것(서버를 만들 때)

8. **이미지**: `server/Dockerfile`(JDK 21로 `bootJar` → JRE 21에 jar만, 비루트), `ai/Dockerfile`(python 3.12-slim, `pip install .`,
   비루트, 진입점 `contexa-worker`), 각 폴더의 `.dockerignore`.
9. **배포 파일**: `deploy/compose.prod.yml`(db·server·ai·caddy, 볼륨, 헬스체크, 로그 크기 제한), `deploy/Caddyfile`,
   `deploy/env.example`(변수 이름만).

있으면 좋은 것: 로그아웃(`POST /api/v1/auth/logout`, 토큰 폐기), 백업 스크립트(`deploy/backup.sh`).

확인

- `./gradlew test`, ai·eval 테스트.
- 이 맥에서 `docker compose -f deploy/compose.prod.yml up --build`로 전체를 띄운다. release 빌드 앱으로 다음을 해 본다.
  - 허용 목록 밖 이메일로 가입하면 거절된다.
  - 로그인 → 문서 올리기 → 처리 완료 → 검토 → 질문.
- prod 프로필에서 다음을 확인한다.
  - `/dev/demo` → 404, 밖에서 `/internal` → 404
  - 일꾼 토큰이 없거나 기본값이면 server가 켜지지 않는다.

## 2. 서버 만들기 (Oracle Cloud)

- Oracle Cloud 가입(카드 확인 있음) → Ampere A1 VM, Ubuntu LTS. 카드·신분 정보는 채팅이나 레포에 적지 않는다.
- SSH는 키 로그인만(비밀번호 로그인 끄기). 자동 보안 업데이트.
- 포트는 **두 군데**에서 연다.
  - VCN 보안 목록(Security List) 인바운드: 22(내 IP만)·80·443
  - VM 안의 방화벽: Oracle Ubuntu 이미지는 iptables에 22만 열려 있는 경우가 있다. 80·443을 열고 저장한다.
- Docker Engine과 Compose 플러그인을 설치한다.
- DuckDNS에서 서브도메인을 만들고 VM 공인 IP를 건다. 고정 공인 IP(Reserved IP)를 쓰면 주소가 바뀌지 않는다.
- CPU가 ARM(arm64)이다. 이미지는 VM에서 빌드하므로 따로 맞출 것은 없다. 베이스 이미지(Temurin, python slim, pgvector, Caddy)는 arm64를 낸다.

## 3. 비밀값

- VM의 `/opt/contexa/.env`(권한 600)에만 둔다.
  - `POSTGRES_PASSWORD`, `CONTEXA_WORKER_TOKEN`(`openssl rand -hex 32`)
  - `ANTHROPIC_API_KEY`, `CONTEXA_ALLOWED_EMAILS`, 도메인
- 모델 키는 **배포용으로 새로 발급**한다. 로컬 키와 나눠 두면 문제가 생겼을 때 그 키만 지우면 된다. Console에서 월 사용 한도를 건다.
- git, 이미지, 채팅에는 넣지 않는다. 로컬 `.env`를 그대로 복사하지 않는다.

## 4. 올리기

1. VM에서 `git clone` → `docker compose -f deploy/compose.prod.yml up -d --build`. Flyway가 기동할 때 스키마를 만든다.
2. Caddy가 인증서를 받았는지 확인한다.
3. 허용 목록의 내 이메일로 가입하고 로그인한다.
4. 시연 데이터가 필요하면, dev-seed를 켠 server를 내부 망에서만 한 번 띄워 `/dev/demo`를 부르고 바로 내린다(밖에는 열리지 않는다).
5. 아래 점검 목록을 돈다.

## 5. 앱: 서명된 APK를 GitHub Releases로

- 서명 키(키스토어)는 레포 밖 `~/.contexa/contexa-release.jks`에 두고 따로 백업한다.
  - **잃어버리면 같은 앱으로 업데이트를 낼 수 없다.**
  - 비밀번호는 환경 변수 `CONTEXA_KEYSTORE_PASSWORD`로 받는다. 키스토어가 없으면 release 빌드는 서명 없이 만들어진다.
- 빌드: `./gradlew :app:assembleRelease -PapiBaseUrl=https://주소/api/v1/`. 평문 HTTP는 release에서 막혀 있다.
- 공개: 데모 서버가 생긴 뒤 `v0.x.y` 태그와 GitHub Release에 APK를 올린다. APK에는 server 주소가 박히므로 서버 없이 올리지 않는다.
  - 받는 사람은 "출처를 알 수 없는 앱 설치"를 허용해야 한다. README에 SHA-256 체크섬을 함께 적는다.
- 버전: 올릴 때마다 `versionCode`를 늘린다.

## 6. 운영

- 백업: 매일 `pg_dump`를 VM 밖(내 컴퓨터 등)으로 옮기고 7일치를 둔다. 복원을 한 번 해 본다.
- 업데이트: 백업 → `git pull` → `docker compose up -d --build`. 스키마는 되돌리지 않으므로 되돌릴 때는 이전 커밋 + 백업 복원.
- 신고: `select * from artifact_report order by created_at desc`로 보고 프롬프트·검사를 고친다.
- 비용: Console 사용량을 본다. Oracle 무료 한도를 넘는 자원을 만들지 않는다.

## 점검 목록 (올린 뒤)

- 인증서가 유효하고 http → https로 넘어간다.
- 밖에서 `/internal/v1/jobs/ontology`, `/dev/demo`, `/`, `/mcp` → 404. 5432·8090 포트는 닫혀 있다.
- 다음이 막힌다.
  - 허용 목록 밖 이메일 가입 → 거절
  - 로그인을 연속으로 틀리면 → 429
  - 하루 AI 작업 수를 넘기면 → 429
- 앱: 로그인 → 문서 올리기 → 처리 완료 → 검토 → 질문 답변 → 인용을 눌러 원문 발언 열기.
- 일꾼 로그에 키와 회의 원문이 찍히지 않는다.

## 7. Google Play 출시 — 보류(11/25)

포트폴리오 목적에는 비용이 크다. 요건은 2026-11-25에 Google 도움말로 확인했고, 정책이 자주 바뀌므로 다시 할 때 확인한다.

| 요건 | 내용 | 지금 |
| --- | --- | --- |
| 개발자 계정 | 등록비 25달러, 신분증 확인(며칠 걸릴 수 있다) | 안 함 |
| 비공개 테스트 | 2023-11-13 이후 만든 개인 계정은 테스터 12명 이상이 14일 연속 참여해야 공개 출시를 신청할 수 있다 | 안 함 |
| 계정 삭제 | 앱 안에서 지우는 길 + 앱 없이 요청하는 웹 페이지 | server는 됨(1절 5). 앱 화면·웹 페이지 없음 |
| AI 답변 신고 | 앱을 나가지 않고 부적절한 결과를 신고 | server는 됨(1절 6). 앱 화면 없음 |
| 개인정보처리방침 | 모으는 것, 회의 내용이 모델 API로 전송됨, 보관과 삭제 | 없음. 초대제 데모는 README 안내로 대신한다 |
| 그래픽 | 적응형 아이콘(5절에서 만듦), 스토어 그래픽, 스크린샷, 설명 | 아이콘만 |
| 서명 | AAB(`bundleRelease`) + Play 앱 서명 | APK 서명만 |
| 심사용 계정 | "앱 액세스"에 시연 계정 | 없음 |

이미 맞춘 것: `targetSdk 37`, release는 HTTPS만(`network_security_config`), 광고·위치 권한 없음(인터넷 권한만).

## 나중에 (11/21 계획에서 남긴 것)

- CI: GitHub Actions로 테스트, 비밀값 검사(gitleaks), 취약점 검사(Trivy), 이미지 빌드.
- 쿠버네티스(k3s) 연습, 자동 배포.
- MCP 공개(6단계): `/mcp`를 에이전트 토큰과 Origin 허용 목록으로만 연다.
- 임베딩 서비스(5단계): bge-m3 CPU 추론에 메모리가 더 든다. VM 크기를 그때 다시 본다.
