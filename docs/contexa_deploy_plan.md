# Contexa 배포 계획 (보류)

작성 2026-11-21. **아직 실행하지 않은 계획이다.** 저장소에는 지금 Dockerfile·CI·쿠버네티스 매니페스트가 없고, `docker-compose.yml`은 개발용 DB만 띄운다.

목표는 무료로, 보안을 챙기며 배포를 연습하는 것이다. 클라이언트가 Android 앱이므로 배포 대상은 server·ai·db이고, 앱은 APK로 따로 전달한다.

무료 조건과 가격은 실행 시점에 다시 확인한다. AWS EKS는 클러스터만 켜 두어도 과금되므로 무료 연습에서 제외했다. AI 일꾼은 Claude API 요금이 들므로 사용 한도를 걸고, 클라우드에서는 기본 0개로 둔다.

## 공개 전에 반드시 고칠 것 (현재 코드 기준)

| 문제 | 위치 |
|---|---|
| `POST /dev/seed`가 기본으로 켜져 있어 누구나 데모 계정 토큰을 받는다 | `application.yml`의 `contexa.dev-seed.enabled: true` |
| 일꾼 토큰 기본값이 `dev-worker-token`이다 | `application.yml`, `ai/contexa_ai/config.py` |
| DB 계정·비밀번호가 `contexa`/`contexa`다 | `application.yml`, `docker-compose.yml` |
| 일꾼 전용 `/internal/v1/jobs`가 사용자 API와 같은 포트에 있다 | `api/InternalJobController.java` |
| 로그인 시도 횟수 제한이 없고 로그아웃 API가 없다 | `api/AuthController.java` |
| 개발용 페이지가 `/`에서 서비스된다 | `server/src/main/resources/static/index.html` |

## D1. 컨테이너 이미지

- `server/Dockerfile`: 2단 빌드(JDK 21로 `bootJar` → `eclipse-temurin:21-jre`에 jar만 복사), 비루트 사용자.
- `ai/Dockerfile`: `python:3.12-slim`에 `pip install .`, 진입점 `contexa-worker`, 비루트 사용자.
- 각 폴더 `.dockerignore`: `build/`, `.gradle/`, `.venv/`, 테스트 산출물 제외.
- `compose.full.yml`: db + server + ai를 한 번에 띄워 이미지가 서로 붙는지 확인. 비밀값은 gitignore된 `.env`에서 읽는다. 기존 `docker-compose.yml`은 그대로 둔다.

## D2. 보안 수정 (서버)

개발 흐름과 기존 테스트는 `application.yml` 기본값에 기대고 있으므로 건드리지 않고 `application-prod.yml` 프로필을 추가한다.

- `server.address: 0.0.0.0`
- `contexa.dev-seed.enabled: false` — `DevSeedController`·`DevSeed`는 이미 `@ConditionalOnProperty`라 설정만으로 꺼진다.
- `spring.web.resources.add-mappings: false` — 개발용 페이지 차단.
- DB 접속 정보와 `CONTEXA_WORKER_TOKEN`은 기본값 없는 환경 변수로. prod에서 토큰이 `dev-worker-token`이거나 32자 미만이면 기동 실패.
- `server.forward-headers-strategy: native` — 인그레스 뒤에서 원래 주소와 HTTPS 여부 인식.
- `spring-boot-starter-actuator` 추가, 관리 포트를 8091로 분리하고 `health`만 노출(프로브용).
- `POST /api/v1/auth/logout` — 사용자 토큰 폐기. `identity/IdentityAccess.java`의 에이전트 토큰 폐기 방식(`revoked_at`)을 재사용.

## D3. 로컬 쿠버네티스 (kind)

Kustomize로 `deploy/k8s/base` + `overlays/local` + `overlays/cloud`.

- `db`: `pgvector/pgvector:pg16` StatefulSet + PVC + ClusterIP Service.
- `server`: Deployment(`SPRING_PROFILES_ACTIVE=prod`, 프로브는 8091의 `/actuator/health/{liveness,readiness}`) + Service. Flyway가 기동 때 마이그레이션.
- `ai`: Deployment(`CONTEXA_SERVER_URL=http://server:8090`). 외부로 여는 포트 없음.
- 인그레스는 Traefik으로 통일(k3s 기본 내장). **`/api/v1`만 연다.** `/internal`, `/dev`, `/actuator`, `/mcp`는 경로를 만들지 않는다.
- `/api/v1/auth/*`에 요청 횟수 제한(로그인 무차별 대입 방지).
- NetworkPolicy: 기본 전부 차단 후 인그레스 → server:8090, ai → server:8090, server → db:5432, ai → 외부 443만 허용.
- 파드 공통: `runAsNonRoot`, `readOnlyRootFilesystem`, `capabilities: drop [ALL]`, `seccompProfile: RuntimeDefault`, `automountServiceAccountToken: false`, 리소스 요청·한도. 네임스페이스 Pod Security `restricted`.
- 비밀값: `secrets.example.env`(이름만)를 두고 실제 값은 gitignore된 파일에서 `secretGenerator`로 Secret 생성.

설치할 Traefik 버전과 kind의 NetworkPolicy 지원 여부는 이 단계에서 실제로 확인한다.

## D4. CI (GitHub Actions)

- PR·main 푸시마다: server 테스트(테스트가 5434 포트 DB를 쓰므로 pgvector 서비스 컨테이너를 같은 포트로), ai 테스트, Android 단위 테스트와 빌드.
- 보안 검사: gitleaks(비밀값 유출), Trivy(의존성·Dockerfile·매니페스트·이미지). 높은 등급 취약점이 있으면 실패.
- main 푸시 때 `linux/arm64`·`linux/amd64` 이미지를 GHCR에 푸시(태그는 커밋 SHA). Oracle 무료 VM이 ARM이라 arm64가 필요하다.
- 워크플로 권한 최소화(`contents: read`, `packages: write`), 액션은 커밋 SHA로 고정, Dependabot(gradle, pip, docker, github-actions).
- 저장소가 비공개면 Actions 무료 분량에 한도가 있다.

## D5. 무료 클라우드 (Oracle Cloud Always Free + k3s)

- ARM VM 1대(Ubuntu). 보안 목록에서 80·443만 공개하고 22는 본인 IP로 제한한다.
- k3s 단일 노드(`--secrets-encryption`). k8s API 포트 6443은 공개하지 않고 SSH 터널로만 `kubectl` 접속.
- 호스트 방화벽, SSH 비밀번호 로그인 끄기, 자동 보안 업데이트.
- `kubectl apply -k deploy/k8s/overlays/cloud` — 이미지는 GHCR의 SHA 태그, `ai` 복제본 0, DB 백업용 `pg_dump` CronJob.
- HTTPS: Let's Encrypt 자동 발급. 도메인이 없으면 무료 서브도메인을 쓴다.
- 배포는 우선 수동으로 하고 자동 배포(CD)는 그 뒤에 정한다.

## D6. 앱 배포

- release 빌드는 공개 HTTPS 주소를 가리키게 한다(평문 HTTP 금지).
- 서명 키는 저장소에 넣지 않고 CI 비밀값으로 관리한다.
- 무료 경로: 서명한 APK를 GitHub Releases로 전달. Play 스토어 등록은 등록비가 들어 선택 사항이다.

## 확인 방법

1. D1: `docker compose -f compose.full.yml up --build` 후 앱(또는 curl)으로 가입·로그인·문서 올리기. `docker run --rm <이미지> id`로 비루트 확인.
2. D2: `./gradlew test` 통과. prod 프로필에서 `POST /dev/seed` → 404, `GET /` → 404, 로그아웃 뒤 같은 토큰 → 401, 토큰 변수 없이 기동 → 실패.
3. D3: 모든 파드 Ready. 밖에서 `/internal/v1/jobs/claim`·`/actuator/health` → 404. 임시 파드에서 `db:5432` 접속이 막히는지, 로그인 연속 요청이 429로 바뀌는지.
4. D4: PR의 모든 작업이 통과하고 main 병합 뒤 GHCR에 두 아키텍처 이미지가 올라왔는지.
5. D5: 공개 주소에서 인증서 유효, 80 → 443 이동. 외부에서 6443·5432·8090·8091 포트가 닫혀 있는지.
