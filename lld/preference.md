# Preference Module — LLD

*Implementation specification for the three-tier Preference Model: hard constraints (DB-locked, user-only), taste profile (AI-maintained JSONB), lifestyle config (user-set). Translates [preference-model.md](../design/preference-model.md) into a buildable Spring Boot module.*

## Scope

This document specifies the `preference` module — package layout, JPA entities, Flyway migrations, repositories, service interfaces, DTOs, mappers, REST controllers, validation, events, business-logic flows, transaction boundaries, and the test plan. Conventions defer to [lld/style-guide.md](style-guide.md); this LLD restates a rule only when the module-specific application matters.

The HLD's three tiers map to three independent persistence shapes:

| Tier | Storage | Mutability | Update path |
|---|---|---|---|
| Hard constraints | Relational columns + child tables (`preference_hard_constraints` aggregate) | User-only; never AI | `PreferenceUpdateService.updateHardConstraints` |
| Taste profile | JSONB document on `preference_taste_profile` + version history + unbounded archive | AI-maintained via delta operations from the Feedback module | `PreferenceUpdateService.applyTasteProfileDeltas` |
| Lifestyle config | JSONB document on `preference_lifestyle_config` (read whole, not token-budgeted) | User-only via settings UI | `PreferenceUpdateService.updateLifestyleConfig` |

The shared `HardConstraintFilterService` (per [technical-architecture.md §Hard Constraint Filter](../design/technical-architecture.md#hard-constraint-filter)) lives in this module because it reads from the hard constraints table and is injected by every component that produces food output (planner, optimiser, recipe discovery, grocery).

---

## Package Layout

```
com.example.mealprep.preference/
├── PreferenceModule.java                  facade re-exporting public service interfaces
├── api/
│   ├── controller/                        HardConstraintsController, TasteProfileController,
│   │                                      LifestyleConfigController, ProfileMetadataController
│   ├── dto/                               records (see DTOs section)
│   └── mapper/                            HardConstraintsMapper, TasteProfileMapper,
│                                          LifestyleConfigMapper, ProfileMetadataMapper
├── domain/
│   ├── entity/                            JPA entities (see Entities section)
│   ├── repository/                        Spring Data interfaces — package-private
│   └── service/
│       ├── PreferenceQueryService.java    public interface
│       ├── PreferenceUpdateService.java   public interface
│       ├── HardConstraintFilterService.java public interface (re-exported via PreferenceModule)
│       ├── PreferenceServiceImpl.java     single impl of all three
│       └── internal/
│           ├── TasteProfileDeltaApplier   applies validated deltas to the document
│           ├── TasteProfileBudgetGuard    enforces the ~2500-token budget
│           └── DietaryIdentityResolver    evaluates base + exceptions for the filter
├── event/                                 PreferenceChangedEvent, HardConstraintsChangedEvent
├── exception/                             module-root + per-failure subclasses (see Errors)
├── validation/                            @ValidDietaryIdentity, @ValidNoveltyTolerance + validators
└── config/                                PreferenceJsonConfig — registers the JSONB ObjectMapper module
```

`PreferenceModule.java` carries no business logic — it gives other modules a one-line view of what's exposed.

---

## Database

Migrations live under `src/main/resources/db/migration/` with the project-wide timestamp scheme from [technical-architecture.md §Migrations](../design/technical-architecture.md#migrations):

```
V20260501120000__preference_create_hard_constraints.sql
V20260501120100__preference_create_taste_profile_and_archive.sql
V20260501120200__preference_create_lifestyle_config.sql
V20260501120300__preference_create_profile_metadata.sql
R__preference_seed_allergen_derivatives.sql            (repeatable)
```

### V20260501120000 — Hard constraints

```sql
CREATE TABLE preference_hard_constraints (
    id                       uuid PRIMARY KEY,
    user_id                  uuid NOT NULL UNIQUE,
    allergies                text[] NOT NULL DEFAULT '{}',
    dietary_identity_base    varchar(32) NOT NULL DEFAULT 'omnivore',
    dietary_identity_label   varchar(64),
    medical_diets            text[] NOT NULL DEFAULT '{}',
    version                  bigint NOT NULL DEFAULT 0,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);
-- Hot read: HardConstraintFilterService.check(userId, ...) on every food output.
CREATE UNIQUE INDEX idx_pref_hard_constraints_user ON preference_hard_constraints (user_id);

CREATE TABLE preference_dietary_identity_exceptions (
    id                       uuid PRIMARY KEY,
    hard_constraints_id      uuid NOT NULL REFERENCES preference_hard_constraints(id) ON DELETE CASCADE,
    allows                   varchar(64) NOT NULL,
    frequency                varchar(32),
    context                  varchar(32) NOT NULL DEFAULT 'any'
);
-- Used when the filter widens the base diet during ingredient checks.
CREATE INDEX idx_pref_dietary_exceptions_hc ON preference_dietary_identity_exceptions (hard_constraints_id);

CREATE TABLE preference_hard_intolerances (
    id                       uuid PRIMARY KEY,
    hard_constraints_id      uuid NOT NULL REFERENCES preference_hard_constraints(id) ON DELETE CASCADE,
    substance                varchar(64) NOT NULL,
    severity                 varchar(32) NOT NULL,
    notes                    varchar(255)
);
CREATE INDEX idx_pref_hard_intolerances_hc ON preference_hard_intolerances (hard_constraints_id);

CREATE TABLE preference_age_restrictions (
    id                       uuid PRIMARY KEY,
    hard_constraints_id      uuid NOT NULL REFERENCES preference_hard_constraints(id) ON DELETE CASCADE,
    rule_key                 varchar(64) NOT NULL,
    auto_populated           boolean NOT NULL DEFAULT false
);
CREATE INDEX idx_pref_age_restrictions_hc ON preference_age_restrictions (hard_constraints_id);

CREATE TABLE preference_hard_constraints_audit (
    id                       uuid PRIMARY KEY,
    hard_constraints_id      uuid NOT NULL REFERENCES preference_hard_constraints(id) ON DELETE CASCADE,
    actor_user_id            uuid NOT NULL,
    field_changed            varchar(64) NOT NULL,
    previous_value_json      jsonb NOT NULL,
    new_value_json           jsonb NOT NULL,
    occurred_at              timestamptz NOT NULL
);
-- Audit-log query endpoint and safety reviews.
CREATE INDEX idx_pref_hc_audit_hc_time ON preference_hard_constraints_audit (hard_constraints_id, occurred_at DESC);

CREATE TABLE preference_allergen_derivatives (
    id                       uuid PRIMARY KEY,
    allergen                 varchar(64) NOT NULL,
    derivative               varchar(128) NOT NULL,
    UNIQUE (allergen, derivative)
);
-- HardConstraintFilterService expands a stored allergy to its known derivatives.
CREATE INDEX idx_pref_allergen_derivatives_allergen ON preference_allergen_derivatives (allergen);
```

### V20260501120100 — Taste profile, versions, archive

```sql
CREATE TABLE preference_taste_profile (
    id                       uuid PRIMARY KEY,
    user_id                  uuid NOT NULL UNIQUE,
    document                 jsonb NOT NULL,                  -- shape mirrored by TasteProfileDocument
    document_version         integer NOT NULL DEFAULT 1,      -- monotonic, increments per delta apply
    feedback_cursor          varchar(64),                     -- last incorporated feedback id
    based_on_feedback_count  integer NOT NULL DEFAULT 0,
    last_delta_applied_at    timestamptz,
    last_token_estimate      integer,
    -- Embedding pipeline (locked 2026-05-07): vector populated async after document changes
    taste_vector             vector(1536),                    -- pgvector; OpenAI text-embedding-3-small
    taste_vector_status      varchar(16) NOT NULL DEFAULT 'pending', -- pending | embedded | failed
    taste_vector_doc_version integer,                         -- doc_version this vector was computed from
    taste_vector_model_id    varchar(96),
    taste_vector_embedded_at timestamptz,
    optimistic_version       bigint NOT NULL DEFAULT 0,       -- @Version
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);
CREATE UNIQUE INDEX idx_pref_taste_profile_user ON preference_taste_profile (user_id);
-- HNSW for "recipes nearest to this user's taste" lookups (rare but useful for "recommendations" queries).
CREATE INDEX idx_pref_taste_vector ON preference_taste_profile USING hnsw (taste_vector vector_cosine_ops)
    WHERE taste_vector IS NOT NULL;

CREATE TABLE preference_taste_profile_versions (
    id                       uuid PRIMARY KEY,
    taste_profile_id         uuid NOT NULL REFERENCES preference_taste_profile(id) ON DELETE CASCADE,
    document_version         integer NOT NULL,
    document_snapshot        jsonb NOT NULL,
    feedback_range_start     varchar(64),
    feedback_range_end       varchar(64),
    trigger                  varchar(16) NOT NULL,            -- batch | weekly | manual
    deltas_applied           jsonb NOT NULL,
    model_tier_used          varchar(16) NOT NULL,
    generated_at             timestamptz NOT NULL,
    UNIQUE (taste_profile_id, document_version)
);
-- Version history listing and rollback.
CREATE INDEX idx_pref_tp_versions_tp_ver ON preference_taste_profile_versions (taste_profile_id, document_version DESC);

CREATE TABLE preference_taste_profile_archive (
    id                       uuid PRIMARY KEY,
    user_id                  uuid NOT NULL,
    field_path               varchar(128) NOT NULL,           -- "ingredient_preferences.favourites"
    item_key                 varchar(128) NOT NULL,
    item_payload             jsonb NOT NULL,
    evidence_count           integer NOT NULL DEFAULT 0,
    last_signal_at           date,
    archived_at              timestamptz NOT NULL,
    archived_reason          varchar(32) NOT NULL,            -- low_evidence | stale | token_pressure
    re_promoted_at           timestamptz
);
-- Delta pipeline detects re-emerging preferences (RE-PROMOTE op).
CREATE INDEX idx_pref_archive_user_field_key ON preference_taste_profile_archive (user_id, field_path, item_key);
-- User-facing archive history view.
CREATE INDEX idx_pref_archive_user_archived_at ON preference_taste_profile_archive (user_id, archived_at DESC);
```

The taste profile carries **two version fields**, deliberately. `document_version` is the HLD's monotonic integer (incremented per delta apply, used for history and rollback). `optimistic_version` is JPA's `@Version` for concurrent-write safety. They serve different purposes and must not be merged.

### V20260501120200 — Lifestyle config

The HLD does not specify whether to store lifestyle config columnar or as a document. **Choosing JSONB.** The config has 10+ nested sections (meal_structure, novelty_tolerance, batch_cooking, ...) read whole-document by the planner; columnar storage would mean ~50 columns and a fragile change process. The style guide cautions against JSONB for stable-shape relational data, which this is closer to — **worth user review**, but JSONB matches the read pattern.

```sql
CREATE TABLE preference_lifestyle_config (
    id                       uuid PRIMARY KEY,
    user_id                  uuid NOT NULL UNIQUE,
    document                 jsonb NOT NULL,                  -- shape mirrored by LifestyleConfigDocument
    last_review_prompt_at    timestamptz,                     -- supports the 2-3 month review nudge
    optimistic_version       bigint NOT NULL DEFAULT 0,       -- @Version
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);
CREATE UNIQUE INDEX idx_pref_lifestyle_config_user ON preference_lifestyle_config (user_id);

CREATE TABLE preference_lifestyle_config_audit (
    id                       uuid PRIMARY KEY,
    lifestyle_config_id      uuid NOT NULL REFERENCES preference_lifestyle_config(id) ON DELETE CASCADE,
    actor_user_id            uuid NOT NULL,
    field_path               varchar(128) NOT NULL,           -- e.g. "batch_cooking.prep_days"
    previous_value_json      jsonb NOT NULL,
    new_value_json           jsonb NOT NULL,
    occurred_at              timestamptz NOT NULL
);
CREATE INDEX idx_pref_lc_audit_lc_time ON preference_lifestyle_config_audit (lifestyle_config_id, occurred_at DESC);
```

### V20260501120300 — Profile metadata

```sql
CREATE TABLE preference_profile_metadata (
    id                              uuid PRIMARY KEY,
    user_id                         uuid NOT NULL UNIQUE,
    age                             integer,
    age_group                       varchar(16) NOT NULL,     -- young_child | child | teen | adult
    portion_scale                   numeric(3,2) NOT NULL DEFAULT 1.00,
    preference_volatility           varchar(16) NOT NULL DEFAULT 'normal',
    update_confirmation_threshold   integer NOT NULL DEFAULT 3,
    optimistic_version              bigint NOT NULL DEFAULT 0,
    created_at                      timestamptz NOT NULL,
    updated_at                      timestamptz NOT NULL
);
CREATE UNIQUE INDEX idx_pref_profile_metadata_user ON preference_profile_metadata (user_id);
```

### R__preference_seed_allergen_derivatives.sql

Repeatable migration seeding the v1 starter list (peanuts → peanut oil, peanut butter, satay sauce; tree nuts → almond extract, marzipan; ...). Repeatable so additions don't pollute the version sequence.

---

## Entities

All entities follow the style guide: UUID `@Id` set application-side, `@Version` on every mutable aggregate root, `@CreatedDate`/`@LastModifiedDate` audit columns, Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`. JSONB columns mapped via `@Type(JsonType.class)` from `hypersistence-utils`.

| Entity | Notes |
|---|---|
| `HardConstraints` | Aggregate root. `userId` unique. Owns `@OneToMany` collections (cascade ALL, orphanRemoval) for `DietaryIdentityException`, `HardIntolerance`, `AgeRestriction`. `@Version Long version`. |
| `DietaryIdentityException`, `HardIntolerance`, `AgeRestriction` | Children. `@ManyToOne(fetch = LAZY)` back to parent via `@JoinColumn(name = "hard_constraints_id")`. No `@Version` — parent's version covers the aggregate. |
| `HardConstraintsAuditLog` | Append-only. No `@Version`, no `@LastModifiedDate`. `previousValueJson` / `newValueJson` as `JsonNode` via `@Type(JsonType.class)`. |
| `TasteProfile` | Aggregate root. `document` mapped to `TasteProfileDocument` record-tree via JSONB. Two version fields: `int documentVersion` (HLD monotonic) and `@Version Long optimisticVersion` (JPA concurrency). |
| `TasteProfileVersion` | Append-only. Stores full document snapshot, applied deltas, trigger, model tier. |
| `PreferenceArchiveEntry` | One row per archived item. `archivedReason` enum; `rePromotedAt` non-null after a RE-PROMOTE. |
| `LifestyleConfig` | Aggregate root. `document` mapped to `LifestyleConfigDocument` via JSONB. `lastReviewPromptAt` supports the 2-3 month review nudge. |
| `LifestyleConfigAuditLog` | Append-only, section-level diffs. |
| `ProfileMetadata` | One row per user. Enums for `ageGroup`, `preferenceVolatility`. `BigDecimal portionScale` (precision 3, scale 2). |
| `AllergenDerivative` | Reference data. No `@Version` — refreshed via the repeatable migration. |

Enums local to the module: `DietaryIdentityBase` (`OMNIVORE`, `VEGETARIAN`, `VEGAN`, `PESCATARIAN`, `KETO`, `PALEO`, `OTHER`), `AgeGroup`, `PreferenceVolatility`, `ArchiveReason` (`LOW_EVIDENCE`, `STALE`, `TOKEN_PRESSURE`), `IngredientPreferenceSource` (`FEEDBACK`, `INFERRED`, `ONBOARDING`), `ExperimentStatus` (`TESTING`, `PROMOTED`, `DISCARDED`), `SkillLevel`, `TasteProfileTrigger` (`BATCH`, `WEEKLY`, `MANUAL`).

---

## DTOs

All DTOs are Java records per the style guide. Hard-constraints request/response and the soft-bundle:

```java
public record HardConstraintsDto(
    UUID id, UUID userId,
    List<String> allergies,
    DietaryIdentityDto dietaryIdentity,
    List<String> medicalDiets,
    List<HardIntoleranceDto> intolerances,
    List<AgeRestrictionDto> ageRestrictions,
    long version
) {}

public record DietaryIdentityDto(
    DietaryIdentityBase base,
    String labelForDisplay,
    List<DietaryIdentityExceptionDto> exceptions
) {}

public record DietaryIdentityExceptionDto(String allows, String frequency, String context) {}
public record HardIntoleranceDto(String substance, String severity, String notes) {}
public record AgeRestrictionDto(String ruleKey, boolean autoPopulated) {}

public record UpdateHardConstraintsRequest(
    @NotNull List<@NotBlank String> allergies,
    @NotNull @Valid @ValidDietaryIdentity DietaryIdentityDto dietaryIdentity,
    @NotNull List<@NotBlank String> medicalDiets,
    @NotNull @Valid List<HardIntoleranceDto> intolerances,
    @NotNull @Valid List<AgeRestrictionDto> ageRestrictions,
    long expectedVersion,
    Boolean confirmTier1Removals          // GAP-04: required true to apply a Tier-1 removal; absent ⇒ false
) {}

// GAP-04 rejection payload (carried on the 409 ProblemDetail's removedConstraints[] extension).
public record RemovedTier1Constraint(Tier1Category category, String value) {}
public enum Tier1Category { ALLERGY, MEDICAL_DIET, SEVERE_INTOLERANCE, DIETARY_IDENTITY_BASE }

public record SoftPreferenceBundleDto(
    UUID userId,
    TasteProfileDto tasteProfile,
    LifestyleConfigDto lifestyleConfig,
    ProfileMetadataDto profileMetadata
) {}
```

### Taste profile document

`TasteProfileDocument` is the canonical Java mirror of the JSONB shape. The application owns this schema — the AI never produces it whole; it produces deltas applied to this structure. One nested record per HLD section (`SoftConstraints`, `FlavourPreferences`, `TexturePreferences`, `IngredientPreferences`, `CuisinePreferences`, `CookingPreferences`, `PortionStyle`, `HouseholdContext`, `RecipeRecommendation`, `ActiveExperiment`). Leaf types are primitives, enums, or `List<String>` — never raw `JsonNode` except in delta payloads.

```java
public record TasteProfileDocument(
    LocalDate lastUpdated, int version,
    int basedOnFeedbackCount, String feedbackCursor,
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
) {
    public record IngredientPreferences(
        List<IngredientPreference> favourites,
        List<IngredientPreference> disliked,
        List<TrendingIngredient> trendingPositive,
        List<TrendingIngredient> trendingNegative
    ) {}
    public record IngredientPreference(
        String item, int evidenceCount, LocalDate lastSignal,
        IngredientPreferenceSource source
    ) {}
    // ... other nested records mirror the HLD shape one-for-one
}

public record TasteProfileDto(
    UUID id, UUID userId,
    TasteProfileDocument document,
    int documentVersion, String feedbackCursor,
    int basedOnFeedbackCount, Instant lastDeltaAppliedAt,
    Integer lastTokenEstimate, long optimisticVersion
) {}
```

### Delta operations

Sealed interface — each op is one record, giving compile-time exhaustiveness in `switch` and Jackson polymorphic deserialisation via `@JsonTypeInfo(use = NAME, property = "op")`.

```java
public sealed interface TasteProfileDelta permits
    Add, Remove, Update, UpdateNotes,
    PromoteExperiment, DiscardExperiment, Archive, RePromote {

    String fieldPath();

    record Add(String fieldPath, JsonNode item)                                   implements TasteProfileDelta {}
    record Remove(String fieldPath, String itemKey)                               implements TasteProfileDelta {}
    record Update(String fieldPath, String itemKey, JsonNode patch)               implements TasteProfileDelta {}
    record UpdateNotes(String fieldPath, String notes)                            implements TasteProfileDelta {}
    record PromoteExperiment(String hypothesis,
                             String targetFieldPath, JsonNode promotedItem)       implements TasteProfileDelta { ... }
    record DiscardExperiment(String hypothesis)                                   implements TasteProfileDelta { ... }
    record Archive(String fieldPath, String itemKey, ArchiveReason reason)        implements TasteProfileDelta {}
    record RePromote(String fieldPath, String itemKey)                            implements TasteProfileDelta {}
}

public record ApplyTasteProfileDeltasRequest(
    @NotNull @Size(max = 50) List<@Valid TasteProfileDelta> deltas,
    @NotNull TasteProfileTrigger trigger,
    String feedbackRangeStart, String feedbackRangeEnd,
    AiModelTier modelTierUsed
) {}
```

### Lifestyle config

`LifestyleConfigDocument` mirrors the HLD's nested JSON one-for-one — one record per section (`MealStructure`, `MealTiming`, `NoveltyTolerance`, `CookingContexts`, `BatchCooking`, `ReheatingPreferences`, `EatingContext`, `SeasonalPreferences`, `MealTypePreferences`, `Accompaniments`, `GroceryQualityPreferences`, `PantryTracking`). Fields kept structured per HLD; free-text `notes` retained where the HLD lists them (flavour, cuisine, meal_timing) for AI context.

The `PantryTracking` section is a project-wide feature flag exposed through this module:

```java
public record PantryTracking(
    boolean enabled                            // false → provisions inventory ignored project-wide for this user
) {}
```

When `enabled = false`: the provisions module's inventory CRUD endpoints still work (the user might re-enable later), but the planner reads no inventory and weights its `provisions` sub-score neutrally; the notification module skips expiry/staple alerts; auto-deduct on cook is a no-op. Default `true`. Read by every module that consults pantry state via `PreferenceQueryService.getLifestyleConfig(userId).document().pantryTracking().enabled()`. **Per-item disable is not modelled in v1** — flag-level on/off only. Per-item opt-out is a documented future enhancement.

```java
public record LifestyleConfigDto(
    UUID id, UUID userId,
    LifestyleConfigDocument document,
    Instant lastReviewPromptAt,
    long optimisticVersion
) {}

public record UpdateLifestyleConfigRequest(
    @NotNull @Valid LifestyleConfigDocument document,
    long expectedVersion
) {}
```

### Filter results

```java
public record FilterResultDto(boolean safe, List<FilterFlagDto> flags) {}

public record FilterFlagDto(
    FilterFlagType type,             // ALLERGY | DIETARY_IDENTITY | INTOLERANCE | AGE_RESTRICTION | AMBIGUOUS
    String offendingItem,
    String matchedRule,
    String message
) {}
```

---

## Mappers

MapStruct interfaces, `@Mapper(componentModel = "spring")`. One per entity-DTO pair. Custom mappings declared explicitly only where field names diverge.

```java
@Mapper(componentModel = "spring", uses = { DietaryIdentityMapper.class })
public interface HardConstraintsMapper {
    HardConstraintsDto toDto(HardConstraints entity);
    List<HardConstraintsDto> toDtos(List<HardConstraints> entities);
}

@Mapper(componentModel = "spring")
public interface TasteProfileMapper {
    TasteProfileDto toDto(TasteProfile entity);
    List<TasteProfileDto> toDtos(List<TasteProfile> entities);
    TasteProfileVersionDto toVersionDto(TasteProfileVersion entity);
}

@Mapper(componentModel = "spring")
public interface LifestyleConfigMapper {
    LifestyleConfigDto toDto(LifestyleConfig entity);
    List<LifestyleConfigDto> toDtos(List<LifestyleConfig> entities);
}

@Mapper(componentModel = "spring")
public interface ProfileMetadataMapper {
    ProfileMetadataDto toDto(ProfileMetadata entity);
}
```

---

## Repositories

Package-private interfaces (no `public`); cross-module access goes through service interfaces only.

```java
interface HardConstraintsRepository extends JpaRepository<HardConstraints, UUID> {
    Optional<HardConstraints> findByUserId(UUID userId);

    @EntityGraph(attributePaths = {"exceptions", "intolerances", "ageRestrictions"})
    Optional<HardConstraints> findWithChildrenByUserId(UUID userId);

    @EntityGraph(attributePaths = {"exceptions", "intolerances", "ageRestrictions"})
    List<HardConstraints> findWithChildrenByUserIdIn(List<UUID> userIds);
}

interface HardConstraintsAuditLogRepository extends JpaRepository<HardConstraintsAuditLog, UUID> {
    Page<HardConstraintsAuditLog> findByHardConstraintsIdOrderByOccurredAtDesc(UUID id, Pageable p);
}

interface TasteProfileRepository extends JpaRepository<TasteProfile, UUID> {
    Optional<TasteProfile> findByUserId(UUID userId);
    List<TasteProfile> findByUserIdIn(List<UUID> userIds);
}

interface TasteProfileVersionRepository extends JpaRepository<TasteProfileVersion, UUID> {
    Page<TasteProfileVersion> findByTasteProfileIdOrderByDocumentVersionDesc(UUID id, Pageable p);
    Optional<TasteProfileVersion> findByTasteProfileIdAndDocumentVersion(UUID id, int v);
}

interface PreferenceArchiveRepository extends JpaRepository<PreferenceArchiveEntry, UUID> {
    Optional<PreferenceArchiveEntry> findByUserIdAndFieldPathAndItemKey(UUID u, String f, String k);
    List<PreferenceArchiveEntry> findAllByUserId(UUID userId);
    Page<PreferenceArchiveEntry> findByUserIdOrderByArchivedAtDesc(UUID userId, Pageable p);
}

interface LifestyleConfigRepository extends JpaRepository<LifestyleConfig, UUID> {
    Optional<LifestyleConfig> findByUserId(UUID userId);
    List<LifestyleConfig> findByUserIdIn(List<UUID> userIds);
}

interface ProfileMetadataRepository extends JpaRepository<ProfileMetadata, UUID> {
    Optional<ProfileMetadata> findByUserId(UUID userId);
    List<ProfileMetadata> findByUserIdIn(List<UUID> userIds);
}

interface AllergenDerivativeRepository extends JpaRepository<AllergenDerivative, UUID> {
    @Query("select ad.derivative from AllergenDerivative ad where ad.allergen in :allergens")
    Set<String> findDerivativesForAllergens(@Param("allergens") Collection<String> allergens);
}
```

`@EntityGraph` on the hot-read query keeps the filter call to a single JOIN — no N+1.

---

## Service Interfaces

Per the style guide, both module interfaces are implemented by a single `PreferenceServiceImpl`. `HardConstraintFilterService` is a separate interface so cross-module callers (planner, optimiser, recipe discovery, grocery) inject only the filter — narrower API, narrower coupling.

### `PreferenceQueryService`

```java
public interface PreferenceQueryService {

    // Hard constraints
    Optional<HardConstraintsDto> getHardConstraints(UUID userId);
    List<HardConstraintsDto> getHardConstraintsByUserIds(List<UUID> userIds);
    Page<HardConstraintsAuditEntryDto> getHardConstraintsAuditLog(UUID userId, Pageable pageable);

    // Taste profile
    Optional<TasteProfileDto> getTasteProfile(UUID userId);
    List<TasteProfileDto> getTasteProfilesByUserIds(List<UUID> userIds);
    Page<TasteProfileVersionDto> getTasteProfileVersions(UUID userId, Pageable pageable);
    Optional<TasteProfileVersionDto> getTasteProfileVersion(UUID userId, int documentVersion);
    Page<PreferenceArchiveEntryDto> getPreferenceArchive(UUID userId, Pageable pageable);

    // Loaded by the feedback module's delta-update task so the AI can detect re-emerging preferences.
    List<PreferenceArchiveEntryDto> getFullPreferenceArchive(UUID userId);

    // Lifestyle config
    Optional<LifestyleConfigDto> getLifestyleConfig(UUID userId);
    List<LifestyleConfigDto> getLifestyleConfigsByUserIds(List<UUID> userIds);

    // Profile metadata
    Optional<ProfileMetadataDto> getProfileMetadata(UUID userId);
    List<ProfileMetadataDto> getProfileMetadataByUserIds(List<UUID> userIds);

    // Bundle for the planner / optimiser. Hard constraints intentionally NOT bundled — the
    // safety-critical read path stays explicit so a stale-soft-data cache cannot leak into it.
    Optional<SoftPreferenceBundleDto> getSoftPreferences(UUID userId);
    List<SoftPreferenceBundleDto> getSoftPreferencesByUserIds(List<UUID> userIds);
}
```

`SoftPreferenceBundleDto` is added to satisfy the planner and optimiser's read pattern in one round trip. The HLD does not specify this aggregation; **worth user review.**

### `PreferenceUpdateService`

```java
public interface PreferenceUpdateService {

    // Hard constraints — user-only, audited, never AI.
    HardConstraintsDto initialiseHardConstraints(UUID userId, UpdateHardConstraintsRequest request);
    HardConstraintsDto updateHardConstraints(UUID userId, UpdateHardConstraintsRequest request, UUID actorUserId);

    // Taste profile. Called by the feedback module AFTER its AI task has produced and parsed deltas.
    // This method does NOT call the AI; it applies pre-validated deltas to the document.
    TasteProfileDto applyTasteProfileDeltas(UUID userId, ApplyTasteProfileDeltasRequest request);

    // Manual user override. Flagged in the version log so the AI doesn't re-learn from old data.
    TasteProfileDto applyManualTasteProfileOverride(UUID userId, ApplyTasteProfileDeltasRequest req, UUID actorUserId);

    // Reverts to a prior document_version. Replaying feedback from that cursor forward is the
    // feedback module's responsibility (delegated via FeedbackReplayService).
    TasteProfileDto rollbackTasteProfile(UUID userId, int targetDocumentVersion, UUID actorUserId);

    // Lifestyle config — user-only.
    LifestyleConfigDto initialiseLifestyleConfig(UUID userId, UpdateLifestyleConfigRequest request);
    LifestyleConfigDto updateLifestyleConfig(UUID userId, UpdateLifestyleConfigRequest request, UUID actorUserId);
    LifestyleConfigDto markLifestyleConfigReviewed(UUID userId);   // resets the review nudge

    // Profile metadata.
    ProfileMetadataDto upsertProfileMetadata(UUID userId, ProfileMetadataDto metadata);
}
```

`actorUserId` threads who-performed-the-change for the audit log — equals `userId` for self-edits, may differ once household-admin edits arrive.

### `HardConstraintFilterService`

```java
public interface HardConstraintFilterService {
    FilterResultDto check(UUID userId, List<String> ingredientMappingKeys);
    FilterResultDto checkRecipe(UUID userId, UUID recipeId, List<String> recipeIngredientMappingKeys);
    List<UUID> filterRecipes(UUID userId, Map<UUID, List<String>> recipesIngredientKeys);
    FilterResultDto checkForHousehold(List<UUID> userIds, List<String> ingredientMappingKeys);
}
```

The technical-architecture HLD shows `checkRecipe(userId, recipeId)` — i.e. the filter loads the recipe itself. The LLD changes this to require the caller to pass the ingredient keys. **Reasoning:** the filter must not depend on `RecipeQueryService` because every food-output module (including recipe) injects the filter — that creates a cycle. Callers already have the ingredient list in hand at the moment they call. The household variant is implied by the HLD's union-of-eaters rule but not given an explicit signature; added here because the planner needs it for shared meal slots. **Worth user review.**

---

## REST Controllers

All endpoints under `/api/v1/preferences/...`. `userId` resolved server-side from auth context per [technical-architecture.md §Frontend-Backend Contract](../design/technical-architecture.md#frontend-backend-contract). OpenAPI: `@Tag(name = "Preferences")` on each controller, `@Operation` on each handler.

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET    | `/api/v1/preferences/hard-constraints` | — | `HardConstraintsDto` | 200 / 404 |
| PUT    | `/api/v1/preferences/hard-constraints` | `UpdateHardConstraintsRequest` | `HardConstraintsDto` | 200 / 400 / 404 / 409 |
| GET    | `/api/v1/preferences/hard-constraints/audit-log?page=&size=` | — | `Page<HardConstraintsAuditEntryDto>` | 200 |
| GET    | `/api/v1/preferences/taste-profile` | — | `TasteProfileDto` | 200 / 404 |
| GET    | `/api/v1/preferences/taste-profile/versions?page=&size=` | — | `Page<TasteProfileVersionDto>` | 200 |
| GET    | `/api/v1/preferences/taste-profile/versions/{documentVersion}` | — | `TasteProfileVersionDto` | 200 / 404 |
| POST   | `/api/v1/preferences/taste-profile/rollback` | `{ targetDocumentVersion }` | `TasteProfileDto` | 200 / 404 / 409 |
| POST   | `/api/v1/preferences/taste-profile/manual-overrides` | `ApplyTasteProfileDeltasRequest` | `TasteProfileDto` | 200 / 400 / 422 |
| GET    | `/api/v1/preferences/taste-profile/archive?page=&size=` | — | `Page<PreferenceArchiveEntryDto>` | 200 |
| GET    | `/api/v1/preferences/lifestyle-config` | — | `LifestyleConfigDto` | 200 / 404 |
| PUT    | `/api/v1/preferences/lifestyle-config` | `UpdateLifestyleConfigRequest` | `LifestyleConfigDto` | 200 / 400 / 404 / 409 |
| POST   | `/api/v1/preferences/lifestyle-config/mark-reviewed` | — | `LifestyleConfigDto` | 200 |
| GET    | `/api/v1/preferences/profile-metadata` | — | `ProfileMetadataDto` | 200 / 404 |
| PUT    | `/api/v1/preferences/profile-metadata` | `ProfileMetadataDto` | `ProfileMetadataDto` | 200 / 400 |
| GET    | `/api/v1/preferences/soft-bundle` | — | `SoftPreferenceBundleDto` | 200 / 404 |

`applyTasteProfileDeltas` (the AI-driven path) is **not** exposed via REST — it is invoked in-process by the feedback module via direct service injection. There is no client of this method outside the JVM.

Hard constraints have no POST: they are seeded at user-creation by the auth module via `initialiseHardConstraints`; the public REST surface is read + update only.

### Error responses

All error responses use RFC 9457 `ProblemDetail`. Module-specific exceptions and their mappings (handled in the project-wide `GlobalExceptionHandler`):

| Exception | Status | `type` URI |
|---|---|---|
| `HardConstraintsNotFoundException` | 404 | `https://mealprep.example.com/problems/hard-constraints-not-found` |
| `TasteProfileNotFoundException` | 404 | `https://mealprep.example.com/problems/taste-profile-not-found` |
| `LifestyleConfigNotFoundException` | 404 | `https://mealprep.example.com/problems/lifestyle-config-not-found` |
| `InvalidTasteProfileDeltaException` | 422 | `https://mealprep.example.com/problems/invalid-taste-profile-delta` |
| `TasteProfileBudgetExceededException` | 422 | `https://mealprep.example.com/problems/taste-profile-budget-exceeded` |
| `HardConstraintFilterAmbiguityException` | 422 | `https://mealprep.example.com/problems/hard-constraint-ambiguous` |
| `Tier1RemovalRequiresConfirmationException` (GAP-04) | 409 | `https://mealprep.example.com/problems/tier1-removal-requires-confirmation` — carries `reason=TIER1_REMOVAL_REQUIRES_CONFIRMATION` + `removedConstraints[]` extensions |
| `OptimisticLockException` (JPA) | 409 | `https://mealprep.example.com/problems/optimistic-lock` |
| `MethodArgumentNotValidException` | 400 | `errors[]` extension on ProblemDetail |

Module root: `PreferenceException extends MealPrepException`.

---

## Validation

Standard Jakarta annotations applied at request-record level: `@NotNull`, `@NotBlank`, `@Size`, `@Min`/`@Max` (e.g. `@Min(0)` on `evidenceCount`, `@Max(100)` on pagination size), `@Valid` for nested records, `@Pattern` (e.g. `feedbackCursor` matching `^feedback-\d+$`).

Custom validators in `validation/`:

- **`@ValidDietaryIdentity`** (class-level on `DietaryIdentityDto`) — asserts `base` is a known enum, each `exception.allows` is in a known sub-category list (`fish`, `poultry`, `dairy`, `eggs`, `gluten`, ...), `exception.context` ∈ {`any`, `social`, `weekend`, `weekday`}, and no exception's `allows` collides with a substance the user has listed as an allergy or hard intolerance.
- **`@ValidNoveltyTolerance`** — applied inside `LifestyleConfigDocument.NoveltyTolerance`. Asserts each per-slot `mode` ∈ {`rotation`, `batch_repeat`, `high_variety`, `static`} and that mode-specific fields are coherent (`rotation` requires `rotationSize > 0`; `batch_repeat` requires `maxConsecutiveSame > 0`; etc.). Also asserts `recipeRepeatCooldownWeeks` values ≥ 0.

Validation failures bubble up as `MethodArgumentNotValidException` mapped to 400 ProblemDetails by the global advice.

**Delta validation is service-layer, not Jakarta** — operations cannot be fully validated by annotations because their target paths and item keys must match current document state. Validation happens in `TasteProfileDeltaApplier` and throws `InvalidTasteProfileDeltaException` (422):
- `fieldPath` resolves to a known location in `TasteProfileDocument`
- `Remove` / `Update` target an existing item
- `RePromote` requires a matching archive entry (else fall back to `Add` with a warn-log)
- `PromoteExperiment.hypothesis` matches an active experiment
- Total delta count ≤ 50 per request

---

## Events

### Published

```java
public record HardConstraintsChangedEvent(
    UUID userId, UUID hardConstraintsId,
    Set<String> changedFields,                 // "allergies", "dietary_identity_base", ...
    UUID traceId, Instant occurredAt
) {}

public record PreferenceChangedEvent(
    UUID userId, PreferenceTier tier,          // TASTE_PROFILE | LIFESTYLE_CONFIG
    int documentVersion,                       // taste profile version, or 0 for lifestyle
    UUID traceId, Instant occurredAt
) {}

public enum PreferenceTier { HARD_CONSTRAINTS, TASTE_PROFILE, LIFESTYLE_CONFIG }
```

`HardConstraintsChangedEvent` is split from `PreferenceChangedEvent` so the planner can react more aggressively to safety-critical changes (cf. [meal-planner.md](../design/meal-planner.md): any hard-constraint change auto-triggers re-opt). The technical-architecture event catalogue lists only `PreferenceChangedEvent` — adding a sibling here. **Worth user review.**

Published via the standard `ApplicationEventPublisher` after the relevant write transaction. Listeners use `@TransactionalEventListener(phase = AFTER_COMMIT)` per the style guide.

### Consumed

None. The preference module **does not consume** `FeedbackProcessedEvent`. **Locked decision (2026-05-07).** The feedback module owns its own processing-count tracking; the preference module's only inbound surface for taste-profile updates is the direct service call `applyTasteProfileDeltas` from the feedback module's update pipeline. Eliminating the cross-module event listener removes a duplicate-state risk and a code path that was only providing observability the feedback module already has.

---

## Business Logic Flows

### Flow 1: Hard constraint update

`PUT /api/v1/preferences/hard-constraints` → `updateHardConstraints(userId, request, actorUserId)`. Service impl method is `@Transactional`. Loads existing aggregate by `userId` (404 if missing — onboarding uses `initialise...`). `expectedVersion` mismatch → `OptimisticLockException` → 409.

**Tier-1 removal safety gate (GAP-04).** After the lock pre-check and **before any mutation**, `Tier1RemovalDetector.detectRemovals(stored, request)` diffs the stored aggregate against the request to find any *removed* Tier-1 constraint: an allergy dropped from `allergies`, a medical diet dropped from `medical_diets`, a severe-intolerance *substance* dropped from `intolerances_hard`, or a **relaxation** of the dietary-identity `base` (the new base excludes a strict subset of the stored base's excluded foods — e.g. `vegan→vegetarian`; tightening like `omnivore→vegetarian` and lateral switches like `vegetarian→keto` are not gated). If ≥1 such removal is detected **and** `confirmTier1Removals` is not `true`, the method throws `Tier1RemovalRequiresConfirmationException` → **409** with `reason=TIER1_REMOVAL_REQUIRES_CONFIRMATION` and the `removedConstraints[]` the UI names in the confirmation interstitial — no audit row, no event, no version bump. The client re-submits the same payload with `confirmTier1Removals=true` to proceed. Additions, reorderings, label-only edits, and non-Tier-1 edits return an empty removal set, so they apply one-step unchanged. Age restrictions are not gated (auto-managed for child profiles). The detector is a pure function (no I/O) so the gate is unit-tested in isolation; the in-process directive-apply path (`PreferenceDirectiveApplyTarget`) passes `confirmTier1Removals=true` as an authoritative system actor (and only adds an intolerance anyway).

Once the gate passes, it diffs each field, replaces scalars and child collections (cascade orphanRemoval handles child churn). Writes one `HardConstraintsAuditLog` row per changed field. Publishes `HardConstraintsChangedEvent` after commit. Returns the updated DTO.

### Flow 2: Hard-filter check (the deterministic safety net)

The single most safety-critical path. **Code, not AI** per [technical-architecture.md §Hard Constraint Filter](../design/technical-architecture.md#hard-constraint-filter). Method is `@Transactional(readOnly = true)`.

1. Caller invokes `check(userId, ingredientMappingKeys)` or one of the batch siblings.
2. Loads `HardConstraints` via `findWithChildrenByUserId` (single JOIN, no N+1) and `ProfileMetadata` for age-restriction context.
3. **Allergy check.** For each ingredient key, asserts `key ∉ allergies ∪ derivativesOf(allergies)`. Derivative set fetched once per call. Match is exact-string against pre-normalised keys (caller responsibility — keys come pre-normalised per [technical-architecture.md `ingredient_mapping_key`](../design/technical-architecture.md#cross-module-references)).
4. **Severe intolerance check.** Identical to allergy check.
5. **Dietary identity check.** `DietaryIdentityResolver` evaluates `base` ∪ matching exceptions. Conditional exceptions widen `base` only when their `context` matches the call context; **`frequency` is not evaluated at filter time** (the filter has no week-view) — frequency is enforced by the planner's scoring.
6. **Age restriction check.** Each `ageRestriction.ruleKey` runs against the ingredient list via a static rule registry (e.g. `no_whole_nuts` rejects ingredients tagged `whole_nut`).
7. **Ambiguity flagging.** When the filter cannot decisively pass or fail (e.g. milk allergy with lactose-free exception, ingredient is "yoghurt" with no lactose-free tag), returns `safe = false` with an `AMBIGUOUS` flag rather than silently passing. The HLD calls this out as the safer of the two approaches; the LLD makes it default.
8. Returns `FilterResultDto`. Caller is responsible for blocking.

Every invocation is logged at INFO with `userId`, `traceId`, ingredient count, result summary — required by [technical-architecture.md §Observability](../design/technical-architecture.md#observability) for the safety audit trail. Full ingredient lists at DEBUG only (PII rule).

### Flow 3: Taste profile delta application

The AI prompt itself is **out of scope for this LLD** (deferred — see Out of Scope). This flow is what happens after the AI has produced deltas and the feedback module has called us.

1. Feedback module calls `applyTasteProfileDeltas(userId, request)` from within its delta-update transaction. Annotation: `@Transactional` (default REQUIRED — joins the caller's tx). Joining is intentional: the two operations are logically one feedback-processing unit; rolling back the whole thing on failure is the right behaviour.
2. Loads the user's `TasteProfile` (404 if missing — initialised at onboarding).
3. `TasteProfileDeltaApplier.validate` walks the delta list — schema-correct (Jakarta already passed), field paths resolve, items exist where required, RePromote target found in archive (else fall back to Add with warn-log), PromoteExperiment hypothesis matches an active experiment, total ≤ 50. Any failure → `InvalidTasteProfileDeltaException` (422). Whole batch rejected; no partial application.
4. `TasteProfileDeltaApplier.apply` mutates the document in op order. Each op produces a new `TasteProfileDocument` (immutable record, copy-and-replace at each path). Archived items simultaneously written to `preference_taste_profile_archive`.
5. `TasteProfileBudgetGuard.estimate` runs on the new document. If estimate > 2500 tokens, throws `TasteProfileBudgetExceededException` — the AI was supposed to propose archives first; this is a guardrail. Feedback module retries with a corrective prompt (its concern).
6. Writes the document back to `TasteProfile`, increments `documentVersion`, updates `feedbackCursor`, `basedOnFeedbackCount`, `lastDeltaAppliedAt`, `lastTokenEstimate`.
7. Persists a `TasteProfileVersion` snapshot (deltas applied, trigger, model tier, feedback range). JPA increments parent's `optimisticVersion`.
8. **Anomaly detection.** If more than 3 archive/remove ops in this batch, logs WARN per HLD §Versioning. The feedback module surfaces this to the user; the preference module's role is to log and allow.
9. Publishes `PreferenceChangedEvent(tier=TASTE_PROFILE)` after commit. Returns the updated DTO.
10. **Trigger taste-vector re-embed.** A `@Async` listener on `PreferenceChangedEvent(tier=TASTE_PROFILE)` debounces and re-embeds: composes input text from the document's structured fields + free-text notes, calls `aiService.embed(new TasteProfileEmbeddingTask(profileId, inputText))`, writes the result back via `PreferenceUpdateService.storeTasteVector(profileId, vector, modelId, docVersion)`. Debounce window: 5 minutes — coalesces rapid delta-apply bursts. On `AiUnavailable`: status flips to `pending` and the next change retries; the planner falls back to neutral 0.5 in `PreferenceSubScore` if no vector is available yet. Cold start: a newly-created taste profile has `taste_vector_status = 'pending'` until the first delta-apply or onboarding-derived embedding lands.

### Flow 4: Lifestyle config update

`PUT /api/v1/preferences/lifestyle-config`. Method is `@Transactional`. Loads existing config. Stale `expectedVersion` → 409. Validates the document (Jakarta + custom `@ValidNoveltyTolerance`). Section-level diff: walks top-level fields and writes one `LifestyleConfigAuditLog` row per changed section. Replaces `document`, persists. Publishes `PreferenceChangedEvent(tier=LIFESTYLE_CONFIG)`.

### Flow 5: Soft preferences exposed to the planner

The planner injects `PreferenceQueryService` and calls `getSoftPreferences(userId)` once per planning run, before composition — one round trip returns taste profile, lifestyle config, and profile metadata. The planner does **not** receive hard constraints in this bundle; it calls `getHardConstraints` (or, more commonly, invokes `HardConstraintFilterService` directly during composition). This split is deliberate: soft data is amenable to short-lived caching at the planner side; hard data must always be fresh because the user may have just edited their allergies.

For shared meals, the planner fetches per-eater bundles via `getSoftPreferencesByUserIds` (one batch round trip) and the household module's merge logic combines them — the preference module exposes per-user data, not merged data.

---

## Concurrency and Transactions

| Concern | Decision |
|---|---|
| `@Transactional` placement | All service-impl methods. Repositories never. |
| Read-method propagation | `@Transactional(readOnly = true)`. |
| Write-method propagation | Default REQUIRED. `applyTasteProfileDeltas` joins the caller's transaction (feedback module). All other writes are top-level. |
| Optimistic locking | `@Version` on `HardConstraints`, `TasteProfile.optimisticVersion`, `LifestyleConfig.optimisticVersion`, `ProfileMetadata.optimisticVersion`. Children inherit via the parent aggregate. Reference data (`AllergenDerivative`) and append-only logs (`HardConstraintsAuditLog`, `TasteProfileVersion`, `LifestyleConfigAuditLog`) — no `@Version`. |
| Pessimistic locking | None. No business case here per the style guide. |
| Document mutation | `TasteProfileDocument` and `LifestyleConfigDocument` are immutable records. Updates produce new instances; JPA's dirty-check sees the reference change and writes the new JSONB. |
| Single-flight | Not required. Concurrent same-user writes resolve via `@Version` — second writer gets 409 and retries upstream. |

---

## Test Plan

Unit tests use `@ExtendWith(MockitoExtension.class)`. Integration tests are `*IT.java` with Testcontainers Postgres. Names follow `methodName_scenario_expected`. Test code itself is out of scope here; the list below is the spec.

### Unit

| Class | Verifies |
|---|---|
| `PreferenceServiceImplTest` | All `PreferenceQueryService` and `PreferenceUpdateService` happy paths and error mappings, with mocked repositories and mocked `TasteProfileDeltaApplier` / `TasteProfileBudgetGuard`. |
| `HardConstraintFilterServiceTest` | Allergy match, derivative match, dietary base + exception evaluation per context, age restriction rule lookup, ambiguity flagging. Confirms frequency is **not** evaluated at filter time. |
| `DietaryIdentityResolverTest` | Pure logic. Covers omnivore (no-op), vegetarian + fish exception in social context, keto + weekend exception, vegan with no exceptions. Property-style: every (base, exceptions, ingredient, context) tuple resolves to allow/deny without throwing. |
| `TasteProfileDeltaApplierTest` | Each delta op produces the expected document mutation. Validation failures (unknown path, missing item, malformed PromoteExperiment) throw `InvalidTasteProfileDeltaException`. Order-sensitive cases (Add then Remove same item; Remove then Add into a uniqueness-constrained section). |
| `TasteProfileBudgetGuardTest` | Token estimation produces deterministic counts on canonical fixtures. Adding a single ingredient bumps the estimate predictably. Three sample documents (small, medium, near-budget). |
| `DietaryIdentityValidatorTest` | Custom validator accepts valid identities; rejects collisions with allergy list and unknown sub-categories. |
| `NoveltyToleranceValidatorTest` | Mode-specific field coherence per mode. |
| `HardConstraintsMapperTest`, `TasteProfileMapperTest`, `LifestyleConfigMapperTest` | MapStruct round-trips preserve all fields, including child collections / nested document trees. |

### Integration

| Class | Verifies |
|---|---|
| `HardConstraintsControllerIT` | Full HTTP cycle over MockMvc: GET (200/404), PUT (200/400/409 on stale version), audit log pagination, ProblemDetail shape on errors. Verifies `HardConstraintsChangedEvent` is published exactly once after commit. |
| `TasteProfileControllerIT` | GET, version listing, version retrieval (200/404), rollback (success and 409 on stale state), manual override happy + 422 on invalid delta, archive listing. |
| `LifestyleConfigControllerIT` | GET / PUT happy paths, validation rejection (invalid novelty mode), 409 on stale version, mark-reviewed updates `lastReviewPromptAt`. |
| `ProfileMetadataControllerIT` | GET / PUT happy paths, age-group enum acceptance, portion scale numeric range. |
| `PreferenceServiceIT` | Service-layer end-to-end against real DB: delta-apply increments `documentVersion` and writes a `TasteProfileVersion`. Verifies `@EntityGraph` keeps the hot-read to one statement (Hibernate statistics). Audit log writes one row per changed field. |
| `HardConstraintFilterServiceIT` | Real DB: peanut allergy catches "peanut oil" via seeded derivatives; vegetarian + fish-on-weekends allows fish in `weekend` context, rejects in `weekday`; coeliac promotes gluten to allergy-equivalent; ambiguous case (lactose-free yoghurt in milk allergy with lactose-free exception) returns `AMBIGUOUS` flag; household variant returns the most restrictive union. |
| `FlywayMigrationIT` | Boots Postgres, runs all preference migrations, validates schema matches the JPA mapping (`spring.jpa.hibernate.ddl-auto=validate`). Catches drift early. |
| `TasteProfileBudgetIT` | Synthetic deltas pushing the document above 2500 tokens cause `TasteProfileBudgetExceededException` and the prior version is preserved (no partial state). |
| `EventPublicationIT` | Updates publish events only after commit. A failing test-scoped listener does not roll back the underlying state (proves `AFTER_COMMIT` semantics). |

---

## Out of Scope

Deferred deliberately — these belong elsewhere or to a later phase:

- **The AI prompt for taste-profile delta updates.** The prompt text, system message, and tool-use schema for the delta operation belong to the feedback module's `TasteProfileDeltaTask` (an `AiTask` per [technical-architecture.md §AI Service](../design/technical-architecture.md#ai-service-architecture)) and to the prompt-engineering work that follows. This LLD specifies only how validated deltas are received and applied.
- **Frontend / UI / API consumer concerns.** The "Here's what I think you like" view, lifestyle settings UI, hard-constraints editor — Figma phase, then frontend LLD.
- **Cross-module orchestration.** Re-optimisation triggered by `PreferenceChangedEvent` is the planner's concern. The Optimiser's response to data-model changes is the optimiser module's concern. This LLD specifies what we publish, not what listeners do with it.
- **Authentication.** Owned by the auth module — `userId` resolution from session/token, password hashing, household-admin role checks.
- **Household merging logic.** The preference module exposes per-user data and a per-household filter check; the merge of soft preferences for shared meals (mean of taste vectors, weighted by per-person priority) belongs to the household LLD.
- **Onboarding wizard flow.** Progressive-disclosure UX is product/UX. The preference module exposes the underlying init endpoints (`initialiseHardConstraints`, `initialiseLifestyleConfig`); the wizard composes them.
- **Sensible defaults for unconfigured lifestyle fields.** The HLD says these will be defined "once all three data model designs are complete" — not specified here.
- **Per-section feedback counts on the taste profile.** Reviewed and deferred per [preference-model-review-notes.md §A4](../design/preference-model-review-notes.md). Revisit if confidence weighting becomes a real problem.
- **Weather-reactive preferences, supplement timing, defrost lead-time tolerance, fallback meals, activity-adjusted preferences.** Open questions in the HLD; revisit when the relevant adjacent designs land.
- **Specific scoring formulas** for how the planner weights soft preferences against nutrition and provisions — owned by the planner LLD.
