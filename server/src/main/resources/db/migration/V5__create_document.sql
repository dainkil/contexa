-- 자료실의 장부: 문서, 문서의 판(버전), 발언.

create table document (
    id         uuid        primary key,
    project_id uuid        not null references project (id),
    title      text        not null,
    kind       text        not null
        check (kind in ('TRANSCRIPT', 'DOCUMENT', 'REGULATION', 'MEMO', 'ROSTER')),
    language   text,                                  -- 혼합언어면 비워 둔다
    created_by uuid        not null references app_user (id),
    created_at timestamptz not null default now()
);
create index document_project_idx on document (project_id, created_at);

-- 같은 문서를 다시 올리면 새 판이 된다. 이전 판은 덮어쓰지 않는다.
-- 지금은 글자 내용만 저장한다. 원본 파일(PDF, 음성)은 나중에 파일 저장소에 두고 여기에는 경로를 적는다.
create table source_version (
    id           uuid        primary key,
    document_id  uuid        not null references document (id),
    version_no   integer     not null check (version_no >= 1),
    content      text        not null,
    content_hash text        not null,
    created_by   uuid        not null references app_user (id),
    created_at   timestamptz not null default now(),
    constraint source_version_no_uq unique (document_id, version_no),
    constraint source_version_hash_uq unique (document_id, content_hash)
);

-- 발언: 문서의 한 판을 잘라낸 조각. 결정 그래프에서 SPAN 카드가 된다.
create table span (
    id                uuid        primary key,
    project_id        uuid        not null references project (id),
    document_id       uuid        not null references document (id),
    source_version_id uuid        not null references source_version (id),
    seq               integer     not null check (seq >= 1),   -- 그 판 안에서의 순서
    speaker_label     text,                                     -- 전사문에 적힌 화자 표기 그대로
    text              text        not null,
    revision          bigint      not null default 1 check (revision >= 1),
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    constraint span_seq_uq unique (source_version_id, seq)
);
create index span_project_idx on span (project_id);

-- 발언을 정정하기 전의 내용. 지우지 않는다.
create table span_history (
    span_id    uuid        not null references span (id),
    revision   bigint      not null,
    text       text        not null,
    changed_by uuid        not null references app_user (id),
    changed_at timestamptz not null default now(),
    primary key (span_id, revision)
);
