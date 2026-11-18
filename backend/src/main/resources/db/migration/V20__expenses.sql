-- Expenses: what a branch (or HQ) spent to run the business. Never deleted: a wrong entry is voided with a reason.
CREATE TABLE expense_categories (
  id     BIGSERIAL PRIMARY KEY,
  name   VARCHAR(60) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE UNIQUE INDEX expense_categories_name_uq ON expense_categories (lower(name));
INSERT INTO expense_categories(name) VALUES ('Rent'), ('Utilities'), ('Salaries and wages'), ('Supplies'), ('Repairs and maintenance'),
  ('Marketing'), ('Delivery and transport'), ('Licenses and permits'), ('Other');

CREATE TABLE expenses (
  id           BIGSERIAL PRIMARY KEY,
  branch_id    BIGINT REFERENCES branches(id),             -- null = an HQ expense not tied to one branch
  category_id  BIGINT NOT NULL REFERENCES expense_categories(id),
  amount       BIGINT NOT NULL CHECK (amount > 0),         -- centavos
  expense_date DATE NOT NULL,
  description  VARCHAR(200) NOT NULL,
  paid_with    VARCHAR(10) NOT NULL CHECK (paid_with IN ('CASH','BANK','GCASH','MAYA','CHECK')),
  receipt_no   VARCHAR(40),
  created_by   BIGINT NOT NULL REFERENCES users(id),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  voided_at    TIMESTAMPTZ,
  voided_by    BIGINT REFERENCES users(id),
  void_reason  VARCHAR(200)
);
CREATE INDEX expenses_branch_date_idx ON expenses(branch_id, expense_date DESC);
CREATE INDEX expenses_date_idx ON expenses(expense_date DESC);
