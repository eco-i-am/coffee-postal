-- HQ buys from suppliers. A supplier PO names the branch that receives the goods; receiving adds to that branch's stock.
-- Amounts owed = value of what has been received, minus payments made. Money is centavos; quantities are thousandths.
CREATE TABLE suppliers (
  id         BIGSERIAL PRIMARY KEY,
  name       VARCHAR(120) NOT NULL,
  contact    VARCHAR(120),
  phone      VARCHAR(40),
  email      VARCHAR(120),
  address    VARCHAR(200),
  terms_days INT NOT NULL DEFAULT 0 CHECK (terms_days BETWEEN 0 AND 365),
  active     BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX suppliers_name_uq ON suppliers (lower(name));

CREATE TABLE supplier_pos (
  id          BIGSERIAL PRIMARY KEY,
  supplier_id BIGINT NOT NULL REFERENCES suppliers(id),
  branch_id   BIGINT NOT NULL REFERENCES branches(id),       -- where the goods arrive
  status      VARCHAR(10) NOT NULL DEFAULT 'ORDERED' CHECK (status IN ('ORDERED','PARTIAL','RECEIVED','CANCELLED')),
  note        VARCHAR(200),
  expected_on DATE,
  created_by  BIGINT NOT NULL REFERENCES users(id),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX supplier_pos_supplier_idx ON supplier_pos(supplier_id, created_at DESC);

CREATE TABLE supplier_po_lines (
  id           BIGSERIAL PRIMARY KEY,
  po_id        BIGINT NOT NULL REFERENCES supplier_pos(id) ON DELETE CASCADE,
  item_id      BIGINT NOT NULL REFERENCES items(id),
  qty          BIGINT NOT NULL CHECK (qty > 0),               -- ordered, thousandths of the item's unit
  unit_cost    BIGINT NOT NULL CHECK (unit_cost >= 0),        -- centavos per unit, as agreed with the supplier
  received_qty BIGINT NOT NULL DEFAULT 0 CHECK (received_qty >= 0 AND received_qty <= qty)
);
CREATE INDEX supplier_po_lines_po_idx ON supplier_po_lines(po_id);

CREATE TABLE supplier_po_receipts (
  id          BIGSERIAL PRIMARY KEY,
  line_id     BIGINT NOT NULL REFERENCES supplier_po_lines(id) ON DELETE CASCADE,
  qty         BIGINT NOT NULL CHECK (qty > 0),
  received_by BIGINT NOT NULL REFERENCES users(id),
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE supplier_payments (
  id          BIGSERIAL PRIMARY KEY,
  po_id       BIGINT NOT NULL REFERENCES supplier_pos(id),
  amount      BIGINT NOT NULL CHECK (amount > 0),
  method      VARCHAR(10) NOT NULL CHECK (method IN ('CASH','BANK','GCASH','MAYA','CHECK')),
  reference   VARCHAR(60),
  paid_on     DATE NOT NULL,
  recorded_by BIGINT NOT NULL REFERENCES users(id),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX supplier_payments_po_idx ON supplier_payments(po_id);
