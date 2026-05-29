# AUDIT discovery/discovery-1 — ingredientMappingKey dropped end-to-end — ingredient-bearing discovered recipes cannot ingest

| field | value |
|---|---|
| Severity | **CRITICAL** |
| Module | discovery |
| Dimension | MISSING_CAPABILITY |
| Verification | verified-confirmed |
| **Triage (edit me)** | **FIX** |
| Source | design/audits/2026-05-29-v1-backend-conformance-audit.md |

## Where

src/main/java/com/example/mealprep/recipe/spi/ImportedRecipeData.java:34-40; DiscoveryJobRunner.java:707-717; source/internal/JsonLdRecipeExtractor.java:135; recipe/.../RecipeServiceImpl.java:1037; db/migration/V20260601800400__recipe_create_ingredients.sql:8

## What's wrong

LLD line 291 defines ParsedRecipe.ParsedIngredient with an ingredientMappingKey, and the HLD (recipe-system.md lines 117, 476-477) requires every ingredient to carry ingredient_mapping_key for USDA/nutrition mapping. But: (a) JsonLdRecipeExtractor.mapToParsed builds `new ParsedIngredient(line, null, null, null, null, false)` — mapping key always null; (b) the SPI carrier ImportedRecipeData.ImportedIngredient has NO mapping-key field at all; (c) DiscoveryJobRunner.toImportedRecipeData maps only displayName/quantity/unit/preparation/optional, silently discarding ParsedIngredient.ingredientMappingKey; (d) RecipeServiceImpl.populateImportedIngredients hardcodes `.ingredientMappingKey(null)`; (e) recipe_ingredients.ingredient_mapping_key is `varchar(160) NOT NULL`. Net: any discovered recipe with >=1 ingredient throws a NOT-NULL violation on saveImportedRecipe, which the runner catches as EXTRACTION_FAILED (DiscoveryJobRunner.java:679-698) and never ingests. The E2eSeedDiscoverySource is deliberately ingredient-free for exactly this reason (its own comment at lines 122-128 documents the defect). Real curated/CSE recipes therefore never reach the catalogue, and even if persisted the null key defeats nutrition mapping.

## Recommendation

Add ingredientMappingKey to ImportedRecipeData.ImportedIngredient; have JsonLdRecipeExtractor populate it (at minimum via core IngredientMappingKeys.normalise(displayName) as a deterministic v1 fallback); carry it through DiscoveryJobRunner.toImportedRecipeData; and stop hardcoding null in RecipeServiceImpl.populateImportedIngredients. Add a DiscoveryRunnerIT that ingests an ingredient-bearing recipe end-to-end to lock this.
