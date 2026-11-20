-- Free-form tags on a product (comma separated, lower case), so one product can show under several headings on the POS.
ALTER TABLE products ADD COLUMN tags TEXT NOT NULL DEFAULT '';
