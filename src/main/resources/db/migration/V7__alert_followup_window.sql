ALTER TABLE incident ADD COLUMN live_window BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_incident_live_window ON incident (server_id, live_window, window_end, window_start);
