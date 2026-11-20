-- SKU, barcode and a short description on menu products and inventory items. All optional.
-- products already has sku (unique) and description from V3, so only barcode is new there.
-- A SKU or barcode must identify one product (or item), so each is unique where it is filled in (case does not matter).
ALTER TABLE products ADD COLUMN barcode     VARCHAR(40);
ALTER TABLE items    ADD COLUMN sku         VARCHAR(40);
ALTER TABLE items    ADD COLUMN barcode     VARCHAR(40);
ALTER TABLE items    ADD COLUMN description VARCHAR(500);

CREATE UNIQUE INDEX products_sku_uq     ON products (lower(sku))     WHERE sku IS NOT NULL;
CREATE UNIQUE INDEX products_barcode_uq ON products (lower(barcode)) WHERE barcode IS NOT NULL;
CREATE UNIQUE INDEX items_sku_uq        ON items (lower(sku))        WHERE sku IS NOT NULL;
CREATE UNIQUE INDEX items_barcode_uq    ON items (lower(barcode))    WHERE barcode IS NOT NULL;
