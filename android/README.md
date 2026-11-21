# contexa-android

Contexa의 Android 앱. Kotlin + Jetpack Compose.

## 준비

Android Studio를 설치하고 한 번 실행해 SDK(Android 17, API 37)와 에뮬레이터 이미지를 받는다.

```sh
brew install --cask android-studio
```

Android Studio에서 이 `android/` 폴더를 열면 `local.properties`(SDK 위치)가 자동으로 생긴다.

## 실행

server가 먼저 떠 있어야 한다.

```sh
docker compose up -d db                 # 저장소 최상위에서
(cd server && ./gradlew bootRun)        # 127.0.0.1:8090
```

앱은 `http://127.0.0.1:8090/api/v1/`로 붙는다. 기기(에뮬레이터 포함)의 8090 포트를 이 컴퓨터로 돌려야 하고, 기기를 다시 켤 때마다 다시 해야 한다.

```sh
./gradlew :app:installDebug     # 포트를 돌리고(reversePort) 설치한다
./gradlew :app:reversePort      # Android Studio의 Run으로 설치했다면 이것만 따로
```

화면은 Galaxy S26 Ultra의 기본 해상도(1080×2340, 19.5:9, 가로 384dp)에 맞춰 본다. 에뮬레이터를 그 모양으로 바꾸려면:

```sh
adb shell wm size 1080x2340 && adb shell wm density 450    # 되돌리기: wm size reset, wm density reset
```

에뮬레이터의 `10.0.2.2` 주소는 쓰지 않는다. 에뮬레이터 37.3에서 앱의 연결이 시간 초과로 실패했다.

## 시연

server를 띄운 뒤 한 번만 부르면 시연용 계정과 프로젝트가 생긴다. 이미 있으면 그대로 둔다.

```sh
curl -X POST http://127.0.0.1:8090/dev/demo
```

앱에서 `demo@contexa.app` / `contexa-demo`로 로그인하고 "국제교류의 밤 준비"를 연다. 내용은 `server/.../dev/DemoSeed.java`에 있다.

| 순서 | 화면 | 보여 줄 것 |
|---|---|---|
| 1 | 자료함 | 한국어·영어·아랍어가 섞인 회의 전사문 네 개. "4차 회의"를 열면 그 회의의 할 일과 담당자, 발언이 나온다 |
| 2 | 검토함 | 4차 회의에서 AI가 뽑은 카드 네 장. "초청장 아랍어 문구 검토"를 확인하면 맥락의 할 일로 넘어간다 |
| 3 | 질문 | 한국어 질문에는 한국어로, 영어 질문에는 영어로 답한 대화. 근거 줄을 누르면 원문 발언으로 간다 |
| 4 | 맥락 → 확정 | 확정된 결정 세 가지(개최 방식·행사 날짜·장소)와 "확인" 칸(인쇄 예산 승인 전, 케이터링 업체 미정) |
| 5 | 맥락 → 할 일 → 내 할 일 | 시연 계정이 맡은 "초청장 문구 정리"를 수락한다 |
| 6 | 설정 | 내 정보와 로그아웃 |

대화에 있는 답 세 개는 미리 써 넣은 것이다. 시연 중에 새로 물으면 AI 인턴(`ai/`, `ANTHROPIC_API_KEY` 필요)이 떠 있어야 답이 온다.

server 설정은 바꾸지 않는다.

## 명령

```sh
./gradlew :app:assembleDebug        # 개발용 APK
./gradlew :app:testDebugUnitTest    # 단위 테스트
```

## 구조

- `gradle/libs.versions.toml` — 라이브러리 버전.
- `app/src/main/res/xml/network_security_config.xml` — HTTPS만 허용. `app/src/debug/`의 같은 이름 파일이 개발 빌드에서만 이 컴퓨터로 가는 평문 HTTP를 연다.
- `app/src/main/java/com/contexa/android/` — 앱 코드.
  - `api/` — server와 주고받는 타입과 호출. `session/` — 로그인 토큰 보관.
  - `ui/theme/` — 색·글자·모서리. 모양은 당근의 [SEED Design](https://github.com/daangn/seed-design)(Apache-2.0)을 따른다. 색·모서리·글자 크기 토큰과 버튼·배지·칩 규격을 옮겼고, 강조색만 주황 대신 SEED의 초록 단계를 쓴다. 당근의 로고·캐릭터 같은 브랜드 자산은 쓰지 않는다. 글자는 제목 24·소제목 18·본문 16·버튼 14, 이 네 가지만 쓴다(`Type.kt`). 화면 제목 밑에 설명 글을 붙이지 않는다.
  - `ui/components/` — 여러 화면이 같이 쓰는 버튼·꼬리표·화면 제목·구역·근거 인용·전체 화면 입력 창.
  - `ui/auth/`, `ui/projects/` — 로그인과 프로젝트 고르기.
  - `ui/project/` — 프로젝트 안의 아래 탭(자료함·검토함·질문·맥락·설정).
  - `ui/documents/`(자료함·원문 뷰어), `ui/reviews/`(검토함), `ui/ask/`(대화처럼 묻는 질문), `ui/context/`(확정된 결정의 나무 그림·확인 칸·할 일·이력·근거 따라가기), `ui/settings/`(내 정보·로그아웃) — 화면과 그 규칙.

AI 일꾼(`ai/`)이 꺼져 있으면 올린 문서는 "처리 대기"에 머문다. 30초가 지나면 앱이 일꾼을 확인하라고 안내한다. 질문도 마찬가지로 "답을 쓰는 중"에 머물고, 3분 뒤에 같은 안내가 나온다.
