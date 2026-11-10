-- =============================================================================
-- product-service: 100-product seed data (Phase 4 slice - data).
--
-- Inserts 20 categories (5 root + 15 subcategories), 100 products across
-- them, and inventory/ratings for all. Uses ON CONFLICT ... DO NOTHING for
-- idempotency - safe to run multiple times.
--
-- Product data is realistic (names, descriptions, prices, images from
-- Unsplash). Seller info and ratings are sampled to demonstrate
-- denormalization and rich data.
-- =============================================================================

-- =============================================================================
-- Categories (5 root + 15 subcategories = 20 total)
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
-- Products (100 across various categories with realistic data)
-- Sample data structure: electronics, home, fashion, sports, books
-- =============================================================================

-- Electronics products (35 products)
INSERT INTO product.products (
  id, sku, name, price_amount, price_currency, active, category_id, seller_name,
  description, list_price, sale_price, discount_percent, thumbnail_url, color, material,
  average_rating, rating_count, review_count, is_featured, seller_rating, is_seller_verified,
  created_at, updated_at, version
)
SELECT
  'aaaaaaaa-aaaa-aaaa-aaaa-' || to_char((row_number() OVER (ORDER BY ordinality))::integer, '000000000001'),
  'SKU-ELEC-' || lpad((row_number() OVER (ORDER BY ordinality))::text, 4, '0'),
  name,
  price_amount,
  'USD',
  true,
  category_id,
  seller_name,
  description,
  CASE WHEN price_amount > 500 THEN price_amount * 1.2 ELSE price_amount * 1.15 END,
  price_amount,
  CASE WHEN price_amount > 500 THEN 17 ELSE 13 END,
  'https://images.unsplash.com/photo-1517694712202-14dd9538aa97?w=400',
  color,
  'Plastic/Metal',
  CASE WHEN (row_number() OVER (ORDER BY ordinality)) % 3 = 0 THEN 4.5 ELSE NULL END,
  CASE WHEN (row_number() OVER (ORDER BY ordinality)) % 3 = 0 THEN 120 ELSE 0 END,
  CASE WHEN (row_number() OVER (ORDER BY ordinality)) % 3 = 0 THEN 85 ELSE 0 END,
  (row_number() OVER (ORDER BY ordinality)) <= 5,
  4.8,
  true,
  now(),
  now(),
  0
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
ON CONFLICT DO NOTHING;

-- Home & Garden products (25 products)
INSERT INTO product.products (
  id, sku, name, price_amount, price_currency, active, category_id, seller_name,
  description, list_price, sale_price, discount_percent, thumbnail_url, color, material,
  average_rating, rating_count, review_count, is_featured, seller_rating, is_seller_verified,
  created_at, updated_at, version
)
SELECT
  'bbbbbbbb-bbbb-bbbb-bbbb-' || to_char((35 + row_number() OVER (ORDER BY ordinality))::integer, '000000000001'),
  'SKU-HOME-' || lpad((row_number() OVER (ORDER BY ordinality))::text, 4, '0'),
  name,
  price_amount,
  'USD',
  true,
  category_id,
  seller_name,
  description,
  CASE WHEN price_amount > 200 THEN price_amount * 1.2 ELSE price_amount * 1.15 END,
  price_amount,
  CASE WHEN price_amount > 200 THEN 17 ELSE 13 END,
  'https://images.unsplash.com/photo-1556909114-f6e7ad7d3136?w=400',
  color,
  'Stainless Steel/Plastic',
  CASE WHEN (row_number() OVER (ORDER BY ordinality)) % 2 = 0 THEN 4.3 ELSE NULL END,
  CASE WHEN (row_number() OVER (ORDER BY ordinality)) % 2 = 0 THEN 95 ELSE 0 END,
  CASE WHEN (row_number() OVER (ORDER BY ordinality)) % 2 = 0 THEN 68 ELSE 0 END,
  false,
  4.6,
  true,
  now(),
  now(),
  0
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
ON CONFLICT DO NOTHING;

-- Fashion products (20 products)
INSERT INTO product.products (
  id, sku, name, price_amount, price_currency, active, category_id, seller_name,
  description, list_price, sale_price, discount_percent, thumbnail_url, color, material,
  average_rating, rating_count, review_count, is_featured, seller_rating, is_seller_verified,
  created_at, updated_at, version
)
SELECT
  'cccccccc-cccc-cccc-cccc-' || to_char((60 + row_number() OVER (ORDER BY ordinality))::integer, '000000000001'),
  'SKU-FASH-' || lpad((row_number() OVER (ORDER BY ordinality))::text, 4, '0'),
  name,
  price_amount,
  'USD',
  true,
  category_id,
  seller_name,
  description,
  price_amount * 1.3,
  price_amount,
  23,
  'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=400',
  color,
  'Cotton/Polyester',
  CASE WHEN (row_number() OVER (ORDER BY ordinality)) % 4 = 0 THEN 4.4 ELSE NULL END,
  CASE WHEN (row_number() OVER (ORDER BY ordinality)) % 4 = 0 THEN 110 ELSE 0 END,
  CASE WHEN (row_number() OVER (ORDER BY ordinality)) % 4 = 0 THEN 78 ELSE 0 END,
  false,
  4.5,
  true,
  now(),
  now(),
  0
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
ON CONFLICT DO NOTHING;

-- Sports products (15 products)
INSERT INTO product.products (
  id, sku, name, price_amount, price_currency, active, category_id, seller_name,
  description, list_price, sale_price, discount_percent, thumbnail_url, color, material,
  average_rating, rating_count, review_count, is_featured, seller_rating, is_seller_verified,
  created_at, updated_at, version
)
SELECT
  'dddddddd-dddd-dddd-dddd-' || to_char((80 + row_number() OVER (ORDER BY ordinality))::integer, '000000000001'),
  'SKU-SPRT-' || lpad((row_number() OVER (ORDER BY ordinality))::text, 4, '0'),
  name,
  price_amount,
  'USD',
  true,
  category_id,
  seller_name,
  description,
  price_amount * 1.25,
  price_amount,
  20,
  'https://images.unsplash.com/photo-1517836357463-d25ddfcbf042?w=400',
  color,
  'Nylon/Rubber',
  CASE WHEN (row_number() OVER (ORDER BY ordinality)) % 3 = 0 THEN 4.6 ELSE NULL END,
  CASE WHEN (row_number() OVER (ORDER BY ordinality)) % 3 = 0 THEN 100 ELSE 0 END,
  CASE WHEN (row_number() OVER (ORDER BY ordinality)) % 3 = 0 THEN 70 ELSE 0 END,
  false,
  4.7,
  true,
  now(),
  now(),
  0
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
ON CONFLICT DO NOTHING;

-- Books products (5 products)
INSERT INTO product.products (
  id, sku, name, price_amount, price_currency, active, category_id, seller_name,
  description, list_price, sale_price, discount_percent, thumbnail_url, color, material,
  average_rating, rating_count, review_count, is_featured, seller_rating, is_seller_verified,
  created_at, updated_at, version
)
SELECT
  'eeeeeeee-eeee-eeee-eeee-' || to_char((95 + row_number() OVER (ORDER BY ordinality))::integer, '000000000001'),
  'SKU-BOOK-' || lpad((row_number() OVER (ORDER BY ordinality))::text, 4, '0'),
  name,
  price_amount,
  'USD',
  true,
  category_id,
  seller_name,
  description,
  price_amount * 1.1,
  price_amount,
  10,
  'https://images.unsplash.com/photo-1507842217343-583f20270319?w=400',
  color,
  'Paper',
  CASE WHEN (row_number() OVER (ORDER BY ordinality)) % 2 = 0 THEN 4.5 ELSE NULL END,
  CASE WHEN (row_number() OVER (ORDER BY ordinality)) % 2 = 0 THEN 75 ELSE 0 END,
  CASE WHEN (row_number() OVER (ORDER BY ordinality)) % 2 = 0 THEN 55 ELSE 0 END,
  false,
  4.4,
  true,
  now(),
  now(),
  0
FROM (
  VALUES
    ('The Silent Patient', 16.99, '11111111-1111-1111-1111-111111111212', 'BookStore'),
    ('Educated', 18.99, '11111111-1111-1111-1111-111111111213', 'BookStore'),
    ('Atomic Habits', 17.99, '11111111-1111-1111-1111-111111111213', 'BookStore'),
    ('Dune', 15.99, '11111111-1111-1111-1111-111111111212', 'BookStore'),
    ('The Midnight Library', 16.99, '11111111-1111-1111-1111-111111111212', 'BookStore')
) AS t(name, price_amount, category_id, seller_name)
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Inventory (stock levels for all 100 products)
-- =============================================================================

INSERT INTO inventory.stock_levels (
  product_id, quantity, reserved_quantity, status, low_stock_threshold,
  warehouse_code, created_at, updated_at, version
)
SELECT
  id,
  (RANDOM() * 950 + 50)::integer,
  (RANDOM() * 50)::integer,
  CASE
    WHEN (RANDOM() * 950 + 50)::integer <= 20 THEN 'LOW_STOCK'
    WHEN (RANDOM() * 950 + 50)::integer = 0 THEN 'OUT_OF_STOCK'
    ELSE 'IN_STOCK'
  END,
  10,
  'MAIN-WH',
  now(),
  now(),
  0
FROM product.products
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Ratings (sample ratings for 30% of products - ~30 products with 3-5 ratings each)
-- This demonstrates the denormalization flow
-- =============================================================================

INSERT INTO rating.ratings (
  id, product_id, user_id, user_display_name, rating_value, review_title, review_text,
  is_verified_purchase, helpful_count, unhelpful_count, created_at, updated_at, version
)
SELECT
  '77777777-7777-' || lpad((row_number() OVER (ORDER BY RAND()))::text, 14, '0'),
  product_id,
  '99999999-9999-' || lpad((RANDOM() * 1000)::text, 14, '0'),
  'User' || (RANDOM() * 10000)::integer,
  CASE (RANDOM() * 5)::integer
    WHEN 0 THEN 5
    WHEN 1 THEN 5
    WHEN 2 THEN 5
    WHEN 3 THEN 4
    WHEN 4 THEN 3
    ELSE 5
  END,
  'Great product!',
  'This product exceeded my expectations. Highly recommend!',
  true,
  (RANDOM() * 20)::integer,
  (RANDOM() * 5)::integer,
  now() - (RANDOM() * 365 || ' days')::interval,
  now(),
  0
FROM (
  SELECT DISTINCT product_id
  FROM (
    SELECT product_id, ROW_NUMBER() OVER (ORDER BY product_id) as rn
    FROM inventory.stock_levels
  ) sub
  WHERE rn % 3 = 0
) selected_products,
GENERATE_SERIES(1, (RANDOM() * 3 + 2)::integer)
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Verify data loaded (optional - count results)
-- =============================================================================

-- SELECT
--   (SELECT COUNT(*) FROM product.categories) as categories,
--   (SELECT COUNT(*) FROM product.products) as products,
--   (SELECT COUNT(*) FROM inventory.stock_levels) as inventory_rows,
--   (SELECT COUNT(*) FROM rating.ratings) as ratings;
