-- 결재 창구의 장부.

-- 검토 항목: 카드 한 장과 그 카드에 딸린 줄을 한 묶음으로 본다. 카드마다 하나만 있다.
-- 묶음에 확인할 것이 남아 있으면 OPEN, 없으면 DONE이다. 새 후보가 들어오면 다시 OPEN이 된다.
create table review_item (
    id         uuid        primary key,
    project_id uuid        not null,
    owner_type text        not null,
    owner_id   uuid        not null,
    state      text        not null default 'OPEN' check (state in ('OPEN', 'DONE')),
    version    bigint      not null default 1 check (version >= 1),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint review_item_owner_uq unique (project_id, owner_type, owner_id)
);
create index review_item_state_idx on review_item (project_id, state, created_at);

-- 검토 기록: 누가 언제 어떤 묶음에 무엇을 했는지. 지우지 않는다.
-- idempotency_key는 같은 요청이 두 번 와도 한 번만 처리하기 위한 값이다.
create table review_action (
    id              uuid        primary key,
    project_id      uuid        not null,
    review_id       uuid        not null references review_item (id),
    actor_id        uuid        not null,
    idempotency_key text        not null,
    summary         text        not null,
    created_at      timestamptz not null default now(),
    constraint review_action_key_uq unique (project_id, idempotency_key)
);
create index review_action_review_idx on review_action (review_id, created_at);
