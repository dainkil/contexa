-- 출입 관리의 장부: 사용자, 프로젝트, 구성원, 접속 토큰, 문서 열람 범위.

create table app_user (
    id            uuid        primary key,
    email         text        not null unique,       -- 소문자로 저장한다
    display_name  text        not null,
    password_hash text        not null,              -- 비밀번호 원문은 저장하지 않는다
    created_at    timestamptz not null default now()
);

create table project (
    id         uuid        primary key,
    title      text        not null,
    created_by uuid        not null references app_user (id),
    created_at timestamptz not null default now()
);

-- OWNER: 구성원 관리와 검토. REVIEWER: 검토(확인·정정). MEMBER: 읽기와 자기 업무.
create table project_member (
    project_id uuid        not null references project (id),
    user_id    uuid        not null references app_user (id),
    role       text        not null check (role in ('OWNER', 'REVIEWER', 'MEMBER')),
    joined_at  timestamptz not null default now(),
    primary key (project_id, user_id)
);
create index project_member_user_idx on project_member (user_id);

-- 접속 토큰. 토큰 원문은 발급할 때 한 번만 보여 주고, 여기에는 해시만 저장한다.
-- USER 토큰은 로그인한 사람의 것이고, AGENT 토큰은 외부 AI 에이전트가 한 프로젝트만 읽도록 발급한 것이다.
create table access_token (
    id         uuid        primary key,
    token_hash text        not null unique,
    kind       text        not null check (kind in ('USER', 'AGENT')),
    user_id    uuid        not null references app_user (id),   -- USER: 주인, AGENT: 발급한 사람
    project_id uuid        references project (id),             -- AGENT 토큰만 가진다
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    revoked_at timestamptz,
    constraint access_token_agent_has_project check ((kind = 'AGENT') = (project_id is not null))
);

-- 문서를 누가 읽을 수 있는가. PROJECT는 구성원 모두, PRIVATE는 올린 사람만(개인 메모).
-- external_ok가 참인 문서만 외부로 나가는 답변과 외부 에이전트에게 근거로 쓰인다.
create table document_acl (
    document_id uuid    primary key,
    project_id  uuid    not null references project (id),
    owner_id    uuid    not null references app_user (id),
    visibility  text    not null check (visibility in ('PROJECT', 'PRIVATE')),
    external_ok boolean not null default false
);
create index document_acl_project_idx on document_acl (project_id);
