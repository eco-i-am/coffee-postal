-- Support chat: a branch writes to HQ support, HQ replies, and the history stays. One thread per question.
INSERT INTO modules(code, scope, label) VALUES ('branch_support', 'BRANCH', 'Support Chat');

-- every branch role can ask for help; Super Admin and Branch Owner also manage (close and reopen) their threads
INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete)
SELECT r.id, 'branch_support', TRUE, TRUE, r.name IN ('Super Admin', 'Branch Owner'), FALSE
FROM roles r
WHERE r.name = 'Super Admin' OR r.scope = 'BRANCH';

CREATE TABLE support_threads (
  id             BIGSERIAL PRIMARY KEY,
  branch_id      BIGINT NOT NULL REFERENCES branches(id),
  subject        VARCHAR(120) NOT NULL,
  status         VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
  created_by     BIGINT NOT NULL REFERENCES users(id),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  branch_unread  INT NOT NULL DEFAULT 0,    -- HQ replies the branch has not opened
  hq_unread      INT NOT NULL DEFAULT 0     -- branch messages HQ has not opened
);
CREATE INDEX support_threads_branch_idx ON support_threads(branch_id, updated_at DESC);
CREATE INDEX support_threads_updated_idx ON support_threads(updated_at DESC);

CREATE TABLE support_messages (
  id         BIGSERIAL PRIMARY KEY,
  thread_id  BIGINT NOT NULL REFERENCES support_threads(id) ON DELETE CASCADE,
  sender_id  BIGINT NOT NULL REFERENCES users(id),
  from_hq    BOOLEAN NOT NULL,
  body       VARCHAR(2000) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX support_messages_thread_idx ON support_messages(thread_id, id);
