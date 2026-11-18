-- Z reading number: one running count per branch, given when a shift closes (the X reading is the same report on an open shift).
ALTER TABLE shifts ADD COLUMN z_no INT;
CREATE UNIQUE INDEX shifts_branch_z_idx ON shifts(branch_id, z_no) WHERE z_no IS NOT NULL;

-- How many times a receipt was printed again after the first one.
ALTER TABLE orders ADD COLUMN reprint_count INT NOT NULL DEFAULT 0;
