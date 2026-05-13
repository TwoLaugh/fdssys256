# Ticket: feedback — 01a Entities, Migrations, Repositories + Enums + Mappers + Stable DTOs

## Summary

Foundation slice for the `feedback` module: the four JPA entities (`FeedbackEntry` aggregate root, `RoutingLogEntry`, `MisclassificationCorrection`, `ClarificationQuery`), their Flyway migrations, package-private Spring Data repositories, MapStruct mappers, the seven module-local enums, the stable read-side DTOs, the module-root exception hierarchy, and the `FeedbackBoundaryTest` ArchUnit gate. Per [`lld/feedback.md §Database` lines 58-201](../../lld/feedback.md), [`lld/feedback.md §Entities` lines 203-225](../../lld/feedback.md), [`lld/feedback.md §DTOs` lines 229-364](../../lld/feedback.md), [`lld/feedback.md §Mappers` lines 368-398](../../lld/feedback.md), [`lld/feedback.md §Repositories` lines 401-456](../../lld/feedback.md).

**Defers** (this LLD is 951 lines covering submission + classification + 4-way routing + clarification + correction + sweeps; only the schema + shape layer lands here):

- `FeedbackQueryService` / `FeedbackUpdateService` interfaces + impl + REST controllers + submission flow → **feedback-01b**
- `FeedbackClassificationTask` AiTask + `FeedbackClassifier` + `ConfidenceGate` + Flow 2 (async classification) + graceful-degrade → **feedback-01c**
- `FeedbackRouter` + `DestinationDispatcher` SPI + four destination dispatchers + Flow 3 (multi-destination split) → **feedback-01d**
- `ClarificationQueryController` + answer flow + 7-day TTL sweep + re-fire → **feedback-01e**
- Misclassification correction + replay flow + destination revert SPIs → **feedback-01f**
- Scheduled sweeps (`retryStuckClassifications`, `expireOldClarificationQueries`, transient-failure retry) + cross-module ITs + quality-monitoring rollup repo methods → **feedback-01g**

01a unblocks every downstream ticket: 01b reads/writes `FeedbackEntry`; 01c reads it and writes `RoutingLogEntry`; 01d reads classifications and writes the log; 01e reads `ClarificationQuery`; 01f writes `MisclassificationCorrection`. Without the schema + entity shape in place, none of those can compile.

This is the first feedback ticket — module package is currently empty.

**LLD divergence notes**:

- **`MisclassificationCorrection.replay_status` initial value**: LLD line 161 declares the column `NOT NULL` with allowed values `APPLIED | FAILED | DESTINATION_REJECTED`. LLD line 803 (Flow 4 step 5) says "`replay_status` initially `PENDING_REPLAY`" — but `PENDING_REPLAY` is NOT in the enum. **Resolution**: extend the enum with a fourth value `PENDING_REPLAY` and widen the SQL `CHECK` (or just the varchar — no enum type in DB) to admit it. The column stays `NOT NULL`; the initial insert sets `PENDING_REPLAY`. The replayer (feedback-01f) flips it to `APPLIED` / `FAILED` / `DESTINATION_REJECTED`. **Worth user review** — alternative was to make the column nullable until the replay completes, but that loses the audit invariant that every correction row records its eventual outcome state.
- **`SubmissionStatus` enum**: LLD line 218 lists eight values. **Add** `RECEIVED → CLASSIFYING → CLASSIFIED → CLARIFICATION_PENDING → ROUTED → PARTIALLY_FAILED → FAILED → CORRECTED` exactly as written.
- **`RoutingFailureKind`**: LLD line 221 lists six values (`TRANSIENT | DESTINATION_VALIDATION | DESTINATION_BUSINESS | AI_UNAVAILABLE | UNKNOWN`). The `feedback_routing_log` migration in LLD line 121 says the DB column lists only four (`TRANSIENT | DESTINATION_VALIDATION | DESTINATION_BUSINESS | UNKNOWN`). **Resolution**: ship the migration with all six values admissible (no DB-level CHECK constraint — Postgres varchar admits any string up to length; the Java enum is the authoritative whitelist). The migration comment lists the six.

## Behavioural spec

### Database schema — 4 migrations

1. The four migrations land in `src/main/resources/db/migration/` per [LLD §Database lines 60-67](../../lld/feedback.md). **Renumber timestamps to the wave-3 feedback timestamp range**: pick `V20260601900000__feedback_create_entries.sql`, `V20260601900100__feedback_create_routing_log.sql`, `V20260601900200__feedback_create_misclassification_corrections.sql`, `V20260601900300__feedback_create_clarification_queries.sql`. **Timestamp `V20260601900xxx`** mirrors the recipe (`V20260601800xxx`) / nutrition (`V20260601600xxx`) wave-2 ranges and reserves room before wave-3 sibling modules.
2. Each table schema mirrors [LLD lines 73-196](../../lld/feedback.md) verbatim for column shape (names + types + nullability + defaults + indexes). The only schema-side adjustments:
   - **`feedback_routing_log.failure_kind`**: admit all six `RoutingFailureKind` values per LLD divergence above. Migration comment enumerates them.
   - **`feedback_misclassification_corrections.replay_status`**: enum widened to four values per LLD divergence above.
3. Indexes from [LLD lines 91-97 (entries)](../../lld/feedback.md), [LLD lines 131-139 (routing log)](../../lld/feedback.md), [LLD lines 165-167 (corrections)](../../lld/feedback.md), [LLD lines 190-195 (clarifications)](../../lld/feedback.md) ship in 01a. They support the queries 01b/01e/01g add later.
4. **No cross-module FKs.** `feedback_entries.user_id`, `feedback_misclassification_corrections.actor_user_id`, `feedback_misclassification_corrections.decided_by_user_id` are opaque UUIDs — no FK to `users` table (per LLD line 69: "Nothing in this schema cross-references other modules' tables").
5. **Cross-table FKs within the module**: `feedback_routing_log.feedback_entry_id REFERENCES feedback_entries(id) ON DELETE CASCADE` (LLD line 109); `feedback_misclassification_corrections.feedback_entry_id REFERENCES feedback_entries(id) ON DELETE CASCADE` (LLD line 151); `feedback_misclassification_corrections.original_routing_id REFERENCES feedback_routing_log(id)` (LLD line 152 — no cascade; corrections survive the routing row); `feedback_routing_log.superseded_by REFERENCES feedback_routing_log(id) ON DELETE SET NULL` (LLD line 123 — self-referential, sets null on delete to avoid pinning); `feedback_clarification_queries.feedback_entry_id REFERENCES feedback_entries(id) ON DELETE CASCADE` (LLD line 177); `feedback_misclassification_corrections.replay_routing_id REFERENCES feedback_routing_log(id)` (LLD line 158, no cascade, nullable).
6. **Partial indexes** (LLD lines 93-95, 137-139, 191-195): the four partial indexes from the LLD ship as-is — they speed the async sweeps that feedback-01g adds later.

### Entities — JPA mapping

7. **`FeedbackEntry`** per [LLD §Entities row 1 line 210](../../lld/feedback.md). Aggregate root. Fields:
   - `id (UUID, application-set, updatable=false, nullable=false)`
   - `userId (UUID NOT NULL)`
   - `text (String, `columnDefinition = "text"`, NOT NULL — never truncated per LLD line 100)`
   - `uiContext (UiContextDocument record, `@Type(JsonType.class)`, jsonb NOT NULL)`
   - `submissionStatus (SubmissionStatus enum, `@Enumerated(STRING)`, length 24, NOT NULL)`
   - `classificationAttempts (int NOT NULL DEFAULT 0)`
   - `lastClassifiedAt (Instant nullable)`
   - `traceId (UUID NOT NULL)`
   - `optimisticVersion (long, `@Version`, column `optimistic_version`, NOT NULL DEFAULT 0)`
   - `createdAt (@CreatedDate, Instant NOT NULL, updatable=false)`
   - `updatedAt (@LastModifiedDate, Instant NOT NULL)`
   - `routingLog (List<RoutingLogEntry>, `@OneToMany(mappedBy = "feedbackEntry", cascade = ALL, fetch = LAZY)`, Builder.Default empty list)` — for use by `@EntityGraph` queries; lazy by default.
8. **`RoutingLogEntry`** per [LLD §Entities row 2 line 211](../../lld/feedback.md). Append-only-with-status-update (no `@Version`). Fields:
   - `id (UUID, application-set)`
   - `feedbackEntry (@ManyToOne(fetch = LAZY), @JoinColumn(name = "feedback_entry_id"), NOT NULL)` — back-pointer
   - `destination (Destination enum, `@Enumerated(STRING)`, length 16, NOT NULL)`
   - `confidence (BigDecimal, precision=4, scale=3, NOT NULL)` — 0.000…1.000
   - `extractedFeedback (String, columnDefinition = "text", NOT NULL)`
   - `structuredPayload (JsonNode, `@Type(JsonType.class)`, jsonb NOT NULL)`
   - `routingDecision (RoutingDecision enum, `@Enumerated(STRING)`, length 24, NOT NULL)`
   - `status (RoutingStatus enum, `@Enumerated(STRING)`, length 24, NOT NULL)`
   - `actionTaken (String, length 512, nullable)`
   - `destinationResultJson (JsonNode, `@Type(JsonType.class)`, jsonb, nullable)`
   - `failureKind (RoutingFailureKind enum, `@Enumerated(STRING)`, length 32, nullable)`
   - `failureMessage (String, length 512, nullable)`
   - `supersededById (UUID, nullable)` — **raw UUID, NOT a `@ManyToOne` to self.** Self-FK is in SQL (line 123), but the Java side keeps it loose to avoid managing a self-referential association.
   - `classificationAttempt (int NOT NULL)` — which attempt (1-indexed) produced this routing
   - `routedAt (Instant NOT NULL)`
   - `completedAt (Instant nullable)` — when status moves to terminal
   - `createdAt (@CreatedDate, Instant NOT NULL, updatable=false)`
   - **No `@Version`** — concurrency by single-writer-per-row.
   - **No `@LastModifiedDate`** — status changes update `completedAt` instead. The entity is conceptually append-with-status — there's no general "updated_at" semantic.
9. **`MisclassificationCorrection`** per [LLD §Entities row 3 line 212](../../lld/feedback.md). Append-only. **No `@Version`, no `@LastModifiedDate`.** Fields:
   - `id (UUID, application-set)`
   - `feedbackEntry (@ManyToOne(fetch = LAZY), @JoinColumn(name = "feedback_entry_id"), NOT NULL)` — back-pointer (for the `findByFeedbackEntryUserIdOrderByOccurredAtDesc` query)
   - `originalRoutingId (UUID NOT NULL)` — **raw UUID, NOT a `@ManyToOne` to `RoutingLogEntry`.** Per LLD line 212: "no FK relation in JPA — kept as raw UUIDs to avoid an ownership cycle with `RoutingLogEntry`'s `supersededById`."
   - `correctedDestination (Destination enum, `@Enumerated(STRING)`, length 16, NOT NULL)`
   - `userCorrectionNote (String, length 512, nullable)`
   - `actorUserId (UUID NOT NULL)`
   - `originalConfidence (BigDecimal, precision=4, scale=3, NOT NULL)` — copied at correction time for ground-truth labelling
   - `originalDestination (Destination enum, NOT NULL)` — copied likewise
   - `replayRoutingId (UUID nullable)` — set after the corrected destination runs
   - `replayStatus (CorrectionReplayStatus enum, `@Enumerated(STRING)`, length 24, NOT NULL)` — initial value `PENDING_REPLAY` per LLD divergence
   - `occurredAt (Instant NOT NULL)`
   - `createdAt (@CreatedDate, updatable=false)` — defensive audit column; the LLD doesn't list it but the style guide ([style-guide.md §Entities line 150](../../lld/style-guide.md)) requires `createdAt` / `updatedAt` on every entity. **`updatedAt` omitted** because the row is append-only-with-replay-stamp; track the replay update via `replayStatus` flip alone. **Worth implementer review** — feedback-01f's replay path mutates `replayStatus` + `replayRoutingId`, which is technically "modifying" the row, but the conventional `@LastModifiedDate` is omitted to honour the LLD's append-only framing. If reviewer prefers strict append-only, replace replay update with a sibling row.
10. **`ClarificationQuery`** per [LLD §Entities row 4 line 213](../../lld/feedback.md). Fields:
    - `id (UUID, application-set)`
    - `feedbackEntry (@OneToOne(fetch = LAZY), @JoinColumn(name = "feedback_entry_id"), NOT NULL)` — one query per feedback entry at a time
    - `classifierOptionsJson (JsonNode, `@Type(JsonType.class)`, jsonb NOT NULL)`
    - `questionText (String, length 512, NOT NULL)`
    - `status (ClarificationStatus enum, `@Enumerated(STRING)`, length 24, NOT NULL)`
    - `selectedDestination (Destination enum, nullable)`
    - `userClarificationText (String, columnDefinition = "text", nullable)`
    - `expiresAt (Instant NOT NULL)`
    - `answeredAt (Instant nullable)`
    - `optimisticVersion (long, `@Version`, column `optimistic_version`, NOT NULL DEFAULT 0)` — per LLD line 213: "`@Version` because the user can answer concurrently with the daily expiry sweep."
    - `createdAt (@CreatedDate)`, `updatedAt (@LastModifiedDate)`

### Module-local enums

11. **Eight module-local enums** in `domain/entity/` (or `spi/` for `Destination` per below). Verbatim values from [LLD §Entities lines 217-224](../../lld/feedback.md):
    - **`Destination`**: `RECIPE | PREFERENCE | NUTRITION | PROVISIONS` — **lives in `feedback.spi.Destination`** (not `domain/entity/`). Reason: the four `DestinationDispatcher` SPIs in feedback-01d are keyed by `Map<Destination, DestinationDispatcher>`. The SPI subpackage matches the [recipe-01f / nutrition-01e SPI placement pattern](../recipe/01f-write-api-and-events.md). Move to `spi/` proactively in 01a so 01d doesn't have to refactor.
    - **`SubmissionStatus`**: `RECEIVED | CLASSIFYING | CLASSIFIED | CLARIFICATION_PENDING | ROUTED | PARTIALLY_FAILED | FAILED | CORRECTED`
    - **`RoutingDecision`**: `AUTO_ROUTED | ROUTED_WITH_FLAG | CLARIFICATION_QUEUED`
    - **`RoutingStatus`**: `PENDING | APPLIED | FAILED | CORRECTED_AWAY | REPLAYED | AWAITING_USER_APPROVAL`
    - **`RoutingFailureKind`**: `TRANSIENT | DESTINATION_VALIDATION | DESTINATION_BUSINESS | AI_UNAVAILABLE | UNKNOWN`
    - **`ClarificationStatus`**: `PENDING | ANSWERED | EXPIRED`
    - **`CorrectionReplayStatus`**: `PENDING_REPLAY | APPLIED | FAILED | DESTINATION_REJECTED` — extended per LLD divergence above
    - **`Screen`** (lives in `api/dto/`, not `domain/entity/`): `RECIPE_DETAIL | PLAN_MEAL_DETAIL | PLAN_VIEW | GROCERY | NUTRITION_DASHBOARD | SETTINGS | GENERAL` per [LLD §DTOs line 250](../../lld/feedback.md). Module-local — restated rather than imported from any cross-module enum (LLD line 269).

### DTOs — stable shape

12. **`UiContextDocument`** record per [LLD §DTOs §UiContextDto line 241](../../lld/feedback.md) — mirrors the JSONB shape on `feedback_entries.ui_context`. Lives in `domain/document/` (NOT `api/dto/` — it's the storage shape, not a wire shape; the wire shape is `UiContextDto`). Same field set: `screen`, `recipeId`, `recipeVersion`, `mealSlotId`, `planId`, `referenceDate`.
13. **`UiContextDto`** per LLD line 241 — wire shape. `@ValidUiContext` annotation is **NOT** in scope for 01a (lands in 01b alongside submission validation); the DTO ships without the class-level validator annotation.
14. **`FeedbackEntryDto`** per [LLD line 255](../../lld/feedback.md). The DTO field `routes: List<RoutingDecisionDto>` is populated by `FeedbackEntryMapper` (uses `RoutingLogMapper`).
15. **`RoutingDecisionDto`** per [LLD line 295](../../lld/feedback.md). The `destinationResult` field is `Object` — `JsonNode` deserialised lazily by the mapper's custom `@Named("readDestinationResult")` qualifier (which 01a ships as a no-op default returning the raw `JsonNode`; the typed-shell logic lands in 01d alongside the destination dispatchers).
16. **`ClarificationQueryDto`** per [LLD line 326](../../lld/feedback.md), **`ClarificationOptionDto`** per LLD line 336.
17. **`MisclassificationCorrectionDto`**: LLD doesn't fully spec the DTO shape (only the mapper signature, line 392). Ship a record matching the entity's exposed fields:
    ```java
    public record MisclassificationCorrectionDto(
        UUID id, UUID feedbackEntryId, UUID originalRoutingId,
        Destination correctedDestination, Destination originalDestination,
        BigDecimal originalConfidence, String userCorrectionNote,
        UUID actorUserId, UUID replayRoutingId, CorrectionReplayStatus replayStatus,
        Instant occurredAt, Instant createdAt) {}
    ```
    **LLD gap** — the DTO is implicit. **Worth user review.**
18. **Request DTOs** (`SubmitFeedbackRequest`, `CorrectionRequest`, `AnswerClarificationRequest`) — **DEFERRED to the tickets that own each flow**:
    - `SubmitFeedbackRequest` → feedback-01b
    - `CorrectionRequest` → feedback-01f
    - `AnswerClarificationRequest` → feedback-01e
    Reason: each request DTO carries flow-specific validation (`@ValidUiContext`, `@AssertTrue isAtLeastOneProvided`, etc.) that belongs with the flow's validator.
19. **`ClassificationResult` and `ClassificationOutput`** records (LLD lines 274-285) — **DEFERRED to feedback-01c** alongside `FeedbackClassificationTask`. The structured-AI-output type isn't needed by 01a's schema/repo layer.

### Mappers

20. Four MapStruct mappers, `@Mapper(componentModel = "spring")`, one per entity-DTO pair per [LLD §Mappers lines 372-395](../../lld/feedback.md):
    - **`FeedbackEntryMapper`** — declares `uses = { RoutingLogMapper.class }`. The `routes` field on `FeedbackEntryDto` is populated from `entity.routingLog`. `pendingClarificationQueryId` field is **NOT** populated by this mapper — it requires a repository lookup (done in service layer); MapStruct annotation `@Mapping(target = "pendingClarificationQueryId", ignore = true)`. Service layer fills it.
    - **`RoutingLogMapper`** — maps `RoutingLogEntry` → `RoutingDecisionDto`. The `destinationResult` field: `@Mapping(target = "destinationResult", source = "destinationResultJson", qualifiedByName = "readDestinationResult")` with a `@Named("readDestinationResult")` default helper returning the raw `JsonNode` (or `null`).
    - **`ClarificationQueryMapper`** — `ClarificationQuery` → `ClarificationQueryDto`. Maps `classifierOptionsJson (JsonNode)` to `options (List<ClarificationOptionDto>)` via a `@Named("readOptions")` helper that deserialises the JSON array into the typed record list (uses an injected `ObjectMapper`).
    - **`MisclassificationCorrectionMapper`** — straightforward field-for-field. `feedbackEntryId` from `entity.feedbackEntry.id` via nested `@Mapping(source = "feedbackEntry.id", target = "feedbackEntryId")`.

### Repositories

21. Four Spring Data repositories, **package-private** (no `public` modifier on the interface — per [style-guide.md §Module Package Structure line 64](../../lld/style-guide.md)). Verbatim signatures from [LLD §Repositories lines 405-453](../../lld/feedback.md):
    - **`FeedbackEntryRepository`** — five methods (`findById`, `findWithRoutingByIdAndUserId` with `@EntityGraph(attributePaths = { "routingLog" })`, `findByUserIdOrderByCreatedAtDesc` page, `findBySubmissionStatusInAndCreatedAtBefore` for the sweep). **Skip `getByIds` plural** — not in the LLD's repo (the style guide says batch siblings on query services, not repos).
    - **`RoutingLogRepository`** — three methods (`findByFeedbackEntryIdOrderByRoutedAtAsc`, `findByIdAndFeedbackEntryUserId`, `aggregateByDestination` JPQL with `DestinationRollupRow` projection). The `DestinationRollupRow` is a **NEW interface projection** (Spring Data projection); ship it as a sibling interface in the repo package.
    - **`MisclassificationCorrectionRepository`** — three methods (`findByFeedbackEntryUserIdOrderByOccurredAtDesc` page, `countByOccurredAtBetween`, `countByOriginalDestinationAndOccurredAtBetween`).
    - **`ClarificationQueryRepository`** — three methods (`findByIdAndFeedbackEntryUserId`, `findByFeedbackEntryUserIdAndStatusOrderByCreatedAtAsc` page, `findByStatusAndExpiresAtBefore` for the sweep).
22. **`DestinationRollupRow`** interface projection — fields `destination()`, `count()`, `avgConfidence()`. The JPQL `select r.destination, count(r), avg(r.confidence) ...` returns rows in this shape. Spring Data binds positionally via the interface; use **named** projection columns by switching to `select new com.example.mealprep.feedback.domain.repository.DestinationRollupRow(...)` constructor projection if the interface form misbehaves. **Worth implementer review** — interface projections for JPQL aggregates can be finicky; the JPQL→Java naming is the gotcha.

### Module facade + boundary

23. **`FeedbackModule.java`** at module root — re-exports the public service interfaces. **In 01a the file is a STUB** with a single empty private constructor (and a Javadoc explaining the re-export will happen in 01b when the services land):
    ```java
    package com.example.mealprep.feedback;
    /** Facade — public service interfaces re-exported here once they land in feedback-01b. */
    public final class FeedbackModule { private FeedbackModule() {} }
    ```
24. **`FeedbackBoundaryTest`** at `src/test/java/com/example/mealprep/feedback/FeedbackBoundaryTest.java`. ArchUnit rules:
    ```java
    @AnalyzeClasses(packages = "com.example.mealprep")
    class FeedbackBoundaryTest {
      @ArchTest static final ArchRule reposPackagePrivate = classes()
          .that().resideInAPackage("..feedback.domain.repository..")
          .should().notBePublic();
      @ArchTest static final ArchRule noCrossModuleRepoImport = noClasses()
          .that().resideOutsideOfPackages("com.example.mealprep.feedback..")
          .should().dependOnClassesThat().resideInAPackage("com.example.mealprep.feedback.domain.repository..");
      @ArchTest static final ArchRule spiAllowed = classes()
          .that().resideInAPackage("com.example.mealprep.feedback.spi..")
          .should().bePublic();   // SPIs ARE intended to be cross-module visible
    }
    ```
    Same shape as `RecipeBoundaryTest` / `NutritionBoundaryTest`.

### Module exception hierarchy

25. **`FeedbackException extends MealPrepException`** (or `RuntimeException` if no project-wide `MealPrepException` yet — verify against the existing recipe/nutrition modules; the wave-2 modules use the project-wide root). 01a ships only the module root + **two** subclasses (the ones referenced by the read endpoints that 01b layers on):
    - **`FeedbackEntryNotFoundException`** (404) per LLD line 621
    - **`RoutingDecisionNotFoundException`** (404) per LLD line 622
    The other five exceptions (`ClarificationQueryNotFoundException`, `ClarificationQueryExpiredException`, `InvalidCorrectionTargetException`, `ClassificationFailedException`) — **DEFERRED to the tickets that throw them** (01e for clarification ones, 01f for correction, 01c for classification).
26. **No `FeedbackExceptionHandler` in 01a.** Lands in 01b alongside the first REST controller (the LLD's [§REST Controllers line 587](../../lld/feedback.md) declares both controllers, but the handler can ship with the first one). 01a doesn't expose HTTP yet.

## Database

```
NEW   src/main/resources/db/migration/V20260601900000__feedback_create_entries.sql
NEW   src/main/resources/db/migration/V20260601900100__feedback_create_routing_log.sql
NEW   src/main/resources/db/migration/V20260601900200__feedback_create_misclassification_corrections.sql
NEW   src/main/resources/db/migration/V20260601900300__feedback_create_clarification_queries.sql
```

**Schema details** — copy [LLD §Database lines 73-196](../../lld/feedback.md) verbatim with these adjustments:

- Add `created_at` audit column to `feedback_misclassification_corrections` (per Behavioural §9 — style guide requires it; LLD omits).
- Loosen `failure_kind` and `replay_status` value sets per LLD divergence notes.
- Confirm **`text[]` → `jsonb` workaround** is NOT needed here: no `text[]` columns in any of the four tables. All collection-ish data is JSONB already (`ui_context`, `structured_payload`, `destination_result_json`, `safety_gate_findings`, `classifier_options_json`).

## OpenAPI updates

**Zero OpenAPI changes.** 01a ships entities + repos + DTOs + mappers — no HTTP endpoints. Do NOT touch `paths/`, `schemas/`, or the entry `openapi.yaml`. The first feedback path ref lands in 01b alongside `POST /api/v1/feedback`.

## Verbatim shape snippets

### `FeedbackEntry` entity

```java
@Entity
@Table(name = "feedback_entries")
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class FeedbackEntry {

  @Id @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "text", nullable = false, columnDefinition = "text")
  private String text;

  @Type(JsonType.class)
  @Column(name = "ui_context", nullable = false, columnDefinition = "jsonb")
  private UiContextDocument uiContext;

  @Enumerated(EnumType.STRING)
  @Column(name = "submission_status", nullable = false, length = 24)
  private SubmissionStatus submissionStatus;

  @Column(name = "classification_attempts", nullable = false)
  private int classificationAttempts;

  @Column(name = "last_classified_at")
  private Instant lastClassifiedAt;

  @Column(name = "trace_id", nullable = false)
  private UUID traceId;

  @Version @Column(name = "optimistic_version", nullable = false)
  private long optimisticVersion;

  @CreatedDate @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @LastModifiedDate @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(mappedBy = "feedbackEntry", cascade = CascadeType.ALL, orphanRemoval = false, fetch = FetchType.LAZY)
  @Builder.Default
  private List<RoutingLogEntry> routingLog = new ArrayList<>();
}
```

### `RoutingLogEntry` — no `@Version`, append-with-status-update

```java
@Entity
@Table(name = "feedback_routing_log")
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class RoutingLogEntry {

  @Id @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "feedback_entry_id", nullable = false, updatable = false)
  private FeedbackEntry feedbackEntry;

  @Enumerated(EnumType.STRING)
  @Column(name = "destination", nullable = false, length = 16)
  private Destination destination;

  @Column(name = "confidence", nullable = false, precision = 4, scale = 3)
  private BigDecimal confidence;

  @Column(name = "extracted_feedback", nullable = false, columnDefinition = "text")
  private String extractedFeedback;

  @Type(JsonType.class)
  @Column(name = "structured_payload", nullable = false, columnDefinition = "jsonb")
  private JsonNode structuredPayload;

  @Enumerated(EnumType.STRING)
  @Column(name = "routing_decision", nullable = false, length = 24)
  private RoutingDecision routingDecision;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 24)
  private RoutingStatus status;

  @Column(name = "action_taken", length = 512)
  private String actionTaken;

  @Type(JsonType.class)
  @Column(name = "destination_result_json", columnDefinition = "jsonb")
  private JsonNode destinationResultJson;

  @Enumerated(EnumType.STRING)
  @Column(name = "failure_kind", length = 32)
  private RoutingFailureKind failureKind;

  @Column(name = "failure_message", length = 512)
  private String failureMessage;

  @Column(name = "superseded_by")
  private UUID supersededById;     // raw UUID, no JPA association — see LLD line 212

  @Column(name = "classification_attempt", nullable = false)
  private int classificationAttempt;

  @Column(name = "routed_at", nullable = false)
  private Instant routedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @CreatedDate @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;
}
```

### `MisclassificationCorrection` — append-only

```java
@Entity
@Table(name = "feedback_misclassification_corrections")
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class MisclassificationCorrection {

  @Id @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "feedback_entry_id", nullable = false, updatable = false)
  private FeedbackEntry feedbackEntry;

  @Column(name = "original_routing_id", nullable = false, updatable = false)
  private UUID originalRoutingId;     // raw UUID — no JPA association, per LLD line 212

  @Enumerated(EnumType.STRING)
  @Column(name = "corrected_destination", nullable = false, length = 16, updatable = false)
  private Destination correctedDestination;

  @Column(name = "user_correction_note", length = 512, updatable = false)
  private String userCorrectionNote;

  @Column(name = "actor_user_id", nullable = false, updatable = false)
  private UUID actorUserId;

  @Column(name = "original_confidence", nullable = false, precision = 4, scale = 3, updatable = false)
  private BigDecimal originalConfidence;

  @Enumerated(EnumType.STRING)
  @Column(name = "original_destination", nullable = false, length = 16, updatable = false)
  private Destination originalDestination;

  @Column(name = "replay_routing_id")
  private UUID replayRoutingId;     // updated by 01f's replayer; nullable until replay completes

  @Enumerated(EnumType.STRING)
  @Column(name = "replay_status", nullable = false, length = 24)
  private CorrectionReplayStatus replayStatus;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @CreatedDate @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;
}
```

### `UiContextDocument` storage shape

```java
public record UiContextDocument(
    Screen screen,
    UUID recipeId, Integer recipeVersion,
    UUID mealSlotId, UUID planId,
    LocalDate referenceDate) {}
```

Same field set as `UiContextDto`; lives in `feedback/domain/document/`. Jackson serialises to/from the JSONB column.

### Repositories — package-private

```java
package com.example.mealprep.feedback.domain.repository;

interface FeedbackEntryRepository extends JpaRepository<FeedbackEntry, UUID> {

  @EntityGraph(attributePaths = { "routingLog" })
  Optional<FeedbackEntry> findWithRoutingByIdAndUserId(UUID id, UUID userId);

  Page<FeedbackEntry> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  List<FeedbackEntry> findBySubmissionStatusInAndCreatedAtBefore(
      Collection<SubmissionStatus> statuses, Instant before);
}
```

Note: `findById(UUID)` is inherited from `JpaRepository`; LLD line 407 restates it but it's a no-op declaration; OK to omit the override.

## Edge-case checklist

- [ ] All four migrations land in `V20260601900xxx` timestamp range, named per LLD §Database
- [ ] `feedback_entries.text` is `text` (not `varchar`) — Flyway-side assertion via `information_schema.columns`
- [ ] `feedback_entries.ui_context` is `jsonb NOT NULL` — assertion likewise
- [ ] `feedback_routing_log.confidence` is `numeric(4,3)` — assertion likewise
- [ ] `feedback_misclassification_corrections.replay_status` admits `PENDING_REPLAY` value (no DB CHECK constraint, but the enum is widened on the Java side)
- [ ] All seven module-local enums declare exact value sets per LLD §Entities line 217-224 + the divergence notes
- [ ] `Destination` lives in `com.example.mealprep.feedback.spi.Destination` (not `domain/entity/`)
- [ ] `FeedbackEntryMapper.toDto` round-trips a feedback entry with routing log (uses `RoutingLogMapper`); `pendingClarificationQueryId` is ignored (filled in service layer later)
- [ ] `RoutingLogMapper.toDto` round-trips a routing entry with non-null `destinationResultJson` — `destinationResult` field on the DTO surfaces the raw `JsonNode`
- [ ] `RoutingLogMapper.toDto` round-trips a routing entry with null `destinationResultJson` — `destinationResult` is null in the DTO
- [ ] `ClarificationQueryMapper.toDto` deserialises `classifierOptionsJson` into `List<ClarificationOptionDto>`; round-trip on a query with two options preserves both
- [ ] `MisclassificationCorrectionMapper.toDto` round-trips
- [ ] All four repos are package-private (compile error if outside-module test tries to import — verified by `FeedbackBoundaryTest`)
- [ ] `FeedbackEntryRepository.findWithRoutingByIdAndUserId` actually eager-fetches `routingLog` (asserted via Hibernate stats: one SQL with a LEFT JOIN, not N+1)
- [ ] `RoutingLogRepository.aggregateByDestination` JPQL projection compiles + returns the right shape — IT against Testcontainers Postgres
- [ ] `FeedbackBoundaryTest` passes: repos package-private; no cross-module repo import; SPI public
- [ ] JSONB round-trip on each `@Type(JsonType.class)` column — persist entity with non-trivial JSON, re-read, equality holds
- [ ] `@Version` on `FeedbackEntry` increments on update — concurrent update test surfaces `OptimisticLockingFailureException`
- [ ] `@Version` on `ClarificationQuery` likewise
- [ ] `RoutingLogEntry` has NO `@Version` — modifying the same row twice in different transactions doesn't throw OL (the LLD's intent — single-writer-per-row guarded by router code, not JPA)
- [ ] `MisclassificationCorrection` has NO `@Version`
- [ ] FK cascades behave: deleting a `FeedbackEntry` cascades to its `RoutingLogEntry` rows, its `MisclassificationCorrection` rows, and its `ClarificationQuery` row — IT verifies
- [ ] FK on `RoutingLogEntry.supersededBy` sets NULL on delete (self-referential)
- [ ] FK on `MisclassificationCorrection.originalRoutingId` does NOT cascade (corrections survive routing-row deletion — actually, in practice routing rows never delete; the test asserts the FK constraint exists but is not cascading)
- [ ] All four partial indexes from the LLD are present (verified via `pg_indexes` query in an IT)
- [ ] `FlywayMigrationIT`: boots Postgres, runs all `feedback_*` migrations, validates schema matches JPA mapping (`spring.jpa.hibernate.ddl-auto=validate`)
- [ ] Module package layout matches [LLD §Package Layout lines 21-48](../../lld/feedback.md) — `internal/` subpackage exists but is empty in 01a (helpers land in 01c-01g)

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601900000__feedback_create_entries.sql
NEW   src/main/resources/db/migration/V20260601900100__feedback_create_routing_log.sql
NEW   src/main/resources/db/migration/V20260601900200__feedback_create_misclassification_corrections.sql
NEW   src/main/resources/db/migration/V20260601900300__feedback_create_clarification_queries.sql

NEW   src/main/java/com/example/mealprep/feedback/FeedbackModule.java                        (stub — see Behavioural §23)

NEW   src/main/java/com/example/mealprep/feedback/spi/Destination.java                       (placed in spi/ — see Behavioural §11)

NEW   src/main/java/com/example/mealprep/feedback/domain/entity/FeedbackEntry.java
NEW   src/main/java/com/example/mealprep/feedback/domain/entity/RoutingLogEntry.java
NEW   src/main/java/com/example/mealprep/feedback/domain/entity/MisclassificationCorrection.java
NEW   src/main/java/com/example/mealprep/feedback/domain/entity/ClarificationQuery.java
NEW   src/main/java/com/example/mealprep/feedback/domain/entity/SubmissionStatus.java
NEW   src/main/java/com/example/mealprep/feedback/domain/entity/RoutingDecision.java
NEW   src/main/java/com/example/mealprep/feedback/domain/entity/RoutingStatus.java
NEW   src/main/java/com/example/mealprep/feedback/domain/entity/RoutingFailureKind.java
NEW   src/main/java/com/example/mealprep/feedback/domain/entity/ClarificationStatus.java
NEW   src/main/java/com/example/mealprep/feedback/domain/entity/CorrectionReplayStatus.java

NEW   src/main/java/com/example/mealprep/feedback/domain/document/UiContextDocument.java

NEW   src/main/java/com/example/mealprep/feedback/domain/repository/FeedbackEntryRepository.java
NEW   src/main/java/com/example/mealprep/feedback/domain/repository/RoutingLogRepository.java
NEW   src/main/java/com/example/mealprep/feedback/domain/repository/MisclassificationCorrectionRepository.java
NEW   src/main/java/com/example/mealprep/feedback/domain/repository/ClarificationQueryRepository.java
NEW   src/main/java/com/example/mealprep/feedback/domain/repository/DestinationRollupRow.java   (interface projection)

NEW   src/main/java/com/example/mealprep/feedback/api/dto/UiContextDto.java
NEW   src/main/java/com/example/mealprep/feedback/api/dto/Screen.java
NEW   src/main/java/com/example/mealprep/feedback/api/dto/FeedbackEntryDto.java
NEW   src/main/java/com/example/mealprep/feedback/api/dto/RoutingDecisionDto.java
NEW   src/main/java/com/example/mealprep/feedback/api/dto/ClarificationQueryDto.java
NEW   src/main/java/com/example/mealprep/feedback/api/dto/ClarificationOptionDto.java
NEW   src/main/java/com/example/mealprep/feedback/api/dto/MisclassificationCorrectionDto.java

NEW   src/main/java/com/example/mealprep/feedback/api/mapper/FeedbackEntryMapper.java
NEW   src/main/java/com/example/mealprep/feedback/api/mapper/RoutingLogMapper.java
NEW   src/main/java/com/example/mealprep/feedback/api/mapper/ClarificationQueryMapper.java
NEW   src/main/java/com/example/mealprep/feedback/api/mapper/MisclassificationCorrectionMapper.java

NEW   src/main/java/com/example/mealprep/feedback/exception/FeedbackException.java
NEW   src/main/java/com/example/mealprep/feedback/exception/FeedbackEntryNotFoundException.java
NEW   src/main/java/com/example/mealprep/feedback/exception/RoutingDecisionNotFoundException.java

NEW   src/test/java/com/example/mealprep/feedback/FeedbackBoundaryTest.java
NEW   src/test/java/com/example/mealprep/feedback/FeedbackEntityRoundTripIT.java               (Testcontainers; JSONB + @Version + cascades)
NEW   src/test/java/com/example/mealprep/feedback/FeedbackMapperTest.java                       (MapStruct round-trips)
NEW   src/test/java/com/example/mealprep/feedback/FlywayMigrationIT.java                        (schema-vs-JPA validation)
NEW   src/test/java/com/example/mealprep/feedback/testdata/FeedbackTestData.java                (builders for the entities — used by 01b-01g)
```

Count: ~38 files. Estimated agent runtime 35-45 min — mostly mechanical (records + entities + migrations). The non-trivial bits are `RoutingLogRepository.aggregateByDestination` JPQL + the four MapStruct mappers + the `@OneToOne` on `ClarificationQuery → FeedbackEntry`.

**Files this ticket does NOT modify**:
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java`
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` (the cross-module one) — verify the existing rule already covers the new module's repo package; if it enumerates allowed packages, add `feedback`
- Any other module's files

## Dependencies

- **Hard dependency**: `auth-01a` (merged) — implicit; no auth used in 01a (no controllers), but the auth module's `MealPrepException` may be the root for `FeedbackException`. Verify; otherwise extend `RuntimeException`.
- **Hard dependency**: project-setup (merged) — Postgres + Flyway + Hibernate + hypersistence-utils + MapStruct + Lombok + ArchUnit configured.
- **Hard dependency**: refactor-01-split-merge-zones (merged) — per-module layout convention.
- **No cross-module hard dependencies on wave-2 modules** — 01a is purely schema + shape. The `Destination` enum's *values* match the four wave-2 modules' service surfaces (preference / nutrition / provisions / recipe-via-optimiser), but 01a doesn't import any cross-module type.
- **Sibling tickets running in parallel** (Wave 3): `adaptation-pipeline-01a`, `planner-01a`, `discovery-01a`. None touch any feedback file.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + ArchUnit + Flyway migration test)
- [ ] All edge-case items above ticked
- [ ] No regression on existing tests
- [ ] `FlywayMigrationIT` green — schema matches JPA at `ddl-auto=validate`
- [ ] `FeedbackBoundaryTest` green — repos package-private, no cross-module repo import
- [ ] JSONB round-trip ITs green for all four entities (specifically: `ui_context`, `structured_payload`, `destination_result_json`, `classifier_options_json`)
- [ ] FK cascade behaviour verified in IT (delete `FeedbackEntry` cascades to its three child tables)

## What's NOT in scope

- `FeedbackQueryService` / `FeedbackUpdateService` interfaces or impl → **01b**
- `FeedbackServiceImpl` (single impl of both query and update interfaces) → **01b**
- `FeedbackController` / `ClarificationQueryController` REST endpoints → **01b** (FeedbackController), **01e** (ClarificationQueryController)
- `FeedbackExceptionHandler` `@RestControllerAdvice` → **01b**
- `SubmitFeedbackRequest`, `SubmitFeedbackResponse`, `CorrectionRequest`, `AnswerClarificationRequest` request/response DTOs → **01b / 01e / 01f**
- `ClassificationResult`, `ClassificationOutput`, `ClassificationResultDto` records → **01c**
- `FeedbackClassificationTask` AiTask → **01c**
- `FeedbackClassifier`, `FeedbackRouter`, `ConfidenceGate`, `CorrectionReplayer` internal helpers → **01c / 01d / 01f**
- `DestinationDispatcher` SPI + four destination impls → **01d**
- `@ValidUiContext`, `@ValidDestination` custom validators → **01b** (UiContext), **01f** (Destination)
- `FeedbackSubmittedEvent`, `FeedbackProcessedEvent`, `FeedbackMisclassificationCorrectedEvent` → **01b** (Submitted/Processed), **01f** (Corrected)
- `FeedbackAsyncConfig` thread-pool bean → **01c** (the classifier needs it)
- `FeedbackJsonConfig` — only needed if `ObjectMapper` customisation is required; **defer** until a flow proves a need (no `@JsonView` / mixin in scope today)
- `@TransactionalEventListener` on `AiCallSucceededEvent` telemetry stamping `lastClassifiedAt` → **01g**
- Scheduled sweeps (`retryStuckClassifications`, `expireOldClarificationQueries`) → **01g**
- Quality-monitoring service / dashboard DTO → **out of LLD scope** (LLD §Quality Monitoring line 873 — folded to future cross-cutting module)
- Service-layer batch query siblings (`getByIds`) → **01b** when service interfaces land
- Cross-module integration tests (`FeedbackPreferenceIntegrationIT`, `FeedbackOptimiserIntegrationIT`, etc.) → **01g**

Squash-merge with: `feat(feedback): 01a — entities, migrations, repositories, mappers, enums, and module boundary`
