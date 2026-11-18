-- Branch purchase orders: the full 9-stage flow, a history of every change, and receiving in parts.
--   REQUESTED -> PENDING -> TO_PAY -> PROCESSING -> FOR_PICKUP -> IN_TRANSIT -> PICKED_UP -> DELIVERED -> COMPLETED
--   (or REJECTED by HQ, CANCELLED by the branch). Old statuses are mapped: APPROVED -> PENDING, SHIPPED -> IN_TRANSIT, RECEIVED -> COMPLETED.
ALTER TABLE branch_pos DROP CONSTRAINT branch_pos_status_check;
ALTER TABLE branch_pos ALTER COLUMN status TYPE VARCHAR(12);
UPDATE branch_pos SET status = 'PENDING'    WHERE status = 'APPROVED';
UPDATE branch_pos SET status = 'IN_TRANSIT' WHERE status = 'SHIPPED';
UPDATE branch_pos SET status = 'COMPLETED'  WHERE status = 'RECEIVED';
ALTER TABLE branch_pos ADD CONSTRAINT branch_pos_status_check CHECK (status IN
  ('REQUESTED','PENDING','TO_PAY','PROCESSING','FOR_PICKUP','IN_TRANSIT','PICKED_UP','DELIVERED','COMPLETED','REJECTED','CANCELLED'));

CREATE TABLE branch_po_status_history (
  id          BIGSERIAL PRIMARY KEY,
  po_id       BIGINT NOT NULL REFERENCES branch_pos(id) ON DELETE CASCADE,
  from_status VARCHAR(12),
  to_status   VARCHAR(12) NOT NULL,
  by_user     BIGINT REFERENCES users(id),
  note        VARCHAR(300),
  at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX branch_po_status_history_po_idx ON branch_po_status_history(po_id, id);
-- orders that already exist get a starting point
INSERT INTO branch_po_status_history(po_id, from_status, to_status, by_user, at)
SELECT id, NULL, 'REQUESTED', requested_by, requested_at FROM branch_pos;
INSERT INTO branch_po_status_history(po_id, from_status, to_status, by_user, at, note)
SELECT id, 'REQUESTED', status, decided_by, coalesce(received_at, shipped_at, decided_at, requested_at), 'Carried over from the earlier version'
FROM branch_pos WHERE status <> 'REQUESTED';

-- what arrived, delivery by delivery (qty_received on the line stays the running total)
CREATE TABLE branch_po_receipts (
  id          BIGSERIAL PRIMARY KEY,
  line_id     BIGINT NOT NULL REFERENCES branch_po_lines(id) ON DELETE CASCADE,
  qty         BIGINT NOT NULL CHECK (qty > 0),
  received_by BIGINT NOT NULL REFERENCES users(id),
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX branch_po_receipts_line_idx ON branch_po_receipts(line_id);
