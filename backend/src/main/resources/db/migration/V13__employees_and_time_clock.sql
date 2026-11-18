-- Employees and time in / time out (plan section 4a).
-- An employee is not necessarily a system user: most staff only ever punch a clock with a 6-digit PIN.
-- branch_id NULL = HQ staff (they do not punch on a branch tablet).
CREATE TABLE employees (
  id         BIGSERIAL PRIMARY KEY,
  branch_id  BIGINT REFERENCES branches(id),
  full_name  VARCHAR(120) NOT NULL,
  position   VARCHAR(80),
  status     VARCHAR(8) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  -- The PIN itself is never stored. pin_lookup (keyed hash, secret lives in the server config) finds the person from a typed PIN
  -- and keeps PINs unique within a branch. pin_salt + pin_check let the tablet recognise a PIN with no internet.
  pin_lookup CHAR(64),
  pin_salt   VARCHAR(32),
  pin_check  CHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX employees_branch_idx ON employees(branch_id, status);
-- one PIN per person within a branch; a person who left frees theirs
CREATE UNIQUE INDEX employees_branch_pin_idx ON employees(branch_id, pin_lookup) WHERE pin_lookup IS NOT NULL AND status = 'ACTIVE';

-- Append-only record of punches. A correction changes punched_at and says who and why; the original time is kept in audit_logs.
CREATE TABLE attendance_logs (
  id                BIGSERIAL PRIMARY KEY,
  employee_id       BIGINT NOT NULL REFERENCES employees(id),
  branch_id         BIGINT NOT NULL REFERENCES branches(id),
  kind              VARCHAR(3) NOT NULL CHECK (kind IN ('IN','OUT')),
  punched_at        TIMESTAMPTZ NOT NULL,        -- the time of record
  device_time       TIMESTAMPTZ NOT NULL,        -- what the tablet's clock said
  server_time       TIMESTAMPTZ NOT NULL DEFAULT now(),
  source            VARCHAR(7) NOT NULL CHECK (source IN ('ONLINE','OFFLINE')),
  client_id         UUID UNIQUE,                 -- makes a re-sent offline punch harmless
  flag              VARCHAR(10) CHECK (flag IN ('CLOCK_SKEW','MANUAL')),
  recorded_by       BIGINT REFERENCES users(id), -- the tablet account (or the manager for a manual punch)
  corrected_by      BIGINT REFERENCES users(id),
  correction_reason VARCHAR(200)
);
CREATE INDEX attendance_employee_idx ON attendance_logs(employee_id, punched_at DESC);
CREATE INDEX attendance_branch_idx ON attendance_logs(branch_id, punched_at DESC);

-- wrong-PIN tries on the clock, per branch, so a 6-digit PIN cannot be guessed by trying them all
CREATE TABLE clock_attempts (
  id        BIGSERIAL PRIMARY KEY,
  branch_id BIGINT NOT NULL REFERENCES branches(id),
  success   BOOLEAN NOT NULL,
  at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX clock_attempts_branch_idx ON clock_attempts(branch_id, at DESC);

-- The clock screen is its own module so a tablet account can be limited to it.
INSERT INTO modules(code, scope, label) VALUES ('time_clock','BRANCH','Time In / Out');
INSERT INTO roles(name, scope, description) VALUES ('Time Clock','BRANCH','Branch tablet: staff time in and time out only');

INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete)
SELECT r.id, 'time_clock', TRUE, TRUE, FALSE, FALSE
FROM roles r WHERE r.name IN ('Time Clock','Branch Owner','Branch Manager','Cashier');

-- Super Admin holds every module (an admin cannot hand out access they do not have themselves)
INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete)
SELECT r.id, 'time_clock', TRUE, TRUE, TRUE, TRUE FROM roles r WHERE r.name = 'Super Admin';
