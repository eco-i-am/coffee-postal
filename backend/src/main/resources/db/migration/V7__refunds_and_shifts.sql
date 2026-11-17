-- Cashier shifts (cash drawer) and cash in/out. Money is centavos.
CREATE TABLE shifts (
  id            BIGSERIAL PRIMARY KEY,
  branch_id     BIGINT NOT NULL REFERENCES branches(id),
  cashier_id    BIGINT NOT NULL REFERENCES users(id),
  opened_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  opening_cash  BIGINT NOT NULL CHECK (opening_cash >= 0),
  closed_at     TIMESTAMPTZ,
  expected_cash BIGINT,            -- what the drawer should hold, worked out when the shift closes
  counted_cash  BIGINT CHECK (counted_cash >= 0)   -- what the cashier actually counted
);
-- one open shift per cashier at a time (also stops a double-tap opening two)
CREATE UNIQUE INDEX shifts_one_open_per_cashier ON shifts(cashier_id) WHERE closed_at IS NULL;
CREATE INDEX shifts_branch_idx ON shifts(branch_id, opened_at DESC);

CREATE TABLE cash_movements (
  id         BIGSERIAL PRIMARY KEY,
  shift_id   BIGINT NOT NULL REFERENCES shifts(id),
  kind       VARCHAR(3) NOT NULL CHECK (kind IN ('IN','OUT')),
  amount     BIGINT NOT NULL CHECK (amount > 0),
  reason     VARCHAR(200) NOT NULL,
  user_id    BIGINT NOT NULL REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX cash_movements_shift_idx ON cash_movements(shift_id);

-- Orders remember which shift they were rung up in, and how a refund was approved.
ALTER TABLE orders
  ADD COLUMN shift_id           BIGINT REFERENCES shifts(id),
  ADD COLUMN refund_reason      VARCHAR(300),
  ADD COLUMN refunded_at        TIMESTAMPTZ,
  ADD COLUMN refunded_by        BIGINT REFERENCES users(id),
  ADD COLUMN refund_approved_by BIGINT REFERENCES users(id);
CREATE INDEX orders_shift_idx ON orders(shift_id);

-- Manager PIN attempts, so a 4-digit PIN cannot be guessed by trying them all.
CREATE TABLE pin_attempts (
  id        BIGSERIAL PRIMARY KEY,
  branch_id BIGINT NOT NULL REFERENCES branches(id),
  user_id   BIGINT REFERENCES users(id),      -- who typed it (the signed-in cashier)
  success   BOOLEAN NOT NULL,
  at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX pin_attempts_branch_idx ON pin_attempts(branch_id, at DESC);
