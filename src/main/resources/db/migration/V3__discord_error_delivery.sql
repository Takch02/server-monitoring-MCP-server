-- NULL: 알림 제외, PENDING: 대기, 그 외: 배정된 log_alert_delivery.id
ALTER TABLE server_log ADD COLUMN alert_delivery VARCHAR(36);
CREATE INDEX idx_log_delivery ON server_log (server_id, alert_delivery, id);

CREATE TABLE log_alert_channel (
    server_id BIGINT PRIMARY KEY,
    webhook_hash CHAR(64) NOT NULL,
    encrypted_webhook TEXT NOT NULL,
    last_sent_at BIGINT,
    available_at BIGINT NOT NULL DEFAULT 0,
    active_delivery VARCHAR(36),
    lease_token VARCHAR(36),
    FOREIGN KEY (server_id) REFERENCES target_server(id)
);

CREATE TABLE log_alert_delivery (
    id VARCHAR(36) PRIMARY KEY,
    server_id BIGINT NOT NULL,
    sent_at BIGINT,
    FOREIGN KEY (server_id) REFERENCES target_server(id)
);
