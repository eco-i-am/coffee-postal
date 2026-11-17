-- POS catalog. Prices in integer centavos.
CREATE TABLE product_categories (
  id         BIGSERIAL PRIMARY KEY,
  name       VARCHAR(80) NOT NULL UNIQUE,
  sort_order INT NOT NULL DEFAULT 0,
  active     BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE products (
  id          BIGSERIAL PRIMARY KEY,
  category_id BIGINT NOT NULL REFERENCES product_categories(id),
  name        VARCHAR(120) NOT NULL,
  sku         VARCHAR(40) UNIQUE,
  description TEXT,
  price       BIGINT NOT NULL CHECK (price >= 0),   -- selling price, centavos, VAT-inclusive
  cost        BIGINT NOT NULL DEFAULT 0 CHECK (cost >= 0),
  has_sizes   BOOLEAN NOT NULL DEFAULT FALSE,       -- drinks offer Regular / Large
  art         VARCHAR(20) NOT NULL DEFAULT 'hot',   -- tile illustration: hot, iced, frap, pastry, cookie, sand
  color       VARCHAR(7)  NOT NULL DEFAULT '#FFC74A',
  active      BOOLEAN NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX products_category_idx ON products(category_id);

CREATE TABLE product_variants (                      -- sizes
  id          BIGSERIAL PRIMARY KEY,
  product_id  BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  name        VARCHAR(40) NOT NULL,
  price_delta BIGINT NOT NULL DEFAULT 0,
  sort_order  INT NOT NULL DEFAULT 0,
  UNIQUE (product_id, name)
);

CREATE TABLE addons (
  id     BIGSERIAL PRIMARY KEY,
  name   VARCHAR(80) NOT NULL UNIQUE,
  price  BIGINT NOT NULL CHECK (price >= 0),
  active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE product_addons (
  product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  addon_id   BIGINT NOT NULL REFERENCES addons(id),
  PRIMARY KEY (product_id, addon_id)
);

-- A row switches a product off (or sets a branch price) for one branch. No row = available at the HQ price.
CREATE TABLE branch_products (
  branch_id  BIGINT NOT NULL REFERENCES branches(id),
  product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  available  BOOLEAN NOT NULL DEFAULT TRUE,
  price      BIGINT CHECK (price >= 0),
  PRIMARY KEY (branch_id, product_id)
);
