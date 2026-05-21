# Ticket: recipe — 02b Multi-Dimensional Rating (per-version, 4 dimensions, closes the learning loop)

## Summary

Implement the **multi-dimensional rating** feature per [`design/recipe-system.md` lines 156-181](../../design/recipe-system.md), gap-audit C-A-026, C-IMP-009, C-IMP-010. Per roadmap §B3.

Four rating dimensions, each 0-100:
- **taste** — did it taste good (core signal)
- **effort_worth_it** — was prep/cook time worth the result (signals method simplification)
- **portion_fit** — were portions right for this user's appetite
- **repeat_value** — would they want this again soon

Plus an aggregate (weighted blend) for compact displays. **Per-version** — attached to the planned slot's pinned `RecipeVersion`, not the recipe itself. Aggregate is computed across all ratings for that version.

**UX**: the default rating path asks only for `taste` (one-tap); the user opens "rate in detail" for the other three. Both paths supported by the same endpoint.

**Closes the loop**: posting a rating fires `FeedbackProcessedEvent` so the rating contributes to taste profile learning. This is **C-IMP-009** (per-version rating data flow) — the connection between rating capture and the feedback module's classifier.

Closes: **C-A-026** (Multi-dimensional recipe rating), **C-IMP-009** (Per-version rating data flow), **C-IMP-010** (Recipe rating UI default-tap + detailed mode).

## Behavioural spec

### Database schema

1. Migration `V20260615220000__recipe_create_ratings.sql`:
   ```sql
   CREATE TABLE recipe_ratings (
       id                       uuid PRIMARY KEY,
       recipe_id                uuid NOT NULL,                  -- denormalised; FK is on version_id
       version_id               uuid NOT NULL REFERENCES recipe_versions(id) ON DELETE CASCADE,
       user_id                  uuid NOT NULL,                  -- the rater; no FK per the cross-module rule
       household_id             uuid,                            -- nullable; populated when rating a household meal
       slot_id                  uuid,                            -- nullable; the planned meal slot rated (if any)
       taste                    integer,                         -- 0-100 nullable (taste required for any rating)
       effort_worth_it          integer,                         -- 0-100 nullable
       portion_fit              integer,                         -- 0-100 nullable
       repeat_value             integer,                         -- 0-100 nullable
       aggregate                integer NOT NULL,                -- computed, 0-100
       notes                    varchar(1000),                   -- optional free-text
       trace_id                 uuid,
       optimistic_version       bigint NOT NULL DEFAULT 0,
       created_at               timestamptz NOT NULL,
       updated_at               timestamptz NOT NULL,
       CONSTRAINT chk_taste_range CHECK (taste IS NULL OR (taste >= 0 AND taste <= 100)),
       CONSTRAINT chk_effort_range CHECK (effort_worth_it IS NULL OR (effort_worth_it >= 0 AND effort_worth_it <= 100)),
       CONSTRAINT chk_portion_range CHECK (portion_fit IS NULL OR (portion_fit >= 0 AND portion_fit <= 100)),
       CONSTRAINT chk_repeat_range CHECK (repeat_value IS NULL OR (repeat_value >= 0 AND repeat_value <= 100)),
       CONSTRAINT chk_aggregate_range CHECK (aggregate >= 0 AND aggregate <= 100),
       CONSTRAINT chk_taste_required CHECK (taste IS NOT NULL)   -- taste must be supplied
   );
   CREATE INDEX idx_recipe_ratings_version ON recipe_ratings (version_id, created_at DESC);
   CREATE INDEX idx_recipe_ratings_recipe ON recipe_ratings (recipe_id, created_at DESC);
   CREATE INDEX idx_recipe_ratings_user ON recipe_ratings (user_id, created_at DESC);
   CREATE INDEX idx_recipe_ratings_slot ON recipe_ratings (slot_id) WHERE slot_id IS NOT NULL;
   ```
2. Migration `V20260615220100__recipe_create_rating_aggregate_view.sql` — a materialised-view-or-computed-table strategy for fast aggregate queries per version:
   ```sql
   -- v1: skip the materialised view. Aggregates computed live via SUM/COUNT queries on
   -- recipe_ratings — workload is light (rating frequency per recipe is low; query is indexed).
   -- If perf bites, add a materialised view in a follow-up.
   --
   -- This migration is intentionally empty (comment-only) to reserve the timestamp slot
   -- and document the deferred decision.
   ```
   **Worth user review**: ship as a no-op placeholder, or skip the migration entirely. **Decision**: skip the empty migration — adding it creates noise. Track the materialised-view decision in the ticket's `What's NOT in scope`.

### Entity

3. **`RecipeRating`** at `recipe.domain.entity.RecipeRating`. Lombok per style guide. Fields:
   - `id (UUID, @Id, application-set)`
   - `recipeId (UUID, NOT NULL, updatable=false)` — denormalised for index lookups
   - `versionId (UUID, NOT NULL, updatable=false)` — FK to `recipe_versions`
   - `userId (UUID, NOT NULL, updatable=false)`
   - `householdId (UUID, nullable, updatable=false)`
   - `slotId (UUID, nullable, updatable=false)`
   - `taste (Integer, NOT NULL)` — 0-100; the chk constraint enforces non-null at DB
   - `effortWorthIt (Integer, nullable)`
   - `portionFit (Integer, nullable)`
   - `repeatValue (Integer, nullable)`
   - `aggregate (int, NOT NULL)` — computed at write time
   - `notes (String, length=1000, nullable)`
   - `traceId (UUID, nullable)`
   - `optimisticVersion (long, @Version)`
   - `createdAt (@CreatedDate)`, `updatedAt (@LastModifiedDate)`
4. **Note** the `chk_taste_required` constraint at the DB level mirrors the `@NotNull` annotation on the entity field — defense-in-depth.

### Aggregate computation

5. **Aggregate formula** (per `design/recipe-system.md:178`):
   ```
   aggregate = round(
     (taste * 0.40) +
     (coalesce(effort_worth_it, taste) * 0.25) +
     (coalesce(portion_fit, taste) * 0.15) +
     (coalesce(repeat_value, taste) * 0.20)
   )
   ```
   - When a non-taste dimension is null, it defaults to the `taste` value (so the aggregate isn't pulled down by missing data).
   - **Computed server-side at write time**. The client never supplies the aggregate. Recomputed on update (PUT).
   - **Worth user review**: the weights (40/25/15/20) are not specified in the HLD. This proposal weights taste most heavily; the others are equal-ish. Document the choice in the PR.

### Repository (package-private)

6. **`RecipeRatingRepository`** at `recipe.domain.repository`:
   ```java
   interface RecipeRatingRepository extends JpaRepository<RecipeRating, UUID> {
     Optional<RecipeRating> findByVersionIdAndUserId(UUID versionId, UUID userId);
     Page<RecipeRating> findByVersionIdOrderByCreatedAtDesc(UUID versionId, Pageable p);
     Page<RecipeRating> findByRecipeIdOrderByCreatedAtDesc(UUID recipeId, Pageable p);
     Page<RecipeRating> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable p);

     // Aggregates for "show me the recipe's average ratings".
     @Query("""
         select new com.example.mealprep.recipe.api.dto.RecipeRatingSummaryDto(
             r.versionId,
             avg(r.taste), avg(r.effortWorthIt), avg(r.portionFit),
             avg(r.repeatValue), avg(r.aggregate), count(r)
         )
         from RecipeRating r
         where r.versionId = :versionId
         """)
     RecipeRatingSummaryDto aggregateByVersion(@Param("versionId") UUID versionId);

     @Query("""
         select new com.example.mealprep.recipe.api.dto.RecipeRatingSummaryDto(
             null,
             avg(r.taste), avg(r.effortWorthIt), avg(r.portionFit),
             avg(r.repeatValue), avg(r.aggregate), count(r)
         )
         from RecipeRating r
         where r.recipeId = :recipeId
         """)
     RecipeRatingSummaryDto aggregateByRecipe(@Param("recipeId") UUID recipeId);
   }
   ```

### DTOs

7. **`RecipeRatingDto`**:
   ```java
   public record RecipeRatingDto(
       UUID id, UUID recipeId, UUID versionId, UUID userId,
       UUID householdId, UUID slotId,
       int taste, Integer effortWorthIt, Integer portionFit, Integer repeatValue,
       int aggregate, String notes,
       UUID traceId, long optimisticVersion,
       Instant createdAt, Instant updatedAt) {}
   ```
8. **`CreateRatingRequest`**:
   ```java
   public record CreateRatingRequest(
       @NotNull UUID versionId,
       @Nullable UUID slotId,             // optional — null if rating without a planned slot context
       @Min(0) @Max(100) @NotNull Integer taste,
       @Min(0) @Max(100) @Nullable Integer effortWorthIt,
       @Min(0) @Max(100) @Nullable Integer portionFit,
       @Min(0) @Max(100) @Nullable Integer repeatValue,
       @Size(max=1000) @Nullable String notes) {}
   ```
9. **`UpdateRatingRequest`** — same shape as create plus `@Min(0) long expectedVersion`. Used to revise a rating (some users may want to refine after a re-cook).
10. **`RecipeRatingSummaryDto`**:
    ```java
    public record RecipeRatingSummaryDto(
        @Nullable UUID versionId,           // null for recipe-level aggregates
        Double avgTaste, Double avgEffortWorthIt, Double avgPortionFit,
        Double avgRepeatValue, Double avgAggregate,
        long count) {}
    ```

### Mapper

11. **`RecipeRatingMapper`** — MapStruct.

### Service interfaces

12. **`RecipeRatingQueryService`** at `recipe.domain.service`:
    ```java
    public interface RecipeRatingQueryService {
      Optional<RecipeRatingDto> getById(UUID ratingId);
      Optional<RecipeRatingDto> getByVersionAndUser(UUID versionId, UUID userId);
      Page<RecipeRatingDto> listByVersion(UUID versionId, Pageable p);
      Page<RecipeRatingDto> listByRecipe(UUID recipeId, Pageable p);
      Page<RecipeRatingDto> listByUser(UUID userId, Pageable p);
      RecipeRatingSummaryDto getSummaryByVersion(UUID versionId);
      RecipeRatingSummaryDto getSummaryByRecipe(UUID recipeId);
    }
    ```
13. **`RecipeRatingUpdateService`**:
    ```java
    public interface RecipeRatingUpdateService {
      RecipeRatingDto create(UUID userId, CreateRatingRequest request);
      RecipeRatingDto update(UUID userId, UUID ratingId, UpdateRatingRequest request);
      void delete(UUID userId, UUID ratingId);
    }
    ```
14. **`RecipeRatingServiceImpl`** at `recipe.domain.service.internal`. Single impl. `@Transactional` on writes.

### REST controller

15. **`RecipeRatingController`** at `recipe.api.controller`. `@RequestMapping("/api/v1/recipes/{recipeId}/ratings")`. Endpoints:

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| POST | `/api/v1/recipes/{recipeId}/ratings` | `CreateRatingRequest` | `RecipeRatingDto` | 201 / 400 / 404 |
| GET | `/api/v1/recipes/{recipeId}/ratings` | query `?versionId=&page=&size=` | `Page<RecipeRatingDto>` | 200 |
| GET | `/api/v1/recipes/{recipeId}/ratings/mine` | query `?versionId=` | `RecipeRatingDto` | 200 / 404 |
| GET | `/api/v1/recipes/{recipeId}/ratings/summary` | query `?versionId=` | `RecipeRatingSummaryDto` | 200 |
| PUT | `/api/v1/recipes/{recipeId}/ratings/{ratingId}` | `UpdateRatingRequest` | `RecipeRatingDto` | 200 / 400 / 404 / 409 |
| DELETE | `/api/v1/recipes/{recipeId}/ratings/{ratingId}` | — | — | 204 / 404 |

16. **Authentication required**. `userId` via `CurrentUserResolver`.
17. **Validation**: `recipeId` path matches the rating's recipe_id (else 400). The `versionId` in the request body must belong to the path's `recipeId` (else 400).
18. **Idempotency / unique-per-user-per-version**: a user can have only one rating per version. POST when one exists → 409 with `Conflict: rating already exists for this version, use PUT`. **Alternative**: POST updates the existing row (upsert). **Decision**: 409 is the explicit-contract path; the client uses PUT to update. **Worth user review.**

### Closes-the-loop: `FeedbackProcessedEvent`

19. **On every POST or PUT**, the service fires a **`RecipeRatingFiredEvent`** that the feedback module can listen to. The event carries the structured rating data; the feedback module classifies it as feedback (e.g. low `effort_worth_it` with high `taste` → "user feedback: method too involved"). **Per C-IMP-009**.
20. **Event shape**:
    ```java
    public record RecipeRatingFiredEvent(
        UUID ratingId, UUID userId,
        UUID recipeId, UUID versionId, UUID slotId,
        int taste, Integer effortWorthIt, Integer portionFit, Integer repeatValue, int aggregate,
        String notes,
        UUID traceId, Instant occurredAt) implements OriginAwareEvent {
      @Override public Origin origin() { return Origin.USER; }
      @Override public String originTrace() { return null; }
    }
    ```
21. **Listener wiring**: this ticket does NOT add a listener inside the feedback module — the feedback module's listener is a follow-up. For 01b's "closes the loop" goal, the **event publication is the deliverable**; the feedback module will consume it in its own follow-up ticket once the consumption pattern stabilises. **Alternative**: directly construct a `SubmitFeedbackRequest` from the rating + call `FeedbackUpdateService.submitFeedback(...)` from the rating service. This is the more-loop-closing approach but couples the recipe module to feedback's HTTP-shape DTO. **Decision**: ship the event-only path now; the feedback-side listener is a one-line follow-up.

### Cross-cutting

22. New exceptions:
    - `RecipeRatingNotFoundException` (404)
    - `DuplicateRecipeRatingException` (409) — POST on an existing user-version rating
    - `RecipeRatingValidationException` (400) — recipeId/versionId mismatch
23. `RecipeExceptionHandler` (existing) gains the three.
24. **ArchUnit**: standard module-boundary rules cover the new repo.

### Events

25. **Published**: `RecipeRatingFiredEvent` (per §20). `AFTER_COMMIT`.
26. **Consumed**: none.

## Database

```
NEW   src/main/resources/db/migration/V20260615220000__recipe_create_ratings.sql
```

(The aggregate view migration is intentionally skipped per §2.)

## OpenAPI updates

Add **6 paths** + **4 schemas** to `paths/recipe.yaml` and `schemas/recipe.yaml`:
- 6 endpoints in §15.
- Schemas: `RecipeRatingDto`, `CreateRatingRequest`, `UpdateRatingRequest`, `RecipeRatingSummaryDto`.

## Edge-case checklist

- [ ] Migration applies cleanly; FK to `recipe_versions` enforced via cascade-delete.
- [ ] **Happy path POST**: `taste=85, effort_worth_it=70, portion_fit=90, repeat_value=75` → aggregate = `round(85*0.40 + 70*0.25 + 90*0.15 + 75*0.20)` = `round(34 + 17.5 + 13.5 + 15)` = `80`. Row persisted, 201 returned with `aggregate=80`.
- [ ] **One-tap POST** (only `taste`): `taste=80` → non-taste dimensions null → aggregate = `round(80*0.40 + 80*0.25 + 80*0.15 + 80*0.20)` = `80` (coalesce-to-taste means a single-value rating yields its taste as aggregate).
- [ ] **Boundary**: `taste=0` → aggregate=0; `taste=100` → aggregate=100.
- [ ] **Out-of-range**: `taste=101` → 400 (Jakarta `@Max(100)`).
- [ ] **Missing taste**: `taste=null` → 400 (Jakarta `@NotNull`).
- [ ] **Duplicate**: POST on an existing `(userId, versionId)` → 409 `DuplicateRecipeRatingException`.
- [ ] **PUT updates**: 200 with recomputed aggregate; `optimistic_version` bumped.
- [ ] **PUT stale version**: `expectedVersion=0` on a rating at version 3 → 409.
- [ ] **DELETE**: 204; row gone.
- [ ] **DELETE non-owner**: user A tries to delete user B's rating → 404 (no row found in the userId-scoped query).
- [ ] **Path-vs-body recipeId mismatch**: `POST /recipes/{X}/ratings` with body `versionId` belonging to recipe Y → 400 `RecipeRatingValidationException`.
- [ ] **Version doesn't exist**: `versionId` references non-existent version → 404 `RecipeNotFoundException` (or new `RecipeVersionNotFoundException` if defined).
- [ ] **Recipe doesn't exist**: 404.
- [ ] **Summary by version**: aggregates across all ratings for a version (avg of taste, avg aggregate, count).
- [ ] **Summary by recipe**: aggregates across all ratings on all versions of a recipe (the design's "show me how this recipe's doing" query).
- [ ] **Pagination**: default 20, max 100 per `tickets/infra/01b`.
- [ ] **`RecipeRatingFiredEvent`** fires `AFTER_COMMIT` on POST AND on PUT. Not on DELETE.
- [ ] **`OriginAwareEvent`** correctly returns `Origin.USER` (per `tickets/core/02b`).
- [ ] **Trace ID**: `X-Trace-Id` header propagates to the row's `trace_id` and the event's `traceId`.
- [ ] **Notes XSS**: stored as-is; output-side rendering is the frontend's concern.
- [ ] **Aggregate rounding**: half-up (`Math.round`); 79.5 → 80.
- [ ] **Cascade**: deleting a `RecipeVersion` cascades to its ratings (FK).
- [ ] **`RecipeDto` integration**: the existing `RecipeDto` (from `recipe-01a`) does NOT auto-include rating data — clients call the summary endpoint explicitly. **Worth user review** if the user wants `aggregate_rating` as an inline field on `RecipeDto`.
- [ ] OpenAPI contract test passes for all 6 endpoints.
- [ ] ArchUnit: no cross-module repo import.

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260615220000__recipe_create_ratings.sql

NEW   src/main/java/com/example/mealprep/recipe/domain/entity/RecipeRating.java
NEW   src/main/java/com/example/mealprep/recipe/domain/repository/RecipeRatingRepository.java
NEW   src/main/java/com/example/mealprep/recipe/domain/service/RecipeRatingQueryService.java
NEW   src/main/java/com/example/mealprep/recipe/domain/service/RecipeRatingUpdateService.java
NEW   src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeRatingServiceImpl.java

NEW   src/main/java/com/example/mealprep/recipe/api/controller/RecipeRatingController.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/RecipeRatingDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/CreateRatingRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/UpdateRatingRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/RecipeRatingSummaryDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/mapper/RecipeRatingMapper.java

NEW   src/main/java/com/example/mealprep/recipe/event/RecipeRatingFiredEvent.java

NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeRatingNotFoundException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/DuplicateRecipeRatingException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeRatingValidationException.java

MOD   src/main/java/com/example/mealprep/recipe/RecipeModule.java
MOD   src/main/java/com/example/mealprep/recipe/api/RecipeExceptionHandler.java               (3 new mappings)
MOD   src/main/resources/openapi/paths/recipe.yaml                                            (6 paths)
MOD   src/main/resources/openapi/schemas/recipe.yaml                                          (4 schemas)

NEW   src/test/java/com/example/mealprep/recipe/RecipeRatingServiceImplTest.java
NEW   src/test/java/com/example/mealprep/recipe/RecipeRatingAggregateTest.java                (weighted-formula unit test)
NEW   src/test/java/com/example/mealprep/recipe/RecipeRatingControllerIT.java
NEW   src/test/java/com/example/mealprep/recipe/RecipeRatingFiredEventIT.java                 (verifies AFTER_COMMIT)
NEW   src/test/java/com/example/mealprep/recipe/testdata/RecipeRatingTestData.java
```

Total: ~18 new files + 4 mods. Estimated agent runtime 3-4 hours.

## Dependencies

- **Hard dependency**: `recipe-01a` (merged) — `Recipe`, `RecipeVersion`, `RecipeModule`.
- **Hard dependency**: `tickets/core/02b-origin-tracking-foundation.md` — `OriginAwareEvent` interface.
- **Hard dependency**: `auth-01a` (merged) — auth.
- **Soft dependency**: `feedback` module exists (already merged per audit). A future listener in `feedback` consumes `RecipeRatingFiredEvent`.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green
- [ ] All edge-case items above ticked
- [ ] PR description includes the weighted formula + an example calculation
- [ ] Manual smoke: register user → create recipe → plan slot → POST rating → GET summary → see aggregate

## What's NOT in scope

- **Aggregate materialised view** — deferred until perf shows live aggregation is slow.
- **Feedback listener** consuming `RecipeRatingFiredEvent` — follow-up.
- **Embedding `aggregate_rating` in `RecipeDto`** — keep summary endpoint separate.
- **Per-household rating** (multiple users in a household each rate the same meal) — supported by the schema (`household_id`); UI surfaces are out of scope.
- **Rating history / time-series view** — out of scope; the DELETE-then-POST + the existing list endpoint suffice.
- **Adaptation-pipeline integration** (the rating drives adaptation per `design/recipe-system.md`) — owned by the adaptation pipeline; this ticket publishes the event that pipeline listeners can consume.

Squash-merge with: `feat(recipe): 02b — multi-dimensional rating (4 dimensions, per-version, fires feedback event) (Tier B B3)`
