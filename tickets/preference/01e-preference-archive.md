# Ticket: preference — 01e Preference Archive (pruned-item retention + re-emergence detection)

## Summary

Implement the **preference archive** per [`design/preference-model.md` lines 71](../../design/preference-model.md) (the "preference archive preserves the full learning history" paragraph) and [`lld/preference.md` lines 175-191, 258, 487-491, 530, 532-535, 615-616](../../lld/preference.md). Per roadmap §B1.3.

The archive is the **unbounded retention** target for items pruned from the taste profile under token pressure (per the HLD's 2500-token budget). Three roles:

1. **Audit / history**: every preference the system ever held is preserved, even after pruning.
2. **Re-emergence detection** (C-IMP-007): when new AI feedback supports an item that exists in archive, the delta operation is `RE_PROMOTE` (lift back to the active profile) rather than `ADD` (fresh discovery). This prevents preference cycling and means pruning is never lossy.
3. **User-facing archive view**: "show me preferences the system used to know about" — a read endpoint backing a frontend "preference history" page.

**Depends on `tickets/preference/01c-taste-profile-entity.md`** — the archive is a valid destination only if the taste profile entity exists. The `01c` ticket establishes the `TasteProfileDelta` sealed interface with the `Archive(fieldPath, itemKey, reason)` and `RePromote(fieldPath, itemKey)` record permits; this ticket adds the **persistence side** of those deltas (the archive table + repo + service + read endpoint).

**The actual `Archive` and `RePromote` delta-apply logic** (the code that moves an item from `TasteProfileDocument` to `preference_taste_profile_archive` and back) is part of the **deferred `TasteProfileDeltaApplier` real implementation** (mentioned as deferred in `01c` and in `tickets/feedback/01g-destination-bridges.md`). 01e ships:
- The table + entity + repository
- A `PreferenceArchiveQueryService` for reading the archive (REST endpoint)
- A `PreferenceArchiveUpdateService` (package-private to preference module) with two methods — `archiveItem(...)` and `markRePromoted(...)` — that the future `TasteProfileDeltaApplier` calls in-process when applying the delta operations.
- The read endpoint for the user-facing view.
- The cross-module read API (`getFullPreferenceArchive`) consumed by the future AI delta-update task (per `lld/preference.md:534-535`).

Closes: **C-A-004** (Preference archive — unbounded), **C-IMP-007** (Re-emerging preference detection from archive — partial: the storage + read API; the AI prompt that consumes them is its own deferred work).

## Behavioural spec

### Database schema

1. Migration `V20260615170000__preference_create_taste_profile_archive.sql` per [`lld/preference.md:175-191`](../../lld/preference.md):
   ```sql
   CREATE TABLE preference_taste_profile_archive (
       id                       uuid PRIMARY KEY,
       user_id                  uuid NOT NULL,
       field_path               varchar(128) NOT NULL,
       item_key                 varchar(128) NOT NULL,
       item_payload             jsonb NOT NULL,
       evidence_count           integer NOT NULL DEFAULT 0,
       last_signal_at           date,
       archived_at              timestamptz NOT NULL,
       archived_reason          varchar(32) NOT NULL,
       re_promoted_at           timestamptz
   );
   CREATE INDEX idx_pref_archive_user_field_key
       ON preference_taste_profile_archive (user_id, field_path, item_key);
   CREATE INDEX idx_pref_archive_user_archived_at
       ON preference_taste_profile_archive (user_id, archived_at DESC);
   ```
2. **`field_path`** is the path inside `TasteProfileDocument` where the item lived — `"ingredientPreferences.favourites"`, `"recipesToRepeat"`, `"activeExperiments"`, etc. Length-bounded at 128. The taste-profile delta-applier resolves `field_path` to a real document location at re-promote time.
3. **`item_key`** is the **identity** of the item within its field. For `IngredientPreference`, that's `item` (e.g. `"chicken thighs"`). For `RecipeRecommendation`, that's `name`. For `ActiveExperiment`, that's `hypothesis`. The key is the field the AI uses to dedup — same `item_key` at the same `field_path` for the same user → "same logical preference".
4. **`item_payload`** carries the full JSON shape of the archived item. Restored verbatim on re-promotion (with `last_signal` and `evidence_count` updated to reflect the new signal).
5. **`re_promoted_at`** is nullable; non-null after a `RE_PROMOTE`. Allows the archive to track "has been resurrected" history. **A re-promoted item is NOT deleted from the archive** — the row remains as history, with `re_promoted_at` set. If the same item is archived again later, **a new row is inserted** (no upsert) — the archive is append-only-with-status. The `(user_id, field_path, item_key, re_promoted_at IS NULL)` predicate finds the currently-archived (non-re-promoted) instance.
6. **`archived_reason`** enum (stored as varchar): `LOW_EVIDENCE | STALE | TOKEN_PRESSURE`. Per `lld/preference.md:258`.
7. **No `@Version`** on the archive entity. Append-only-with-a-single-mutable-column (`re_promoted_at`). Concurrency on the mutation: the only writer is `TasteProfileDeltaApplier`, which runs under the same single-flight-per-user `@Transactional` boundary as the taste-profile update; no concurrent `RE_PROMOTE` for the same item can happen.

### Entity

8. **`PreferenceArchiveEntry`** at `preference.domain.entity.PreferenceArchiveEntry`. Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`. Fields:
   - `id (UUID, @Id, application-set, updatable=false, nullable=false)`
   - `userId (UUID, NOT NULL, updatable=false)`
   - `fieldPath (String, length=128, NOT NULL, updatable=false)`
   - `itemKey (String, length=128, NOT NULL, updatable=false)`
   - `itemPayload (JsonNode, @Type(JsonType.class), columnDefinition="jsonb", NOT NULL, updatable=false)`
   - `evidenceCount (int, NOT NULL DEFAULT 0, updatable=false)`
   - `lastSignalAt (LocalDate, nullable, updatable=false)`
   - `archivedAt (Instant, NOT NULL, updatable=false)`
   - `archivedReason (ArchiveReason enum, @Enumerated(STRING), length=32, NOT NULL, updatable=false)`
   - `rePromotedAt (Instant, nullable)` — the **only** mutable column

### Repository (package-private)

9. **`PreferenceArchiveRepository`** at `preference.domain.repository`:
    ```java
    interface PreferenceArchiveRepository extends JpaRepository<PreferenceArchiveEntry, UUID> {

      /** Currently-archived (not yet re-promoted) entry for a logical preference. */
      Optional<PreferenceArchiveEntry> findByUserIdAndFieldPathAndItemKeyAndRePromotedAtIsNull(
          UUID userId, String fieldPath, String itemKey);

      /** User-facing archive listing (newest archived first). */
      Page<PreferenceArchiveEntry> findByUserIdOrderByArchivedAtDesc(UUID userId, Pageable p);

      /** All archive entries for a user — used by the delta-update task to detect re-emerging preferences. */
      List<PreferenceArchiveEntry> findAllByUserId(UUID userId);

      /** Filter by field path — used by the future user-facing "show me ingredient archive" view. */
      Page<PreferenceArchiveEntry> findByUserIdAndFieldPathStartingWithOrderByArchivedAtDesc(
          UUID userId, String fieldPathPrefix, Pageable p);

      /** Active (not-yet-re-promoted) entries — used by analytics. */
      long countByUserIdAndRePromotedAtIsNull(UUID userId);
    }
    ```

### Service interfaces

10. **`PreferenceArchiveQueryService`** at `preference.domain.service.PreferenceArchiveQueryService`. Public — re-exported via `PreferenceModule`. Methods:
    ```java
    public interface PreferenceArchiveQueryService {
      Page<PreferenceArchiveEntryDto> getArchive(UUID userId, Pageable pageable);
      Page<PreferenceArchiveEntryDto> getArchiveForField(UUID userId, String fieldPathPrefix, Pageable pageable);

      /** Full snapshot for the AI delta-update task per lld/preference.md:534-535. */
      List<PreferenceArchiveEntryDto> getFullArchive(UUID userId);

      /** Active entries (not yet re-promoted) — used by analytics. */
      long countActiveEntries(UUID userId);
    }
    ```
11. **`PreferenceArchiveUpdateService`** at `preference.domain.service.PreferenceArchiveUpdateService`. **Public interface** (re-exported via `PreferenceModule`), but **only the `TasteProfileDeltaApplier` should call it** — enforced via convention + a Javadoc note. Methods:
    ```java
    public interface PreferenceArchiveUpdateService {
      /** Called by TasteProfileDeltaApplier when applying an Archive delta op. */
      PreferenceArchiveEntryDto archiveItem(UUID userId, ArchiveItemRequest request);

      /** Called by TasteProfileDeltaApplier when applying a RePromote delta op.
       *  Marks the most-recent unpromoted entry for (userId, fieldPath, itemKey) as re-promoted.
       *  Throws PreferenceArchiveEntryNotFoundException if no matching entry exists. */
      PreferenceArchiveEntryDto markRePromoted(UUID userId, String fieldPath, String itemKey);
    }
    ```
12. **`PreferenceArchiveServiceImpl`** at `preference.domain.service.internal`. Single impl of both interfaces. `@Service`, `@Transactional` on writes; `@Transactional(readOnly = true)` on reads.

### DTOs

13. **`PreferenceArchiveEntryDto`** at `preference.api.dto`:
    ```java
    public record PreferenceArchiveEntryDto(
        UUID id, UUID userId,
        String fieldPath, String itemKey,
        JsonNode itemPayload,
        int evidenceCount, LocalDate lastSignalAt,
        Instant archivedAt, ArchiveReason archivedReason,
        Instant rePromotedAt) {}
    ```
14. **`ArchiveItemRequest`** (internal, in-process; not exposed via REST):
    ```java
    public record ArchiveItemRequest(
        @NotBlank @Size(max=128) String fieldPath,
        @NotBlank @Size(max=128) String itemKey,
        @NotNull JsonNode itemPayload,
        @Min(0) int evidenceCount,
        @Nullable LocalDate lastSignalAt,
        @NotNull ArchiveReason reason) {}
    ```
    Validation runs in `PreferenceArchiveServiceImpl.archiveItem` via programmatic Validator invocation (no controller, no implicit Jakarta-on-controller path).

### Module-local enum

15. **`ArchiveReason`** at `preference.domain.entity.ArchiveReason`. Values: `LOW_EVIDENCE, STALE, TOKEN_PRESSURE`. Verbatim from `lld/preference.md:258`. **NOTE**: the `ArchiveReason` enum was declared as in-scope for `tickets/preference/01a-hard-constraints-aggregate.md` per the LLD — verify it isn't already shipped; if so, **reuse** rather than duplicate. (Cross-check the existing `preference/01a` PR; the hard-constraints ticket explicitly defers this enum so it should land here.)

### Mapper

16. **`PreferenceArchiveMapper`** — MapStruct. `@Mapper(componentModel = "spring")`. Method `toDto(PreferenceArchiveEntry)`, plural `toDtos(List<...>)`.

### REST controller

17. **`PreferenceArchiveController`** at `preference.api.controller`. `@RequestMapping("/api/v1/preferences/archive")`. Endpoints:

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET | `/api/v1/preferences/archive` | query `?page=&size=&fieldPathPrefix=` | `Page<PreferenceArchiveEntryDto>` | 200 |
| GET | `/api/v1/preferences/archive/active-count` | — | `{ count: long }` | 200 |

**Only two read endpoints.** No POST or PUT — the archive is written exclusively via `PreferenceArchiveUpdateService.archiveItem` from the in-process `TasteProfileDeltaApplier` path.

Authentication required. `userId` via `CurrentUserResolver`.

`fieldPathPrefix` query param is optional. When present, filters via `findByUserIdAndFieldPathStartingWithOrderByArchivedAtDesc`. Example: `?fieldPathPrefix=ingredientPreferences` returns only ingredient archives.

### Cross-module re-emergence flow (documented for the future delta-applier ticket)

18. **Re-emergence detection**, per `lld/preference.md:534-535` and `design/preference-model.md:71`: the future AI delta-update task (which runs inside the feedback module's `TasteProfileDeltaTask`) calls `preferenceArchiveQueryService.getFullArchive(userId)` **before** producing deltas, passing the archive into the prompt as context. When new feedback supports an archived item, the AI emits a `RePromote(fieldPath, itemKey)` delta instead of `Add(fieldPath, item)`. The applier:
    - Calls `preferenceArchiveUpdateService.markRePromoted(userId, fieldPath, itemKey)` to flip `re_promoted_at`.
    - Restores the item into the live document at the appropriate path (using `itemPayload`) — but with updated `evidence_count` and `last_signal` reflecting the new feedback signal (NOT the stale archived values).
19. **`Add` on an item that exists in archive** — if the AI doesn't notice and emits `Add` instead of `RePromote`, the future applier's `archiveItem` call surfaces the duplicate via a uniqueness check (the `findByUserIdAndFieldPathAndItemKeyAndRePromotedAtIsNull` query returning non-empty); the applier converts to `RePromote` automatically and logs a WARN. **This auto-conversion behaviour is documented here for the future applier; 01e ships only the query/update API that enables the behaviour.**

### Cross-cutting

20. New exceptions:
    - `PreferenceArchiveEntryNotFoundException` (404) — thrown by `markRePromoted` if no matching unpromoted entry exists for `(userId, fieldPath, itemKey)`.
21. `GlobalExceptionHandler` gains the new mapping.
22. `PreferenceModule` facade adds the two new service accessors.
23. ArchUnit: existing rule covers the new repo.

### Events

24. **Published**:
    - `PreferenceArchivedEvent(UUID userId, UUID archiveEntryId, String fieldPath, String itemKey, ArchiveReason reason, UUID traceId, Instant occurredAt)` — fired by `archiveItem` `AFTER_COMMIT`. No v1 consumers; emitted for future "your profile got smaller" analytics or notification.
    - `PreferenceRePromotedEvent(UUID userId, UUID archiveEntryId, String fieldPath, String itemKey, UUID traceId, Instant occurredAt)` — fired by `markRePromoted` `AFTER_COMMIT`. No v1 consumers.
25. **Consumed**: none.

## Database

```
NEW   src/main/resources/db/migration/V20260615170000__preference_create_taste_profile_archive.sql
```

Schema per §1.

## OpenAPI updates

Add 2 paths + 1 schema to `paths/preference.yaml` and `schemas/preference.yaml`:

- `GET /api/v1/preferences/archive` (with `fieldPathPrefix`, `page`, `size` query params; response `Page<PreferenceArchiveEntryDto>`)
- `GET /api/v1/preferences/archive/active-count` (response `{ count: integer }` — inline schema)

**Schema `PreferenceArchiveEntryDto`** in `schemas/preference.yaml`:
```yaml
PreferenceArchiveEntryDto:
  type: object
  required: [id, userId, fieldPath, itemKey, itemPayload, evidenceCount, archivedAt, archivedReason]
  properties:
    id: { type: string, format: uuid }
    userId: { type: string, format: uuid }
    fieldPath: { type: string, maxLength: 128 }
    itemKey: { type: string, maxLength: 128 }
    itemPayload: { type: object, additionalProperties: true }
    evidenceCount: { type: integer, minimum: 0 }
    lastSignalAt: { type: string, format: date, nullable: true }
    archivedAt: { type: string, format: date-time }
    archivedReason: { type: string, enum: [LOW_EVIDENCE, STALE, TOKEN_PRESSURE] }
    rePromotedAt: { type: string, format: date-time, nullable: true }
```

## Edge-case checklist

- [ ] Migration applies cleanly; `FlywayMigrationIT` passes.
- [ ] `ddl-auto=validate` accepts the entity-to-schema mapping.
- [ ] `PreferenceArchiveEntry` JSONB round-trip — persist with a non-trivial `itemPayload` (e.g. a full `IngredientPreference` shape) → re-read → equal.
- [ ] `archiveItem` inserts a new row with `re_promoted_at = NULL`.
- [ ] `archiveItem` called twice for the same `(userId, fieldPath, itemKey)` inserts **two rows** (no upsert) — this is intentional per §5.
- [ ] `markRePromoted` flips `re_promoted_at` on the most-recent unpromoted entry for `(userId, fieldPath, itemKey)`.
- [ ] `markRePromoted` for a `(userId, fieldPath, itemKey)` with no unpromoted entries → throws `PreferenceArchiveEntryNotFoundException` (404).
- [ ] `markRePromoted` does not touch entries that were already re-promoted (they remain as history).
- [ ] After a `RE_PROMOTE` → `ARCHIVE` cycle: the table contains two rows for the same logical item — the first with `re_promoted_at` set, the second with `re_promoted_at = NULL`.
- [ ] GET `/api/v1/preferences/archive` returns rows newest-first.
- [ ] GET with `?fieldPathPrefix=ingredientPreferences` returns only rows where `field_path` starts with `"ingredientPreferences"`.
- [ ] GET `/active-count` returns the count of unpromoted entries (the integer count of `re_promoted_at IS NULL` rows for the user).
- [ ] **Cross-tenant**: user A's session cannot read user B's archive (all queries scope to `CurrentUserResolver` user).
- [ ] Pagination works (default 20, max 100).
- [ ] `PreferenceArchivedEvent` fires `AFTER_COMMIT` for each successful `archiveItem`.
- [ ] `PreferenceRePromotedEvent` fires for each successful `markRePromoted`.
- [ ] `getFullArchive(userId)` returns all entries (both promoted and not) — used by the AI prompt for full context.
- [ ] **Empty archive**: `GET /archive` returns `Page<>` with `content: []`, `totalElements: 0`.
- [ ] **Large archive** (100+ entries): pagination doesn't time out (smoke at IT level).
- [ ] `itemKey` length cap (128 chars): an entry attempted with a 129-char itemKey via `archiveItem` fails Jakarta validation programmatically.
- [ ] OpenAPI contract test for both endpoints.
- [ ] ArchUnit: no class outside `preference..` imports the archive repository.
- [ ] The `archiveItem` method is NOT exposed via REST — verified by grep / contract test (no `POST /archive` endpoint).
- [ ] **Trace ID propagation**: events carry the originating trace id when `archiveItem` is called inside an upstream transaction (the future delta-applier passes it).

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260615170000__preference_create_taste_profile_archive.sql

NEW   src/main/java/com/example/mealprep/preference/domain/entity/PreferenceArchiveEntry.java
NEW   src/main/java/com/example/mealprep/preference/domain/entity/ArchiveReason.java                    (if not already shipped by 01a — verify)

NEW   src/main/java/com/example/mealprep/preference/domain/repository/PreferenceArchiveRepository.java

NEW   src/main/java/com/example/mealprep/preference/domain/service/PreferenceArchiveQueryService.java
NEW   src/main/java/com/example/mealprep/preference/domain/service/PreferenceArchiveUpdateService.java
NEW   src/main/java/com/example/mealprep/preference/domain/service/internal/PreferenceArchiveServiceImpl.java

NEW   src/main/java/com/example/mealprep/preference/api/controller/PreferenceArchiveController.java
NEW   src/main/java/com/example/mealprep/preference/api/dto/PreferenceArchiveEntryDto.java
NEW   src/main/java/com/example/mealprep/preference/api/dto/ArchiveItemRequest.java
NEW   src/main/java/com/example/mealprep/preference/api/mapper/PreferenceArchiveMapper.java

NEW   src/main/java/com/example/mealprep/preference/event/PreferenceArchivedEvent.java
NEW   src/main/java/com/example/mealprep/preference/event/PreferenceRePromotedEvent.java

NEW   src/main/java/com/example/mealprep/preference/exception/PreferenceArchiveEntryNotFoundException.java

MOD   src/main/java/com/example/mealprep/preference/PreferenceModule.java
MOD   src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java
MOD   src/main/resources/openapi/paths/preference.yaml
MOD   src/main/resources/openapi/schemas/preference.yaml

NEW   src/test/java/com/example/mealprep/preference/PreferenceArchiveServiceImplTest.java
NEW   src/test/java/com/example/mealprep/preference/PreferenceArchiveFlowIT.java
NEW   src/test/java/com/example/mealprep/preference/PreferenceArchiveEventPublicationIT.java
NEW   src/test/java/com/example/mealprep/preference/testdata/PreferenceArchiveTestData.java
```

Total: ~14 new files + 4 mods. Estimated agent runtime 2-3 hours.

## Dependencies

- **Hard dependency**: `tickets/preference/01c-taste-profile-entity.md` — the archive is meaningful only in the presence of a taste profile. **Strict ordering**: 01c merges first.
- **Hard dependency**: `core-01-decision-log` (merged) — `MealPrepEvent` base.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`.
- **Hard dependency**: `preference-01a` (merged) — `PreferenceModule` facade exists.
- **Downstream consumer**: the deferred `TasteProfileDeltaApplier` (deferred ticket from `01c`) — once that lands, it will call `archiveItem` and `markRePromoted` from the in-process delta-apply path.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit + contract tests)
- [ ] All edge-case items above ticked
- [ ] Manual smoke documented in PR: register user → create taste profile → directly call `archiveItem` from a test → GET `/archive` returns the row → call `markRePromoted` → row's `re_promoted_at` populated

## What's NOT in scope

- **`TasteProfileDeltaApplier` real implementation** — deferred from 01c; will consume this ticket's APIs when it lands.
- **The AI prompt** that uses archive context to emit `RePromote` deltas — owned by the feedback module's `TasteProfileDeltaTask`.
- **Archive pruning / TTL** — the archive is **unbounded**; HLD specifies no retention cap. If volume grows past a real-world threshold, a future ticket can add tier-based archiving (cold storage, etc.). Not a v1 concern.
- **User-initiated archive deletion / "forget this preference"** — out of scope; route via the taste-profile manual-override flow if needed.
- **GDPR data export / delete** integration — covered by the broader roadmap C2 GDPR ticket; this ticket's table is cascade-deleted when the user is hard-deleted via the future GDPR endpoint.

Squash-merge with: `feat(preference): 01e — preference archive + re-emergence storage API (Tier B B1.3)`
