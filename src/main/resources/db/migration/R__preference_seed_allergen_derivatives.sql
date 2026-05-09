-- Repeatable migration seeding the v1 allergen-to-derivative mapping.
-- Idempotent: re-running with the same content makes no changes; changing content re-applies
-- (Flyway re-runs R__ migrations whenever their checksum changes).
--
-- Keys use lowercase-underscored convention consistent with the LLD's expected
-- ingredientMappingKey format. UUIDs are deterministic constants so re-applying produces
-- equal rows.

INSERT INTO preference_allergen_derivatives (id, allergen, derivative) VALUES
  -- peanut
  ('00000000-0000-0000-0000-000000000001', 'peanut', 'peanut_oil'),
  ('00000000-0000-0000-0000-000000000002', 'peanut', 'peanut_butter'),
  ('00000000-0000-0000-0000-000000000003', 'peanut', 'peanut_flour'),
  ('00000000-0000-0000-0000-000000000004', 'peanut', 'satay_sauce'),
  ('00000000-0000-0000-0000-000000000005', 'peanut', 'groundnut_oil'),
  -- tree_nut
  ('00000000-0000-0000-0000-000000000006', 'tree_nut', 'almond'),
  ('00000000-0000-0000-0000-000000000007', 'tree_nut', 'walnut'),
  ('00000000-0000-0000-0000-000000000008', 'tree_nut', 'cashew'),
  ('00000000-0000-0000-0000-000000000009', 'tree_nut', 'hazelnut'),
  ('00000000-0000-0000-0000-00000000000a', 'tree_nut', 'pecan'),
  ('00000000-0000-0000-0000-00000000000b', 'tree_nut', 'pistachio'),
  ('00000000-0000-0000-0000-00000000000c', 'tree_nut', 'brazil_nut'),
  ('00000000-0000-0000-0000-00000000000d', 'tree_nut', 'macadamia'),
  ('00000000-0000-0000-0000-00000000000e', 'tree_nut', 'almond_extract'),
  ('00000000-0000-0000-0000-00000000000f', 'tree_nut', 'marzipan'),
  -- dairy
  ('00000000-0000-0000-0000-000000000010', 'dairy', 'milk'),
  ('00000000-0000-0000-0000-000000000011', 'dairy', 'cheese'),
  ('00000000-0000-0000-0000-000000000012', 'dairy', 'butter'),
  ('00000000-0000-0000-0000-000000000013', 'dairy', 'whey'),
  ('00000000-0000-0000-0000-000000000014', 'dairy', 'casein'),
  ('00000000-0000-0000-0000-000000000015', 'dairy', 'lactose'),
  ('00000000-0000-0000-0000-000000000016', 'dairy', 'ghee'),
  ('00000000-0000-0000-0000-000000000017', 'dairy', 'cream'),
  ('00000000-0000-0000-0000-000000000018', 'dairy', 'yoghurt'),
  -- egg
  ('00000000-0000-0000-0000-000000000019', 'egg', 'egg_white'),
  ('00000000-0000-0000-0000-00000000001a', 'egg', 'egg_yolk'),
  ('00000000-0000-0000-0000-00000000001b', 'egg', 'mayo'),
  ('00000000-0000-0000-0000-00000000001c', 'egg', 'meringue'),
  ('00000000-0000-0000-0000-00000000001d', 'egg', 'albumen'),
  -- gluten
  ('00000000-0000-0000-0000-00000000001e', 'gluten', 'wheat'),
  ('00000000-0000-0000-0000-00000000001f', 'gluten', 'barley'),
  ('00000000-0000-0000-0000-000000000020', 'gluten', 'rye'),
  ('00000000-0000-0000-0000-000000000021', 'gluten', 'malt'),
  ('00000000-0000-0000-0000-000000000022', 'gluten', 'bulgur'),
  ('00000000-0000-0000-0000-000000000023', 'gluten', 'spelt'),
  ('00000000-0000-0000-0000-000000000024', 'gluten', 'semolina'),
  ('00000000-0000-0000-0000-000000000025', 'gluten', 'couscous'),
  -- soy
  ('00000000-0000-0000-0000-000000000026', 'soy', 'tofu'),
  ('00000000-0000-0000-0000-000000000027', 'soy', 'soy_sauce'),
  ('00000000-0000-0000-0000-000000000028', 'soy', 'edamame'),
  ('00000000-0000-0000-0000-000000000029', 'soy', 'tempeh'),
  ('00000000-0000-0000-0000-00000000002a', 'soy', 'miso'),
  -- shellfish
  ('00000000-0000-0000-0000-00000000002b', 'shellfish', 'shrimp'),
  ('00000000-0000-0000-0000-00000000002c', 'shellfish', 'prawn'),
  ('00000000-0000-0000-0000-00000000002d', 'shellfish', 'crab'),
  ('00000000-0000-0000-0000-00000000002e', 'shellfish', 'lobster'),
  ('00000000-0000-0000-0000-00000000002f', 'shellfish', 'scallop'),
  ('00000000-0000-0000-0000-000000000030', 'shellfish', 'mussel'),
  -- fish
  ('00000000-0000-0000-0000-000000000031', 'fish', 'anchovy'),
  ('00000000-0000-0000-0000-000000000032', 'fish', 'fish_sauce'),
  ('00000000-0000-0000-0000-000000000033', 'fish', 'worcestershire_sauce'),
  -- sesame
  ('00000000-0000-0000-0000-000000000034', 'sesame', 'tahini'),
  ('00000000-0000-0000-0000-000000000035', 'sesame', 'sesame_oil')
ON CONFLICT (allergen, derivative) DO NOTHING;
