-- 답의 언어를 정하지 않고 물을 수 있다. 비어 있으면 AI 인턴이 질문을 쓴 언어로 답한다.
alter table artifact alter column lang drop not null;
