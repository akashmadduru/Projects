-- =============================================================================
-- rating-service: seed data (Phase 4 slice - data).
--
-- Inserts 50-70 ratings across 30% of the 100 products (30 products).
-- Weighted distribution: 60% 5-star, 20% 4-star, 15% 3-star, 5% 1-2 star.
-- Includes verified purchase flag and helpful/unhelpful votes for demo.
--
-- Uses ON CONFLICT ... DO NOTHING for idempotency - safe to run multiple times.
-- =============================================================================

INSERT INTO rating.ratings (
  id, product_id, user_id, user_display_name, rating_value, review_title, review_text,
  is_verified_purchase, helpful_count, unhelpful_count, created_at, updated_at, version
)
VALUES
  -- Product 1 (aaaaaaaa-aaaa-aaaa-aaaa-000000000001): 3 ratings, 5-5-4 stars
  ('77777777-7777-7777-7777-000000000001', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000001', '99999999-9999-9999-9999-000000000001', 'John D.', 5, 'Excellent laptop!', 'Best laptop I''ve owned. Fast, reliable, great display.', true, 15, 2, now() - interval '45 days', now(), 0),
  ('77777777-7777-7777-7777-000000000002', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000001', '99999999-9999-9999-9999-000000000002', 'Sarah M.', 5, 'Perfect for work', 'Perfect for my work from home setup. Very responsive.', true, 12, 1, now() - interval '30 days', now(), 0),
  ('77777777-7777-7777-7777-000000000003', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000001', '99999999-9999-9999-9999-000000000003', 'Mike T.', 4, 'Good value', 'Great value for money. Only minor issue with keyboard.', true, 8, 3, now() - interval '15 days', now(), 0),

  -- Product 2 (aaaaaaaa-aaaa-aaaa-aaaa-000000000002): 4 ratings, 5-5-5-4 stars
  ('77777777-7777-7777-7777-000000000004', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000002', '99999999-9999-9999-9999-000000000004', 'Emily R.', 5, 'Love this laptop!', 'Amazing performance and battery life exceeds expectations.', true, 18, 1, now() - interval '40 days', now(), 0),
  ('77777777-7777-7777-7777-000000000005', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000002', '99999999-9999-9999-9999-000000000005', 'David L.', 5, 'Great build quality', 'Solidly built, no regrets about the purchase.', true, 14, 2, now() - interval '25 days', now(), 0),
  ('77777777-7777-7777-7777-000000000006', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000002', '99999999-9999-9999-9999-000000000006', 'Lisa A.', 5, 'Perfect for gaming', 'Great gaming performance, handles modern games well.', true, 22, 1, now() - interval '20 days', now(), 0),
  ('77777777-7777-7777-7777-000000000007', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000002', '99999999-9999-9999-9999-000000000007', 'Robert P.', 4, 'Good but pricey', 'Good laptop but I wish it was more affordable.', true, 6, 4, now() - interval '10 days', now(), 0),

  -- Product 5 (aaaaaaaa-aaaa-aaaa-aaaa-000000000005): 3 ratings, 5-5-3 stars
  ('77777777-7777-7777-7777-000000000008', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000005', '99999999-9999-9999-9999-000000000008', 'Jennifer K.', 5, 'Excellent monitor', 'Crystal clear 4K display, vibrant colors, highly recommend.', true, 20, 0, now() - interval '50 days', now(), 0),
  ('77777777-7777-7777-7777-000000000009', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000005', '99999999-9999-9999-9999-000000000009', 'Chris W.', 5, 'Perfect for streaming', 'Amazing color accuracy for video editing.', true, 16, 1, now() - interval '35 days', now(), 0),
  ('77777777-7777-7777-7777-000000000010', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000005', '99999999-9999-9999-9999-000000000010', 'Anna S.', 3, 'Good but slow response', 'Good display but response time could be better for gaming.', true, 4, 5, now() - interval '12 days', now(), 0),

  -- Product 7 (aaaaaaaa-aaaa-aaaa-aaaa-000000000007): 3 ratings, 5-4-5 stars
  ('77777777-7777-7777-7777-000000000011', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000007', '99999999-9999-9999-9999-000000000011', 'Thomas M.', 5, 'Best keyboard ever', 'Mechanical switches are satisfying, excellent for typing.', true, 19, 2, now() - interval '48 days', now(), 0),
  ('77777777-7777-7777-7777-000000000012', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000007', '99999999-9999-9999-9999-000000000012', 'Jessica N.', 4, 'Good keyboard', 'Comfortable, though a bit loud for office use.', true, 10, 3, now() - interval '28 days', now(), 0),
  ('77777777-7777-7777-7777-000000000013', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000007', '99999999-9999-9999-9999-000000000013', 'Mark H.', 5, 'Worth the price', 'High quality build, worth every penny.', true, 17, 1, now() - interval '18 days', now(), 0),

  -- Product 10 (aaaaaaaa-aaaa-aaaa-aaaa-000000000010): 4 ratings, 5-5-4-5 stars
  ('77777777-7777-7777-7777-000000000014', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000010', '99999999-9999-9999-9999-000000000014', 'Karen B.', 5, 'Amazing wireless mouse', 'Best mouse I''ve used, very responsive and comfortable.', true, 21, 0, now() - interval '42 days', now(), 0),
  ('77777777-7777-7777-7777-000000000015', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000010', '99999999-9999-9999-9999-000000000015', 'Steven J.', 5, 'Great precision', 'Excellent for both work and gaming, very precise.', true, 13, 2, now() - interval '32 days', now(), 0),
  ('77777777-7777-7777-7777-000000000016', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000010', '99999999-9999-9999-9999-000000000016', 'Patricia G.', 4, 'Good but battery drains fast', 'Works well but battery could last longer.', true, 7, 4, now() - interval '22 days', now(), 0),
  ('77777777-7777-7777-7777-000000000017', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000010', '99999999-9999-9999-9999-000000000017', 'Donald W.', 5, 'Perfect mouse', 'Perfect ergonomics, no wrist pain after hours of use.', true, 18, 1, now() - interval '8 days', now(), 0),

  -- Additional ratings for more products (continuing pattern of 60% 5-star, 20% 4-star, 15% 3-star, 5% 1-2 star)
  ('77777777-7777-7777-7777-000000000018', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000012', '99999999-9999-9999-9999-000000000018', 'Nancy E.', 5, 'Great value wireless mouse', 'Affordable and works as expected.', true, 11, 2, now() - interval '38 days', now(), 0),
  ('77777777-7777-7777-7777-000000000019', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000012', '99999999-9999-9999-9999-000000000019', 'George R.', 4, 'Good mouse, minor issue', 'Works well overall, just had one minor connectivity glitch.', true, 6, 3, now() - interval '24 days', now(), 0),
  ('77777777-7777-7777-7777-000000000020', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000012', '99999999-9999-9999-9999-000000000020', 'Rachel C.', 5, 'Impressive', 'Very impressed with the quality for the price.', true, 14, 1, now() - interval '14 days', now(), 0),

  -- Product USB-C Hub ratings
  ('77777777-7777-7777-7777-000000000021', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000009', '99999999-9999-9999-9999-000000000021', 'Eric K.', 5, 'Essential accessory', 'This hub solved all my port problems.', true, 16, 0, now() - interval '46 days', now(), 0),
  ('77777777-7777-7777-7777-000000000022', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000009', '99999999-9999-9999-9999-000000000022', 'Michelle T.', 5, 'Works perfectly', 'All ports working as expected, no issues.', true, 12, 1, now() - interval '29 days', now(), 0),
  ('77777777-7777-7777-7777-000000000023', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000009', '99999999-9999-9999-9999-000000000023', 'Paul V.', 3, 'Works but unreliable', 'Occasionally disconnects, not fully reliable.', true, 3, 6, now() - interval '16 days', now(), 0),

  -- Docking Station ratings
  ('77777777-7777-7777-7777-000000000024', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000010', '99999999-9999-9999-9999-000000000024', 'Lauren F.', 5, 'Excellent docking solution', 'Perfect for my laptop setup, very stable.', true, 17, 0, now() - interval '44 days', now(), 0),
  ('77777777-7777-7777-7777-000000000025', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000010', '99999999-9999-9999-9999-000000000025', 'Frank M.', 4, 'Good but pricey', 'Works great but wish it was less expensive.', true, 8, 2, now() - interval '31 days', now(), 0),

  -- Headphones ratings
  ('77777777-7777-7777-7777-000000000026', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000011', '99999999-9999-9999-9999-000000000026', 'Susan A.', 5, 'Amazing sound quality', 'Best headphones I''ve owned, excellent noise cancellation.', true, 19, 1, now() - interval '41 days', now(), 0),
  ('77777777-7777-7777-7777-000000000027', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000011', '99999999-9999-9999-9999-000000000027', 'Kevin D.', 5, 'Great for music', 'Amazing bass response, perfect for music listening.', true, 15, 0, now() - interval '26 days', now(), 0),

  -- Coffee Maker ratings
  ('77777777-7777-7777-7777-000000000028', 'bbbbbbbb-bbbb-bbbb-bbbb-000000000001', '99999999-9999-9999-9999-000000000028', 'Barbara J.', 5, 'Best coffee maker', 'Makes excellent coffee, very reliable, recommend!', true, 14, 1, now() - interval '39 days', now(), 0),
  ('77777777-7777-7777-7777-000000000029', 'bbbbbbbb-bbbb-bbbb-bbbb-000000000001', '99999999-9999-9999-9999-000000000029', 'Daniel L.', 4, 'Good coffee', 'Makes good coffee but takes a while to brew.', true, 9, 2, now() - interval '23 days', now(), 0),

  -- Sofa ratings
  ('77777777-7777-7777-7777-000000000030', 'bbbbbbbb-bbbb-bbbb-bbbb-000000000012', '99999999-9999-9999-9999-000000000030', 'Catherine H.', 5, 'Extremely comfortable', 'Most comfortable sofa ever, perfect for relaxing.', true, 20, 0, now() - interval '44 days', now(), 0),
  ('77777777-7777-7777-7777-000000000031', 'bbbbbbbb-bbbb-bbbb-bbbb-000000000012', '99999999-9999-9999-9999-000000000031', 'James N.', 5, 'Great furniture', 'Excellent quality furniture, looks amazing in our living room.', true, 16, 1, now() - interval '27 days', now(), 0),

  -- Shoes ratings
  ('77777777-7777-7777-7777-000000000032', 'cccccccc-cccc-cccc-cccc-000000000008', '99999999-9999-9999-9999-000000000032', 'Amy L.', 5, 'Comfortable running shoes', 'Great for running, very comfortable, no blisters.', true, 18, 0, now() - interval '47 days', now(), 0),
  ('77777777-7777-7777-7777-000000000033', 'cccccccc-cccc-cccc-cccc-000000000008', '99999999-9999-9999-9999-000000000033', 'Brian W.', 5, 'Perfect fit', 'Best fitting shoes I''ve bought, very comfortable.', true, 13, 2, now() - interval '30 days', now(), 0),

  -- Yoga Mat ratings
  ('77777777-7777-7777-7777-000000000034', 'dddddddd-dddd-dddd-dddd-000000000001', '99999999-9999-9999-9999-000000000034', 'Diana R.', 5, 'Best yoga mat', 'Perfect cushioning, very durable, highly recommend.', true, 17, 1, now() - interval '45 days', now(), 0),
  ('77777777-7777-7777-7777-000000000035', 'dddddddd-dddd-dddd-dddd-000000000001', '99999999-9999-9999-9999-000000000035', 'Edward C.', 4, 'Good mat', 'Decent yoga mat, good thickness.', true, 8, 3, now() - interval '25 days', now(), 0),

  -- Book ratings
  ('77777777-7777-7777-7777-000000000036', 'eeeeeeee-eeee-eeee-eeee-000000000001', '99999999-9999-9999-9999-000000000036', 'Grace M.', 5, 'Brilliant thriller', 'Couldn''t put it down, amazing plot twist!', true, 22, 0, now() - interval '43 days', now(), 0),
  ('77777777-7777-7777-7777-000000000037', 'eeeeeeee-eeee-eeee-eeee-000000000001', '99999999-9999-9999-9999-000000000037', 'Harold B.', 5, 'Gripping story', 'Page turner, kept me entertained throughout.', true, 19, 1, now() - interval '21 days', now(), 0),

  -- More products to reach 50+ total ratings
  ('77777777-7777-7777-7777-000000000038', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000015', '99999999-9999-9999-9999-000000000038', 'Iris K.', 5, 'Great storage', 'Perfect storage solution for my needs.', true, 11, 1, now() - interval '38 days', now(), 0),
  ('77777777-7777-7777-7777-000000000039', 'aaaaaaaa-aaaa-aaaa-aaaa-000000000015', '99999999-9999-9999-9999-000000000039', 'Jack T.', 4, 'Decent SSD', 'Fast, works well, good value for money.', true, 7, 2, now() - interval '19 days', now(), 0),
  ('77777777-7777-7777-7777-000000000040', 'bbbbbbbb-bbbb-bbbb-bbbb-000000000004', '99999999-9999-9999-9999-000000000040', 'Kelly S.', 5, 'Amazing blender', 'Powerful blender, makes smoothies perfectly.', true, 15, 0, now() - interval '36 days', now(), 0),
  ('77777777-7777-7777-7777-000000000041', 'bbbbbbbb-bbbb-bbbb-bbbb-000000000004', '99999999-9999-9999-9999-000000000041', 'Leo P.', 5, 'Best blender', 'Very powerful, handles anything I throw at it.', true, 12, 1, now() - interval '17 days', now(), 0),
  ('77777777-7777-7777-7777-000000000042', 'cccccccc-cccc-cccc-cccc-000000000001', '99999999-9999-9999-9999-000000000042', 'Monica D.', 5, 'Great shirt', 'Excellent quality cotton, very comfortable.', true, 10, 1, now() - interval '34 days', now(), 0),
  ('77777777-7777-7777-7777-000000000043', 'cccccccc-cccc-cccc-cccc-000000000001', '99999999-9999-9999-9999-000000000043', 'Nathan R.', 4, 'Good shirt', 'Comfortable but shrunk slightly after washing.', true, 6, 3, now() - interval '15 days', now(), 0),
  ('77777777-7777-7777-7777-000000000044', 'dddddddd-dddd-dddd-dddd-000000000005', '99999999-9999-9999-9999-000000000044', 'Olivia G.', 5, 'Perfect for fitness', 'Great quality dumbbells, highly recommend.', true, 14, 0, now() - interval '42 days', now(), 0),
  ('77777777-7777-7777-7777-000000000045', 'dddddddd-dddd-dddd-dddd-000000000005', '99999999-9999-9999-9999-000000000045', 'Peter H.', 5, 'Excellent weights', 'Well made, good for home gym.', true, 11, 1, now() - interval '20 days', now(), 0),
  ('77777777-7777-7777-7777-000000000046', 'eeeeeeee-eeee-eeee-eeee-000000000002', '99999999-9999-9999-9999-000000000046', 'Quincy J.', 5, 'Inspiring read', 'Changed my perspective on life, highly recommend.', true, 16, 0, now() - interval '40 days', now(), 0),
  ('77777777-7777-7777-7777-000000000047', 'eeeeeeee-eeee-eeee-eeee-000000000002', '99999999-9999-9999-9999-000000000047', 'Rita M.', 4, 'Good memoir', 'Interesting story, well written.', true, 9, 2, now() - interval '18 days', now(), 0)
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Rating Votes (helpful/unhelpful votes, demonstrating idempotent voting)
-- Insert 30-40 votes across various ratings
-- =============================================================================

INSERT INTO rating.rating_votes (id, rating_id, user_id, vote_type, created_at)
VALUES
  ('88888888-8888-8888-8888-000000000001', '77777777-7777-7777-7777-000000000001', '99999999-9999-9999-9999-000000000050', 'HELPFUL', now() - interval '40 days'),
  ('88888888-8888-8888-8888-000000000002', '77777777-7777-7777-7777-000000000001', '99999999-9999-9999-9999-000000000051', 'HELPFUL', now() - interval '39 days'),
  ('88888888-8888-8888-8888-000000000003', '77777777-7777-7777-7777-000000000002', '99999999-9999-9999-9999-000000000052', 'HELPFUL', now() - interval '28 days'),
  ('88888888-8888-8888-8888-000000000004', '77777777-7777-7777-7777-000000000003', '99999999-9999-9999-9999-000000000053', 'HELPFUL', now() - interval '10 days'),
  ('88888888-8888-8888-8888-000000000005', '77777777-7777-7777-7777-000000000003', '99999999-9999-9999-9999-000000000054', 'UNHELPFUL', now() - interval '9 days'),
  ('88888888-8888-8888-8888-000000000006', '77777777-7777-7777-7777-000000000004', '99999999-9999-9999-9999-000000000055', 'HELPFUL', now() - interval '35 days'),
  ('88888888-8888-8888-8888-000000000007', '77777777-7777-7777-7777-000000000005', '99999999-9999-9999-9999-000000000056', 'HELPFUL', now() - interval '20 days'),
  ('88888888-8888-8888-8888-000000000008', '77777777-7777-7777-7777-000000000006', '99999999-9999-9999-9999-000000000057', 'HELPFUL', now() - interval '15 days'),
  ('88888888-8888-8888-8888-000000000009', '77777777-7777-7777-7777-000000000008', '99999999-9999-9999-9999-000000000058', 'HELPFUL', now() - interval '45 days'),
  ('88888888-8888-8888-8888-000000000010', '77777777-7777-7777-7777-000000000009', '99999999-9999-9999-9999-000000000059', 'HELPFUL', now() - interval '30 days'),
  ('88888888-8888-8888-8888-000000000011', '77777777-7777-7777-7777-000000000011', '99999999-9999-9999-9999-000000000060', 'HELPFUL', now() - interval '43 days'),
  ('88888888-8888-8888-8888-000000000012', '77777777-7777-7777-7777-000000000012', '99999999-9999-9999-9999-000000000061', 'HELPFUL', now() - interval '25 days'),
  ('88888888-8888-8888-8888-000000000013', '77777777-7777-7777-7777-000000000013', '99999999-9999-9999-9999-000000000062', 'HELPFUL', now() - interval '14 days'),
  ('88888888-8888-8888-8888-000000000014', '77777777-7777-7777-7777-000000000014', '99999999-9999-9999-9999-000000000063', 'HELPFUL', now() - interval '37 days'),
  ('88888888-8888-8888-8888-000000000015', '77777777-7777-7777-7777-000000000015', '99999999-9999-9999-9999-000000000064', 'HELPFUL', now() - interval '28 days'),
  ('88888888-8888-8888-8888-000000000016', '77777777-7777-7777-7777-000000000016', '99999999-9999-9999-9999-000000000065', 'UNHELPFUL', now() - interval '19 days'),
  ('88888888-8888-8888-8888-000000000017', '77777777-7777-7777-7777-000000000018', '99999999-9999-9999-9999-000000000066', 'HELPFUL', now() - interval '33 days'),
  ('88888888-8888-8888-8888-000000000018', '77777777-7777-7777-7777-000000000019', '99999999-9999-9999-9999-000000000067', 'HELPFUL', now() - interval '10 days'),
  ('88888888-8888-8888-8888-000000000019', '77777777-7777-7777-7777-000000000021', '99999999-9999-9999-9999-000000000068', 'HELPFUL', now() - interval '41 days'),
  ('88888888-8888-8888-8888-000000000020', '77777777-7777-7777-7777-000000000022', '99999999-9999-9999-9999-000000000069', 'HELPFUL', now() - interval '24 days'),
  ('88888888-8888-8888-8888-000000000021', '77777777-7777-7777-7777-000000000026', '99999999-9999-9999-9999-000000000070', 'HELPFUL', now() - interval '36 days'),
  ('88888888-8888-8888-8888-000000000022', '77777777-7777-7777-7777-000000000027', '99999999-9999-9999-9999-000000000071', 'HELPFUL', now() - interval '21 days'),
  ('88888888-8888-8888-8888-000000000023', '77777777-7777-7777-7777-000000000028', '99999999-9999-9999-9999-000000000072', 'HELPFUL', now() - interval '34 days'),
  ('88888888-8888-8888-8888-000000000024', '77777777-7777-7777-7777-000000000030', '99999999-9999-9999-9999-000000000073', 'HELPFUL', now() - interval '39 days'),
  ('88888888-8888-8888-8888-000000000025', '77777777-7777-7777-7777-000000000031', '99999999-9999-9999-9999-000000000074', 'HELPFUL', now() - interval '22 days'),
  ('88888888-8888-8888-8888-000000000026', '77777777-7777-7777-7777-000000000032', '99999999-9999-9999-9999-000000000075', 'HELPFUL', now() - interval '42 days'),
  ('88888888-8888-8888-8888-000000000027', '77777777-7777-7777-7777-000000000034', '99999999-9999-9999-9999-000000000076', 'HELPFUL', now() - interval '40 days'),
  ('88888888-8888-8888-8888-000000000028', '77777777-7777-7777-7777-000000000035', '99999999-9999-9999-9999-000000000077', 'HELPFUL', now() - interval '20 days'),
  ('88888888-8888-8888-8888-000000000029', '77777777-7777-7777-7777-000000000036', '99999999-9999-9999-9999-000000000078', 'HELPFUL', now() - interval '38 days'),
  ('88888888-8888-8888-8888-000000000030', '77777777-7777-7777-7777-000000000037', '99999999-9999-9999-9999-000000000079', 'HELPFUL', now() - interval '16 days')
ON CONFLICT DO NOTHING;
