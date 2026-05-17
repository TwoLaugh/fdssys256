-- Discovery — v1 curated source seed (01e).
-- One row per CURATED site + the SEARCH (Google CSE) row. Replaces 01a's empty
-- R__discovery_seed_source_registry.sql (renamed to the brief's filename; LLD line 64 spelled it
-- R__discovery_seed_source_registry.sql — divergence noted, brief wins).
--
-- Idempotent: Flyway re-runs a repeatable on checksum change; each INSERT ... ON CONFLICT
-- (source_key) DO UPDATE refreshes display_name / kind / base_url / user_agent / crawl_config /
-- requests_per_minute / requests_per_day / respect_robots_txt / updated_at, and preserves
-- operator-managed state: enabled, user_disabled, failure_streak, last_*_at, quality_score, notes.
--
-- gen_random_uuid() is in Postgres 16 core (no pgcrypto extension needed). The id is only used on
-- INSERT; ON CONFLICT keeps the existing row's id.
--
-- v1 list per lld/recipe-extraction-pipeline.md line 331: 14 curated + 1 search = 15 sources.
-- LLD divergence (worth user review): below the 25-30 target; expanding is a future seed-only diff.

INSERT INTO discovery_sources (
    id, source_key, display_name, source_type, kind, base_url,
    enabled, user_disabled, requests_per_minute, requests_per_day,
    respect_robots_txt, user_agent, crawl_config, failure_streak,
    created_at, updated_at
) VALUES
    (gen_random_uuid(), 'bbc_good_food', 'BBC Good Food', 'CURATED', 'SITEMAP',
     'https://www.bbcgoodfood.com', true, false, 6, 500, true,
     'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
     '{"sitemapUrl": "https://www.bbcgoodfood.com/sitemap.xml", "pathFilter": "/recipes/"}'::jsonb,
     0, now(), now()),
    (gen_random_uuid(), 'serious_eats', 'Serious Eats', 'CURATED', 'SITEMAP',
     'https://www.seriouseats.com', true, false, 6, 500, true,
     'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
     '{"sitemapUrl": "https://www.seriouseats.com/sitemap.xml", "pathFilter": "/recipes/"}'::jsonb,
     0, now(), now()),
    (gen_random_uuid(), 'allrecipes', 'AllRecipes', 'CURATED', 'SITEMAP',
     'https://www.allrecipes.com', true, false, 6, 500, true,
     'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
     '{"sitemapUrl": "https://www.allrecipes.com/sitemap.xml", "pathFilter": "/recipe/"}'::jsonb,
     0, now(), now()),
    (gen_random_uuid(), 'bon_appetit', 'Bon Appétit', 'CURATED', 'SITEMAP',
     'https://www.bonappetit.com', true, false, 6, 500, true,
     'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
     '{"sitemapUrl": "https://www.bonappetit.com/sitemap.xml", "pathFilter": "/recipe/"}'::jsonb,
     0, now(), now()),
    (gen_random_uuid(), 'nyt_cooking', 'NYT Cooking', 'CURATED', 'SITEMAP',
     'https://cooking.nytimes.com', true, false, 6, 500, true,
     'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
     '{"sitemapUrl": "https://cooking.nytimes.com/sitemap.xml", "pathFilter": "/recipes/"}'::jsonb,
     0, now(), now()),
    (gen_random_uuid(), 'hello_fresh', 'HelloFresh', 'CURATED', 'SITEMAP',
     'https://www.hellofresh.co.uk', true, false, 6, 500, true,
     'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
     '{"sitemapUrl": "https://www.hellofresh.co.uk/sitemap.xml", "pathFilter": "/recipes/"}'::jsonb,
     0, now(), now()),
    (gen_random_uuid(), 'eat_this', 'Eat This, Not That!', 'CURATED', 'RSS_FEED',
     'https://www.eatthis.com', true, false, 6, 500, true,
     'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
     '{"rssUrl": "https://www.eatthis.com/feed/"}'::jsonb,
     0, now(), now()),
    (gen_random_uuid(), 'delicious_au', 'delicious. (AU)', 'CURATED', 'SITEMAP',
     'https://www.delicious.com.au', true, false, 6, 500, true,
     'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
     '{"sitemapUrl": "https://www.delicious.com.au/sitemap.xml", "pathFilter": "/recipes/"}'::jsonb,
     0, now(), now()),
    (gen_random_uuid(), 'food52', 'Food52', 'CURATED', 'SITEMAP',
     'https://food52.com', true, false, 6, 500, true,
     'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
     '{"sitemapUrl": "https://food52.com/sitemap.xml", "pathFilter": "/recipes/"}'::jsonb,
     0, now(), now()),
    (gen_random_uuid(), 'smitten_kitchen', 'Smitten Kitchen', 'CURATED', 'RSS_FEED',
     'https://smittenkitchen.com', true, false, 6, 500, true,
     'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
     '{"rssUrl": "https://smittenkitchen.com/feed/"}'::jsonb,
     0, now(), now()),
    (gen_random_uuid(), 'half_baked_harvest', 'Half Baked Harvest', 'CURATED', 'SITEMAP',
     'https://www.halfbakedharvest.com', true, false, 6, 500, true,
     'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
     '{"sitemapUrl": "https://www.halfbakedharvest.com/sitemap.xml", "pathFilter": "/recipe/"}'::jsonb,
     0, now(), now()),
    (gen_random_uuid(), 'jamie_oliver', 'Jamie Oliver', 'CURATED', 'SITEMAP',
     'https://www.jamieoliver.com', true, false, 6, 500, true,
     'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
     '{"sitemapUrl": "https://www.jamieoliver.com/sitemap.xml", "pathFilter": "/recipes/"}'::jsonb,
     0, now(), now()),
    (gen_random_uuid(), 'deliciously_ella', 'Deliciously Ella', 'CURATED', 'SITEMAP',
     'https://deliciouslyella.com', true, false, 6, 500, true,
     'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
     '{"sitemapUrl": "https://deliciouslyella.com/sitemap.xml", "pathFilter": "/recipes/"}'::jsonb,
     0, now(), now()),
    (gen_random_uuid(), 'hairy_bikers', 'Hairy Bikers', 'CURATED', 'SITEMAP',
     'https://hairybikers.com', true, false, 6, 500, true,
     'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
     '{"sitemapUrl": "https://hairybikers.com/sitemap.xml", "pathFilter": "/recipes/"}'::jsonb,
     0, now(), now()),
    (gen_random_uuid(), 'google_cse', 'Google Custom Search', 'SEARCH', 'SEARCH_API',
     'https://www.googleapis.com/customsearch/v1', true, false, 60, 100, false,
     'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
     '{"searchEngineId": "configured-via-env-var"}'::jsonb,
     0, now(), now())
ON CONFLICT (source_key) DO UPDATE SET
    display_name        = EXCLUDED.display_name,
    kind                = EXCLUDED.kind,
    base_url            = EXCLUDED.base_url,
    requests_per_minute = EXCLUDED.requests_per_minute,
    requests_per_day    = EXCLUDED.requests_per_day,
    respect_robots_txt  = EXCLUDED.respect_robots_txt,
    user_agent          = EXCLUDED.user_agent,
    crawl_config        = EXCLUDED.crawl_config,
    updated_at          = now();

-- Note: nyt_cooking is paywall-aware; v1 is best-effort. Recorded in `notes` only if an operator
-- sets it (the DO UPDATE deliberately preserves operator-managed `notes`).
-- crawl_config.searchEngineId for google_cse is an informational literal placeholder only — the
-- adapter ALWAYS uses GoogleCustomSearchConfig.searchEngineId (env-backed) and ignores this JSONB.
-- NB: do NOT put a Spring/Flyway placeholder token (dollar-brace ENV_VAR brace) anywhere in this
-- file, including comments — Spring Boot Flyway runs placeholder substitution over the whole SQL
-- and aborts boot with "No value provided for placeholder".
