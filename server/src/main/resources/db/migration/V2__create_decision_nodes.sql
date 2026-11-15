-- 결정·업무 관리가 소유하는 카드 5종.
-- 다른 카드로의 연결(소속 항목, 담당자, 대체 대상)은 여기에 두지 않고 graph_edge에만 둔다.
-- decision_state, current_event_id, standing, state는 줄에서 계산한 결과를 적어 둔 값이다.
-- 행이 바뀔 때마다 revision을 1 올린다.

create table topic (
    id           uuid        primary key,
    project_id   uuid        not null,
    title        text        not null,
    status       text        not null default 'CANDIDATE'
        check (status in ('CANDIDATE', 'CONFIRMED', 'HELD', 'REJECTED', 'RETRACTED')),
    revision     bigint      not null default 1 check (revision >= 1),
    confirmed_by uuid,
    confirmed_at timestamptz,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    constraint topic_confirmed_has_time check (status <> 'CONFIRMED' or confirmed_at is not null)
);
create index topic_project_idx on topic (project_id);

-- 정해야 할 항목 하나. 예: "개최 방식"
create table decision_item (
    id               uuid        primary key,
    project_id       uuid        not null,
    title            text        not null,
    decision_state   text        not null default 'UNDECIDED'
        check (decision_state in ('UNDECIDED', 'DECIDED', 'CONTESTED', 'WITHDRAWN')),
    current_event_id uuid,
    status           text        not null default 'CANDIDATE'
        check (status in ('CANDIDATE', 'CONFIRMED', 'HELD', 'REJECTED', 'RETRACTED')),
    revision         bigint      not null default 1 check (revision >= 1),
    confirmed_by     uuid,
    confirmed_at     timestamptz,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    constraint decision_item_confirmed_has_time check (status <> 'CONFIRMED' or confirmed_at is not null),
    constraint decision_item_current_only_when_decided
        check ((decision_state = 'DECIDED') = (current_event_id is not null))
);
create index decision_item_project_idx on decision_item (project_id);

-- 항목에 대해 실제로 일어난 일. 예: "3차 회의에서 대면 개최로 확정"
create table decision_event (
    id           uuid        primary key,
    project_id   uuid        not null,
    kind         text        not null
        check (kind in ('PROPOSAL', 'DISCUSSION', 'DEFERRAL', 'DECISION', 'CHANGE', 'WITHDRAWAL')),
    statement    text        not null,
    occurred_at  timestamptz,
    standing     text        not null default 'ACTIVE'
        check (standing in ('ACTIVE', 'SUPERSEDED', 'WITHDRAWN')),
    status       text        not null default 'CANDIDATE'
        check (status in ('CANDIDATE', 'CONFIRMED', 'HELD', 'REJECTED', 'RETRACTED')),
    revision     bigint      not null default 1 check (revision >= 1),
    confirmed_by uuid,
    confirmed_at timestamptz,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    constraint decision_event_confirmed_has_time check (status <> 'CONFIRMED' or confirmed_at is not null)
);
create index decision_event_project_idx on decision_event (project_id);

-- 충족되어야 진행할 수 있는 것. 예: "예산 승인". condition은 SQL 예약어라 이름을 피했다.
create table decision_condition (
    id           uuid        primary key,
    project_id   uuid        not null,
    description  text        not null,
    state        text        not null default 'UNMET' check (state in ('UNMET', 'MET')),
    status       text        not null default 'CANDIDATE'
        check (status in ('CANDIDATE', 'CONFIRMED', 'HELD', 'REJECTED', 'RETRACTED')),
    revision     bigint      not null default 1 check (revision >= 1),
    confirmed_by uuid,
    confirmed_at timestamptz,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    constraint decision_condition_confirmed_has_time check (status <> 'CONFIRMED' or confirmed_at is not null)
);
create index decision_condition_project_idx on decision_condition (project_id);

-- 누군가 해야 하는 일. 담당자는 ASSIGNED_TO 줄로만 표현한다.
create table task (
    id                     uuid        primary key,
    project_id             uuid        not null,
    title                  text        not null,
    due_at                 timestamptz,
    work_status            text        not null default 'PLANNED'
        check (work_status in ('PLANNED', 'IN_PROGRESS', 'ON_HOLD', 'DONE', 'CANCELLED')),
    work_status_changed_by uuid,
    work_status_changed_at timestamptz,
    acceptance             text        not null default 'PENDING'
        check (acceptance in ('PENDING', 'ACCEPTED', 'DECLINED')),
    acceptance_by          uuid,
    acceptance_at          timestamptz,
    status                 text        not null default 'CANDIDATE'
        check (status in ('CANDIDATE', 'CONFIRMED', 'HELD', 'REJECTED', 'RETRACTED')),
    revision               bigint      not null default 1 check (revision >= 1),
    confirmed_by           uuid,
    confirmed_at           timestamptz,
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now(),
    constraint task_confirmed_has_time check (status <> 'CONFIRMED' or confirmed_at is not null),
    constraint task_acceptance_has_actor check (acceptance = 'PENDING' or acceptance_by is not null)
);
create index task_project_idx on task (project_id);
