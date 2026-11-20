-- Announcements: HQ writes to all branches or to chosen ones; each branch user sees them and marks them read.
INSERT INTO modules(code, scope, label) VALUES ('branch_announcements', 'BRANCH', 'Announcements');

-- everyone who works in a branch can read them; Super Admin and Branch Owner get the full set like every other module
INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete)
SELECT r.id, 'branch_announcements', TRUE, r.name IN ('Super Admin', 'Branch Owner'), r.name IN ('Super Admin', 'Branch Owner'),
       r.name IN ('Super Admin', 'Branch Owner')
FROM roles r
WHERE r.name = 'Super Admin' OR r.scope = 'BRANCH';

CREATE TABLE announcements (
  id          BIGSERIAL PRIMARY KEY,
  title       VARCHAR(120) NOT NULL,
  body        VARCHAR(4000) NOT NULL,
  all_branches BOOLEAN NOT NULL DEFAULT TRUE,
  created_by  BIGINT NOT NULL REFERENCES users(id),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  removed_at  TIMESTAMPTZ                                   -- taken down; branches no longer see it
);
CREATE INDEX announcements_created_idx ON announcements(created_at DESC);

CREATE TABLE announcement_branches (
  announcement_id BIGINT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE,
  branch_id       BIGINT NOT NULL REFERENCES branches(id),
  PRIMARY KEY (announcement_id, branch_id)
);

CREATE TABLE announcement_reads (
  announcement_id BIGINT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE,
  user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  read_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (announcement_id, user_id)
);
