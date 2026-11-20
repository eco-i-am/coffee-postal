-- A branch is an Express or a Kiosk. Existing branches take it from their code prefix (X-6040 is Express, K-6340 is Kiosk);
-- codes stay as they are. Flagship and sample branches have no type.
ALTER TABLE branches ADD COLUMN branch_type TEXT CHECK (branch_type IN ('EXPRESS', 'KIOSK'));

UPDATE branches SET branch_type = CASE WHEN code LIKE 'X-%' THEN 'EXPRESS' ELSE 'KIOSK' END WHERE code ~ '^[XK]-';
