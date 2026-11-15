-- 이름 확인의 장부: 인물·기관 카드와 언어별 이름 표기.

create table entity (
    id           uuid        primary key,
    project_id   uuid        not null references project (id),
    kind         text        not null check (kind in ('PERSON', 'ORGANIZATION')),
    name         text        not null,                    -- 대표 표기
    account_id   uuid        references app_user (id),    -- 이 인물이 어느 계정인지. 이름 확인과 따로 확인한다
    status       text        not null default 'CANDIDATE'
        check (status in ('CANDIDATE', 'CONFIRMED', 'HELD', 'REJECTED', 'RETRACTED')),
    revision     bigint      not null default 1 check (revision >= 1),
    confirmed_by uuid,
    confirmed_at timestamptz,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    constraint entity_confirmed_has_time check (status <> 'CONFIRMED' or confirmed_at is not null)
);
create index entity_project_idx on entity (project_id);
-- 한 프로젝트에서 한 계정은 인물 카드 하나에만 연결된다
create unique index entity_account_uq on entity (project_id, account_id) where account_id is not null;

-- 언어별 표기. 확인되지 않은 음역·번역은 CANDIDATE("제안 표기")로 남는다.
create table entity_name (
    id               uuid        primary key,
    entity_id        uuid        not null references entity (id),
    lang             text        not null check (lang in ('ko', 'en', 'ar')),
    form             text        not null,
    status           text        not null default 'CANDIDATE'
        check (status in ('CANDIDATE', 'CONFIRMED', 'REJECTED')),
    evidence_span_id uuid,
    confirmed_by     uuid,
    confirmed_at     timestamptz,
    created_at       timestamptz not null default now(),
    constraint entity_name_uq unique (entity_id, lang, form)
);
