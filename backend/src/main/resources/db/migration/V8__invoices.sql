-- Invoices HQ issues to branches for purchase orders, and the payments recorded against them.
-- HQ sees these as receivables, the branch as payables. Money is centavos.
-- Status is never stored: paid = sum(payments), balance = amount - paid, overdue = balance > 0 and past due_date.
CREATE TABLE invoices (
  id        BIGSERIAL PRIMARY KEY,
  po_id     BIGINT NOT NULL UNIQUE REFERENCES branch_pos(id),   -- one invoice per purchase order
  branch_id BIGINT NOT NULL REFERENCES branches(id),
  amount    BIGINT NOT NULL CHECK (amount > 0),
  issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  due_date  DATE NOT NULL
);
CREATE INDEX invoices_branch_idx ON invoices(branch_id, due_date);

CREATE TABLE invoice_payments (
  id          BIGSERIAL PRIMARY KEY,
  invoice_id  BIGINT NOT NULL REFERENCES invoices(id),
  amount      BIGINT NOT NULL CHECK (amount > 0),
  method      VARCHAR(8) NOT NULL CHECK (method IN ('CASH','BANK','GCASH','MAYA','CHECK')),
  reference   VARCHAR(80),
  paid_on     DATE NOT NULL,
  recorded_by BIGINT NOT NULL REFERENCES users(id),
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX invoice_payments_invoice_idx ON invoice_payments(invoice_id);
