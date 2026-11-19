-- Coffee Postal master data: ingredient list + Kiosk menu (source: ishiki-os product-dev/coffee-postal/document, item list.xlsx)
-- Idempotent: safe to run twice. Items/categories skip if the name exists; products skip if a product with that name exists.
-- Prices in centavos. Item stock is counted in the item's own pack unit; conversions allow g/ml/pc entry.
-- products.cost = the "CP For Sale Price" (what HQ charges the branch per cup). Recipes are NOT included (no data yet).
BEGIN;

INSERT INTO product_headers(name, sort_order) VALUES ('Drinks', 1) ON CONFLICT (name) DO NOTHING;
INSERT INTO product_categories(name, sort_order, header_id)
SELECT v.name, v.ord, (SELECT id FROM product_headers WHERE name='Drinks') FROM (VALUES ('Coffee',1),('Milk Tea',2),('Pastel',3)) v(name,ord)
ON CONFLICT (name) DO NOTHING;
UPDATE product_categories SET header_id=(SELECT id FROM product_headers WHERE name='Drinks') WHERE name IN ('Coffee','Milk Tea','Pastel') AND header_id IS NULL;

INSERT INTO item_categories(name, sort_order)
SELECT v.name, v.ord FROM (VALUES ('Coffee & Tea',1),('Packaging',2),('Creams & Flavors',3),('Sweeteners & Syrups',4)) v(name,ord)
WHERE NOT EXISTS (SELECT 1 FROM item_categories c WHERE lower(c.name)=lower(v.name));

INSERT INTO units(code) SELECT v.code FROM (VALUES ('kg'),('g'),('pack'),('pc'),('L'),('ml')) v(code)
WHERE NOT EXISTS (SELECT 1 FROM units u WHERE lower(u.code)=lower(v.code));

INSERT INTO items(name, unit, cost, category_id)
SELECT v.name, v.unit, v.cost, (SELECT id FROM item_categories c WHERE lower(c.name)=lower(v.cat))
FROM (VALUES
  ('Beans','kg',110000,'Coffee & Tea'),
  ('12 oz cup','pack',50000,'Packaging'),
  ('16 oz cup','pack',50000,'Packaging'),
  ('Cup Lid','pack',5000,'Packaging'),
  ('Thin Straw','pack',5000,'Packaging'),
  ('Thick Straw','pack',8000,'Packaging'),
  ('Hot Cup','pack',60000,'Packaging'),
  ('Milk Cream','kg',29000,'Creams & Flavors'),
  ('Seasalt','L',41000,'Sweeteners & Syrups'),
  ('Caramel','L',29000,'Sweeteners & Syrups'),
  ('Sweetener','L',19000,'Sweeteners & Syrups'),
  ('Mocha Cream','kg',34000,'Creams & Flavors'),
  ('Yema Cream','kg',29000,'Creams & Flavors'),
  ('Muscovado Sugar','kg',19000,'Sweeteners & Syrups'),
  ('Condensed Cream','kg',10000,'Creams & Flavors'),
  ('Ube Cream','kg',29000,'Creams & Flavors'),
  ('Mango Cream','kg',29000,'Creams & Flavors'),
  ('Melon Cream','kg',29000,'Creams & Flavors'),
  ('Avocado Cream','kg',29000,'Creams & Flavors'),
  ('Matcha Cream','kg',41000,'Creams & Flavors'),
  ('Cacao Cream','kg',39000,'Creams & Flavors'),
  ('Black Tea','pack',20600,'Coffee & Tea'),
  ('Pearl','kg',15000,'Coffee & Tea')
) v(name,unit,cost,cat)
WHERE NOT EXISTS (SELECT 1 FROM items i WHERE lower(i.name)=lower(v.name));

INSERT INTO item_conversions(item_id, unit, factor)
SELECT i.id, v.unit, v.factor FROM (VALUES
  ('Beans','g',1),
  ('12 oz cup','pc',10),
  ('16 oz cup','pc',10),
  ('Cup Lid','pc',10),
  ('Thin Straw','pc',10),
  ('Thick Straw','pc',10),
  ('Hot Cup','pc',10),
  ('Milk Cream','g',1),
  ('Seasalt','ml',1),
  ('Caramel','ml',1),
  ('Sweetener','ml',1),
  ('Mocha Cream','g',1),
  ('Yema Cream','g',1),
  ('Muscovado Sugar','g',1),
  ('Condensed Cream','g',1),
  ('Ube Cream','g',1),
  ('Mango Cream','g',1),
  ('Melon Cream','g',1),
  ('Avocado Cream','g',1),
  ('Matcha Cream','g',1),
  ('Cacao Cream','g',1),
  ('Black Tea','g',5),
  ('Pearl','g',1)
) v(name,unit,factor) JOIN items i ON lower(i.name)=lower(v.name)
WHERE NOT EXISTS (SELECT 1 FROM item_conversions c WHERE c.item_id=i.id AND lower(c.unit)=lower(v.unit));

INSERT INTO products(category_id, name, price, cost, art, has_sizes)
SELECT (SELECT id FROM product_categories c WHERE c.name=v.cat), v.name, v.price, v.cost, v.art, v.sizes
FROM (VALUES
  ('Hot Black Coffee','Coffee','hot',6000,1700,false),
  ('Iced Black Coffee','Coffee','iced',6000,1700,true),
  ('Hot Mocha Coffee','Coffee','hot',7500,2600,false),
  ('Iced Mocha','Coffee','iced',7500,2600,true),
  ('Hot Caramel Coffee','Coffee','hot',7500,2500,false),
  ('Iced Caramel','Coffee','iced',7500,2500,true),
  ('Hot Yema Coffee','Coffee','hot',7500,2500,false),
  ('Iced Yema','Coffee','iced',7500,2500,true),
  ('Hot Mestizo Brew','Coffee','hot',8500,2700,false),
  ('Iced Mestizo Brew','Coffee','iced',8500,2700,true),
  ('Hot Juan Latte','Coffee','hot',7500,2500,false),
  ('Iced Juan Latte','Coffee','iced',7500,2500,true),
  ('Iced Seasalt Latte','Coffee','iced',8500,2700,true),
  ('Ube Milk Tea','Milk Tea','iced',6500,2200,false),
  ('Mango Milk Tea','Milk Tea','iced',6500,2200,false),
  ('Avocado Milk Tea','Milk Tea','iced',6500,2200,false),
  ('Matcha Milk Tea','Milk Tea','iced',7000,2500,false),
  ('Cacao Milk Tea','Milk Tea','iced',7000,2500,false),
  ('Hot Pastel Matcha & Cacao','Pastel','hot',5500,1800,false),
  ('Pastel Matcha & Cacao','Pastel','iced',5500,1800,true),
  ('Hot Pastel Ube','Pastel','hot',5000,1600,false),
  ('Pastel Ube','Pastel','iced',5000,1600,true),
  ('Pastel Mango','Pastel','iced',5000,1600,true),
  ('Pastel Melon','Pastel','iced',5000,1600,true),
  ('Pastel Avocado','Pastel','iced',5000,1600,true)
) v(name,cat,art,price,cost,sizes)
WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.name=v.name);

INSERT INTO product_variants(product_id, name, price_delta, sort_order)
SELECT p.id, v.vname, v.delta, v.ord FROM (VALUES
  ('Iced Black Coffee','Regular',0,0),
  ('Iced Black Coffee','Large',1000,1),
  ('Iced Mocha','Regular',0,0),
  ('Iced Mocha','Large',1500,1),
  ('Iced Caramel','Regular',0,0),
  ('Iced Caramel','Large',1500,1),
  ('Iced Yema','Regular',0,0),
  ('Iced Yema','Large',1500,1),
  ('Iced Mestizo Brew','Regular',0,0),
  ('Iced Mestizo Brew','Large',1500,1),
  ('Iced Juan Latte','Regular',0,0),
  ('Iced Juan Latte','Large',1500,1),
  ('Iced Seasalt Latte','Regular',0,0),
  ('Iced Seasalt Latte','Large',1500,1),
  ('Pastel Matcha & Cacao','Economy',0,0),
  ('Pastel Matcha & Cacao','Business Class',1000,1),
  ('Pastel Ube','Economy',0,0),
  ('Pastel Ube','Business Class',1000,1),
  ('Pastel Mango','Economy',0,0),
  ('Pastel Mango','Business Class',1000,1),
  ('Pastel Melon','Economy',0,0),
  ('Pastel Melon','Business Class',1000,1),
  ('Pastel Avocado','Economy',0,0),
  ('Pastel Avocado','Business Class',1000,1)
) v(pname,vname,delta,ord) JOIN products p ON p.name=v.pname
ON CONFLICT (product_id, name) DO NOTHING;

COMMIT;
