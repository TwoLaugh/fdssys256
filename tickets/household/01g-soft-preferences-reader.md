# Ticket: household + preference — 01g Real `SoftPreferencesReader` (household-merge soft-pref read)

> **CRITICAL SCOPE BOUNDARY — READ FIRST.** This ticket wires the household merge to the **non-vector soft-preference maps only**: the plain `ingredientLikes` / `cuisineLikes` like-score maps, the `avoidList`, and the lightweight lifestyle window/novelty/batch flags that the merge already consumes. **The full taste-vector / embedding merge is explicitly OUT OF SCOPE** — it belongs to the deferred pgvector embedding vertical (audit **E5**, [`lld/preference.md:144`](../../lld/preference.md) "vector populated async"). Do NOT pull `TasteProfile.tasteVector` / `TasteVectorStatus` / `TasteProfileDocument`'s embedding fields into the household-side bundle. See §What's NOT in scope. **This is the most ambiguous / lowest-priority of the deferred-gap tickets** — its upstream partially straddles E5, and the audit (C6 caveat) flagged it as a candidate to defer entirely.

## Summary

Provide a **real `SoftPreferencesReader` `@Component`** (on the preference side) to replace the empty-list Noop at [`src/main/java/com/example/mealprep/household/spi/internal/NoopSoftPreferencesReaderConfiguration.java:38-46`](../../src/main/java/com/example/mealprep/household/spi/internal/NoopSoftPreferencesReaderConfiguration.java). The household merge path is fully wired: `HouseholdServiceImpl.mergeSoftPreferencesForSlot` ([`HouseholdServiceImpl.java:830-856`](../../src/main/java/com/example/mealprep/household/domain/service/internal/HouseholdServiceImpl.java)) and `mergeSoftPreferencesForUsers` ([`HouseholdServiceImpl.java:858-881`](../../src/main/java/com/example/mealprep/household/domain/service/internal/HouseholdServiceImpl.java)) call `resolveSoftPreferencesReader().getSoftPreferencesByUserIds(resolved)` ([`HouseholdServiceImpl.java:854, 879`](../../src/main/java/com/example/mealprep/household/domain/service/internal/HouseholdServiceImpl.java)), then hand the bundles to `SoftPreferenceMerger.merge(...)` ([`SoftPreferenceMerger.java:44-69`](../../src/main/java/com/example/mealprep/household/domain/service/internal/SoftPreferenceMerger.java)). Today the only `SoftPreferencesReader` bean is the Noop, which returns `List.of()` ([`NoopSoftPreferencesReaderConfiguration.java:40-45`](../../src/main/java/com/example/mealprep/household/spi/internal/NoopSoftPreferencesReaderConfiguration.java)) — so every household merge produces an empty result. This ticket makes the merge read real per-user soft preferences.

Per [`lld/preference.md:547-548`](../../lld/preference.md) (`PreferenceQueryService.getSoftPreferences` / `getSoftPreferencesByUserIds` — designed but **not yet built**) and [`lld/preference.md:621, Flow 5`](../../lld/preference.md) (the `soft-bundle` read surface), and the household merge rules at [`lld/household.md`](../../lld/household.md) (mean-weighted taste, set-union avoid, most-restrictive lifestyle — implemented in `SoftPreferenceMerger`).

Ships:
- **A thin preference query** `getSoftPreferencesByUserIds(List<UUID>)` (+ `getSoftPreferences(UUID)`) on `PreferenceQueryService`, projecting each user's taste profile + lifestyle config down to the **non-vector** bundle shape.
- **A real `SoftPreferencesReader` `@Component`** (preference side) adapting the preference query output into the household-owned `SoftPreferenceBundleDto`.

Closes: **C6** — the household-merge soft-pref read. The SPI + DTO already exist (household owns the stubs — see §Contradiction note).

**Dependency / ordering**: standalone but **soft-blocked** by the absence of any preference soft-bundle read surface — this ticket *builds* the thin one. Order LAST among the deferred-gap tickets (lowest priority). `preference-01c` (taste profile entity) + `preference-01d` (lifestyle config) merged — they own the source data this query reads.

## Contradiction note (vs the audit)

The audit (C6) states the preference module "ships **no** `getSoftPreferences`/`SoftPreferenceBundleDto` surface." Two corrections from reading the code:
- **`SoftPreferenceBundleDto` DOES already exist** — but in **`household.api.dto`** ([`SoftPreferenceBundleDto.java`](../../src/main/java/com/example/mealprep/household/api/dto/SoftPreferenceBundleDto.java)), a deliberately lightweight household-owned stub (its javadoc: *"Lightweight household-owned stub for 01e; preference-01c owns the canonical record"*). Likewise the household-owned `TasteProfileDocument` ([`household/api/dto/TasteProfileDocument.java`](../../src/main/java/com/example/mealprep/household/api/dto/TasteProfileDocument.java) — `ingredientLikes`/`cuisineLikes`/`avoidList` only) and `LifestyleConfigDocument` ([`household/api/dto/LifestyleConfigDocument.java`](../../src/main/java/com/example/mealprep/household/api/dto/LifestyleConfigDocument.java)).
- **The SPI `SoftPreferencesReader` + its method `getSoftPreferencesByUserIds` ALSO already exist** ([`SoftPreferencesReader.java:27`](../../src/main/java/com/example/mealprep/household/spi/SoftPreferencesReader.java)) — so there is **no SPI authoring** in this ticket, contrary to "needs a thin preference query + DTO on the preference side." What is genuinely missing is (a) the **preference-side query** that produces bundles and (b) the **real `@Component` impl** of the SPI. The DTO already lives household-side; this ticket reuses it (no new DTO unless the SPI is relocated — see §6).

This is worth the user's reconciliation: the gap is narrower than C6 implies (the contract types exist), but the *data-producing* preference query and the SPI impl are real work.

## Behavioural spec

### The SPI contract (fixed)

[`SoftPreferencesReader.java:17-28`](../../src/main/java/com/example/mealprep/household/spi/SoftPreferencesReader.java):

```java
List<SoftPreferenceBundleDto> getSoftPreferencesByUserIds(List<UUID> userIds);
// MUST return one bundle per input user, in input order; MAY return null-fielded bundles for
// users with no soft prefs; MUST NOT return fewer bundles than userIds (callers index by position).
```

[`SoftPreferenceBundleDto.java:11-12`](../../src/main/java/com/example/mealprep/household/api/dto/SoftPreferenceBundleDto.java): `(UUID userId, TasteProfileDocument tasteProfile, LifestyleConfigDocument lifestyleConfig)` — both nullable.

The merger ([`SoftPreferenceMerger.java`](../../src/main/java/com/example/mealprep/household/domain/service/internal/SoftPreferenceMerger.java)) consumes **only**: `tasteProfile.ingredientLikes()`, `tasteProfile.cuisineLikes()` (weighted-mean, [`SoftPreferenceMerger.java:73-123`](../../src/main/java/com/example/mealprep/household/domain/service/internal/SoftPreferenceMerger.java)), `tasteProfile.avoidList()` (set-union, [`SoftPreferenceMerger.java:80-87`](../../src/main/java/com/example/mealprep/household/domain/service/internal/SoftPreferenceMerger.java)), and the four lifestyle fields (most-restrictive, [`SoftPreferenceMerger.java:127-167`](../../src/main/java/com/example/mealprep/household/domain/service/internal/SoftPreferenceMerger.java)). **It reads no vector/embedding field** — confirming the scope boundary at the consumer.

### The thin preference query

1. Add to `PreferenceQueryService` ([`PreferenceQueryService.java`](../../src/main/java/com/example/mealprep/preference/domain/service/PreferenceQueryService.java) — currently only `getHardConstraints` + audit-log):
   ```java
   /** One soft-pref bundle per input user, in input order (callers index by position). Returns a
    *  null-fielded bundle for a user with no taste profile / lifestyle config. NON-VECTOR only —
    *  the taste vector / embedding is intentionally excluded (deferred embedding vertical). */
   List<SoftPreferenceBundleDto> getSoftPreferencesByUserIds(List<UUID> userIds);
   Optional<SoftPreferenceBundleDto> getSoftPreferences(UUID userId);
   ```
   matching the LLD's designed signatures at [`lld/preference.md:547-548`](../../lld/preference.md).
2. **DTO ownership decision (worth user review)**: the bundle/document types live household-side today. Options:
   - **(a) Reuse the household DTOs** — preference imports `household.api.dto.{SoftPreferenceBundleDto, TasteProfileDocument, LifestyleConfigDocument}`. **Cost**: preference depends on household's `api.dto` package (a slightly odd direction — household is "downstream" of preference conceptually). **Benefit**: zero new types, the SPI works unchanged.
   - **(b) Relocate the canonical records to preference** (per the household javadoc's "preference-01c owns the canonical record") and have household import them, OR move the SPI to `core/` (the [`SoftPreferencesReader` javadoc](../../src/main/java/com/example/mealprep/household/spi/SoftPreferencesReader.java) calls this out as a "worth user review" relocation — *"the user/parent may prefer it relocated to core/ (cross-module integration contract) or to preference"*). **Cost**: mechanical rename + import update across household + the SPI impl + tests.
   - **Recommendation: (a) for this ticket** — reuse the household DTOs to keep the change small and the C6 gap closed; **flag the relocation (b) as a separate refactor** so this ticket doesn't balloon. The SPI impl bridges any naming.
3. **Impl on `PreferenceServiceImpl`** (the single preference service impl): for each userId, load the user's `TasteProfile` (entity at [`preference/domain/entity/TasteProfile.java`](../../src/main/java/com/example/mealprep/preference/domain/entity/TasteProfile.java), its `TasteProfileDocument` at [`preference/domain/document/TasteProfileDocument.java`](../../src/main/java/com/example/mealprep/preference/domain/document/TasteProfileDocument.java)) and `LifestyleConfig`, then **project down** to the lightweight household shape:
   - **`ingredientLikes` / `cuisineLikes`**: derive `Map<String, BigDecimal>` like-scores in `[-1, 1]` from the preference taste profile's ingredient/cuisine preference collections (e.g. favourites → positive, disliked → negative; map the preference module's evidence/score model to a single like-score per key). **This projection is the substantive work** — define it deterministically and document the mapping.
   - **`avoidList`**: `List<String>` of `ingredientMappingKey`s the user avoids (NOT allergens — those are hard constraints, per the [`household TasteProfileDocument` javadoc](../../src/main/java/com/example/mealprep/household/api/dto/TasteProfileDocument.java)). Source from the taste profile's disliked/avoid collections.
   - **lifestyle** → `LifestyleConfigDocument(mealTimingWindowStart, mealTimingWindowEnd, noveltyTolerancePercent, batchCookingPreferred)` from `preference-01d`'s lifestyle config.
   - **A user with no taste profile / lifestyle config** → a bundle with `null` `tasteProfile` and/or `null` `lifestyleConfig` (the merger handles nulls cleanly — [`SoftPreferenceMerger.java:50-59, 98-100, 135-138`](../../src/main/java/com/example/mealprep/household/domain/service/internal/SoftPreferenceMerger.java)). **Never** return fewer bundles than userIds (SPI contract).
   - `@Transactional(readOnly = true)`. Batch-load to avoid N+1 (one query per collection across all userIds, or an `@EntityGraph` — the household merge passes the full eater set).
4. **VECTOR EXCLUSION (enforce in code + test)**: the preference `TasteProfile` carries a `tasteVector` + `TasteVectorStatus` ([`preference/domain/entity/TasteVectorStatus.java`](../../src/main/java/com/example/mealprep/preference/domain/entity/TasteVectorStatus.java)). The projection MUST NOT read or surface these. The household `TasteProfileDocument` has no field for them, so the type system already prevents leakage — but add an explicit test asserting the bundle is built without touching the vector (and that a user with `tasteVectorStatus = PENDING`/unbuilt still produces a full like-map bundle — the merge does not wait on embeddings).

### The real SPI impl

5. New `@Component PreferenceSoftPreferencesReader` in the **preference module** (`preference.spi.internal`) implementing `com.example.mealprep.household.spi.SoftPreferencesReader`. As a plain `@Component`, it out-ranks the Noop `@Bean @ConditionalOnMissingBean(SoftPreferencesReader.class)` ([`NoopSoftPreferencesReaderConfiguration.java:31-35`](../../src/main/java/com/example/mealprep/household/spi/internal/NoopSoftPreferencesReaderConfiguration.java)) so the Noop steps aside (the exact pattern the Noop's javadoc anticipates: *"Wired only when no other SoftPreferencesReader bean is present (e.g. before preference-01c lands)"*). Keep the Noop in place for household-only test slices. The impl simply delegates to `preferenceQueryService.getSoftPreferencesByUserIds(userIds)` — a thin adapter (if §2(a) is taken, it is nearly pass-through).
6. **SPI relocation caveat (worth user review)**: the [`SoftPreferencesReader` javadoc](../../src/main/java/com/example/mealprep/household/spi/SoftPreferencesReader.java) flags that the SPI may be better placed in `core/` or `preference`. **Keep it in household for this ticket**; relocation is a separate mechanical refactor (don't bundle it — it touches the household consumer + the Noop + tests).

### Cross-cutting

7. **No new REST surface in this ticket** — `getSoftPreferencesByUserIds` is the cross-module read consumed in-process by the household merge. **Note**: the LLD designs a `GET /api/v1/preferences/soft-bundle` endpoint ([`lld/preference.md:621`](../../lld/preference.md)) returning `SoftPreferenceBundleDto` — **that REST endpoint is OUT OF SCOPE here** (it is a separate preference deliverable; this ticket only ships the in-process cross-module read the household merge needs). Flag it as a sibling if the frontend needs the single-user soft-bundle endpoint.
8. **ArchUnit**: the new `@Component` lives in `preference.spi.internal` and implements `household.spi.SoftPreferencesReader` — confirm `PreferenceBoundaryTest` allows the `household.spi` import (and, if §2(a), the `household.api.dto` import). This is the same SPI direction as the other cross-module reverters/targets in the sibling tickets.
9. **`ObjectProvider` resolution**: `HouseholdServiceImpl` resolves via `softPreferencesReaderProvider.getObject()` ([`HouseholdServiceImpl.java:891-893`](../../src/main/java/com/example/mealprep/household/domain/service/internal/HouseholdServiceImpl.java)) — with one real `@Component` present it returns the real reader; no change to household.

### Events

10. **Published**: none (read-only). **Consumed**: none.

## Database

```
(none — no schema changes. Reads existing preference taste-profile + lifestyle-config tables from preference-01c/01d.)
```

## OpenAPI updates

**No OpenAPI changes.** `getSoftPreferencesByUserIds` is an internal cross-module read. (The designed `GET /soft-bundle` endpoint is out of scope — see §7.)

## Edge-case checklist

- [ ] **Real reader wins**: with the preference module on the classpath, `HouseholdServiceImpl`'s `ObjectProvider.getObject()` resolves the real `PreferenceSoftPreferencesReader`, NOT the Noop (IT asserts the bean class).
- [ ] **Noop still present off-classpath**: a household-only test slice (no preference) still gets the Noop → empty bundles (unchanged; the Noop is not deleted).
- [ ] **Single user with prefs**: a user with ingredient likes + lifestyle config → a bundle with populated `ingredientLikes`/`cuisineLikes`/`avoidList` + lifestyle fields; the merge output reflects them (mirror [`HouseholdMergeWithFakeReaderIT`](../../src/test/java/com/example/mealprep/household/HouseholdMergeWithFakeReaderIT.java) asserting `mergedTasteProfile.ingredientLikes.<key>`).
- [ ] **Multi-user mean-weighted**: two eaters with different `onion` like-scores + different priorities → `SoftPreferenceMerger` weighted-mean is correct (the merger is already tested; this verifies the *real* bundles feed it).
- [ ] **Avoid-list union**: two eaters' avoid lists union into the merged `avoidList`.
- [ ] **Most-restrictive lifestyle**: latest start / earliest end / min novelty / all-batch computed across real bundles.
- [ ] **User with no taste profile**: returns a `null`-`tasteProfile` bundle in the right position (NOT omitted); the merge treats it as no contribution; no NPE.
- [ ] **User with no lifestyle config**: `null`-`lifestyleConfig` bundle; merge degrades cleanly.
- [ ] **Bundle count invariant**: `getSoftPreferencesByUserIds(userIds).size() == userIds.size()`, input order preserved (SPI contract).
- [ ] **VECTOR EXCLUDED**: a user with a built `tasteVector` (`tasteVectorStatus = READY`) produces an identical bundle to one with `PENDING`/unbuilt — the projection never reads the vector; explicit unit test asserts this.
- [ ] **No N+1**: merging a 4-eater household issues a bounded number of queries (IT with Hibernate statistics, or `@EntityGraph` — mirror the provisions planner-bundle no-N+1 assertion).
- [ ] **Read-only**: the merge issues zero writes to preference tables (Hibernate-statistics assertion).
- [ ] **Cross-tenant**: a household merge only reads soft prefs for its own members/eaters.
- [ ] **Empty household / empty eater list**: `mergeSoftPreferencesForSlot` already guards empty membership ([`HouseholdServiceImpl.java:837-839`](../../src/main/java/com/example/mealprep/household/domain/service/internal/HouseholdServiceImpl.java)); the reader is never called with an empty resolved list down that path.

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/preference/domain/service/PreferenceQueryService.java                    (add getSoftPreferences / getSoftPreferencesByUserIds — NON-VECTOR)
MOD   src/main/java/com/example/mealprep/preference/domain/service/internal/PreferenceServiceImpl.java             (impl + the taste-profile → like-map projection; explicitly excludes the vector)
NEW   src/main/java/com/example/mealprep/preference/spi/internal/PreferenceSoftPreferencesReader.java              (real SoftPreferencesReader @Component — thin adapter)

NEW   src/test/java/com/example/mealprep/preference/SoftPreferenceProjectionTest.java                             (unit — like-map projection, avoid-list, null-field bundles, VECTOR-EXCLUDED assertion)
NEW   src/test/java/com/example/mealprep/preference/PreferenceSoftPreferencesReaderIT.java                        (Testcontainers — real bundles per userId, order + count invariant, no N+1)
MOD   src/test/java/com/example/mealprep/household/HouseholdMergeWithFakeReaderIT.java                            (add a variant proving the REAL preference reader wins when preference is on the classpath)
MOD   src/test/java/com/example/mealprep/household/HouseholdMergeFlowIT.java                                      (end-to-end: real reader → non-empty merge)
```

Total: ~2 new prod + 2 mods (+ tests). Estimated agent runtime 2-3 days (the substantive work is the taste-profile → like-score projection definition + the no-N+1 batch load + ensuring the vector is provably never touched; the SPI adapter itself is trivial).

## Dependencies

- **Hard dependency**: `preference-01c` (merged) — `TasteProfile` entity + `TasteProfileDocument` (the source the projection reads).
- **Hard dependency**: `preference-01d` (merged) — `LifestyleConfig` (the lifestyle source).
- **Hard dependency**: `household-01e` (merged) — `SoftPreferencesReader` SPI, the household-owned `SoftPreferenceBundleDto`/`TasteProfileDocument`/`LifestyleConfigDocument`, `SoftPreferenceMerger`, the merge consumer + `ObjectProvider` resolution, `HouseholdMergeWithFakeReaderIT` (the wiring template).
- **Soft / informs (NOT a dependency)**: the deferred embedding vertical (E5) — this ticket deliberately does NOT depend on it; the non-vector projection works regardless of embedding status.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] **Run the full preference + household module IT suites locally with Docker** + `redocly lint` + spotless before pushing. Docker IS available locally. Run ITs in ~3-class batches (Hikari pool-exhaustion flake on big sweeps).
- [ ] CI green (build + spotless + ArchUnit + contract tests)
- [ ] All edge-case items above ticked
- [ ] PR description traces: a 2-eater household merge → real `PreferenceSoftPreferencesReader` returns 2 bundles with non-vector like-maps → `SoftPreferenceMerger` produces a non-empty `mergedTasteProfile` (was empty under the Noop); the taste vector is provably never read.

## What's NOT in scope

- **The full taste-vector / embedding merge** — audit **E5**, the deferred pgvector vertical ([`lld/preference.md:144`](../../lld/preference.md)). The projection surfaces only the non-vector like-maps + avoid list + lifestyle flags. **No `tasteVector` / `TasteVectorStatus` / embedding read.**
- **The `GET /api/v1/preferences/soft-bundle` REST endpoint** ([`lld/preference.md:621`](../../lld/preference.md)) — a separate preference deliverable; this ticket ships only the in-process cross-module read.
- **Relocating the `SoftPreferencesReader` SPI / the bundle DTOs from household to core/preference** — a mechanical refactor flagged for separate handling (the SPI + DTO already exist household-side and are reused as-is).
- **Hard constraints in the bundle** — intentionally NOT bundled ([`lld/preference.md:545-547`](../../lld/preference.md): the safety-critical read path stays explicit; a stale soft-data cache must not leak into it). The merge is soft-prefs only.
- **The `SoftPreferenceMerger` rules** — already implemented + tested; this ticket only feeds it real data.
- **Re-merge-on-preference-change** (a household merge cache invalidated when a member's taste profile updates) — out; the merge is computed on demand.

Squash-merge with: `feat(household,preference): 01g — real SoftPreferencesReader (non-vector soft-pref projection feeds the household merge)`
