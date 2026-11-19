# Contexa web

Contexa 웹 화면. React 19 · TypeScript · Vite · TanStack Router/Query · Tailwind 4 · shadcn/ui.

## 실행

```sh
# 레포 루트에서 DB, server를 먼저 띄운다
docker compose up -d db
(cd server && ./gradlew bootRun)      # 127.0.0.1:8090

# web
pnpm install
pnpm dev                              # http://localhost:5173, /api → 127.0.0.1:8090 프록시
pnpm build                            # tsc + vite build
pnpm lint
pnpm test:browser:install             # 처음 한 번 (Playwright Chromium)
pnpm test
```

## 구조

- `src/api/` — server 호출은 여기만 한다.
  - `contextClient.ts`: 읽기(`/context/*`). 화면은 돌려받은 `ContextPacket`만 그린다.
  - `reviewClient.ts`: 바꾸는 동작(확인·정정·보류·거절, 업무 상태 등)과 검토함 조회.
  - `authClient.ts`: 가입·로그인·프로젝트.
  - `types.ts`: server(Java) 응답 타입을 옮긴 것. 원본은 Java 쪽이다.
- `src/routes/` — 파일 기반 라우트. `routeTree.gen.ts`는 Vite 플러그인이 만든다.
  - `(auth)/sign-in`, `(auth)/sign-up`
  - `_authenticated/projects` — 프로젝트 목록·만들기
  - `_authenticated/p/$projectId/{documents,reviews,context,briefing}`
- `src/stores/auth-store.ts` — 로그인 토큰. 지금은 sessionStorage(임시), 쿠키 로그인으로 바꿀 예정.

## 출처

[satnaing/shadcn-admin](https://github.com/satnaing/shadcn-admin) (MIT, 커밋 `e16c87f`)을 바탕으로
데모 화면과 Clerk 연동을 걷어내고 Contexa 화면을 붙였다. 원 저작권 표시는 `LICENSE`에 있다.
