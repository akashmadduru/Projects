-- =============================================================================
-- product-service: demo catalog seed (Phase 4 slice - data). DEV ONLY.
--
-- Lives in classpath:db/seed, which application.yml adds to Flyway's locations
-- ONLY for the `default` (local / docker-compose) profile - never for `test`
-- or `prod`. Repeatable (R__) rather than versioned, so it always runs after
-- every V__ schema migration and never occupies a schema version number.
--
-- Inserts 18 categories (5 root + 13 subcategories) and 100 products.
-- Fixed ids + ON CONFLICT DO NOTHING make it idempotent: a re-run (Flyway
-- re-applies a repeatable whenever its checksum changes) adds missing rows and
-- leaves existing ones untouched - edit values here, and existing rows keep
-- their old values.
--
-- PRODUCT DATA ONLY. Stock levels and ratings live in inventory_db and
-- rating_db, owned by inventory-service and rating-service; this service's
-- Flyway cannot (and must not) write there. Note that seeded products bypass
-- the PRODUCT_CREATED saga, so they get no inventory row unless one is seeded
-- on the inventory side.
--
-- Rows are numbered by name: a VALUES list has no ordinality, and row_number()
-- needs a deterministic ORDER BY to give stable ids/skus across runs.
-- discount_percent follows V4's definition: (list_price - sale_price) /
-- list_price * 100.
-- =============================================================================

-- =============================================================================
-- Categories
-- =============================================================================

INSERT INTO product.categories (id, name, parent_category_id, depth_level, is_active, created_at, updated_at)
VALUES
  -- Root categories
  ('11111111-1111-1111-1111-111111111101', 'Electronics', NULL, 0, true, now(), now()),
  ('11111111-1111-1111-1111-111111111102', 'Home & Garden', NULL, 0, true, now(), now()),
  ('11111111-1111-1111-1111-111111111103', 'Fashion', NULL, 0, true, now(), now()),
  ('11111111-1111-1111-1111-111111111104', 'Sports', NULL, 0, true, now(), now()),
  ('11111111-1111-1111-1111-111111111105', 'Books', NULL, 0, true, now(), now()),

  -- Electronics subcategories
  ('11111111-1111-1111-1111-111111111201', 'Computers', '11111111-1111-1111-1111-111111111101', 1, true, now(), now()),
  ('11111111-1111-1111-1111-111111111202', 'Peripherals', '11111111-1111-1111-1111-111111111101', 1, true, now(), now()),
  ('11111111-1111-1111-1111-111111111203', 'Audio', '11111111-1111-1111-1111-111111111101', 1, true, now(), now()),

  -- Home & Garden subcategories
  ('11111111-1111-1111-1111-111111111204', 'Kitchen', '11111111-1111-1111-1111-111111111102', 1, true, now(), now()),
  ('11111111-1111-1111-1111-111111111205', 'Furniture', '11111111-1111-1111-1111-111111111102', 1, true, now(), now()),
  ('11111111-1111-1111-1111-111111111206', 'Lighting', '11111111-1111-1111-1111-111111111102', 1, true, now(), now()),

  -- Fashion subcategories
  ('11111111-1111-1111-1111-111111111207', 'Clothing', '11111111-1111-1111-1111-111111111103', 1, true, now(), now()),
  ('11111111-1111-1111-1111-111111111208', 'Shoes', '11111111-1111-1111-1111-111111111103', 1, true, now(), now()),
  ('11111111-1111-1111-1111-111111111209', 'Accessories', '11111111-1111-1111-1111-111111111103', 1, true, now(), now()),

  -- Sports subcategories
  ('11111111-1111-1111-1111-111111111210', 'Fitness', '11111111-1111-1111-1111-111111111104', 1, true, now(), now()),
  ('11111111-1111-1111-1111-111111111211', 'Outdoor', '11111111-1111-1111-1111-111111111104', 1, true, now(), now()),

  -- Books subcategories
  ('11111111-1111-1111-1111-111111111212', 'Fiction', '11111111-1111-1111-1111-111111111105', 1, true, now(), now()),
  ('11111111-1111-1111-1111-111111111213', 'Non-Fiction', '11111111-1111-1111-1111-111111111105', 1, true, now(), now())
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Products
-- =============================================================================

-- Electronics products (35 products)
INSERT INTO product.products (
  id, sku, name, price_amount, price_currency, active, category_id, seller_name,
  description, list_price, sale_price, discount_percent, thumbnail_url, color, material,
  average_rating, rating_count, review_count, is_featured, seller_rating, is_seller_verified,
  created_at, updated_at, version
)
SELECT
  ('aaaaaaaa-aaaa-aaaa-aaaa-' || lpad(n::text, 12, '0'))::uuid,
  'SKU-ELEC-' || lpad(n::text, 4, '0'),
  name,
  price_amount,
  'USD',
  true,
  category_id::uuid,
  seller_name,
  name || ', sold by ' || seller_name || '.',
  list_price,
  price_amount,
  round((list_price - price_amount) / list_price * 100, 2),
  'https://images.unsplash.com/photo-1517694712202-14dd9538aa97?w=400',
  NULL,
  'Plastic/Metal',
  CASE WHEN n % 3 = 0 THEN 4.5 END,
  CASE WHEN n % 3 = 0 THEN 120 ELSE 0 END,
  CASE WHEN n % 3 = 0 THEN 85 ELSE 0 END,
  n <= 5,
  4.8,
  true,
  now(),
  now(),
  0
FROM (
  SELECT
    t.*,
    row_number() OVER (ORDER BY t.name) AS n,
    round(t.price_amount * CASE WHEN t.price_amount > 500 THEN 1.2 ELSE 1.15 END, 2) AS list_price
  FROM (
    VALUES
      ('Laptop Pro 15', 1299.99, '11111111-1111-1111-1111-111111111201', 'TechStore'),
      ('Laptop Pro 13', 999.99, '11111111-1111-1111-1111-111111111201', 'TechStore'),
      ('Laptop Ultrabook', 1399.99, '11111111-1111-1111-1111-111111111201', 'TechStore'),
      ('Desktop PC Gaming', 1899.99, '11111111-1111-1111-1111-111111111201', 'TechStore'),
      ('Monitor 4K 32"', 799.99, '11111111-1111-1111-1111-111111111201', 'DisplayCorp'),
      ('Monitor 1080p 27"', 299.99, '11111111-1111-1111-1111-111111111201', 'DisplayCorp'),
      ('Mechanical Keyboard', 129.99, '11111111-1111-1111-1111-111111111202', 'KeyMaster'),
      ('Wireless Mouse', 49.99, '11111111-1111-1111-1111-111111111202', 'MousePro'),
      ('USB-C Hub', 79.99, '11111111-1111-1111-1111-111111111202', 'HubTech'),
      ('Docking Station', 199.99, '11111111-1111-1111-1111-111111111202', 'DockMaster'),
      ('Headphones Wireless', 199.99, '11111111-1111-1111-1111-111111111203', 'SoundMax'),
      ('Headphones Gaming', 99.99, '11111111-1111-1111-1111-111111111203', 'SoundMax'),
      ('Earbuds Pro', 199.99, '11111111-1111-1111-1111-111111111203', 'AudioElite'),
      ('Speaker Bluetooth', 49.99, '11111111-1111-1111-1111-111111111203', 'SoundMax'),
      ('Microphone USB', 99.99, '11111111-1111-1111-1111-111111111203', 'AudioElite'),
      ('Webcam 1080p', 79.99, '11111111-1111-1111-1111-111111111202', 'VisionTech'),
      ('External SSD 1TB', 129.99, '11111111-1111-1111-1111-111111111202', 'StoragePro'),
      ('External HDD 2TB', 79.99, '11111111-1111-1111-1111-111111111202', 'StoragePro'),
      ('USB Flash Drive 64GB', 19.99, '11111111-1111-1111-1111-111111111202', 'MemoryMax'),
      ('HDMI Cable 2.1', 15.99, '11111111-1111-1111-1111-111111111202', 'CableGear'),
      ('Power Bank 20000mAh', 39.99, '11111111-1111-1111-1111-111111111202', 'PowerCell'),
      ('Laptop Stand', 35.99, '11111111-1111-1111-1111-111111111202', 'ErgoPro'),
      ('Cooling Pad Laptop', 49.99, '11111111-1111-1111-1111-111111111202', 'CoolTech'),
      ('Phone Charger Fast', 29.99, '11111111-1111-1111-1111-111111111202', 'ChargeMax'),
      ('MIDI Keyboard', 149.99, '11111111-1111-1111-1111-111111111203', 'MusicTech'),
      ('Audio Interface', 179.99, '11111111-1111-1111-1111-111111111203', 'ProAudio'),
      ('Studio Monitors Pair', 299.99, '11111111-1111-1111-1111-111111111203', 'ProAudio'),
      ('XLR Cables', 24.99, '11111111-1111-1111-1111-111111111203', 'CableGear'),
      ('Pop Filter', 19.99, '11111111-1111-1111-1111-111111111203', 'AudioElite'),
      ('Boom Arm Stand', 34.99, '11111111-1111-1111-1111-111111111203', 'MountPro'),
      ('RGB Lighting Kit', 44.99, '11111111-1111-1111-1111-111111111202', 'LightGear'),
      ('Monitor Light Bar', 59.99, '11111111-1111-1111-1111-111111111202', 'ErgoPro'),
      ('Cable Management Kit', 29.99, '11111111-1111-1111-1111-111111111202', 'OrganizerPro'),
      ('Desk Organizer', 39.99, '11111111-1111-1111-1111-111111111202', 'OrganizerPro'),
      ('Mouse Pad Gaming', 29.99, '11111111-1111-1111-1111-111111111202', 'GamingPro')
  ) AS t(name, price_amount, category_id, seller_name)
) AS s
ON CONFLICT DO NOTHING;

-- Home & Garden products (25 products)
INSERT INTO product.products (
  id, sku, name, price_amount, price_currency, active, category_id, seller_name,
  description, list_price, sale_price, discount_percent, thumbnail_url, color, material,
  average_rating, rating_count, review_count, is_featured, seller_rating, is_seller_verified,
  created_at, updated_at, version
)
SELECT
  ('bbbbbbbb-bbbb-bbbb-bbbb-' || lpad(n::text, 12, '0'))::uuid,
  'SKU-HOME-' || lpad(n::text, 4, '0'),
  name,
  price_amount,
  'USD',
  true,
  category_id::uuid,
  seller_name,
  name || ', sold by ' || seller_name || '.',
  list_price,
  price_amount,
  round((list_price - price_amount) / list_price * 100, 2),
  'https://images.unsplash.com/photo-1556909114-f6e7ad7d3136?w=400',
  NULL,
  'Stainless Steel/Plastic',
  CASE WHEN n % 2 = 0 THEN 4.3 END,
  CASE WHEN n % 2 = 0 THEN 95 ELSE 0 END,
  CASE WHEN n % 2 = 0 THEN 68 ELSE 0 END,
  false,
  4.6,
  true,
  now(),
  now(),
  0
FROM (
  SELECT
    t.*,
    row_number() OVER (ORDER BY t.name) AS n,
    round(t.price_amount * CASE WHEN t.price_amount > 200 THEN 1.2 ELSE 1.15 END, 2) AS list_price
  FROM (
    VALUES
      ('Coffee Maker', 79.99, '11111111-1111-1111-1111-111111111204', 'KitchenMax'),
      ('Blender High-Speed', 99.99, '11111111-1111-1111-1111-111111111204', 'KitchenMax'),
      ('Toaster 4-Slice', 49.99, '11111111-1111-1111-1111-111111111204', 'BreakfastGear'),
      ('Microwave 1000W', 89.99, '11111111-1111-1111-1111-111111111204', 'KitchenMax'),
      ('Air Fryer', 129.99, '11111111-1111-1111-1111-111111111204', 'CookingPro'),
      ('Instant Pot', 99.99, '11111111-1111-1111-1111-111111111204', 'PressureCooking'),
      ('Knife Set 7-Piece', 39.99, '11111111-1111-1111-1111-111111111204', 'CutleryPro'),
      ('Cutting Board Set', 24.99, '11111111-1111-1111-1111-111111111204', 'KitchenMax'),
      ('Pots & Pans Set', 59.99, '11111111-1111-1111-1111-111111111204', 'CookwarePro'),
      ('Dining Table', 299.99, '11111111-1111-1111-1111-111111111205', 'FurnitureCorp'),
      ('Dining Chairs Set 4', 199.99, '11111111-1111-1111-1111-111111111205', 'FurnitureCorp'),
      ('Sofa 3-Seater', 599.99, '11111111-1111-1111-1111-111111111205', 'FurnitureCorp'),
      ('Coffee Table', 149.99, '11111111-1111-1111-1111-111111111205', 'FurnitureCorp'),
      ('Bookshelf 5-Tier', 99.99, '11111111-1111-1111-1111-111111111205', 'StoragePro'),
      ('Desk Home Office', 179.99, '11111111-1111-1111-1111-111111111205', 'OfficeFurniture'),
      ('Office Chair Ergonomic', 249.99, '11111111-1111-1111-1111-111111111205', 'OfficeFurniture'),
      ('Bed Frame Queen', 349.99, '11111111-1111-1111-1111-111111111205', 'BedMaster'),
      ('Mattress Queen', 499.99, '11111111-1111-1111-1111-111111111205', 'MattressPro'),
      ('Ceiling Light 60W', 34.99, '11111111-1111-1111-1111-111111111206', 'LightingPro'),
      ('Wall Lamp LED', 44.99, '11111111-1111-1111-1111-111111111206', 'LightingPro'),
      ('Floor Lamp Arc', 129.99, '11111111-1111-1111-1111-111111111206', 'LightingPro'),
      ('Desk Lamp LED', 54.99, '11111111-1111-1111-1111-111111111206', 'LightingPro'),
      ('Table Lamp', 69.99, '11111111-1111-1111-1111-111111111206', 'LightingPro'),
      ('Curtain Rod Set', 29.99, '11111111-1111-1111-1111-111111111206', 'WindowDressing'),
      ('Area Rug 8x10', 189.99, '11111111-1111-1111-1111-111111111205', 'RugMaster')
  ) AS t(name, price_amount, category_id, seller_name)
) AS s
ON CONFLICT DO NOTHING;

-- Fashion products (20 products)
INSERT INTO product.products (
  id, sku, name, price_amount, price_currency, active, category_id, seller_name,
  description, list_price, sale_price, discount_percent, thumbnail_url, color, material,
  average_rating, rating_count, review_count, is_featured, seller_rating, is_seller_verified,
  created_at, updated_at, version
)
SELECT
  ('cccccccc-cccc-cccc-cccc-' || lpad(n::text, 12, '0'))::uuid,
  'SKU-FASH-' || lpad(n::text, 4, '0'),
  name,
  price_amount,
  'USD',
  true,
  category_id::uuid,
  seller_name,
  name || ', sold by ' || seller_name || '.',
  list_price,
  price_amount,
  round((list_price - price_amount) / list_price * 100, 2),
  'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=400',
  NULL,
  'Cotton/Polyester',
  CASE WHEN n % 4 = 0 THEN 4.4 END,
  CASE WHEN n % 4 = 0 THEN 110 ELSE 0 END,
  CASE WHEN n % 4 = 0 THEN 78 ELSE 0 END,
  false,
  4.5,
  true,
  now(),
  now(),
  0
FROM (
  SELECT
    t.*,
    row_number() OVER (ORDER BY t.name) AS n,
    round(t.price_amount * 1.3, 2) AS list_price
  FROM (
    VALUES
      ('T-Shirt Cotton', 24.99, '11111111-1111-1111-1111-111111111207', 'ClothingHub'),
      ('Jeans Slim Fit', 69.99, '11111111-1111-1111-1111-111111111207', 'DenimCo'),
      ('Hoodie Fleece', 49.99, '11111111-1111-1111-1111-111111111207', 'CasualWear'),
      ('Jacket Bomber', 129.99, '11111111-1111-1111-1111-111111111207', 'OuterwearPro'),
      ('Dress Casual', 59.99, '11111111-1111-1111-1111-111111111207', 'DressStyle'),
      ('Pants Work', 79.99, '11111111-1111-1111-1111-111111111207', 'BusinessWear'),
      ('Shorts Swim', 39.99, '11111111-1111-1111-1111-111111111207', 'BeachGear'),
      ('Sneakers Running', 119.99, '11111111-1111-1111-1111-111111111208', 'SneakerCo'),
      ('Shoes Formal', 149.99, '11111111-1111-1111-1111-111111111208', 'FormalShoes'),
      ('Boots Leather', 179.99, '11111111-1111-1111-1111-111111111208', 'BootMaster'),
      ('Sandals Comfort', 49.99, '11111111-1111-1111-1111-111111111208', 'CasualShoes'),
      ('Socks Pack 6', 14.99, '11111111-1111-1111-1111-111111111209', 'SockHub'),
      ('Hat Baseball', 24.99, '11111111-1111-1111-1111-111111111209', 'HeadwearPro'),
      ('Scarf Winter', 34.99, '11111111-1111-1111-1111-111111111209', 'AccessoryHub'),
      ('Belt Leather', 44.99, '11111111-1111-1111-1111-111111111209', 'BeltMaster'),
      ('Bag Backpack', 89.99, '11111111-1111-1111-1111-111111111209', 'BagCo'),
      ('Wallet Slim', 29.99, '11111111-1111-1111-1111-111111111209', 'AccessoryHub'),
      ('Sunglasses', 79.99, '11111111-1111-1111-1111-111111111209', 'EyeWearPro'),
      ('Watch Analog', 199.99, '11111111-1111-1111-1111-111111111209', 'TimePiece'),
      ('Gloves Winter', 24.99, '11111111-1111-1111-1111-111111111209', 'AccessoryHub')
  ) AS t(name, price_amount, category_id, seller_name)
) AS s
ON CONFLICT DO NOTHING;

-- Sports products (15 products)
INSERT INTO product.products (
  id, sku, name, price_amount, price_currency, active, category_id, seller_name,
  description, list_price, sale_price, discount_percent, thumbnail_url, color, material,
  average_rating, rating_count, review_count, is_featured, seller_rating, is_seller_verified,
  created_at, updated_at, version
)
SELECT
  ('dddddddd-dddd-dddd-dddd-' || lpad(n::text, 12, '0'))::uuid,
  'SKU-SPRT-' || lpad(n::text, 4, '0'),
  name,
  price_amount,
  'USD',
  true,
  category_id::uuid,
  seller_name,
  name || ', sold by ' || seller_name || '.',
  list_price,
  price_amount,
  round((list_price - price_amount) / list_price * 100, 2),
  'https://images.unsplash.com/photo-1517836357463-d25ddfcbf042?w=400',
  NULL,
  'Nylon/Rubber',
  CASE WHEN n % 3 = 0 THEN 4.6 END,
  CASE WHEN n % 3 = 0 THEN 100 ELSE 0 END,
  CASE WHEN n % 3 = 0 THEN 70 ELSE 0 END,
  false,
  4.7,
  true,
  now(),
  now(),
  0
FROM (
  SELECT
    t.*,
    row_number() OVER (ORDER BY t.name) AS n,
    round(t.price_amount * 1.25, 2) AS list_price
  FROM (
    VALUES
      ('Yoga Mat', 29.99, '11111111-1111-1111-1111-111111111210', 'FitGear'),
      ('Dumbbells Set', 79.99, '11111111-1111-1111-1111-111111111210', 'FitGear'),
      ('Resistance Bands', 19.99, '11111111-1111-1111-1111-111111111210', 'FitGear'),
      ('Kettlebell', 49.99, '11111111-1111-1111-1111-111111111210', 'FitGear'),
      ('Treadmill Folding', 599.99, '11111111-1111-1111-1111-111111111210', 'FitGear'),
      ('Stationary Bike', 449.99, '11111111-1111-1111-1111-111111111210', 'FitGear'),
      ('Jump Rope', 14.99, '11111111-1111-1111-1111-111111111210', 'FitGear'),
      ('Medicine Ball', 34.99, '11111111-1111-1111-1111-111111111210', 'FitGear'),
      ('Tent Camping 4-Person', 199.99, '11111111-1111-1111-1111-111111111211', 'OutdoorGear'),
      ('Sleeping Bag', 79.99, '11111111-1111-1111-1111-111111111211', 'OutdoorGear'),
      ('Backpack 65L', 129.99, '11111111-1111-1111-1111-111111111211', 'OutdoorGear'),
      ('Hiking Boots', 149.99, '11111111-1111-1111-1111-111111111211', 'OutdoorGear'),
      ('Bicycle Mountain', 599.99, '11111111-1111-1111-1111-111111111211', 'BikeMax'),
      ('Skateboard Pro', 99.99, '11111111-1111-1111-1111-111111111211', 'SkateGear'),
      ('Football Soccer', 24.99, '11111111-1111-1111-1111-111111111211', 'SportsEquip')
  ) AS t(name, price_amount, category_id, seller_name)
) AS s
ON CONFLICT DO NOTHING;

-- Books products (5 products)
INSERT INTO product.products (
  id, sku, name, price_amount, price_currency, active, category_id, seller_name,
  description, list_price, sale_price, discount_percent, thumbnail_url, color, material,
  average_rating, rating_count, review_count, is_featured, seller_rating, is_seller_verified,
  created_at, updated_at, version
)
SELECT
  ('eeeeeeee-eeee-eeee-eeee-' || lpad(n::text, 12, '0'))::uuid,
  'SKU-BOOK-' || lpad(n::text, 4, '0'),
  name,
  price_amount,
  'USD',
  true,
  category_id::uuid,
  seller_name,
  name || ', sold by ' || seller_name || '.',
  list_price,
  price_amount,
  round((list_price - price_amount) / list_price * 100, 2),
  'https://images.unsplash.com/photo-1507842217343-583f20270319?w=400',
  NULL,
  'Paper',
  CASE WHEN n % 2 = 0 THEN 4.5 END,
  CASE WHEN n % 2 = 0 THEN 75 ELSE 0 END,
  CASE WHEN n % 2 = 0 THEN 55 ELSE 0 END,
  false,
  4.4,
  true,
  now(),
  now(),
  0
FROM (
  SELECT
    t.*,
    row_number() OVER (ORDER BY t.name) AS n,
    round(t.price_amount * 1.1, 2) AS list_price
  FROM (
    VALUES
      ('The Silent Patient', 16.99, '11111111-1111-1111-1111-111111111212', 'BookStore'),
      ('Educated', 18.99, '11111111-1111-1111-1111-111111111213', 'BookStore'),
      ('Atomic Habits', 17.99, '11111111-1111-1111-1111-111111111213', 'BookStore'),
      ('Dune', 15.99, '11111111-1111-1111-1111-111111111212', 'BookStore'),
      ('The Midnight Library', 16.99, '11111111-1111-1111-1111-111111111212', 'BookStore')
  ) AS t(name, price_amount, category_id, seller_name)
) AS s
ON CONFLICT DO NOTHING;
