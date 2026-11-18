-- Product headers group categories on the menu (e.g. "Drinks", "Food"). Draft products stay off the POS.
CREATE TABLE product_headers (
  id         BIGSERIAL PRIMARY KEY,
  name       VARCHAR(80) NOT NULL UNIQUE,
  sort_order INT NOT NULL DEFAULT 0,
  active     BOOLEAN NOT NULL DEFAULT TRUE
);

ALTER TABLE product_categories ADD COLUMN header_id BIGINT REFERENCES product_headers(id);
ALTER TABLE products ADD COLUMN published BOOLEAN NOT NULL DEFAULT TRUE;

INSERT INTO modules(code, scope, label) VALUES ('product_headers','ADMIN','Product Headers');

INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete)
SELECT r.id, 'product_headers', TRUE, TRUE, TRUE, r.name = 'Super Admin'
FROM roles r WHERE r.name IN ('Super Admin','HQ Staff');
