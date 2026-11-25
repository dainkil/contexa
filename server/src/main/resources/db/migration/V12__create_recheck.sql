-- 다시 확인할 카드: 확인·보류된 카드가 기대는 카드나 발언이 바뀌면 남는다(결정·업무 관리의 장부).
-- 사람이 다시 보면 닫는다. 닫은 행도 지우지 않는다.
-- 한 카드에 원인이 여럿 쌓일 수 있다. 같은 카드·같은 원인으로는 열린 행이 하나뿐이다.
create table recheck (
    id          uuid        primary key,
    project_id  uuid        not null,
    node_type   text        not null,  -- 다시 볼 카드(안건·결정 항목·결정 사건·조건·할 일)
    node_id     uuid        not null,
    cause_type  text        not null,  -- 바뀐 카드나 발언
    cause_id    uuid        not null,
    reason      text        not null check (reason in ('REVIEW', 'NAME', 'ACCOUNT', 'CONDITION', 'SPAN_REVISION')),
    distance    int         not null check (distance >= 1),
    actor_id    uuid        not null,
    created_at  timestamptz not null default now(),
    resolved_by uuid,
    resolved_at timestamptz,
    resolution  text        check (resolution in ('KEPT', 'REVIEWED')),
    constraint recheck_resolved_ck check ((resolved_at is null) = (resolution is null))
);
create unique index recheck_open_uq on recheck (project_id, node_type, node_id, cause_type, cause_id)
    where resolved_at is null;
-- 검토함 "다시 확인"과 답변서의 표시는 열린 행만 읽는다.
create index recheck_open_idx on recheck (project_id, node_type, node_id) where resolved_at is null;
