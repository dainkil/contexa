-- 검토함 "처리한 것"은 최근에 바뀐 묶음부터 쪽을 나눠 읽는다.
create index review_item_updated_idx on review_item (project_id, state, updated_at);

-- 문서 목록과 원문 뷰어는 문서 판마다 처리 작업(PARSE·EXTRACT)을 찾는다.
create index job_subject_idx on job (project_id, subject_id);
