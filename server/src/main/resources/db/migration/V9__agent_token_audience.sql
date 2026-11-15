-- 외부 에이전트 토큰이 어디까지 읽는가.
--   EXTERNAL: 외부 공유가 허용된 문서의 내용만 (기본값)
--   INTERNAL: 프로젝트 구성원 모두가 읽는 문서의 내용. 개인 메모는 어느 쪽도 읽지 못한다
alter table access_token
    add column audience text not null default 'EXTERNAL' check (audience in ('INTERNAL', 'EXTERNAL'));
