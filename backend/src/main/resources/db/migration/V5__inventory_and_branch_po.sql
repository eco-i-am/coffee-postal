-- Orderable stock items (HQ master list) and the per-branch stock ledger.
-- Quantities are whole numbers in the item's own unit (bag, pack, box, pc...). Money is centavos.
CREATE TABLE items (
  id            BIGSERIAL PRIMARY KEY,
  name          VARCHAR(120) NOT NULL,
  unit          VARCHAR(20)  NOT NULL,
  cost          BIGINT NOT NULL DEFAULT 0 CHECK (cost >= 0),       -- what a branch pays per unit
  reorder_level BIGINT NOT NULL DEFAULT 0 CHECK (reorder_level >= 0),
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX items_name_uq ON items (lower(name));

-- Current stock = sum(qty). Never edited, only appended: that gives history and an audit trail for free.
CREATE TABLE stock_movements (
  id         BIGSERIAL PRIMARY KEY,
  branch_id  BIGINT NOT NULL REFERENCES branches(id),
  item_id    BIGINT NOT NULL REFERENCES items(id),
  qty        BIGINT NOT NULL CHECK (qty <> 0),                     -- signed: + in, - out
  kind       VARCHAR(12) NOT NULL CHECK (kind IN ('OPENING','ADJUST','WASTE','PO_RECEIVE')),
  reason     VARCHAR(200),
  ref        VARCHAR(40),                                          -- e.g. PO-12
  user_id    BIGINT REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX stock_movements_branch_item_idx ON stock_movements(branch_id, item_id, created_at DESC);

-- Branch -> HQ purchase orders.
-- REQUESTED -> APPROVED -> SHIPPED -> RECEIVED, or REJECTED / CANCELLED. No payments yet.
CREATE TABLE branch_pos (
  id            BIGSERIAL PRIMARY KEY,
  branch_id     BIGINT NOT NULL REFERENCES branches(id),
  status        VARCHAR(10) NOT NULL DEFAULT 'REQUESTED'
                CHECK (status IN ('REQUESTED','APPROVED','REJECTED','SHIPPED','RECEIVED','CANCELLED')),
  note          VARCHAR(300),
  reject_reason VARCHAR(300),
  requested_by  BIGINT NOT NULL REFERENCES users(id),
  requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_by    BIGINT REFERENCES users(id),
  decided_at    TIMESTAMPTZ,
  shipped_at    TIMESTAMPTZ,
  received_by   BIGINT REFERENCES users(id),
  received_at   TIMESTAMPTZ
);
CREATE INDEX branch_pos_branch_idx ON branch_pos(branch_id, requested_at DESC);
CREATE INDEX branch_pos_status_idx ON branch_pos(status, requested_at DESC);

CREATE TABLE branch_po_lines (
  id           BIGSERIAL PRIMARY KEY,
  po_id        BIGINT NOT NULL REFERENCES branch_pos(id) ON DELETE CASCADE,
  item_id      BIGINT NOT NULL REFERENCES items(id),
  name         VARCHAR(120) NOT NULL,         -- snapshot at time of order
  unit         VARCHAR(20)  NOT NULL,
  unit_cost    BIGINT NOT NULL,
  qty_ordered  BIGINT NOT NULL CHECK (qty_ordered > 0),
  qty_approved BIGINT CHECK (qty_approved >= 0),
  qty_received BIGINT CHECK (qty_received >= 0),
  UNIQUE (po_id, item_id)
);
