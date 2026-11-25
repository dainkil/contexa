-- 다시 시도 간격: 다시 해 볼 만한 실패 뒤에는 이 시각까지 기다렸다가 가져간다(모델 쪽 장애가 이어질 때 바로 다시 부르지 않게)
alter table job add column not_before timestamptz;

-- 이번 시도를 가져간 시각. 임대 연장(heartbeat)을 끝없이 받아 주지 않도록 한 시도의 전체 시간을 잰다
alter table job add column leased_at timestamptz;
