-- A purchase order says the day the branch wants the delivery. It must be at least the lead time (a setting, 48 hours by default) after the order is sent.
ALTER TABLE branch_pos ADD COLUMN deliver_on DATE;
