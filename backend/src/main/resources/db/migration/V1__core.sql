-- Core: branches, IAM, audit. Money is integer centavos everywhere.
CREATE TABLE branches (
  id            BIGSERIAL PRIMARY KEY,
  code          VARCHAR(20)  NOT NULL UNIQUE,
  name          VARCHAR(120) NOT NULL,
  address       TEXT,
  phone         VARCHAR(40),
  tin           VARCHAR(30),
  vat_registered BOOLEAN NOT NULL DEFAULT FALSE,
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE roles (
  id          BIGSERIAL PRIMARY KEY,
  name        VARCHAR(60) NOT NULL UNIQUE,
  scope       VARCHAR(10) NOT NULL CHECK (scope IN ('ADMIN','BRANCH')),
  description TEXT
);

-- one row per (role, module): view/add/edit/delete
CREATE TABLE role_permissions (
  role_id    BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
  module     VARCHAR(60) NOT NULL,
  can_view   BOOLEAN NOT NULL DEFAULT FALSE,
  can_add    BOOLEAN NOT NULL DEFAULT FALSE,
  can_edit   BOOLEAN NOT NULL DEFAULT FALSE,
  can_delete BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (role_id, module)
);

CREATE TABLE users (
  id            BIGSERIAL PRIMARY KEY,
  username      VARCHAR(60)  NOT NULL UNIQUE,
  email         VARCHAR(160) UNIQUE,
  full_name     VARCHAR(120) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  pin_hash      VARCHAR(100),                 -- cashier / manager PIN
  role_id       BIGINT NOT NULL REFERENCES roles(id),
  status        VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED','BLOCKED')),
  failed_logins INT NOT NULL DEFAULT 0,
  locked_until  TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ADMIN-scope users see all branches (no rows needed); BRANCH-scope users are pinned here
CREATE TABLE user_branches (
  user_id   BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  branch_id BIGINT NOT NULL REFERENCES branches(id),
  PRIMARY KEY (user_id, branch_id)
);

CREATE TABLE login_history (
  id         BIGSERIAL PRIMARY KEY,
  user_id    BIGINT REFERENCES users(id),
  username   VARCHAR(60),
  success    BOOLEAN NOT NULL,
  ip         VARCHAR(64),
  user_agent TEXT,
  at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_logs (
  id         BIGSERIAL PRIMARY KEY,
  at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  user_id    BIGINT REFERENCES users(id),
  branch_id  BIGINT REFERENCES branches(id),
  action     VARCHAR(60) NOT NULL,       -- e.g. PRICE_CHANGE, ORDER_VOID, PO_APPROVE
  entity     VARCHAR(60) NOT NULL,
  entity_id  VARCHAR(60),
  before_json JSONB,
  after_json  JSONB,
  reason     TEXT
);
CREATE INDEX audit_logs_entity_idx ON audit_logs(entity, entity_id);
CREATE INDEX audit_logs_at_idx ON audit_logs(at DESC);

-- seed roles (permissions seeded in V2 once modules are final)
INSERT INTO roles(name, scope, description) VALUES
 ('Super Admin','ADMIN','Full HQ access'),
 ('HQ Staff','ADMIN','HQ operations'),
 ('Branch Owner','BRANCH','Owner of one or more branches'),
 ('Branch Manager','BRANCH','Runs a branch; approves voids/refunds'),
 ('Cashier','BRANCH','POS only');
