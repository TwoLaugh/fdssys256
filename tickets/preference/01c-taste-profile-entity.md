# Ticket: preference — 01c Taste Profile Entity + Service + REST + Audit Log

## Summary

Implement the **Tier 2 Taste Profile** of the three-tier Preference Model — the AI-maintained JSONB document described in [`design/preference-model.md` lines 65-176](../../design/preference-model.md) and specified at the entity level in [`lld/preference.md` lines 132-194, 247-265, 312-381, 462-507, 561-582, 596-619, 696-731](../../lld/preference.md). The single highest-leverage piece of work in the Tier B roadmap (per [`design/audits/2026-05-21-frontend-readiness-roadmap.md` §B1.1](../../design/audits/2026-05-21-frontend-readiness-roadmap.md)) — until this lands, the system has nowhere to apply AI-derived preference deltas and the entire learning loop is open.

Ships:

- **3 new tables**: `preference_taste_profile`, `preference_taste_profile_versions`, `preference_taste_profile_audit` (the latter is the entity-level audit log — distinct from `preference_taste_profile_versions` which captures delta-batch snapshots).
- **`TasteProfile` JPA entity** with two version fields per `lld/preference.md:193` (`documentVersion` monotonic; `optimisticVersion` for `@Version`).
- **`TasteProfileDocument` record-tree** mirroring the HLD shape (`SoftConstraints`, `FlavourPreferences`, `TexturePreferences`, `IngredientPreferences`, `CuisinePreferences`, `CookingPreferences`, `PortionStyle`, `HouseholdContext`, `RecipeRecommendation`, `ActiveExperiment`, plus `learnedInsights: List<String>`).
- **Service split** per the style guide: `TasteProfileQueryService` (re-exported via `PreferenceModule`) and `TasteProfileUpdateService`, both implemented by `TasteProfileServiceImpl`.
- **MapStruct mapper** `TasteProfileMapper`.
- **REST endpoints**: GET view, PUT manual override (flagged in the version log), POST `/refresh-now` (user-triggered AI update — wires the bridge to the feedback module's `TasteProfileDeltaTask`).
- **Audit log table** for entity-level changes (who, when, what fields). This is **separate from** delta-level versioning in `preference_taste_profile_versions` — the audit log captures user-initiated overrides and the system-level refresh trigger; the versions table captures the full document snapshot per delta-batch.

**Deferred to other tickets**:
- **Per-delta versioning rollback** (C-C-040) — the LLD specifies `rollbackTasteProfile(targetDocumentVersion)`; ship the entity + repo for `preference_taste_profile_versions` here, but **defer the rollback endpoint** to a follow-up.
- **`TasteProfileDeltaApplier` + `TasteProfileBudgetGuard` internal helpers + the AI delta pipeline** — the helpers ship here as stub interfaces so the feedback module's bridge in `tickets/feedback/01g-destination-bridges.md` can call them; the **real implementation** of delta-application + budget-enforcement is its own follow-up ticket (deferred capability `01c-delta-applier`).
- **pgvector `taste_vector` column** per `lld/preference.md:146` — ships in this ticket's migration as a nullable column with default status `pending`, but the **async embedding listener** and HNSW index land in a follow-up.
- **`@ValidNoveltyTolerance` validator** belongs to lifestyle config (`01d`).

**Worth user review**: the split between "entity-level audit log" (this ticket) and "delta-batch snapshots" (`preference_taste_profile_versions`, also this ticket) is intentional but adds two-tables-for-similar-thing complexity. Justification: the audit log tracks **why** the profile changed (user override vs AI delta vs system refresh), the versions table tracks **what** the document looked like at each snapshot. They serve different read patterns: "show me what changed and who did it" vs "let me see the full document state at version 14".

Closes: **C-A-003** (Taste profile JSON document — AI-maintained), **C-IMP-008** (Soft-intolerance signal in taste profile — covered by the `softConstraints` field of the document shape).

## Behavioural spec

### Database schema

1. Migration `V20260615150000__preference_create_taste_profile.sql` creates `preference_taste_profile` per [`lld/preference.md:134-157`](../../lld/preference.md). Schema:
   ```sql
   CREATE TABLE preference_taste_profile (
       id                       uuid PRIMARY KEY,
       user_id                  uuid NOT NULL UNIQUE,
       document                 jsonb NOT NULL,
       document_version         integer NOT NULL DEFAULT 1,
       feedback_cursor          varchar(64),
       based_on_feedback_count  integer NOT NULL DEFAULT 0,
       last_delta_applied_at    timestamptz,
       last_token_estimate      integer,
       taste_vector_status      varchar(16) NOT NULL DEFAULT 'pending',
       taste_vector_doc_version integer,
       taste_vector_model_id    varchar(96),
       taste_vector_embedded_at timestamptz,
       optimistic_version       bigint NOT NULL DEFAULT 0,
       created_at               timestamptz NOT NULL,
       updated_at               timestamptz NOT NULL
   );
   CREATE UNIQUE INDEX idx_pref_taste_profile_user ON preference_taste_profile (user_id);
   ```
   **Omitted** from the LLD's columns (deferred to a follow-up `01c-vector` ticket): `taste_vector vector(1536)` column + the HNSW index. The `taste_vector_status` scalar ships here so the future ticket only adds the vector column + index.
2. Migration `V20260615150100__preference_create_taste_profile_versions.sql` creates `preference_taste_profile_versions` per [`lld/preference.md:159-173`](../../lld/preference.md):
   ```sql
   CREATE TABLE preference_taste_profile_versions (
       id                       uuid PRIMARY KEY,
       taste_profile_id         uuid NOT NULL REFERENCES preference_taste_profile(id) ON DELETE CASCADE,
       document_version         integer NOT NULL,
       document_snapshot        jsonb NOT NULL,
       feedback_range_start     varchar(64),
       feedback_range_end       varchar(64),
       trigger                  varchar(16) NOT NULL,
       deltas_applied           jsonb NOT NULL,
       model_tier_used          varchar(16) NOT NULL,
       generated_at             timestamptz NOT NULL,
       UNIQUE (taste_profile_id, document_version)
   );
   CREATE INDEX idx_pref_tp_versions_tp_ver ON preference_taste_profile_versions (taste_profile_id, document_version DESC);
   ```
3. Migration `V20260615150200__preference_create_taste_profile_audit.sql` — the **entity-level audit log** (distinct from the per-delta `versions` table):
   ```sql
   CREATE TABLE preference_taste_profile_audit (
       id                       uuid PRIMARY KEY,
       taste_profile_id         uuid NOT NULL REFERENCES preference_taste_profile(id) ON DELETE CASCADE,
       actor_user_id            uuid NOT NULL,
       actor_type               varchar(16) NOT NULL,           -- USER | AI | SYSTEM
       change_type              varchar(32) NOT NULL,           -- MANUAL_OVERRIDE | AI_DELTA_APPLIED | REFRESH_TRIGGERED | INITIALIZED | ROLLED_BACK
       previous_document_version integer,
       new_document_version     integer NOT NULL,
       summary                  varchar(512),                   -- e.g. "applied 12 deltas from feedback batch f-87..f-99"
       trace_id                 uuid,
       occurred_at              timestamptz NOT NULL
   );
   CREATE INDEX idx_pref_tp_audit_tp_time ON preference_taste_profile_audit (taste_profile_id, occurred_at DESC);
   CREATE INDEX idx_pref_tp_audit_actor ON preference_taste_profile_audit (actor_user_id, occurred_at DESC);
   ```
   **`actor_type` column** anticipates the origin-tracking pattern (`tickets/core/02b`) — values `USER | AI | SYSTEM` correspond to the pattern's actor classification. Ships now so the bridge in `tickets/feedback/01g` writes the right value from day one.

### Entities

4. **`TasteProfile`** at `com.example.mealprep.preference.domain.entity.TasteProfile`. Aggregate root. Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`. Fields:
   - `id (UUID, @Id, application-set, updatable=false, nullable=false)`
   - `userId (UUID, NOT NULL, UNIQUE, updatable=false)`
   - `document (TasteProfileDocument, @Type(JsonType.class), columnDefinition="jsonb", NOT NULL)`
   - `documentVersion (int, NOT NULL DEFAULT 1)` — monotonic, incremented per delta-batch
   - `feedbackCursor (String, length=64)` — nullable; last incorporated feedback id
   - `basedOnFeedbackCount (int, NOT NULL DEFAULT 0)`
   - `lastDeltaAppliedAt (Instant, nullable)`
   - `lastTokenEstimate (Integer, nullable)` — set by the future budget guard
   - `tasteVectorStatus (TasteVectorStatus enum: PENDING | EMBEDDED | FAILED, @Enumerated(STRING), length=16, NOT NULL DEFAULT PENDING)`
   - `tasteVectorDocVersion (Integer, nullable)`
   - `tasteVectorModelId (String, length=96, nullable)`
   - `tasteVectorEmbeddedAt (Instant, nullable)`
   - `optimisticVersion (long, @Version, column="optimistic_version", NOT NULL)`
   - `createdAt (@CreatedDate)`, `updatedAt (@LastModifiedDate)`
5. **`TasteProfileVersion`** at `com.example.mealprep.preference.domain.entity.TasteProfileVersion`. Append-only. Fields:
   - `id (UUID, @Id, application-set)`
   - `tasteProfile (@ManyToOne(fetch=LAZY), @JoinColumn(name="taste_profile_id", nullable=false, updatable=false))`
   - `documentVersion (int, NOT NULL)`
   - `documentSnapshot (TasteProfileDocument, @Type(JsonType.class), columnDefinition="jsonb", NOT NULL)`
   - `feedbackRangeStart (String, length=64, nullable)`
   - `feedbackRangeEnd (String, length=64, nullable)`
   - `trigger (TasteProfileTrigger enum: BATCH | WEEKLY | MANUAL, @Enumerated(STRING), length=16, NOT NULL)`
   - `deltasApplied (JsonNode, @Type(JsonType.class), columnDefinition="jsonb", NOT NULL)` — raw delta array; per-delta typing happens at parse time
   - `modelTierUsed (String, length=16, NOT NULL)` — e.g. `cheap`, `mid`, `frontier`
   - `generatedAt (Instant, NOT NULL)`
   - **No `@Version`** (append-only).
6. **`TasteProfileAuditLog`** at `com.example.mealprep.preference.domain.entity.TasteProfileAuditLog`. Append-only. Fields:
   - `id (UUID)`
   - `tasteProfile (@ManyToOne(fetch=LAZY), @JoinColumn(name="taste_profile_id", nullable=false, updatable=false))`
   - `actorUserId (UUID, NOT NULL)`
   - `actorType (ActorType enum: USER | AI | SYSTEM, @Enumerated(STRING), length=16, NOT NULL)`
   - `changeType (TasteProfileChangeType enum: INITIALIZED | MANUAL_OVERRIDE | AI_DELTA_APPLIED | REFRESH_TRIGGERED | ROLLED_BACK, @Enumerated(STRING), length=32, NOT NULL)`
   - `previousDocumentVersion (Integer, nullable)` — null only for INITIALIZED
   - `newDocumentVersion (int, NOT NULL)`
   - `summary (String, length=512, nullable)`
   - `traceId (UUID, nullable)` — propagated from the originating request
   - `occurredAt (Instant, NOT NULL)`
   - **No `@Version`**, **no `@LastModifiedDate`**.

### Document shape (mirrors HLD)

7. **`TasteProfileDocument`** at `com.example.mealprep.preference.domain.document.TasteProfileDocument`. Java record mirroring the JSONB structure described in [`design/preference-model.md:75-163`](../../design/preference-model.md). Fields:
   ```java
   public record TasteProfileDocument(
       LocalDate lastUpdated,
       int version,                          // monotonic — mirrors entity's documentVersion
       int basedOnFeedbackCount,
       String feedbackCursor,
       SoftConstraints softConstraints,
       FlavourPreferences flavourPreferences,
       TexturePreferences texturePreferences,
       IngredientPreferences ingredientPreferences,
       CuisinePreferences cuisinePreferences,
       CookingPreferences cookingPreferences,
       PortionStyle portionStyle,
       HouseholdContext householdContext,
       List<RecipeRecommendation> recipesToRepeat,
       List<RecipeRecommendation> recipesToAvoid,
       List<ActiveExperiment> activeExperiments,
       List<String> learnedInsights
   ) { /* nested records below */ }
   ```
8. Nested records (each is `public static record` inside `TasteProfileDocument`):
   - `SoftConstraints(List<SoftIntolerance> intolerances)`
   - `SoftIntolerance(String substance, String severity, String notes)`
   - `FlavourPreferences(List<String> likes, List<String> dislikes, String notes)`
   - `TexturePreferences(List<String> likes, List<String> dislikes)`
   - `IngredientPreferences(List<IngredientPreference> favourites, List<IngredientPreference> disliked, List<TrendingIngredient> trendingPositive, List<TrendingIngredient> trendingNegative)`
   - `IngredientPreference(String item, int evidenceCount, LocalDate lastSignal, IngredientPreferenceSource source)`
   - `TrendingIngredient(String item, int evidenceCount, LocalDate firstSignal)`
   - `CuisinePreferences(List<String> favourites, List<String> enjoys, List<String> lessPreferred, String notes)`
   - `CookingPreferences(SkillLevel skillLevel, List<String> preferredMethods, List<String> dislikedMethods)`
   - `PortionStyle(String preference, String saladMeals)`
   - `HouseholdContext(List<String> individualOnlyPreferences, String householdSuitableNotes)`
   - `RecipeRecommendation(String name, String suitableFor, String reason)`
   - `ActiveExperiment(String hypothesis, ExperimentStatus status, int evidenceFor, int evidenceAgainst, LocalDate created)`
9. **Module-local enums** in `preference.domain.entity.*` (per `lld/preference.md:264`):
   - `IngredientPreferenceSource` — `FEEDBACK, INFERRED, ONBOARDING`
   - `ExperimentStatus` — `TESTING, PROMOTED, DISCARDED`
   - `SkillLevel` — `BEGINNER, INTERMEDIATE, ADVANCED`
   - `TasteProfileTrigger` — `BATCH, WEEKLY, MANUAL`
   - `TasteVectorStatus` — `PENDING, EMBEDDED, FAILED`
   - `ActorType` — `USER, AI, SYSTEM`
   - `TasteProfileChangeType` — `INITIALIZED, MANUAL_OVERRIDE, AI_DELTA_APPLIED, REFRESH_TRIGGERED, ROLLED_BACK`

### Repositories (package-private)

10. **`TasteProfileRepository`** at `preference.domain.repository`:
    ```java
    interface TasteProfileRepository extends JpaRepository<TasteProfile, UUID> {
      Optional<TasteProfile> findByUserId(UUID userId);
      List<TasteProfile> findByUserIdIn(Collection<UUID> userIds);
      List<TasteProfile> findByTasteVectorStatus(TasteVectorStatus status);   // for the future async embed pipeline
    }
    ```
11. **`TasteProfileVersionRepository`**:
    ```java
    interface TasteProfileVersionRepository extends JpaRepository<TasteProfileVersion, UUID> {
      Page<TasteProfileVersion> findByTasteProfileIdOrderByDocumentVersionDesc(UUID id, Pageable p);
      Optional<TasteProfileVersion> findByTasteProfileIdAndDocumentVersion(UUID id, int v);
    }
    ```
12. **`TasteProfileAuditLogRepository`**:
    ```java
    interface TasteProfileAuditLogRepository extends JpaRepository<TasteProfileAuditLog, UUID> {
      Page<TasteProfileAuditLog> findByTasteProfileIdOrderByOccurredAtDesc(UUID id, Pageable p);
    }
    ```

### DTOs

13. **`TasteProfileDto`** record at `preference.api.dto`:
    ```java
    public record TasteProfileDto(
        UUID id, UUID userId,
        TasteProfileDocument document,
        int documentVersion, String feedbackCursor,
        int basedOnFeedbackCount, Instant lastDeltaAppliedAt,
        Integer lastTokenEstimate,
        TasteVectorStatus tasteVectorStatus,
        long optimisticVersion,
        Instant createdAt, Instant updatedAt) {}
    ```
14. **`TasteProfileVersionDto`** — entity → DTO mapping per LLD.
15. **`TasteProfileAuditEntryDto`**:
    ```java
    public record TasteProfileAuditEntryDto(
        UUID id, UUID actorUserId, ActorType actorType,
        TasteProfileChangeType changeType,
        Integer previousDocumentVersion, int newDocumentVersion,
        String summary, UUID traceId, Instant occurredAt) {}
    ```
16. **`UpdateTasteProfileRequest`** (manual override) per `lld/preference.md`:
    ```java
    public record UpdateTasteProfileRequest(
        @NotNull @Valid TasteProfileDocument document,
        long expectedVersion) {}
    ```
17. **`ApplyTasteProfileDeltasRequest`** — ships in this ticket as a **DTO record only** (per `lld/preference.md:375-380`); the **`TasteProfileDelta` sealed interface and its 8 record permits** also ship here. The internal `TasteProfileDeltaApplier` that consumes them is **stubbed**: a no-op implementation that throws `UnsupportedOperationException` with the message "delta application landing in deferred ticket 01c-delta-applier". This shape ships now so the feedback bridge in `tickets/feedback/01g` can compile against the stable DTO.
18. **`TriggerTasteProfileRefreshRequest`** for the POST `/refresh-now` endpoint:
    ```java
    public record TriggerTasteProfileRefreshRequest(
        @Nullable String feedbackRangeStart,  // optional override; else "since last cursor"
        @Nullable String feedbackRangeEnd) {}
    ```

### Mapper

19. **`TasteProfileMapper`** — MapStruct, `@Mapper(componentModel = "spring")`. Methods: `toDto(TasteProfile)`, `toVersionDto(TasteProfileVersion)`, `toAuditEntryDto(TasteProfileAuditLog)`, plural variants for collections.

### Service interfaces

20. **`TasteProfileQueryService`** at `preference.domain.service.TasteProfileQueryService`. Public interface, re-exported via `PreferenceModule`. Methods:
    ```java
    public interface TasteProfileQueryService {
      Optional<TasteProfileDto> getTasteProfile(UUID userId);
      List<TasteProfileDto> getTasteProfilesByUserIds(List<UUID> userIds);
      Page<TasteProfileVersionDto> getVersions(UUID userId, Pageable pageable);
      Optional<TasteProfileVersionDto> getVersion(UUID userId, int documentVersion);
      Page<TasteProfileAuditEntryDto> getAuditLog(UUID userId, Pageable pageable);
    }
    ```
21. **`TasteProfileUpdateService`** at `preference.domain.service.TasteProfileUpdateService`. Public interface. Methods:
    ```java
    public interface TasteProfileUpdateService {
      // Onboarding seed — called by auth-creation flow OR explicit "start fresh" endpoint.
      TasteProfileDto initialise(UUID userId);

      // User manual override (PUT). Writes audit row + version snapshot with trigger=MANUAL.
      TasteProfileDto applyManualOverride(UUID userId, UpdateTasteProfileRequest request, UUID actorUserId);

      // AI delta application — called in-process by the feedback bridge. NOT exposed via REST.
      // 01c ships a STUB throwing UnsupportedOperationException.
      TasteProfileDto applyDeltas(UUID userId, ApplyTasteProfileDeltasRequest request);

      // POST /refresh-now — fires an event the feedback module's TasteProfileDeltaTask listens for.
      // Writes audit row with change_type=REFRESH_TRIGGERED. Returns current state immediately;
      // the refresh is async.
      TasteProfileDto triggerRefresh(UUID userId, TriggerTasteProfileRefreshRequest request);
    }
    ```
22. **`TasteProfileServiceImpl`** at `preference.domain.service.internal.TasteProfileServiceImpl`. Single impl of both interfaces. `@Service`, `@Transactional` on writes; `@Transactional(readOnly = true)` on reads. **`@RequiredArgsConstructor`** injecting the three repositories, the mapper, the `ApplicationEventPublisher`, and the `Clock`.

### REST controller

23. **`TasteProfileController`** at `preference.api.controller`. `@RestController @RequestMapping("/api/v1/preferences/taste-profile") @RequiredArgsConstructor`. Endpoints:

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET | `/api/v1/preferences/taste-profile` | — | `TasteProfileDto` | 200 / 404 |
| PUT | `/api/v1/preferences/taste-profile` | `UpdateTasteProfileRequest` | `TasteProfileDto` | 200 / 400 / 404 / 409 |
| POST | `/api/v1/preferences/taste-profile/refresh-now` | `TriggerTasteProfileRefreshRequest` | `TasteProfileDto` | 202 / 404 |
| GET | `/api/v1/preferences/taste-profile/versions` | query `?page=&size=` | `Page<TasteProfileVersionDto>` | 200 |
| GET | `/api/v1/preferences/taste-profile/versions/{documentVersion}` | — | `TasteProfileVersionDto` | 200 / 404 |
| GET | `/api/v1/preferences/taste-profile/audit-log` | query `?page=&size=` | `Page<TasteProfileAuditEntryDto>` | 200 |

24. **Authentication**: every endpoint requires the session-cookie auth from `auth-01a`. `userId` resolved via `CurrentUserResolver`; never accepted from a query parameter.
25. **Rollback endpoint is deferred** (per the Summary). `POST /api/v1/preferences/taste-profile/rollback` ships in a follow-up.

### Events

26. **Published**:
    - `TasteProfileChangedEvent(UUID userId, UUID tasteProfileId, int documentVersion, TasteProfileChangeType changeType, ActorType actorType, UUID traceId, Instant occurredAt)` — extends `core.events.ScopeChangedEvent` with `scopeKind="taste-profile"`, `scopeId=userId`. Fired `AFTER_COMMIT` on every successful write. Consumed by:
      - The future async embedding listener (deferred to vector ticket).
      - The planner module (re-opt trigger on significant preference changes — its choice whether to fire).
    - `TasteProfileRefreshRequestedEvent(UUID userId, UUID tasteProfileId, String feedbackRangeStart, String feedbackRangeEnd, UUID traceId, Instant occurredAt)` — fired by the `/refresh-now` endpoint. Consumed by the feedback module's `TasteProfileDeltaTask` (which itself ships in the deferred delta-applier ticket; for 01c, the event is published but has no listener — that's fine).
27. **Consumed**: none. Per `lld/preference.md:692`, preference module does **not** consume `FeedbackProcessedEvent`; the only inbound surface is the direct `applyDeltas` service call.

### Validation

28. `UpdateTasteProfileRequest` — `@NotNull @Valid` on `document`; `@Min(0)` on `expectedVersion`. The `TasteProfileDocument` itself uses Jakarta annotations on nested records: `@NotNull` on top-level non-null sections, `@Valid` on collection elements, `@Size` on list-level bounds (e.g. `@Size(max=50)` on `favourites`, `@Size(max=20)` on `learnedInsights`). Free-text fields use `@Size(max=512)`.
29. **No custom validator in 01c.** Delta-specific validation (`@ValidTasteProfileDelta`) is a stub interface only — the real validator lands with the delta-applier ticket.

### Cross-cutting

30. New exceptions:
    - `TasteProfileNotFoundException` (404) — already declared in `lld/preference.md:634`; ship here.
    - `InvalidTasteProfileDeltaException` (422) — declared in LLD; ship here (subclass thrown by the stubbed applier means the existence-of-the-class is the only requirement).
    - `TasteProfileBudgetExceededException` (422) — declared; ship the class, not the enforcement.
31. `GlobalExceptionHandler` handlers added for all three.
32. `PreferenceModule` facade gains `TasteProfileQueryService` and `TasteProfileUpdateService` accessor methods (the existing facade has `PreferenceQueryService`/`PreferenceUpdateService` for hard constraints; adding the taste-profile pair is an additive change).
33. ArchUnit: the existing `preferenceReposAreInternalToPreference` rule covers the new repos automatically.

## Database

```
NEW   src/main/resources/db/migration/V20260615150000__preference_create_taste_profile.sql
NEW   src/main/resources/db/migration/V20260615150100__preference_create_taste_profile_versions.sql
NEW   src/main/resources/db/migration/V20260615150200__preference_create_taste_profile_audit.sql
```

Schemas per §1, §2, §3 above.

## OpenAPI updates

Add 6 paths + 6 schemas to `src/main/resources/openapi/paths/preference.yaml` and `schemas/preference.yaml`:

**Paths**: the six endpoints in §23 above. All `security: [cookieAuth: []]`.

**Schemas**:
- `TasteProfileDto`
- `TasteProfileDocument` (with all 12 nested record types as sub-schemas — this is the largest schema addition)
- `TasteProfileVersionDto`
- `TasteProfileAuditEntryDto`
- `UpdateTasteProfileRequest`
- `TriggerTasteProfileRefreshRequest`

The `TasteProfileDocument` schema uses `additionalProperties: false` on the record-level types and `type: array` with `items: { $ref: '#/components/schemas/IngredientPreference' }` on collection fields.

## Edge-case checklist

- [ ] Three migrations apply cleanly against Testcontainers Postgres — `FlywayMigrationIT` passes.
- [ ] `ddl-auto=validate` accepts the entity-to-schema mapping at startup.
- [ ] `TasteProfileDocument` JSONB round-trip: persist a document with all 12 nested sections populated → re-read → equal. `TasteProfileDocumentRoundTripTest` (unit, no Spring context).
- [ ] **`@Version` concurrency**: two concurrent updates to the same row → second writer gets `OptimisticLockingFailureException` → 409 ProblemDetail.
- [ ] **`documentVersion` monotonicity**: each successful write increments by 1; never decrements (rolling back creates a new version with `change_type=ROLLED_BACK`, not a version decrement).
- [ ] **Audit log row written for every change**: `applyManualOverride` writes one row with `change_type=MANUAL_OVERRIDE`, `actor_type=USER`. `triggerRefresh` writes one with `REFRESH_TRIGGERED`, `actor_type=USER`. `initialise` writes one with `INITIALIZED`, `actor_type=USER`. The deferred `applyDeltas` (when implemented) writes `AI_DELTA_APPLIED`, `actor_type=AI`.
- [ ] **Version snapshot written for every write**: every successful update writes one row to `preference_taste_profile_versions` with the full document snapshot. The MANUAL override uses `trigger=MANUAL`; future AI delta uses `trigger=BATCH` or `WEEKLY`.
- [ ] GET on a user with no profile row → 404 `TasteProfileNotFoundException`.
- [ ] **Initialisation**: the `initialise(userId)` method creates a profile with sensible empty defaults (empty lists, `lastUpdated = today`, `version = 1`, `basedOnFeedbackCount = 0`, all enums at their default values).
- [ ] **Cross-tenant**: user A's session cannot GET user B's taste profile (CurrentUserResolver scopes the read).
- [ ] PUT with `expectedVersion = 0` on a profile at version 5 → 409.
- [ ] PUT with valid document increments `documentVersion` to 6 and the `optimisticVersion` field is bumped.
- [ ] POST `/refresh-now` returns 202 with the current state and publishes `TasteProfileRefreshRequestedEvent`.
- [ ] **`applyDeltas` stub**: calling the stub directly throws `UnsupportedOperationException` with the deferred-ticket reference message. The feedback bridge in `tickets/feedback/01g` is responsible for not calling it until the deferred ticket lands.
- [ ] **Audit log pagination**: `?page=0&size=10` returns up to 10 rows ordered by `occurred_at DESC`.
- [ ] **Versions pagination**: same.
- [ ] **JSONB column constraint**: persisting a `TasteProfileDocument` with `null` for any `@NotNull`-annotated nested field throws a Jakarta validation error at the controller layer (400) and never reaches the DB.
- [ ] **Document with very large `learnedInsights`** (e.g. 21 items): rejected with 400 (`@Size(max=20)`).
- [ ] **Free-text fields with HTML / script**: stored as-is (no XSS sanitisation in this ticket — output-side rendering is the frontend's concern).
- [ ] `TasteProfileChangedEvent` fires exactly once per write, `AFTER_COMMIT` — verified via test listener.
- [ ] `PreferenceChangedEvent(tier=TASTE_PROFILE)` from `lld/preference.md:678` — **NOT** published by 01c. The `TasteProfileChangedEvent` is the more-specific event; the legacy `PreferenceChangedEvent` lives in the hard-constraints flow but isn't extended for taste-profile in this ticket. **Worth user review.**
- [ ] `OpenAPI` contract test: request/response shapes match the spec for the six endpoints.
- [ ] ArchUnit: no class outside `preference..` imports any of the three new repositories.
- [ ] **Token budget check is stubbed**: the entity stores `lastTokenEstimate` but no enforcement runs in 01c (the budget guard is deferred).
- [ ] **Trace ID propagation**: the controller receives `X-Trace-Id` (or generates one) and sets it on the audit-log row and the published event.

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260615150000__preference_create_taste_profile.sql
NEW   src/main/resources/db/migration/V20260615150100__preference_create_taste_profile_versions.sql
NEW   src/main/resources/db/migration/V20260615150200__preference_create_taste_profile_audit.sql

NEW   src/main/java/com/example/mealprep/preference/domain/entity/TasteProfile.java
NEW   src/main/java/com/example/mealprep/preference/domain/entity/TasteProfileVersion.java
NEW   src/main/java/com/example/mealprep/preference/domain/entity/TasteProfileAuditLog.java
NEW   src/main/java/com/example/mealprep/preference/domain/entity/TasteProfileTrigger.java
NEW   src/main/java/com/example/mealprep/preference/domain/entity/TasteVectorStatus.java
NEW   src/main/java/com/example/mealprep/preference/domain/entity/ActorType.java
NEW   src/main/java/com/example/mealprep/preference/domain/entity/TasteProfileChangeType.java
NEW   src/main/java/com/example/mealprep/preference/domain/entity/IngredientPreferenceSource.java
NEW   src/main/java/com/example/mealprep/preference/domain/entity/ExperimentStatus.java
NEW   src/main/java/com/example/mealprep/preference/domain/entity/SkillLevel.java

NEW   src/main/java/com/example/mealprep/preference/domain/document/TasteProfileDocument.java     (top-level + 12 nested records)

NEW   src/main/java/com/example/mealprep/preference/domain/repository/TasteProfileRepository.java
NEW   src/main/java/com/example/mealprep/preference/domain/repository/TasteProfileVersionRepository.java
NEW   src/main/java/com/example/mealprep/preference/domain/repository/TasteProfileAuditLogRepository.java

NEW   src/main/java/com/example/mealprep/preference/domain/service/TasteProfileQueryService.java
NEW   src/main/java/com/example/mealprep/preference/domain/service/TasteProfileUpdateService.java
NEW   src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileServiceImpl.java
NEW   src/main/java/com/example/mealprep/preference/domain/service/internal/TasteProfileDeltaApplier.java   (STUB interface + no-op impl)

NEW   src/main/java/com/example/mealprep/preference/api/controller/TasteProfileController.java
NEW   src/main/java/com/example/mealprep/preference/api/dto/TasteProfileDto.java
NEW   src/main/java/com/example/mealprep/preference/api/dto/TasteProfileVersionDto.java
NEW   src/main/java/com/example/mealprep/preference/api/dto/TasteProfileAuditEntryDto.java
NEW   src/main/java/com/example/mealprep/preference/api/dto/UpdateTasteProfileRequest.java
NEW   src/main/java/com/example/mealprep/preference/api/dto/TriggerTasteProfileRefreshRequest.java
NEW   src/main/java/com/example/mealprep/preference/api/dto/ApplyTasteProfileDeltasRequest.java
NEW   src/main/java/com/example/mealprep/preference/api/dto/TasteProfileDelta.java                       (sealed interface + 8 record permits — Add/Remove/Update/UpdateNotes/PromoteExperiment/DiscardExperiment/Archive/RePromote per lld/preference.md:358-373)
NEW   src/main/java/com/example/mealprep/preference/api/mapper/TasteProfileMapper.java

NEW   src/main/java/com/example/mealprep/preference/event/TasteProfileChangedEvent.java
NEW   src/main/java/com/example/mealprep/preference/event/TasteProfileRefreshRequestedEvent.java

NEW   src/main/java/com/example/mealprep/preference/exception/TasteProfileNotFoundException.java
NEW   src/main/java/com/example/mealprep/preference/exception/InvalidTasteProfileDeltaException.java
NEW   src/main/java/com/example/mealprep/preference/exception/TasteProfileBudgetExceededException.java

MOD   src/main/java/com/example/mealprep/preference/PreferenceModule.java                                  (re-export the new services)
MOD   src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java                                (3 new handlers)
MOD   src/main/resources/openapi/paths/preference.yaml                                                     (6 paths)
MOD   src/main/resources/openapi/schemas/preference.yaml                                                   (6 schemas)

NEW   src/test/java/com/example/mealprep/preference/TasteProfileServiceImplTest.java                       (unit)
NEW   src/test/java/com/example/mealprep/preference/TasteProfileDocumentRoundTripTest.java                 (unit)
NEW   src/test/java/com/example/mealprep/preference/TasteProfileFlowIT.java                                (Testcontainers — full HTTP cycle)
NEW   src/test/java/com/example/mealprep/preference/TasteProfileEventPublicationIT.java                    (verify AFTER_COMMIT for both events)
NEW   src/test/java/com/example/mealprep/preference/testdata/TasteProfileTestData.java                     (builders for the document + entity)
```

Total: ~30 new files + 4 mods. Estimated agent runtime 4-6 hours (dominated by the 12-record document tree + the migrations + the 6 endpoints).

## Dependencies

- **Hard dependency**: `core-01-decision-log` (merged) — `ScopeChangedEvent` base.
- **Hard dependency**: `auth-01a` (merged) — session-cookie auth, `CurrentUserResolver`.
- **Hard dependency**: `preference-01a` (merged) — `PreferenceModule` facade exists; this ticket extends it.
- **Sibling ticket**: `tickets/preference/01d-lifestyle-config-entity.md` — independent table; ships in parallel.
- **Downstream consumer**: `tickets/preference/01e-preference-archive.md` (needs taste profile to exist); `tickets/feedback/01g-destination-bridges.md` (needs `TasteProfileUpdateService.applyDeltas` interface to compile against, even though the stub throws).

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit + contract tests)
- [ ] All edge-case items above ticked
- [ ] `FlywayMigrationIT` validates the schema-to-JPA mapping
- [ ] Manual smoke documented in PR: register user → GET (404) → POST init → GET (200) → PUT update → audit log shows 2 rows (INITIALIZED, MANUAL_OVERRIDE)

## What's NOT in scope

- **`TasteProfileDeltaApplier` real implementation** — stub only; full impl in deferred ticket.
- **`TasteProfileBudgetGuard`** real enforcement — deferred.
- **AI delta task** (`TasteProfileDeltaTask` extending `AiTask`) — owned by the feedback module's pipeline.
- **`taste_vector` pgvector column + HNSW index + async embedding listener** — deferred to vector ticket.
- **Rollback endpoint** (`POST /rollback`) — deferred to follow-up.
- **Frontend onboarding wizard** that seeds the initial profile from quiz data.
- **Per-section feedback counts** (per `lld/preference.md:802` — explicitly deferred).
- **Cross-module re-opt** triggered by `TasteProfileChangedEvent` — planner's concern.

Squash-merge with: `feat(preference): 01c — taste profile entity + service + REST + audit log (Tier B B1.1)`
