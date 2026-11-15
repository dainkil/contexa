-- 작업 접수대의 장부: AI 인턴에게 맡길 일.
-- PARSE는 문서를 발언으로 자르기, EXTRACT는 카드·줄 후보 뽑기, GENERATE는 답변 쓰기다.

create table job (
    id               uuid        primary key,
    project_id       uuid        not null references project (id),
    type             text        not null check (type in ('PARSE', 'EXTRACT', 'GENERATE')),
    status           text        not null default 'QUEUED'
        check (status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    subject_id       uuid        not null,            -- 무엇에 대한 일인가. PARSE·EXTRACT는 문서의 판
    payload          text,                            -- 덧붙일 내용. GENERATE의 질문 등
    requested_by     uuid        not null references app_user (id),   -- 이 사람의 권한으로 읽는다
    idempotency_key  text        not null,            -- 같은 일을 두 번 올리지 않기 위한 값
    attempts         integer     not null default 0,
    max_attempts     integer     not null default 3,
    attempt_id       uuid,                            -- 지금(또는 마지막) 시도
    lease_token_hash text,                            -- 일을 가져간 일꾼만 아는 값의 해시
    leased_by        text,
    lease_expires_at timestamptz,                     -- 이때까지 결과가 없으면 다른 일꾼이 다시 가져간다
    last_error       text,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    finished_at      timestamptz,
    constraint job_key_uq unique (project_id, idempotency_key)
);
create index job_queue_idx on job (status, created_at);
