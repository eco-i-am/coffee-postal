-- Indexes for queries that were scanning whole tables: log screens, per-cashier and
-- per-product reports, chain-wide stock reports.
CREATE INDEX login_history_at_idx          ON login_history(at DESC);
CREATE INDEX audit_logs_user_idx           ON audit_logs(user_id, at DESC);
CREATE INDEX audit_logs_branch_idx         ON audit_logs(branch_id, at DESC);
CREATE INDEX orders_cashier_idx            ON orders(cashier_id, created_at DESC);
CREATE INDEX orders_status_idx             ON orders(status, created_at DESC);
CREATE INDEX order_lines_product_idx       ON order_lines(product_id);
CREATE INDEX stock_movements_item_kind_idx ON stock_movements(item_id, kind, created_at DESC);
CREATE INDEX shifts_cashier_idx            ON shifts(cashier_id, opened_at DESC);
