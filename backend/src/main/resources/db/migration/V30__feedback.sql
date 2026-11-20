-- Customer feedback: a rating and an optional comment, entered at the POS after a sale, read by the branch and by HQ.
CREATE TABLE feedback (
  id         BIGSERIAL PRIMARY KEY,
  branch_id  BIGINT NOT NULL REFERENCES branches(id),
  order_ref  VARCHAR(40),                                  -- the order number it was given for, when known
  rating     INT NOT NULL CHECK (rating BETWEEN 1 AND 5),
  comment    VARCHAR(500),
  created_by BIGINT NOT NULL REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX feedback_branch_idx ON feedback(branch_id, created_at DESC);
CREATE INDEX feedback_created_idx ON feedback(created_at DESC);

-- anyone who can see the feedback list at a branch (cashiers included) can also enter it at the till
UPDATE role_permissions SET can_add = TRUE WHERE module = 'feedback' AND can_view;

-- HQ reads every branch's feedback
INSERT INTO modules(code, scope, label) VALUES ('feedback_admin', 'ADMIN', 'Feedback (HQ)');
INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete)
SELECT r.id, 'feedback_admin', TRUE, FALSE, FALSE, FALSE FROM roles r WHERE r.name IN ('Super Admin', 'HQ Staff');
