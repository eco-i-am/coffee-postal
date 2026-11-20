-- ===== Branch customers: a list per branch (name, contact, notes). Order history is read from the orders that carry the same name. =====
INSERT INTO modules(code, scope, label) VALUES ('branch_customers', 'BRANCH', 'Customers');

INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete)
SELECT r.id, 'branch_customers', TRUE,
       TRUE,                                                        -- the counter can register a customer
       r.name <> 'Cashier',
       r.name IN ('Super Admin', 'Branch Owner')
FROM roles r
WHERE r.name = 'Super Admin' OR r.scope = 'BRANCH';

CREATE TABLE customers (
  id         BIGSERIAL PRIMARY KEY,
  branch_id  BIGINT NOT NULL REFERENCES branches(id),
  name       VARCHAR(60) NOT NULL,
  phone      VARCHAR(40),
  email      VARCHAR(120),
  notes      VARCHAR(300),
  created_by BIGINT NOT NULL REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX customers_branch_name_uq ON customers (branch_id, lower(name));

-- ===== Training: HQ publishes videos (a link), documents (a file) and links; branch staff view and mark them done. =====
CREATE TABLE training_materials (
  id           BIGSERIAL PRIMARY KEY,
  title        VARCHAR(120) NOT NULL,
  description  VARCHAR(1000),
  kind         VARCHAR(10) NOT NULL CHECK (kind IN ('VIDEO', 'DOCUMENT', 'LINK')),
  url          VARCHAR(500),                       -- VIDEO and LINK
  file_name    VARCHAR(200),                       -- DOCUMENT
  content_type VARCHAR(100),
  data         BYTEA,
  created_by   BIGINT NOT NULL REFERENCES users(id),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  removed_at   TIMESTAMPTZ
);

CREATE TABLE training_completions (
  material_id  BIGINT NOT NULL REFERENCES training_materials(id) ON DELETE CASCADE,
  user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (material_id, user_id)
);

-- ===== Settings: company details, receipt text and the PO rules that HQ can change without a release. =====
CREATE TABLE settings (
  key        VARCHAR(60) PRIMARY KEY,
  value      VARCHAR(2000) NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by VARCHAR(60)
);
