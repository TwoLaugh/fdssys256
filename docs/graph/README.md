# Graph integration — G05 seed + G06 ingest + G07 recompute (engine side)

Implements tickets `tickets/engine-integration/G05-ingredientmapping-seed.md`,
`G06-batch-ingest.md` and `G07-nutrition-recompute.md` from the spike repo
(`culinary-graph-spike`, branch `culinary-graph-spike`), plus the engine-side nutrient-key
contract test G04 deferred (`GraphNutrientKeyContractTest`).

## The seed artifact (G05)

`src/test/resources/graph-seed/ingredient_mapping_seed.json` — schema `graph-mapping-seed/1`:
**1,179 rows (1,113 USDA / 66 MANUAL)**, one per canonical spike ingredient, engine-document
shaped (typed macros + canonical-key `micros` incl. the `saturated_fat_g` bridge; never a
`vitamins` map), consumed-basis per-100g, stamped with the generating spike commit (`28599f0`)
and corpus fingerprint (`c81a2e87dacf339f`, n=1689, dedupe=39).

Provenance: generated read-only from the spike checkout by
`docs/graph/spike-side/export_mapping_seed.py`:

```
python docs/graph/spike-side/export_mapping_seed.py \
    --spike-root <culinary-graph-spike checkout> \
    --out src/test/resources/graph-seed/ingredient_mapping_seed.json
```

`docs/graph/spike-side/{export_mapping_seed.py,test_export_mapping_seed.py}` are DESTINED FOR
the spike repo's `corpus_expansion/` (the ticket's Part 1); they live here because the spike
checkout was read-only input to this implementation session. When copied there, the default
relative paths apply and the artifact lands at the ticket's canonical location
`export/ingredient_mapping_seed.json`. The engine keeps its committed copy under
`src/test/resources/graph-seed/` because the G05 IT proves the REAL artifact end-to-end.

## Runbook — ordering is the whole point

The seed MUST run **before** any lazy population (USDA/OFF pipeline) can touch spike-canon
keys on the target database, i.e. before the first graph-batch ingest and before any dev/user
flow resolving canon ingredient names:

1. `POST /api/v1/nutrition/admin/ingredient-mappings/seed` with the artifact as the body
   (admin-gated, `mealprep.admin.user-ids`). Expect
   `{"inserted": 1179, ..., "status": "OK"}`; re-runs are idempotent (`skippedIdentical`).
2. **`status: FAILED` (HTTP 409) is a HARD STOP** — a collision means something else wrote a
   spike-canon key first (exactly the poisoning the seed exists to prevent). Existing rows are
   never overwritten; `search_term` is immutable, so adjudication = delete + re-seed under
   human review.
3. Only then: `POST /api/v1/discovery/admin/graph-batches/ingest`
   `{"batchPath": "<abs path to export/batch-.../>"}` with
   `mealprep.graph.import.enabled=true`. G06's pre-flight re-asserts every batch key resolves
   in `nutrition_ingredient_mapping` and aborts (zero writes) otherwise — the detective
   enforcement of the same ordering.

Withdrawal of an ingested batch (flag flipped off): archive each recipe id recorded under the
batch `jobId` in `recipe_imports` / the batch's `ingest_report.json` — see G11's procedure.

## Status notes

- G07 landed: the import loop invokes `GraphImportNutritionRecalc` per dish — the ENGINE
  recomputes per-serving nutrition from the artifact's exact-grams lines × the seeded mappings
  and persists via the `RecipeNutritionWriter` SPI (spike numbers are never persisted, standing
  law #2). Honesty gates fail the dish (counted rejected, row stays PENDING) on any
  non-`calculated` status or zero-kcal result. `ingest_report.json` entries now carry
  `nutritionStatus: CALCULATED`.
- The landed spike exporter stamps a deterministic per-batch `jobId` (UUIDv5) into every
  payload; the ingest runner uses it as-is (one jobId per batch preserved across re-runs) and
  aborts if payloads disagree.
