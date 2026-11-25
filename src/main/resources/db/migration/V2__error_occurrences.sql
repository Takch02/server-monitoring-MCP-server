ALTER TABLE server_log ADD COLUMN error_group_key VARCHAR(64);
ALTER TABLE server_log ADD COLUMN alert_batch VARCHAR(36);
CREATE INDEX idx_log_group ON server_log (server_id, error_group_key, occurred_at);
CREATE INDEX idx_log_alert_batch ON server_log (alert_batch);
CREATE UNIQUE INDEX ux_log_server_event ON server_log (server_id, event_id);
DROP INDEX ux_server_log_eventid ON server_log;
