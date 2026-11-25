-- 기존 설치에서 새 서버 버전 시작 전 실행. 신규 설치는 엔티티에서 두 컬럼의 고유 키를 생성한다.
-- 이전 단일 컬럼 고유 인덱스는 Hibernate update만으로 제거되지 않을 수 있다.
ALTER TABLE server_log
    ADD UNIQUE INDEX ux_log_server_event (server_id, event_id),
    DROP INDEX ux_server_log_eventid;
