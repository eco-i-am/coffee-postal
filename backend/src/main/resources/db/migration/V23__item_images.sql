-- One optional photo per menu product and per inventory item. Kept in the database so it is backed up with everything else.
-- The browser shrinks each photo before upload, so a row is tens of kilobytes.
CREATE TABLE item_images (
  owner_type   VARCHAR(10) NOT NULL CHECK (owner_type IN ('PRODUCT','ITEM')),
  owner_id     BIGINT      NOT NULL,
  content_type VARCHAR(20) NOT NULL CHECK (content_type IN ('image/jpeg','image/png','image/webp')),
  data         BYTEA       NOT NULL,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by   VARCHAR(60),
  PRIMARY KEY (owner_type, owner_id)
);
