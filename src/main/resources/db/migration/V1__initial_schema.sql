CREATE TABLE target_server (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    server_name VARCHAR(255) NOT NULL UNIQUE,
    mcp_token VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE server_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    server_id BIGINT NOT NULL,
    event_id VARCHAR(40) NOT NULL,
    level VARCHAR(255),
    message LONGTEXT,
    occurred_at DATETIME(6),
    created_at DATETIME(6),
    CONSTRAINT fk_log_target FOREIGN KEY (server_id) REFERENCES target_server (id)
);
CREATE UNIQUE INDEX ux_server_log_eventid ON server_log (event_id);
CREATE INDEX idx_log_server_time ON server_log (server_id, occurred_at);
CREATE INDEX idx_log_level ON server_log (server_id, level, occurred_at);

CREATE TABLE server_metric (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    server_id BIGINT NOT NULL,
    captured_at DATETIME(6),
    cpu_usage DOUBLE,
    memory_used_mb DOUBLE,
    memory_max_mb DOUBLE,
    CONSTRAINT fk_metric_target FOREIGN KEY (server_id) REFERENCES target_server (id)
);
CREATE INDEX idx_metric_server_time ON server_metric (server_id, captured_at);

CREATE TABLE server_health_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    server_name VARCHAR(255) NOT NULL,
    ts BIGINT NOT NULL,
    status VARCHAR(255) NOT NULL,
    latency_ms BIGINT NOT NULL,
    http_status INT NOT NULL,
    message VARCHAR(500)
);
CREATE INDEX idx_health_server_ts ON server_health_event (server_name, ts);

CREATE TABLE forwarder_status (
    server_id BIGINT NOT NULL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    started_at DOUBLE NOT NULL,
    report_sequence BIGINT NOT NULL,
    received_at TIMESTAMP(6) NOT NULL,
    snapshot_json TEXT NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6)
);
