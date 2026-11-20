-- The picture a cashier takes when opening a shift. Personal data: shown only to people who see the shift history,
-- and deleted after the same retention period as time clock photos (PhotoService).
CREATE TABLE shift_photos (
  shift_id BIGINT PRIMARY KEY REFERENCES shifts(id) ON DELETE CASCADE,
  jpeg     BYTEA NOT NULL,
  taken_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX shift_photos_taken_idx ON shift_photos(taken_at);
