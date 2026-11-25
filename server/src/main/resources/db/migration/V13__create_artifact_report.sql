-- 답변 신고: 사용자가 AI가 쓴 답변·브리핑이 틀렸거나 부적절하다고 알린 기록.
-- 운영자가 읽고 프롬프트와 검사를 고친다. 본문은 artifact에 있으므로 여기에는 옮겨 적지 않는다.
create table artifact_report (
    id          uuid        primary key,
    project_id  uuid        not null references project (id),
    artifact_id uuid        not null references artifact (id),
    reported_by uuid        not null references app_user (id),
    reason      text        not null check (reason in ('WRONG', 'UNSUPPORTED', 'OFFENSIVE', 'OTHER')),
    note        text,
    created_at  timestamptz not null default now()
);
create index artifact_report_time_idx on artifact_report (created_at);
create index artifact_report_artifact_idx on artifact_report (artifact_id);
