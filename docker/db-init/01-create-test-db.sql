-- server 테스트(./gradlew test)가 쓰는 DB. 개발 DB(contexa)와 나눠 시연 데이터에 테스트 기록이 섞이지 않게 한다.
-- 이미 있는 볼륨에는 돌지 않으므로 그때는 한 번 직접 만든다:
--   docker compose exec db createdb -U contexa contexa_test
create database contexa_test owner contexa;
