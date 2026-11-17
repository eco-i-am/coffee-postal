-- Recipes: what one sale of a product uses up. Sales then deduct stock automatically.
--
-- Stock quantities are now counted in THOUSANDTHS of an item's unit, because a latte uses
-- 0.018 of a 1 kg bag of beans, not a whole bag. (1 bag = 1000.) Existing rows are converted.
UPDATE stock_movements SET qty = qty * 1000;

ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_kind_check;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_kind_check
  CHECK (kind IN ('OPENING','ADJUST','WASTE','PO_RECEIVE','SALE'));

CREATE TABLE product_recipes (
  product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  item_id    BIGINT NOT NULL REFERENCES items(id),
  qty        BIGINT NOT NULL CHECK (qty > 0),     -- thousandths of the item's unit used per ONE product sold
  PRIMARY KEY (product_id, item_id)
);
