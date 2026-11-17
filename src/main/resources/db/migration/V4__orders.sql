-- POS orders. Every order carries a client-generated id so a retried (or offline-synced) order is never double-counted.
CREATE TABLE branch_order_counters (
  branch_id BIGINT PRIMARY KEY REFERENCES branches(id),
  last_no   BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE orders (
  id            UUID PRIMARY KEY,
  branch_id     BIGINT NOT NULL REFERENCES branches(id),
  order_no      BIGINT NOT NULL,
  cashier_id    BIGINT NOT NULL REFERENCES users(id),
  order_type    VARCHAR(10) NOT NULL CHECK (order_type IN ('DINE_IN','TAKEOUT','DELIVERY')),
  discount_type VARCHAR(12) NOT NULL DEFAULT 'NONE' CHECK (discount_type IN ('NONE','SENIOR_PWD','PROMO10')),
  subtotal      BIGINT NOT NULL,          -- before discount
  discount      BIGINT NOT NULL DEFAULT 0,
  total         BIGINT NOT NULL,          -- amount due
  vatable       BIGINT NOT NULL DEFAULT 0,
  vat           BIGINT NOT NULL DEFAULT 0,
  status        VARCHAR(10) NOT NULL DEFAULT 'PAID' CHECK (status IN ('PAID','REFUNDED','VOID')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (branch_id, order_no)
);
CREATE INDEX orders_branch_time_idx ON orders(branch_id, created_at DESC);

CREATE TABLE order_lines (
  id         BIGSERIAL PRIMARY KEY,
  order_id   UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  product_id BIGINT NOT NULL REFERENCES products(id),
  name       VARCHAR(120) NOT NULL,       -- snapshot at time of sale
  variant    VARCHAR(40),
  addons     TEXT,                        -- snapshot, comma separated
  unit_price BIGINT NOT NULL,
  qty        INT NOT NULL CHECK (qty > 0)
);
CREATE INDEX order_lines_order_idx ON order_lines(order_id);

CREATE TABLE order_payments (
  id        BIGSERIAL PRIMARY KEY,
  order_id  UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  method    VARCHAR(10) NOT NULL CHECK (method IN ('CASH','GCASH','MAYA','CARD')),
  amount    BIGINT NOT NULL,              -- applied to the order
  tendered  BIGINT NOT NULL,              -- cash handed over (= amount for non-cash)
  reference VARCHAR(80)
);
