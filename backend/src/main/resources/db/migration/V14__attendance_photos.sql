-- A small photo taken at each time in and time out, so a manager can see who really punched.
-- Kept apart from attendance_logs so the report stays light and old photos can be deleted without touching the punch.
-- Personal data: shown only to managers and HQ, and deleted after the retention period (default 90 days) by PhotoService.
CREATE TABLE attendance_photos (
  log_id   BIGINT PRIMARY KEY REFERENCES attendance_logs(id) ON DELETE CASCADE,
  jpeg     BYTEA NOT NULL,
  taken_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX attendance_photos_taken_idx ON attendance_photos(taken_at);
