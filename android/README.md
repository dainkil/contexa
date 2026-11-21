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

에뮬레이터의 `10.0.2.2` 주소는 쓰지 않는다. 에뮬레이터 37.3에서 앱의 연결이 시간 초과로 실패했다.

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
  - `ui/theme/` — 색·글자·모서리. 모양은 당근의 [SEED Design](https://github.com/daangn/seed-design)(Apache-2.0)을 따른다. 색·모서리·글자 크기 토큰과 버튼·배지·칩 규격을 옮겼고, 강조색만 주황 대신 SEED의 초록 단계를 쓴다. 당근의 로고·캐릭터 같은 브랜드 자산은 쓰지 않는다.
  - `ui/components/` — 여러 화면이 같이 쓰는 버튼·꼬리표·근거 인용·전체 화면 입력 창.
  - `ui/auth/`, `ui/projects/` — 로그인과 프로젝트 고르기.
  - `ui/project/` — 프로젝트 안의 아래 탭(자료함·검토함·질문·맥락).
  - `ui/documents/`(자료함·원문 뷰어), `ui/reviews/`(검토함), `ui/ask/`(질문·답변·브리핑), `ui/context/`(결정 현황·이력·근거 따라가기·할 일·계정 연결) — 화면과 그 규칙.

AI 일꾼(`ai/`)이 꺼져 있으면 올린 문서는 "처리 대기"에 머문다. 30초가 지나면 앱이 일꾼을 확인하라고 안내한다. 질문도 마찬가지로 "쓰는 중"에 머물고, 3분 뒤에 같은 안내가 나온다.
