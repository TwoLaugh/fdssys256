# Ticket: preference — 01b Hard-Constraint Filter Service

## Summary

Implement `HardConstraintFilterService` plus its dependency, the `preference_allergen_derivatives` lookup table. The filter is the hot-path read every food-output module calls (recipe, planner, discovery, adaptation pipeline) to validate that proposed ingredients satisfy a user's hard constraints. Loads stored allergies/intolerances/medical-diets from `preference_hard_constraints` (delivered in 01a), expands allergies to their known derivatives, and returns a structured pass/violation result.

Defers to follow-up: `@ValidDietaryIdentity` custom validator (deferred to 01c).

**Trial purpose**: this is the first ticket built around the upgraded agent prompt with verbatim shape snippets inline (per `templates/agent-prompt-template.md` in the [ai-workflow repo](https://github.com/TwoLaugh/ai-workflow)). Measuring whether info-upfront cuts agent runtime materially vs preference-01a (~25 min, 1 fix iteration).

## Behavioural spec

### Allergen derivatives

1. New table `preference_allergen_derivatives` (id uuid PK, allergen varchar(64), derivative varchar(128), UNIQUE(allergen, derivative), index on `allergen`).
2. Repeatable migration `R__preference_seed_allergen_derivatives.sql` seeds ~50 well-known mappings: `peanut → peanut_oil, peanut_butter, peanut_flour`; `tree_nut → almond, walnut, cashew, hazelnut, ...`; `dairy → milk, cheese, butter, whey, casein, lactose, ghee, ...`; `egg → egg_white, egg_yolk, mayo, meringue, ...`; `gluten → wheat, barley, rye, malt, bulgur, ...`; etc. Use lowercase-underscored keys consistent with the LLD's expected `ingredientMappingKey` format.
3. Seed migration is idempotent: uses `INSERT ... ON CONFLICT (allergen, derivative) DO NOTHING`.

### `HardConstraintFilterService` interface

4. Public interface in `preference.domain.service.HardConstraintFilterService` with four methods (per [`lld/preference.md` §HardConstraintFilterService](../../lld/preference.md)):

```java
public interface HardConstraintFilterService {
  FilterResult check(UUID userId, List<String> ingredientMappingKeys);
  FilterResult checkRecipe(UUID userId, UUID recipeId, List<String> recipeIngredientMappingKeys);
  List<UUID> filterRecipes(UUID userId, Map<UUID, List<String>> recipesIngredientKeys);
  FilterResult checkForHousehold(List<UUID> userIds, List<String> ingredientMappingKeys);
}
```

5. `FilterResult` DTO record: `(boolean passes, List<Violation> violations)`. `Violation` record: `(UUID userId, String ingredientKey, ViolationKind kind, String constraintValue)`. `ViolationKind` enum: `ALLERGY, INTOLERANCE, DIETARY_BASE, DIETARY_EXCEPTION_MISMATCH, MEDICAL_DIET, AGE_RESTRICTION`.

### Filter logic

6. `check(userId, keys)`: load aggregate via `findWithChildrenByUserId` (already in 01a's repo). For each `ingredientKey` in `keys`:
   - Match against `allergies` directly OR against the derivatives expansion (look up `allergen_derivatives` for each user allergy, build the expanded set once per call).
   - Match against `hardIntolerances.substance` directly.
   - Match against `medicalDiets` direct + any documented restrictions (e.g., `low_sodium` rejects `salt`, `soy_sauce`; encode as a static map for v1).
   - Match against `dietaryIdentityBase` (e.g., `vegan` rejects all animal-derived keys; encode the base→excluded-keys map as a static lookup). Apply `dietaryIdentityExceptions` to widen — if the user is vegetarian-with-fish-on-weekends, `fish` keys pass.
   - Match against `ageRestrictions` (e.g., `no_whole_nuts` rejects keys containing `whole_nut_*`).
7. Build `Violation` rows for every match; the response includes ALL violations, not just the first. Filter UI surfaces the full list.
8. `passes = violations.isEmpty()`.
9. Aggregate with no row (user has never set hard constraints) → return `FilterResult(passes=true, violations=[])`. Don't throw.

### Recipe and household variants

10. `checkRecipe(userId, recipeId, keys)`: identical to `check` but each `Violation` carries the `recipeId` for upstream callers (passed back as a separate field on the violation; or stash in a context map — designer's choice; document inline).
11. `filterRecipes(userId, Map<UUID, keys>)`: returns the subset of `recipeId`s where `check(userId, keys) == passes`. Used by the planner's beam search at scale (1000s of recipes per user per planning run); MUST NOT issue 1000 individual aggregate loads. Load the user's aggregate ONCE, expand allergies ONCE, then iterate recipes in-memory.
12. `checkForHousehold(userIds, keys)`: union of all users' constraints. Returns `FilterResult` with `Violation.userId` populated to identify which household member each violation belongs to. Used for shared meals.

### Performance

13. `filterRecipes` is the hot path. The aggregate load + derivatives expansion together must be O(1) per call regardless of recipe count; the per-recipe loop is O(keys × constraints) of in-memory matching. Test with 1000 recipes × 20 keys; assert wall-clock < 200ms on a warm JVM (rough sanity, not a CI gate).

### Cross-cutting

14. `HardConstraintFilterServiceImpl` lives in `preference.domain.service.internal`. Package-private constructor visibility is fine; module facade `PreferenceModule.java` re-exports the public interface (already exists from 01a — append).
15. The filter is `@Transactional(readOnly = true)`. Single read transaction per `check` / `checkRecipe`; `filterRecipes` reads the aggregate once and iterates inside the same tx.
16. No new exceptions or events needed — the filter is read-only and never throws on missing data.

### ArchUnit

17. No new boundary rules. Filter lives in `preference.domain.service.internal`, exposed via the existing public `HardConstraintFilterService` interface in `preference.domain.service`. Fits the existing module-boundary conventions.

## Files this ticket touches

```
src/main/resources/db/migration/V20260601300100__preference_create_allergen_derivatives.sql   new
src/main/resources/db/migration/R__preference_seed_allergen_derivatives.sql                   new
src/main/java/com/example/mealprep/preference/PreferenceModule.java                           modified (re-export filter service)
src/main/java/com/example/mealprep/preference/domain/entity/AllergenDerivative.java           new
src/main/java/com/example/mealprep/preference/domain/repository/AllergenDerivativeRepository.java   new
src/main/java/com/example/mealprep/preference/api/dto/FilterResult.java                       new (record)
src/main/java/com/example/mealprep/preference/api/dto/Violation.java                          new (record)
src/main/java/com/example/mealprep/preference/domain/entity/ViolationKind.java                new (enum)
src/main/java/com/example/mealprep/preference/domain/service/HardConstraintFilterService.java new (interface)
src/main/java/com/example/mealprep/preference/domain/service/internal/HardConstraintFilterServiceImpl.java   new
src/main/java/com/example/mealprep/preference/domain/service/internal/DietaryBaseExclusions.java   new (static lookup tables)
src/main/java/com/example/mealprep/preference/domain/service/internal/MedicalDietRules.java   new (static lookup tables)
src/test/java/com/example/mealprep/preference/HardConstraintFilterServiceImplTest.java        new (unit)
src/test/java/com/example/mealprep/preference/HardConstraintFilterServiceIT.java              new (Testcontainers; verifies derivatives lookup + filterRecipes single-load)
```

## Edge-case checklist

- [ ] User with no aggregate → `passes=true, violations=[]` (not 404, not throw)
- [ ] Allergy direct match → `Violation(kind=ALLERGY)`
- [ ] Allergy via derivative (peanut allergy + peanut_oil ingredient) → `Violation(kind=ALLERGY)` with `constraintValue` showing the original allergen
- [ ] Intolerance match (e.g., lactose-intolerant + milk ingredient) → `Violation(kind=INTOLERANCE)`
- [ ] Medical diet match (low_sodium + salt) → `Violation(kind=MEDICAL_DIET)`
- [ ] Dietary base (vegan + chicken) → `Violation(kind=DIETARY_BASE)`
- [ ] Dietary base + exception (vegetarian + fish, exception allows fish) → no violation for fish
- [ ] Age restriction match → `Violation(kind=AGE_RESTRICTION)`
- [ ] Multiple violations on one ingredient → all returned, not just first
- [ ] `filterRecipes` with 1000 recipes performs ONE aggregate load (verified via Mockito spy on the repo)
- [ ] `checkForHousehold` with 3 users union violations correctly attributed to each user
- [ ] Empty `ingredientKeys` list → `passes=true, violations=[]`
- [ ] Repeatable migration is idempotent (re-running with same seed file = no new rows)
- [ ] `mvn verify` passes locally — ALL existing tests + new tests green

## Dependencies

- **Hard dependency**: `preference-01a` (already merged) — uses the `HardConstraints` aggregate + `findWithChildrenByUserId`.
- **No new cross-module deps**.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on agent's worktree
- [ ] CI green on the PR (build + spotless + OpenAPI lint; Pitest only if `mutation` label applied)
- [ ] Mutation score ≥70% on `HardConstraintFilterServiceImpl` (run pre-merge by labelling PR `mutation`, OR accept that it'll surface on the next push to main)
- [ ] Edge-case checklist all ticked

Squash-merge with: `feat(preference): 01b — hard-constraint filter service + allergen derivatives`
