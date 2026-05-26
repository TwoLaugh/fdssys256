# Ticket: core — 03 Shared `IngredientMappingKeys.normalise()` + Apply at Every Write Boundary

## Summary

Ship a **shared, cross-module `core` utility `IngredientMappingKeys.normalise()`** and apply it at
**every place an `ingredient_mapping_key` is written or looked up**, so "Chicken Breast" and "chicken
breast" resolve to the same key across modules. Per
[`design/technical-architecture.md` §Cross-module references line 238](../../design/technical-architecture.md):

> `ingredient_mapping_key` … **Always lowercase, trimmed. The Nutrition Model provides a
> `normaliseKey()` utility. All modules must use it before storing or looking up keys** — "Chicken
> Breast" and "chicken breast" must resolve to the same entry.

The contract exists, but the normaliser is **trapped inside nutrition** — it's the package-private
`IntakeKeyNormaliser.normalise` in `nutrition.domain.service.internal`
([`IntakeKeyNormaliser.java`](../../src/main/java/com/example/mealprep/nutrition/domain/service/internal/IntakeKeyNormaliser.java)),
whose own Javadoc says: *"Lives in nutrition for now; the helper graduates to a shared util when the
provisions module also needs it."* It is **not cross-module accessible**, and other modules are
already writing keys **RAW**:

- **provisions `GroceryImportProcessor`** writes `line.ingredientMappingKey()` RAW (no normalise) —
  [`GroceryImportProcessor.java` lines 122/130/195/211/252](../../src/main/java/com/example/mealprep/provisions/domain/service/internal/GroceryImportProcessor.java).
- **recipe `RecipeServiceImpl.populateIngredientsFromRequests`** writes `dto.ingredientMappingKey()`
  RAW — [`RecipeServiceImpl.java` line 1197](../../src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeServiceImpl.java).

This ticket **graduates the normaliser to `core`** and applies it at the write boundaries, so the
grocery module (which matches keys across plan demand, inventory, staples, price history, and
supplier products) gets correct cross-module key matching. **This unblocks correct cross-module key
matching that the entire grocery ticket set (`tickets/grocery/01a..01g`) depends on.**

**Same bug-family as the spawned discovery→recipe import mapping-key fix** — that fix addressed
keys keyed RAW on the discovery→recipe import path; this is the same root cause (no shared
normaliser, raw writes) across the provisions-import and recipe-persistence boundaries.

## Decision baked in — a SEPARATE standalone `core` ticket (NOT folded into grocery)

**Product-owner decision (settled):** mapping-key normalisation is its own cross-cutting ticket, NOT
folded into any grocery ticket. It touches three modules (core, provisions, recipe) and is a
prerequisite for grocery's correctness — keeping it standalone means it can land + be reviewed
independently, and the grocery tickets simply depend on it.

## Behavioural spec

### `IngredientMappingKeys.normalise()` — new `core` utility

New **public final** utility in `com.example.mealprep.core.ingredient` (or wherever `core`'s shared
types live — verify; `core.types` holds the shared enums per
[`tickets/core/01` invariant 15](01-decision-log.md)). Algorithm **identical to the existing
nutrition normaliser** so no key already written by nutrition changes meaning:

```java
package com.example.mealprep.core.ingredient;

public final class IngredientMappingKeys {
  private IngredientMappingKeys() {}

  /** Lowercase + trim + collapse internal whitespace to a single space. {@code null} → {@code null}. */
  public static String normalise(String raw) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) return "";
    return trimmed.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }
}
```

**One subtle divergence to confirm:** the nutrition `IntakeKeyNormaliser.normalise` uses
`trimmed.toLowerCase()` (default locale) — this ticket pins `Locale.ROOT` to avoid the Turkish-i
class of locale bugs on deterministic keys. **Worth user review** — switching to `Locale.ROOT` is a
behaviour change for any already-stored key containing an `I`/`i` written under a non-ROOT locale
(unlikely on a UK-English deployment, but a real edge). If the owner prefers byte-for-byte parity
with the existing nutrition util, drop the `Locale.ROOT` and match `toLowerCase()` exactly. The
recommendation is `Locale.ROOT`.

### Nutrition: delegate, don't duplicate

`nutrition.IntakeKeyNormaliser` is kept (it's a `@Component` other nutrition code injects) but its
body **delegates to `core.IngredientMappingKeys.normalise`** so there is a single source of truth.
**Worth user review** — alternative is to delete `IntakeKeyNormaliser` and inject the static util
everywhere in nutrition; rejected to keep the nutrition change minimal (the `@Component` is widely
injected). The delegation is a one-line body change.

### Apply at every write boundary

1. **provisions `GroceryImportProcessor`** — normalise `line.ingredientMappingKey()` before: the
   supplier-product upsert (line ~195), the inventory merge lookup (line ~130), the inventory-row
   create (line ~211/252). The merge-lookup MUST normalise so a raw "Chicken Breast" line merges with
   a normalised "chicken breast" existing row. (This also retroactively closes the provisions-01h
   "keyed RAW" gap the grocery prompt flagged.)
2. **recipe `RecipeServiceImpl.populateIngredientsFromRequests`** — normalise
   `dto.ingredientMappingKey()` before `.ingredientMappingKey(...)` (line ~1197). Check the other
   recipe write sites that set a mapping key (substitution overlay at lines ~1454/1457/1951/1954 set
   `originalMappingKey`/`substituteMappingKey` — normalise those too if they're persisted as match
   keys; **verify** each is a stored key vs a transient compare value).
3. **future grocery writes** — every grocery write boundary (the `ShoppingListCalculator`
   aggregation in 01b, `PriceObservationWriter` in 01c, the order-line writes in 01e, the
   `BasketDraftAssembler`) calls `IngredientMappingKeys.normalise` — the grocery tickets reference
   THIS util by name; this ticket makes it exist.

**Reads/lookups too** — wherever a key is used as a query parameter against a stored key, normalise
the lookup value (the technical-architecture contract is "before storing OR looking up"). Audit the
read sites in the three modules; the highest-risk one is the provisions inventory **merge lookup**
(a raw lookup against normalised rows silently fails to merge → duplicate inventory rows).

### Backfill of already-stored RAW keys — OUT of scope, but FLAGGED

Existing rows written RAW before this ticket are NOT retro-normalised by this ticket. **Worth user
review (HIGH)** — a follow-up data-migration could normalise existing `ingredient_mapping_key` values
in `provision_inventory`, `recipe_ingredients`, `provision_supplier_products`,
`nutrition_ingredient_mapping`, etc. This ticket fixes the **write path forward**; the **backfill is
a separate optional data-migration ticket** (low risk on a pre-launch dataset; higher value post-launch).
The grocery module's cross-module matching works on newly-written keys regardless; stale RAW keys
from before this ticket would mismatch until backfilled.

## Edge-case checklist

- [ ] `normalise("Chicken Breast")` == `normalise("chicken breast")` == `normalise("  chicken   breast ")` == `"chicken breast"`
- [ ] `normalise(null)` → `null`; `normalise("")` → `""`; `normalise("   ")` → `""`
- [ ] `normalise` is idempotent: `normalise(normalise(x)) == normalise(x)`
- [ ] Output algorithm matches the existing nutrition `IntakeKeyNormaliser` (no nutrition key changes meaning) — except the documented `Locale.ROOT` choice
- [ ] nutrition `IntakeKeyNormaliser.normalise` delegates to `core.IngredientMappingKeys.normalise`
- [ ] provisions `GroceryImportProcessor`: supplier-product, inventory-merge-lookup, and inventory-create all normalise
- [ ] provisions merge: a raw "Chicken Breast" line merges with a normalised "chicken breast" existing row (no duplicate inventory row)
- [ ] recipe `populateIngredientsFromRequests` normalises the persisted key
- [ ] recipe substitution mapping keys normalised IFF persisted as match keys (verify each site)
- [ ] No `core` → other-module dependency introduced (core stays leaf; the util is pure, no Spring)
- [ ] `core` boundary / ArchUnit rules still pass (the util has no dependencies)

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/core/ingredient/IngredientMappingKeys.java
NEW   src/test/java/com/example/mealprep/core/ingredient/IngredientMappingKeysTest.java

MOD   src/main/java/com/example/mealprep/nutrition/domain/service/internal/IntakeKeyNormaliser.java        (delegate to core util)
MOD   src/main/java/com/example/mealprep/provisions/domain/service/internal/GroceryImportProcessor.java    (normalise at supplier-upsert + merge-lookup + inventory-create)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeServiceImpl.java             (normalise persisted mapping keys; verify substitution sites)

MOD   src/test/java/.../nutrition/...                                                                      (assert delegation; no key-meaning change)
MOD   src/test/java/.../provisions/GroceryImportProcessorTest.java                                         (mixed-case line merges with normalised row)
MOD   src/test/java/.../recipe/...                                                                         (persisted key is normalised)
```

**Does NOT touch:** any migration (no schema change); the backfill of existing RAW rows (separate
optional ticket); `config/GlobalExceptionHandler.java`; any module's exception handler. **Module
boundary note:** `core` must stay a leaf module — the util is a pure static method with zero
dependencies (no Spring, no other module). Verify `ModuleBoundaryTest` / `CoreBoundaryTest` still
passes (no new outbound edge from core).

## Dependencies

- **Hard:** `core` (merged) — the target module for the shared util.
- **Touches (already merged):** nutrition (`IntakeKeyNormaliser`), provisions (`GroceryImportProcessor`,
  provisions-01h), recipe (`RecipeServiceImpl`).
- **Prerequisite for:** the entire grocery ticket set (`tickets/grocery/01a..01g`) — grocery
  references `IngredientMappingKeys.normalise()` by name. **Land core-03 before grocery-01b** (01a
  only TODO-marks the boundaries since it writes nothing).
- **Sibling / same-family:** the spawned discovery→recipe import mapping-key fix (same root cause —
  coordinate so the two don't both edit the same recipe write site differently).

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green; all edge cases ticked
- [ ] `IngredientMappingKeysTest` covers null/empty/whitespace/case/idempotence
- [ ] nutrition delegation verified; no existing nutrition key changes meaning (modulo the `Locale.ROOT` decision)
- [ ] provisions mixed-case merge no longer creates duplicate inventory rows
- [ ] recipe persisted keys normalised
- [ ] `core` stays a leaf module (no new outbound dependency); boundary tests pass
- [ ] Backfill-of-existing-RAW-keys flagged to the owner as a separate optional data-migration

Squash-merge with: `refactor(core): shared IngredientMappingKeys.normalise() applied at every key write boundary`

## What's NOT in scope

- **Backfill** of already-stored RAW `ingredient_mapping_key` values → separate optional data-migration ticket.
- The grocery module's own use of the util → the grocery tickets (this ticket only makes the util exist + fixes the existing provisions/recipe boundaries).
- The discovery→recipe import mapping-key fix → its own spawned ticket (same family; coordinate).
- Any schema / column-width change to `ingredient_mapping_key` columns.
