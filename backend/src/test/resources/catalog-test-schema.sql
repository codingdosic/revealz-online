CREATE TABLE shop_products (
  product_id TEXT PRIMARY KEY,
  product_type TEXT NOT NULL,
  display_name TEXT,
  description TEXT,
  price_gold INTEGER,
  enabled BOOLEAN NOT NULL,
  pack_size INTEGER,
  weight_n INTEGER,
  weight_r INTEGER,
  weight_sr INTEGER,
  weight_ur INTEGER,
  pool_mode TEXT,
  pool_json JSONB,
  accessory_type TEXT,
  accessory_id TEXT,
  sort_order INTEGER
);

CREATE TABLE app_config (
  config_key TEXT PRIMARY KEY,
  config_value JSONB
);
