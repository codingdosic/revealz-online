BEGIN;

INSERT INTO shop_products (
  product_id, product_type, display_name, description, price_gold, enabled,
  pack_size, weight_n, weight_r, weight_sr, weight_ur, pool_mode, pool_json,
  accessory_type, accessory_id, sort_order
) VALUES
  ('shop_field_white', 'accessory', '화이트 필드', '차가운 백색과 은빛 테두리의 필드', 1000, TRUE, 1, 0, 0, 0, 0, 'explicit', '[]'::jsonb, 'field', 'field_white', 120),
  ('shop_field_board2', 'accessory', '레드 필드', '절제된 적색과 은빛 테두리의 필드', 1000, TRUE, 1, 0, 0, 0, 0, 'explicit', '[]'::jsonb, 'field', 'field_board2', 121),
  ('shop_field_purple', 'accessory', '퍼플 필드', '절제된 자색과 은빛 테두리의 필드', 1000, TRUE, 1, 0, 0, 0, 0, 'explicit', '[]'::jsonb, 'field', 'field_purple', 122),
  ('shop_field_green', 'accessory', '그린 필드', '깊은 녹색과 은빛 테두리의 필드', 1000, TRUE, 1, 0, 0, 0, 0, 'explicit', '[]'::jsonb, 'field', 'field_green', 123),
  ('shop_field_blue', 'accessory', '블루 필드', '선명한 청색과 은빛 테두리의 필드', 1000, TRUE, 1, 0, 0, 0, 0, 'explicit', '[]'::jsonb, 'field', 'field_blue', 124)
ON CONFLICT (product_id) DO UPDATE SET
  product_type = EXCLUDED.product_type,
  display_name = EXCLUDED.display_name,
  description = EXCLUDED.description,
  price_gold = EXCLUDED.price_gold,
  enabled = EXCLUDED.enabled,
  accessory_type = EXCLUDED.accessory_type,
  accessory_id = EXCLUDED.accessory_id,
  sort_order = EXCLUDED.sort_order,
  updated_at = NOW();

INSERT INTO app_config (config_key, config_value)
VALUES ('shop_catalog_revision', '2'::jsonb)
ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value;

COMMIT;
