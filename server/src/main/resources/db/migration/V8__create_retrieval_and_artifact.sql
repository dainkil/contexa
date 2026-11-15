-- 검색: 발언 내용을 글자 일부만 맞아도 찾을 수 있게 하는 색인(트라이그램).
-- 의미로 찾는 검색(임베딩)은 임베딩 모델을 붙일 때 추가한다.
create extension if not exists pg_trgm;
create index span_text_trgm_idx on span using gin (text gin_trgm_ops);

-- 문서 작성의 장부: 질문에 대한 답변, 브리핑, 초안.
-- 산출물은 요청한 사람의 권한으로 만들어지므로, 요청한 사람만 읽는다.
create table artifact (
    id           uuid        primary key,
    project_id   uuid        not null references project (id),
    kind         text        not null check (kind in ('ANSWER', 'BRIEFING', 'DRAFT')),
    question     text        not null,
    lang         text        not null check (lang in ('ko', 'en', 'ar')),
    content      text,                                 -- 아직 만들어지지 않았으면 비어 있다
    job_id       uuid        references job (id),
    requested_by uuid        not null references app_user (id),
    created_at   timestamptz not null default now(),
    published_at timestamptz
);
create index artifact_owner_idx on artifact (project_id, requested_by, created_at);

-- 산출물이 무엇을 보고 만들어졌는가. 나중에 그것이 바뀌면 "다시 확인 필요"로 알리는 데 쓴다.
--   NODE: 참고한 카드와 발언, 그때의 버전.  EDGE: 참고한 줄, 그때의 버전.
--   CITATION: 본문의 [1], [2] 가 가리키는 발언. position이 그 번호다.
create table artifact_ref (
    artifact_id  uuid    not null references artifact (id),
    kind         text    not null check (kind in ('NODE', 'EDGE', 'CITATION')),
    position     integer not null,
    ref_type     text,                                 -- NODE·CITATION: 카드 종류. EDGE: null
    ref_id       uuid    not null,
    seen_version bigint  not null,
    primary key (artifact_id, kind, position)
);
