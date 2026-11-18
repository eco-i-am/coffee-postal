-- Discounts become data instead of three hard-coded codes.
--   kind SENIOR_PWD : VAT removed, then 20% off the VAT-exclusive price (law); value unused
--   kind PERCENT    : value = basis points (1000 = 10.00%)
--   kind FIXED      : value = centavos off the order
-- approval_over: a discount larger than this many centavos needs a manager PIN (NULL = never).
CREATE TABLE discounts (
  code          VARCHAR(20) PRIMARY KEY,
  name          VARCHAR(80) NOT NULL UNIQUE,
  kind          VARCHAR(10) NOT NULL CHECK (kind IN ('SENIOR_PWD','PERCENT','FIXED')),
  value         BIGINT NOT NULL DEFAULT 0 CHECK (value >= 0),
  requires_id   BOOLEAN NOT NULL DEFAULT FALSE,
  approval_over BIGINT CHECK (approval_over >= 0),
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order    INT NOT NULL DEFAULT 0,
  CHECK (kind <> 'PERCENT' OR value <= 10000)
);
INSERT INTO discounts(code, name, kind, value, requires_id, sort_order) VALUES
 ('SENIOR_PWD', 'Senior / PWD', 'SENIOR_PWD', 0, TRUE, 1),
 ('PROMO10',    'Promo 10%',    'PERCENT', 1000, FALSE, 2);

ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_discount_type_check;
ALTER TABLE orders ALTER COLUMN discount_type TYPE VARCHAR(20);
ALTER TABLE orders ADD COLUMN discount_name        VARCHAR(80);                 -- snapshot, so a later rename does not change old receipts
ALTER TABLE orders ADD COLUMN discount_id_no       VARCHAR(40);                 -- Senior/PWD ID number
ALTER TABLE orders ADD COLUMN discount_approved_by BIGINT REFERENCES users(id);
ALTER TABLE orders ADD COLUMN approval_flag        BOOLEAN NOT NULL DEFAULT FALSE;  -- over the limit with no PIN (sold offline): needs a manager's review

INSERT INTO modules(code, scope, label) VALUES ('discounts','ADMIN','Discounts');

INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete)
SELECT r.id, 'discounts', TRUE, TRUE, TRUE, r.name = 'Super Admin'
FROM roles r WHERE r.name IN ('Super Admin','HQ Staff');
