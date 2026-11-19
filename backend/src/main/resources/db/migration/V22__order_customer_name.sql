-- A name or label on the order (customer name, "Table 4", a pickup name) so staff can call it out and find it again.
ALTER TABLE orders ADD COLUMN customer_name VARCHAR(60);
