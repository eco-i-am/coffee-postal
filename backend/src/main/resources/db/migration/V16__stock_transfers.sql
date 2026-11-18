-- Branch to branch stock transfers. The sending branch's stock drops when it sends; the receiving branch's stock
-- rises only when it confirms the goods arrived (so nothing counts twice, and nothing vanishes while on the road).
CREATE TABLE stock_transfers (
  id           BIGSERIAL PRIMARY KEY,
  from_branch  BIGINT NOT NULL REFERENCES branches(id),
  to_branch    BIGINT NOT NULL REFERENCES branches(id),
  status       VARCHAR(10) NOT NULL DEFAULT 'SENT' CHECK (status IN ('SENT','RECEIVED','CANCELLED')),
  note         VARCHAR(200),
  created_by   BIGINT NOT NULL REFERENCES users(id),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_by    BIGINT REFERENCES users(id),
  closed_at    TIMESTAMPTZ,
  CHECK (from_branch <> to_branch)
);
CREATE INDEX stock_transfers_from_idx ON stock_transfers(from_branch, created_at DESC);
CREATE INDEX stock_transfers_to_idx ON stock_transfers(to_branch, created_at DESC);

CREATE TABLE stock_transfer_lines (
  id          BIGSERIAL PRIMARY KEY,
  transfer_id BIGINT NOT NULL REFERENCES stock_transfers(id) ON DELETE CASCADE,
  item_id     BIGINT NOT NULL REFERENCES items(id),
  qty         BIGINT NOT NULL CHECK (qty > 0)      -- thousandths of the item's own unit
);
CREATE INDEX stock_transfer_lines_transfer_idx ON stock_transfer_lines(transfer_id);

ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_kind_check;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_kind_check
  CHECK (kind IN ('OPENING','ADJUST','WASTE','PO_RECEIVE','SALE','TRANSFER_OUT','TRANSFER_IN'));

INSERT INTO modules(code, scope, label) VALUES ('transfers','BRANCH','Stock Transfers'), ('admin_transfers','ADMIN','Stock Transfers');
INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete)
SELECT r.id, 'transfers', TRUE, TRUE, TRUE, r.name = 'Super Admin'
FROM roles r WHERE r.name IN ('Super Admin','Branch Owner','Branch Manager');
INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete)
SELECT r.id, 'admin_transfers', TRUE, FALSE, FALSE, r.name = 'Super Admin'
FROM roles r WHERE r.name IN ('Super Admin','HQ Staff');
