-- Item categories, a units list, and per-item unit conversions (e.g. 1 box = 12 pcs).
-- Stock is always kept in the item's own unit; conversions only let people enter or read a quantity in another unit.
CREATE TABLE item_categories (
  id         BIGSERIAL PRIMARY KEY,
  name       VARCHAR(80) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  active     BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE UNIQUE INDEX item_categories_name_uq ON item_categories (lower(name));
ALTER TABLE items ADD COLUMN category_id BIGINT REFERENCES item_categories(id);

CREATE TABLE units (
  id   BIGSERIAL PRIMARY KEY,
  code VARCHAR(20) NOT NULL
);
CREATE UNIQUE INDEX units_code_uq ON units (lower(code));
INSERT INTO units(code) SELECT DISTINCT ON (lower(trim(unit))) trim(unit) FROM items WHERE trim(unit) <> '';

-- 1 of `unit` = factor/1000 of the item's own unit
CREATE TABLE item_conversions (
  id      BIGSERIAL PRIMARY KEY,
  item_id BIGINT NOT NULL REFERENCES items(id) ON DELETE CASCADE,
  unit    VARCHAR(20) NOT NULL,
  factor  BIGINT NOT NULL CHECK (factor > 0)
);
CREATE UNIQUE INDEX item_conversions_uq ON item_conversions (item_id, lower(unit));
