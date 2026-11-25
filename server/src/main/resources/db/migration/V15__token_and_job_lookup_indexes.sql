-- 자주 도는 조회에 맞춘 인덱스(11/25 점검)

-- 하루 AI 작업 수 세기(job/AiQuota): 이 사람이 지난 24시간에 맡긴 작업
create index job_requester_idx on job (requested_by, created_at);

-- 계정 삭제·프로젝트 탈퇴 때 그 사람의 토큰 지우기
create index access_token_user_idx on access_token (user_id);

-- 프로젝트의 에이전트 토큰 목록·지우기. 로그인 토큰은 project_id가 없다
create index access_token_project_idx on access_token (project_id) where project_id is not null;

-- 문서를 지울 때 그 발언을 근거로 든 이름 표기 찾기(entity/EntityReview)
create index entity_name_span_idx on entity_name (evidence_span_id) where evidence_span_id is not null;
