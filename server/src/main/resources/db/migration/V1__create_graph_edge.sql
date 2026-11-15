-- 결정 그래프의 줄. src가 dst에 기댄다.
create table graph_edge (
    id                uuid        primary key,
    project_id        uuid        not null,
    rel_type          text        not null,
    src_type          text        not null,
    src_id            uuid        not null,
    src_rev           bigint      not null check (src_rev >= 1),
    dst_type          text        not null,
    dst_id            uuid        not null,
    dst_rev           bigint      not null check (dst_rev >= 1),
    status            text        not null
        check (status in ('CANDIDATE', 'CONFIRMED', 'HELD', 'REJECTED', 'RETRACTED')),
    evidence_span_ids uuid[]      not null default '{}',
    confirmed_by      uuid,
    confirmed_at      timestamptz,
    ontology_version  text        not null,
    version           bigint      not null default 1 check (version >= 1),
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),

    constraint graph_edge_no_self_loop
        check (not (src_type = dst_type and src_id = dst_id)),
    constraint graph_edge_confirmed_has_time
        check (status <> 'CONFIRMED' or confirmed_at is not null)
);

-- 근거 쪽 탐색(src에서 출발)과 영향 쪽 탐색(dst에서 출발)
create index graph_edge_src_idx on graph_edge (project_id, src_id);
create index graph_edge_dst_idx on graph_edge (project_id, dst_id);

-- 같은 두 카드 사이에 같은 종류의 살아 있는 줄은 하나만 둔다.
-- 거절·철회된 줄은 이력으로 남고, 다시 제안하면 새 줄이 된다.
create unique index graph_edge_active_uq
    on graph_edge (project_id, rel_type, src_type, src_id, dst_type, dst_id)
    where status in ('CANDIDATE', 'CONFIRMED', 'HELD');
