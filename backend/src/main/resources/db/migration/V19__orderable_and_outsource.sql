-- 1) HQ chooses which items branches can order on a purchase order (the rest are bought from suppliers by HQ only).
ALTER TABLE items ADD COLUMN orderable BOOLEAN NOT NULL DEFAULT TRUE;

-- 2) Outsource purchases: things a branch buys locally, outside the HQ order flow. Lines tied to an item add to stock.
ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_kind_check;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_kind_check
  CHECK (kind IN ('OPENING','ADJUST','WASTE','PO_RECEIVE','SALE','TRANSFER_OUT','TRANSFER_IN','OUTSOURCE'));

CREATE TABLE outsource_purchases (
  id           BIGSERIAL PRIMARY KEY,
  branch_id    BIGINT NOT NULL REFERENCES branches(id),
  vendor       VARCHAR(120) NOT NULL,
  receipt_no   VARCHAR(40),
  purchased_on DATE NOT NULL,
  paid_with    VARCHAR(10) NOT NULL CHECK (paid_with IN ('CASH','BANK','GCASH','MAYA','CHECK')),
  note         VARCHAR(200),
  total        BIGINT NOT NULL CHECK (total >= 0),
  created_by   BIGINT NOT NULL REFERENCES users(id),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX outsource_purchases_branch_idx ON outsource_purchases(branch_id, purchased_on DESC);

CREATE TABLE outsource_lines (
  id          BIGSERIAL PRIMARY KEY,
  purchase_id BIGINT NOT NULL REFERENCES outsource_purchases(id) ON DELETE CASCADE,
  item_id     BIGINT REFERENCES items(id),            -- set when it is a stocked item: stock goes up by qty
  description VARCHAR(120) NOT NULL,
  qty         BIGINT NOT NULL CHECK (qty > 0),        -- thousandths
  unit_cost   BIGINT NOT NULL CHECK (unit_cost >= 0), -- centavos per unit
  amount      BIGINT NOT NULL CHECK (amount >= 0)
);
CREATE INDEX outsource_lines_purchase_idx ON outsource_lines(purchase_id);
