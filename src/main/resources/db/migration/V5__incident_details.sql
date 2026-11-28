CREATE TABLE incident (
    id VARCHAR(36) PRIMARY KEY,
    server_id BIGINT NOT NULL,
    error_group_key VARCHAR(64) NOT NULL,
    representative_log_id BIGINT NOT NULL,
    window_start DATETIME(6) NOT NULL,
    window_end DATETIME(6) NOT NULL,
    expires_at BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (server_id) REFERENCES target_server (id)
);

CREATE INDEX idx_log_incident_window ON server_log (server_id, error_group_key, created_at);
