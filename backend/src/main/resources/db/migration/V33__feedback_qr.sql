-- Every branch has a QR code on its receipts. It opens a public rating page, so customers can rate from their own phone, anywhere.
-- The code in the link is random and belongs to the branch; it lets someone rate that branch and nothing else.
ALTER TABLE branches ADD COLUMN feedback_token VARCHAR(16) NOT NULL DEFAULT substr(md5(random()::text || clock_timestamp()::text), 1, 16);
CREATE UNIQUE INDEX branches_feedback_token_idx ON branches(feedback_token);

-- a rating from the QR page has no signed-in user
ALTER TABLE feedback ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE feedback ADD COLUMN source VARCHAR(4) NOT NULL DEFAULT 'POS' CHECK (source IN ('POS', 'QR'));
