-- Anyone working at the branch can count a delivery in, cashiers included. Ordering, cancelling and closing short stay with managers (branch_po).
INSERT INTO modules(code, scope, label) VALUES ('branch_po_receive', 'BRANCH', 'Receive deliveries');

INSERT INTO role_permissions(role_id, module, can_view, can_add, can_edit, can_delete)
SELECT r.id, 'branch_po_receive', TRUE, FALSE, TRUE, FALSE
FROM roles r
WHERE r.name = 'Super Admin' OR r.scope = 'BRANCH';
