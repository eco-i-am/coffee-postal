-- Module catalogue: what a role can be granted view/add/edit/delete on.
CREATE TABLE modules (
  code  VARCHAR(60) PRIMARY KEY,
  scope VARCHAR(10) NOT NULL CHECK (scope IN ('ADMIN','BRANCH')),
  label VARCHAR(120) NOT NULL
);

INSERT INTO modules(code, scope, label) VALUES
 -- HQ (Admin system)
 ('branches','ADMIN','Branch List'),
 ('branch_members','ADMIN','Branch Members'),
 ('branch_po_admin','ADMIN','Branch PO (HQ)'),
 ('receivables','ADMIN','Receivables'),
 ('announcements','ADMIN','Announcements'),
 ('training_admin','ADMIN','Training (HQ)'),
 ('products','ADMIN','Products'),
 ('product_addons','ADMIN','Product Add-Ons'),
 ('product_categories','ADMIN','Product Categories'),
 ('items_master','ADMIN','Items (master list)'),
 ('suppliers','ADMIN','Suppliers'),
 ('supplier_po','ADMIN','Supplier PO'),
 ('po_items','ADMIN','PO Items'),
 ('support','ADMIN','Support Chat'),
 ('admin_employees','ADMIN','HQ Employees'),
 ('accounting','ADMIN','Expenses (HQ)'),
 ('reports_admin','ADMIN','Reports (HQ)'),
 ('users_admin','ADMIN','User Management (HQ)'),
 ('roles','ADMIN','Roles'),
 ('logs','ADMIN','Logs'),
 ('settings_admin','ADMIN','Settings (HQ)'),
 -- Branch system
 ('dashboard','BRANCH','Dashboard'),
 ('pos','BRANCH','Point of Sale'),
 ('orders','BRANCH','Order List'),
 ('refunds','BRANCH','Refund Order'),
 ('branch_products','BRANCH','Product List'),
 ('feedback','BRANCH','Feedback List'),
 ('branch_inventory','BRANCH','Item Inventory'),
 ('branch_items','BRANCH','Item List'),
 ('branch_po','BRANCH','Purchase Orders'),
 ('payables','BRANCH','Payables'),
 ('outsource','BRANCH','Outsource'),
 ('branch_reports','BRANCH','Reports'),
 ('cashier_reports','BRANCH','Cashier Reports'),
 ('cash_inout','BRANCH','Cash-In / Cash-Out'),
 ('branch_expenses','BRANCH','Branch Expenses'),
 ('branch_training','BRANCH','Training'),
 ('branch_employees','BRANCH','Employees'),
 ('branch_settings','BRANCH','Settings'),
 ('branch_users','BRANCH','User Management');

-- Super Admin: everything
INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete)
SELECT r.id, m.code, TRUE, TRUE, TRUE, TRUE
FROM roles r CROSS JOIN modules m WHERE r.name = 'Super Admin';

-- HQ Staff: HQ modules, no delete; no users/roles/logs
INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete)
SELECT r.id, m.code, TRUE, TRUE, TRUE, FALSE
FROM roles r CROSS JOIN modules m
WHERE r.name = 'HQ Staff' AND m.scope = 'ADMIN' AND m.code NOT IN ('users_admin','roles','logs');

-- Branch Owner: all branch modules
INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete)
SELECT r.id, m.code, TRUE, TRUE, TRUE, TRUE
FROM roles r CROSS JOIN modules m WHERE r.name = 'Branch Owner' AND m.scope = 'BRANCH';

-- Branch Manager: branch modules except user management; no delete
INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete)
SELECT r.id, m.code, TRUE, TRUE, TRUE, FALSE
FROM roles r CROSS JOIN modules m
WHERE r.name = 'Branch Manager' AND m.scope = 'BRANCH' AND m.code <> 'branch_users';

-- Cashier: the counter only
INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete)
SELECT r.id, v.code, TRUE, v.add_, FALSE, FALSE
FROM roles r
JOIN (VALUES ('pos', TRUE), ('orders', FALSE), ('branch_products', FALSE),
             ('feedback', FALSE), ('cash_inout', TRUE), ('branch_training', FALSE)) AS v(code, add_) ON TRUE
WHERE r.name = 'Cashier';
