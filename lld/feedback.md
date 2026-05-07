# Feedback Module — LLD

*Implementation specification for the conversational feedback intake: a single submission API that classifies free-text feedback via a cheap-tier AI task, routes each classification to the relevant destination's update service, and persists every routing decision and correction so misclassifications are recoverable. Translates [feedback-system.md](../design/feedback-system.md) into a buildable Spring Boot module.*

## Scope

This document specifies the `feedback` module — package layout, JPA entities, Flyway migrations, repositories, service interfaces, DTOs, mappers, REST controllers, validation, events, business-logic flows, transaction boundaries, and the test plan. Conventions defer to [lld/style-guide.md](style-guide.md); this LLD restates a rule only when the module-specific application matters.

The HLD's commitment is straightforward: the module is a **classifier and a router** ([feedback-system.md §What It Is](../design/feedback-system.md)). It owns no business state for the four destinations — it persists the original feedback, the AI classifier's output, the routing audit trail, and any user-driven corrections. All update logic lives in the destination modules.

Three concerns map cleanly onto three internal helpers: classification (an `AiTask` dispatched through `AiService`), routing (a dispatcher that fans out to the four destination services), and confirmation (the response shape the controller returns). Confidence thresholds from [feedback-system.md §Confidence handling](../design/feedback-system.md#confidence-handling) drive a three-way fork: auto-route, route-and-flag, or queue a clarification question to the user.

The classification prompt itself is **deferred** — it is part of the prompt-engineering work track per [lld/README.md §LLM prompts to design](README.md#llm-prompts-to-design-9-distinct-prompt-engineering-exercises). This LLD specifies the `AiTask` shape, the structured-output type, the framework around it, and what happens at every confidence threshold; the prompt content lands later via the file-based template loader owned by [ai.md §Flow 5](ai.md).

Cross-module references: classifier dispatch through [ai.md](ai.md), preference routing to [preference.md](preference.md), nutrition routing to [nutrition.md](nutrition.md), provisions routing to [provisions.md](provisions.md), recipe feedback routing **via the adaptation pipeline** (`OptimiserService.handleRecipeFeedback`) per [system-overview.md §Recipe Optimiser Trigger 2](../design/system-overview.md#recipe-optimiser) and [technical-architecture.md §Flow 2](../design/technical-architecture.md). The `optimiser` module's LLD does not yet exist (`adaptation-pipeline.md` is pending per [lld/README.md](README.md)); this LLD references the service interface it will expose and treats it as a forward dependency.

---

## Package Layout

```
com.example.mealprep.feedback/
├── FeedbackModule.java                      facade re-exporting public service interfaces
├── api/
│   ├── controller/                          FeedbackController, ClarificationQueryController
│   ├── dto/                                 records (see DTOs section)
│   └── mapper/                              FeedbackEntryMapper, RoutingLogMapper,
│                                             ClarificationQueryMapper, MisclassificationCorrectionMapper
├── domain/
│   ├── entity/                              JPA entities (see Entities section)
│   ├── repository/                          Spring Data interfaces — package-private
│   └── service/
│       ├── FeedbackQueryService.java        public interface
│       ├── FeedbackUpdateService.java       public interface
│       ├── FeedbackServiceImpl.java         single impl of both
│       └── internal/
│           ├── FeedbackClassifier            wraps AiService.execute(FeedbackClassificationTask)
│           ├── FeedbackClassificationTask    AiTask<ClassificationResult> implementation
│           ├── FeedbackRouter                dispatches each classification to its destination
│           ├── DestinationDispatcher         per-destination strategy interface + four impls
│           ├── ConfidenceGate                applies the ≥0.8 / 0.5-0.8 / <0.5 thresholds
│           └── CorrectionReplayer            re-fires routing for a corrected destination
├── event/                                   FeedbackSubmittedEvent, FeedbackProcessedEvent,
│                                             FeedbackMisclassificationCorrectedEvent
├── exception/                               module-root + per-failure subclasses
├── validation/                              @ValidUiContext, @ValidDestination
└── config/                                  FeedbackJsonConfig, FeedbackAsyncConfig
```

`FeedbackModule.java` carries no business logic — it gives other modules a one-line view of what's exposed.

The `internal/` subpackage holds the two seams that are most likely to change as the system learns: the classifier (today: one cheap-tier AiTask; tomorrow: multi-turn or a learned classifier per [feedback-system.md §Core Design Principle](../design/feedback-system.md#core-design-principle-interface-everything-for-upgrade)) and the router (today: synchronous fan-out; tomorrow: per-destination retry, async fan-out, or batched apply). Keeping them package-private behind `FeedbackUpdateService` makes that swap a one-package change.

`DestinationDispatcher` is one interface with four implementations (`RecipeDestinationDispatcher`, `PreferenceDestinationDispatcher`, `NutritionDestinationDispatcher`, `ProvisionsDestinationDispatcher`). Each translates a `(extractedFeedback, uiContext, structured fields)` triple into the destination's specific update call. Spring picks all four up via an injected `Map<Destination, DestinationDispatcher>`. New destinations are added by introducing a new enum constant and a new dispatcher bean; the router code does not change.

---

## Database

Migrations live under `src/main/resources/db/migration/` with the project-wide timestamp scheme from [technical-architecture.md §Migrations](../design/technical-architecture.md#migrations):

```
V20260501130000__feedback_create_entries.sql
V20260501130100__feedback_create_routing_log.sql
V20260501130200__feedback_create_misclassification_corrections.sql
V20260501130300__feedback_create_clarification_queries.sql
```

Timestamps follow the AI module (`V20260501110000__...`) and the preference module (`V20260501120000__...`). Nothing in this schema cross-references other modules' tables; cross-module identifiers (e.g. `recipe_id`, `meal_slot_id`) are stored as opaque UUIDs in the `ui_context` JSONB.

### V20260501130000 — Feedback entries

```sql
CREATE TABLE feedback_entries (
    id                       uuid PRIMARY KEY,
    user_id                  uuid NOT NULL,
    text                     text NOT NULL,                   -- raw user input; never truncated
    ui_context               jsonb NOT NULL,                  -- shape mirrored by UiContextDocument
    submission_status        varchar(24) NOT NULL,            -- RECEIVED | CLASSIFYING |
                                                              --  CLASSIFIED | CLARIFICATION_PENDING |
                                                              --  ROUTED | PARTIALLY_FAILED |
                                                              --  FAILED | CORRECTED
    classification_attempts  integer NOT NULL DEFAULT 0,      -- counts re-classifications after clarification
    last_classified_at       timestamptz,                     -- null until first classification completes
    trace_id                 uuid NOT NULL,                   -- propagates to ai_call_log + downstream events
    optimistic_version       bigint NOT NULL DEFAULT 0,       -- @Version
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);
-- User-history page on the feedback timeline view.
CREATE INDEX idx_feedback_entries_user_created ON feedback_entries (user_id, created_at DESC);
-- Async classifier sweep finds unstarted submissions if the in-process publish failed.
CREATE INDEX idx_feedback_entries_status_created
    ON feedback_entries (submission_status, created_at)
    WHERE submission_status IN ('RECEIVED', 'CLASSIFYING');
-- Trace lookup on cross-module debugging.
CREATE INDEX idx_feedback_entries_trace ON feedback_entries (trace_id);
```

`text` is `text` not `varchar` — feedback is unbounded prose. `ui_context` is JSONB because the shape is read whole, varies per screen, and we never filter SQL on inner fields. Schema mirror: `UiContextDocument` (Java record). The full state machine is governed in service code; the column captures the latest known state and is updated as the entry progresses.

`classification_attempts` increments each time the entry is re-classified after a `<0.5` clarification answer.

### V20260501130100 — Routing log

```sql
CREATE TABLE feedback_routing_log (
    id                       uuid PRIMARY KEY,
    feedback_entry_id        uuid NOT NULL REFERENCES feedback_entries(id) ON DELETE CASCADE,
    destination              varchar(16) NOT NULL,            -- RECIPE | PREFERENCE | NUTRITION | PROVISIONS
    confidence               numeric(4,3) NOT NULL,           -- 0.000 .. 1.000
    extracted_feedback       text NOT NULL,                   -- the slice the classifier attributed to this destination
    structured_payload       jsonb NOT NULL,                  -- destination-specific structured fields from the classifier
    routing_decision         varchar(24) NOT NULL,            -- AUTO_ROUTED | ROUTED_WITH_FLAG | CLARIFICATION_QUEUED
    status                   varchar(24) NOT NULL,            -- PENDING | APPLIED | FAILED |
                                                              --  CORRECTED_AWAY | REPLAYED |
                                                              --  AWAITING_USER_APPROVAL
    action_taken             varchar(512),                    -- human-readable summary returned by the destination
    destination_result_json  jsonb,                            -- destination-typed result (e.g. AdaptationResult shell)
    failure_kind             varchar(32),                     -- TRANSIENT | DESTINATION_VALIDATION |
                                                              --  DESTINATION_BUSINESS | UNKNOWN
    failure_message          varchar(512),                    -- truncated; never the API key
    superseded_by            uuid REFERENCES feedback_routing_log(id) ON DELETE SET NULL,
                                                              -- set on the original row when a correction replays
    classification_attempt   integer NOT NULL,                -- which attempt (1-indexed) produced this routing
    routed_at                timestamptz NOT NULL,
    completed_at             timestamptz,                     -- when status moved to terminal (APPLIED/FAILED/CORRECTED_AWAY)
    created_at               timestamptz NOT NULL
);
-- Confirmation view: list every routing for a given entry, in routing order.
CREATE INDEX idx_routing_log_entry_routed
    ON feedback_routing_log (feedback_entry_id, routed_at);
-- Quality monitoring: distribution by destination + confidence over time.
CREATE INDEX idx_routing_log_dest_confidence
    ON feedback_routing_log (destination, routed_at DESC);
-- Failure dashboards.
CREATE INDEX idx_routing_log_status
    ON feedback_routing_log (status, routed_at DESC)
    WHERE status IN ('FAILED', 'PENDING');
```

The routing log is the audit trail. Each row is one (feedback, destination) pair; an N-destination feedback writes N rows. Append-only by intent: a successful row is never deleted, never edited beyond the `status` / `action_taken` / `destination_result_json` / `completed_at` fields. Misclassification corrections introduce a sibling row pointing back via `superseded_by`. **No `@Version`** — concurrency on the log is by single-writer-per-row and the parent entry's optimistic lock.

`structured_payload` is the destination-specific subset that the classifier extracted (e.g. `{ "recipeId": "...", "affectsPlan": true }` for `RECIPE`, `{ "type": "COST_CONCERN" }` for `PROVISIONS`). The classifier is allowed to extract whatever it considers useful; the schema is loose because each destination uses different fields. The destination dispatcher is responsible for parsing what it cares about.

### V20260501130200 — Misclassification corrections

```sql
CREATE TABLE feedback_misclassification_corrections (
    id                       uuid PRIMARY KEY,
    feedback_entry_id        uuid NOT NULL REFERENCES feedback_entries(id) ON DELETE CASCADE,
    original_routing_id      uuid NOT NULL REFERENCES feedback_routing_log(id),
    corrected_destination    varchar(16) NOT NULL,            -- RECIPE | PREFERENCE | NUTRITION | PROVISIONS
    user_correction_note     varchar(512),                    -- optional free text
    actor_user_id            uuid NOT NULL,                   -- who did the correction (always the feedback owner today)
    original_confidence      numeric(4,3) NOT NULL,           -- copied for ground-truth labelling
    original_destination     varchar(16) NOT NULL,            -- copied likewise
    replay_routing_id        uuid REFERENCES feedback_routing_log(id),
                                                              -- set after the corrected destination runs
    replay_status            varchar(24) NOT NULL,            -- APPLIED | FAILED | DESTINATION_REJECTED
    occurred_at              timestamptz NOT NULL
);
-- Quality monitoring dataset (see HLD): correction-rate metric, ground-truth export.
CREATE INDEX idx_misclassification_routing
    ON feedback_misclassification_corrections (original_routing_id);
CREATE INDEX idx_misclassification_entry_time
    ON feedback_misclassification_corrections (feedback_entry_id, occurred_at DESC);
```

This is the **ground-truth dataset** referenced in [feedback-system.md §Ground truth from corrections](../design/feedback-system.md#ground-truth-from-corrections). It deliberately copies `original_destination`, `original_confidence`, and the feedback id so a future fine-tune or few-shot pipeline can pull labelled examples without joining back to the routing log every time.

### V20260501130300 — Clarification queries

```sql
CREATE TABLE feedback_clarification_queries (
    id                       uuid PRIMARY KEY,
    feedback_entry_id        uuid NOT NULL REFERENCES feedback_entries(id) ON DELETE CASCADE,
    classifier_options_json  jsonb NOT NULL,                  -- the classifier's best-guess shortlist
    question_text            varchar(512) NOT NULL,           -- canned text + classifier's own framing
    status                   varchar(24) NOT NULL,            -- PENDING | ANSWERED | EXPIRED
    selected_destination     varchar(16),                     -- null until answered; then RECIPE/PREFERENCE/...
    user_clarification_text  text,                            -- optional free-text addition; appended on re-classify
    expires_at               timestamptz NOT NULL,            -- 7-day TTL by default
    answered_at              timestamptz,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);
-- User-facing inbox: open clarifications for this user, oldest first.
CREATE INDEX idx_clarification_user_status_created
    ON feedback_clarification_queries (status, created_at)
    WHERE status = 'PENDING';
-- Daily expiry sweep.
CREATE INDEX idx_clarification_expires
    ON feedback_clarification_queries (expires_at)
    WHERE status = 'PENDING';
```

The HLD's `<0.5` confidence path becomes a **persisted question queue**. The classifier's shortlist (with its own best guesses, e.g. "did you mean: (a) the recipe needs changing, (b) your preferences need updating?") is captured in `classifier_options_json` — a list of `{ destination, snippet, classifierJustification }`. The user's response either selects one option or types a clarification, and the classifier re-runs.

The 7-day TTL avoids an ever-growing inbox of forgotten clarifications. Expired queries are archived (`status = EXPIRED`) and the parent feedback entry's `submission_status` becomes `FAILED` with `failureKind = UNRESOLVED_CLARIFICATION`. **Default TTL of 7 days is not in the HLD — worth user review.**

---

## Entities

All entities follow the style guide: UUID `@Id` set application-side, `@CreatedDate`/`@LastModifiedDate` audit columns, Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor`. JSONB columns mapped via `@Type(JsonType.class)` from `hypersistence-utils`.

| Entity | Notes |
|---|---|
| `FeedbackEntry` | Aggregate root. `userId` indexed. `uiContext` mapped to `UiContextDocument` record via JSONB. `submissionStatus` enum (`SubmissionStatus`). `@OneToMany` to `RoutingLogEntry` (cascade ALL, lazy). `@Version Long optimisticVersion`. |
| `RoutingLogEntry` | Append-only-ish (only the status/action/result/superseded fields update). `@ManyToOne(fetch = LAZY)` back to `FeedbackEntry`. No `@Version` — single-writer-per-row by router. `structuredPayload` and `destinationResultJson` as `JsonNode`. |
| `MisclassificationCorrection` | Append-only. No `@Version`, no `@LastModifiedDate`. `originalRoutingId`, `replayRoutingId` are explicit UUID columns (no FK relation in JPA — kept as raw UUIDs to avoid an ownership cycle with `RoutingLogEntry`'s `supersededById`). |
| `ClarificationQuery` | One row per pending query. `@OneToOne(fetch = LAZY)` to its triggering `FeedbackEntry`. `classifierOptionsJson` as `JsonNode`. `@Version` because the user can answer concurrently with the daily expiry sweep. |

Module-local enums:

- `Destination { RECIPE, PREFERENCE, NUTRITION, PROVISIONS }` — exactly the four from [feedback-system.md §Four Destinations](../design/feedback-system.md#four-destinations).
- `SubmissionStatus { RECEIVED, CLASSIFYING, CLASSIFIED, CLARIFICATION_PENDING, ROUTED, PARTIALLY_FAILED, FAILED, CORRECTED }`.
- `RoutingDecision { AUTO_ROUTED, ROUTED_WITH_FLAG, CLARIFICATION_QUEUED }`.
- `RoutingStatus { PENDING, APPLIED, FAILED, CORRECTED_AWAY, REPLAYED, AWAITING_USER_APPROVAL }`.
- `RoutingFailureKind { TRANSIENT, DESTINATION_VALIDATION, DESTINATION_BUSINESS, AI_UNAVAILABLE, UNKNOWN }`.
- `ClarificationStatus { PENDING, ANSWERED, EXPIRED }`.
- `CorrectionReplayStatus { APPLIED, FAILED, DESTINATION_REJECTED }`.

`AWAITING_USER_APPROVAL` covers the recipe path: the optimiser produces a *proposed* adaptation, not an applied one ([system-overview.md §Recipe Optimiser](../design/system-overview.md#recipe-optimiser) — "propose, not apply for user recipes"). Routing succeeds at the dispatcher level; the actual change is approved later in the optimiser's UI.

---

## DTOs

All DTOs are Java records per the style guide.

### Submission and confirmation

```java
public record SubmitFeedbackRequest(
    @NotBlank @Size(max = 4000) String text,
    @NotNull @Valid UiContextDto context
) {}

public record UiContextDto(
    @NotBlank Screen screen,             // enum below
    UUID recipeId,                        // optional — present when screen attaches a recipe
    Integer recipeVersion,                // optional
    UUID mealSlotId,                      // optional
    UUID planId,                          // optional
    LocalDate referenceDate               // optional — the day the feedback is about
) {}

public enum Screen {
    RECIPE_DETAIL, PLAN_MEAL_DETAIL, PLAN_VIEW,
    GROCERY, NUTRITION_DASHBOARD, SETTINGS, GENERAL
}

public record FeedbackEntryDto(
    UUID id, UUID userId,
    String text,
    UiContextDto context,
    SubmissionStatus submissionStatus,
    int classificationAttempts,
    Instant lastClassifiedAt,
    UUID traceId,
    List<RoutingDecisionDto> routes,                 // populated when status >= CLASSIFIED
    UUID pendingClarificationQueryId,                // null unless awaiting clarification
    Instant createdAt, Instant updatedAt
) {}
```

`Screen` is restated module-locally rather than imported from any cross-module enum; the values come straight from [feedback-system.md §Entry Points and Context](../design/feedback-system.md#entry-points-and-context). Future screens add a constant here only.

### Classification result (the structured AI output)

```java
public record ClassificationResult(
    @NotNull @Size(min = 0, max = 4) List<@Valid ClassificationOutput> classifications,
    @Min(0) @Max(1) BigDecimal overallConfidence,    // optional aggregate the classifier MAY emit
    String classifierNotes                            // free-text the classifier may include for the routing log
) {}

public record ClassificationOutput(
    @NotNull Destination destination,
    @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
    @NotBlank String extractedFeedback,
    @NotNull JsonNode structuredPayload              // destination-specific; see Routing
) {}
```

`ClassificationResult` is the `T` in `AiTask<ClassificationResult>` ([Classifier section](#classifier-aitask) below). Jackson tool-use parsing populates it. JSR-303 annotations on the record drive the AI module's semantic-retry path ([ai.md §Flow 3](ai.md#flow-3-structured-output-parsing)) — a classifier emitting `confidence: 1.5` triggers one corrective re-prompt before failing.

`max = 4` caps the destinations because the universe is exactly four. Empty list (`size = 0`) is allowed: a feedback that the classifier decided was non-actionable ("thanks, this looks good!") routes to nothing and the entry's `submissionStatus` lands at `ROUTED` with zero routes.

### Per-route DTOs returned to the client

```java
public record RoutingDecisionDto(
    UUID id,                                          // the routing_log row id; used to address corrections
    Destination destination,
    BigDecimal confidence,
    RoutingDecision decision,                         // AUTO_ROUTED | ROUTED_WITH_FLAG | CLARIFICATION_QUEUED
    RoutingStatus status,                             // PENDING | APPLIED | FAILED | CORRECTED_AWAY | ...
    String extractedFeedback,
    String actionTaken,
    Object destinationResult,                         // typed shell — see below
    String failureMessage                             // null on success
) {}

public record SubmitFeedbackResponse(
    UUID feedbackId,
    UUID traceId,
    SubmissionStatus submissionStatus,
    List<RoutingDecisionDto> routes,                  // empty until classification completes
    UUID pendingClarificationQueryId                  // null unless < 0.5 path triggered
) {}
```

`destinationResult` is intentionally `Object` in the DTO surface — Jackson serialises whatever the destination dispatcher placed there. For the recipe destination it carries the optimiser's `AdaptationResult` shell (recipe id, proposed change summary, status). For preference / nutrition / provisions it carries small confirmation records (e.g. `PreferenceFeedbackAck { ackId, batchedFor, immediateApply }`). The four shells are defined inside each destination's LLD; this module never types them.

### Correction and clarification

```java
public record CorrectionRequest(
    @NotNull Destination newDestination,
    @Size(max = 512) String userCorrectionNote
) {}

public record ClarificationQueryDto(
    UUID id,
    UUID feedbackEntryId,
    String questionText,
    List<ClarificationOptionDto> options,
    ClarificationStatus status,
    Instant expiresAt,
    Instant createdAt
) {}

public record ClarificationOptionDto(
    Destination destination,
    String snippet,
    String classifierJustification
) {}

public record AnswerClarificationRequest(
    Destination selectedDestination,                  // exactly one of the four; null if user types instead
    @Size(max = 4000) String userClarificationText    // optional free-text refinement
) {
    @AssertTrue(message = "Either selectedDestination or userClarificationText must be provided")
    public boolean isAtLeastOneProvided() {
        return selectedDestination != null
            || (userClarificationText != null && !userClarificationText.isBlank());
    }
}
```

`ClassificationResultDto` is the public-facing view for read endpoints (the inner `ClassificationResult` is internal to the classification flow):

```java
public record ClassificationResultDto(
    int attempt,
    Instant performedAt,
    List<RoutingDecisionDto> classifications,
    BigDecimal overallConfidence,
    String classifierNotes
) {}
```

---

## Mappers

MapStruct interfaces, `@Mapper(componentModel = "spring")`. One per entity-DTO pair.

```java
@Mapper(componentModel = "spring", uses = { RoutingLogMapper.class })
public interface FeedbackEntryMapper {
    FeedbackEntryDto toDto(FeedbackEntry entity);
    List<FeedbackEntryDto> toDtos(List<FeedbackEntry> entities);
}

@Mapper(componentModel = "spring")
public interface RoutingLogMapper {
    RoutingDecisionDto toDto(RoutingLogEntry entity);
    List<RoutingDecisionDto> toDtos(List<RoutingLogEntry> entities);
}

@Mapper(componentModel = "spring")
public interface ClarificationQueryMapper {
    ClarificationQueryDto toDto(ClarificationQuery entity);
    List<ClarificationQueryDto> toDtos(List<ClarificationQuery> entities);
}

@Mapper(componentModel = "spring")
public interface MisclassificationCorrectionMapper {
    MisclassificationCorrectionDto toDto(MisclassificationCorrection entity);
}
```

`destinationResult` is a custom-mapped field via `@Named("readDestinationResult")` — it deserialises the JSONB into a typed shell when the destination is known. On unknown shapes (e.g. an old row whose schema has drifted) it falls back to a generic `Map<String, Object>`.

---

## Repositories

Package-private; cross-module access goes through service interfaces only.

```java
interface FeedbackEntryRepository extends JpaRepository<FeedbackEntry, UUID> {
    Optional<FeedbackEntry> findById(UUID id);

    @EntityGraph(attributePaths = { "routingLog" })
    Optional<FeedbackEntry> findWithRoutingByIdAndUserId(UUID id, UUID userId);

    Page<FeedbackEntry> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    // Async sweep: anything stuck in CLASSIFYING for more than the threshold is retried.
    List<FeedbackEntry> findBySubmissionStatusInAndCreatedAtBefore(
        Collection<SubmissionStatus> statuses, Instant before);
}

interface RoutingLogRepository extends JpaRepository<RoutingLogEntry, UUID> {
    List<RoutingLogEntry> findByFeedbackEntryIdOrderByRoutedAtAsc(UUID entryId);
    Optional<RoutingLogEntry> findByIdAndFeedbackEntryUserId(UUID id, UUID userId);

    // Quality monitoring (admin endpoint owned later — exposed via a separate metrics service in v2).
    @Query("""
        select r.destination, count(r), avg(r.confidence)
          from RoutingLogEntry r
         where r.routedAt between :from and :to
         group by r.destination
        """)
    List<DestinationRollupRow> aggregateByDestination(
        @Param("from") Instant from, @Param("to") Instant to);
}

interface MisclassificationCorrectionRepository
        extends JpaRepository<MisclassificationCorrection, UUID> {
    Page<MisclassificationCorrection> findByFeedbackEntryUserIdOrderByOccurredAtDesc(
        UUID userId, Pageable pageable);

    long countByOccurredAtBetween(Instant from, Instant to);
    long countByOriginalDestinationAndOccurredAtBetween(
        Destination destination, Instant from, Instant to);
}

interface ClarificationQueryRepository extends JpaRepository<ClarificationQuery, UUID> {
    Optional<ClarificationQuery> findByIdAndFeedbackEntryUserId(UUID id, UUID userId);

    Page<ClarificationQuery> findByFeedbackEntryUserIdAndStatusOrderByCreatedAtAsc(
        UUID userId, ClarificationStatus status, Pageable pageable);

    // Daily expiry sweep.
    List<ClarificationQuery> findByStatusAndExpiresAtBefore(
        ClarificationStatus status, Instant before);
}
```

`@EntityGraph` on `findWithRoutingByIdAndUserId` keeps the per-entry detail load to a single JOIN — the routing log list is always read together with the entry on the confirmation view. The user-history list view does **not** eager-load the routing log; the page renders a count and a pill summary only.

---

## Service Interfaces

Per the style guide, both module interfaces are implemented by a single `FeedbackServiceImpl`.

### `FeedbackQueryService`

```java
public interface FeedbackQueryService {

    Optional<FeedbackEntryDto> getById(UUID userId, UUID feedbackId);
    List<FeedbackEntryDto> getByIds(UUID userId, List<UUID> feedbackIds);

    Page<FeedbackEntryDto> listByUser(UUID userId, Pageable pageable);

    // Routing log read; convenience over loading the full entry.
    Optional<RoutingDecisionDto> getRoutingDecision(UUID userId, UUID routingId);

    // Clarification queue (the < 0.5 inbox).
    Page<ClarificationQueryDto> listClarificationQueries(
        UUID userId, ClarificationStatus status, Pageable pageable);
    Optional<ClarificationQueryDto> getClarificationQuery(UUID userId, UUID queryId);

    // Correction history — drives the "ground truth" CSV export endpoint and admin metrics.
    Page<MisclassificationCorrectionDto> listCorrections(UUID userId, Pageable pageable);
}
```

### `FeedbackUpdateService`

```java
public interface FeedbackUpdateService {

    /**
     * Submits free-text feedback. Returns immediately with submissionStatus = RECEIVED;
     * classification + routing run after commit (see Flow 1). Subsequent calls to
     * getById return progressively richer state as classification and routing complete.
     */
    SubmitFeedbackResponse submitFeedback(UUID userId, SubmitFeedbackRequest request);

    /**
     * User-driven correction of a single routing decision. Cancels the original route
     * (best-effort), re-fires the corrected destination, and writes a correction row.
     */
    SubmitFeedbackResponse correctMisclassification(
        UUID userId, UUID feedbackId, UUID routingId, CorrectionRequest request);

    /**
     * User answers a < 0.5 clarification. The classifier re-runs with the additional
     * context and the entry advances down the standard routing pipeline.
     */
    SubmitFeedbackResponse answerClarificationQuery(
        UUID userId, UUID queryId, AnswerClarificationRequest request);

    // Admin / sweep entry points — not REST-exposed; called from @Scheduled jobs.
    void retryStuckClassifications();        // every 2 minutes
    void expireOldClarificationQueries();    // daily 04:00 UTC
}
```

`submitFeedback` returns the receipt eagerly — classification is async after commit so the user sees an instant acknowledgement and the confirmation message lands on the next read. This matches the HLD's flow: "the confirmation message appears immediately after feedback" ([feedback-system.md §Correction limitations](../design/feedback-system.md#correction-limitations)) without holding a transaction across the AI call.

`actorUserId` is **not** threaded as a separate argument here. Today, only the feedback owner submits or corrects their own feedback (no household-admin override). When that changes, the signature follows the preference module's precedent: an extra `UUID actorUserId` argument and an audit field on `MisclassificationCorrection`. **Worth user review** — kept simple for v1.

---

## Classifier (AiTask)

The classifier is the module's only AI dependency. It is invoked exclusively from `FeedbackClassifier`, an internal helper that wraps a fresh `FeedbackClassificationTask` instance per call.

```java
public final class FeedbackClassificationTask implements AiTask<ClassificationResult> {

    private final FeedbackClassificationContext context;

    public FeedbackClassificationTask(FeedbackClassificationContext context) {
        this.context = context;
    }

    @Override public TaskType    getTaskType()        { return TaskType.FEEDBACK_CLASSIFICATION; }
    @Override public String      getSystemPrompt()    { return null;  /* template carries it */ }
    @Override public PromptRef   getUserPromptRef()   { return new PromptRef("feedback/classify-feedback", Optional.empty()); }
    @Override public Map<String, Object> getContext() { return context.toRendererMap(); }
    @Override public ToolDefinition getToolSchema()   { return ToolDefinitions.classifyFeedback(); }
    @Override public Class<ClassificationResult> getResponseType() { return ClassificationResult.class; }
    @Override public UUID        getUserId()          { return context.userId(); }
    @Override public UUID        getTraceId()         { return context.traceId(); }
    @Override public Optional<Duration> getTimeoutOverride() { return Optional.empty(); }
}

public record FeedbackClassificationContext(
    UUID userId,
    UUID traceId,
    String feedbackText,
    UiContextDto uiContext,
    Optional<String> userClarificationText,           // appended on re-classification after a < 0.5 answer
    Optional<Destination> userSelectedHint,           // set when the clarification answer picked an option
    int attemptNumber                                 // 1 for first run; 2+ after clarification
) {
    public Map<String, Object> toRendererMap() { /* trivial getter-style map for the template renderer */ }
}
```

`TaskType.FEEDBACK_CLASSIFICATION` is already enumerated in [ai.md §SPI](ai.md#spi--what-calling-modules-implement) at the cheap tier. Per [ai.md §Configuration](ai.md#configuration), the per-task token cap and timeout live in `AiConfig.taskTypeTokenCaps` / `AiConfig.taskTypeTimeoutMs` — defaults are not pinned in this LLD; see Out of Scope.

`getSystemPrompt()` returning `null` reflects the classifier convention used elsewhere in the project: the system message lives in the same template file as the user prompt, and the renderer separates the two on a marker line. This avoids a code-vs-content split for content the prompt-engineering work owns end-to-end.

`ToolDefinitions.classifyFeedback()` returns the JSON schema for `ClassificationResult` — generated once at startup via the `victools/jsonschema-generator` flow described in [ai.md §Flow 3](ai.md#flow-3-structured-output-parsing). Pinning the schema to the Java type means a record-shape change forces a tool-schema change without a parallel manual edit.

**The prompt content (system message, user template, eval set) is deferred** — owned by the prompt-engineering work track per [lld/README.md §LLM prompts to design](README.md). This LLD specifies only the `AiTask` plumbing.

### Classifier failure handling — graceful degrade

Per [style-guide.md §AI Service — Graceful Degradation](style-guide.md#ai-service--graceful-degradation), every module that calls AI must spec what happens on `AiUnavailable`. The feedback classifier uses **defer-and-pending**:

- `AiService.execute` throws `AiUnavailableException` → the entry stays at `submissionStatus = RECEIVED`, no routing log rows written, no clarification query.
- A `@Scheduled(fixedDelay = 2min)` sweep retries via `retryStuckClassifications()` against entries that have been `RECEIVED` for more than 5 minutes.
- After 24h of failed retries the entry escalates to `submissionStatus = FAILED` and an admin-visible record stays in the table for postmortem.
- The user sees a soft "AI features paused — your feedback is saved and will route automatically when service resumes" banner on the confirmation view.

`AiResponseInvalidException` (parsing failed even after the corrective re-prompt — see [ai.md §Flow 1 step 8](ai.md#flow-1-dispatch-aiserviceexecute)) is treated as a terminal classification failure for that attempt — the entry transitions to `FAILED` immediately and the user sees a fallback "we couldn't make sense of this — could you split it up?" message. The retry sweep does not re-classify a parser failure: the prompt is the bug, not the runtime.

`AiCircuitOpenException` is treated like `AiUnavailableException` — defer to the sweep.

---

## REST Controllers

All endpoints under `/api/v1/feedback/...` and `/api/v1/feedback/clarifications/...`. `userId` resolved server-side from auth context per [technical-architecture.md §Frontend-Backend Contract](../design/technical-architecture.md#frontend-backend-contract). OpenAPI: `@Tag(name = "Feedback")` on the main controller; `@Tag(name = "Feedback — clarifications")` on the secondary; `@Operation` on each handler.

The split into two controllers follows the pilot's multi-controller precedent and matches the user-facing distinction: one controller is "submit & inspect feedback", the other is "answer the clarification queue". They share the same module facade and service.

### `FeedbackController`

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| POST   | `/api/v1/feedback` | `SubmitFeedbackRequest` | `SubmitFeedbackResponse` | 202 / 400 |
| GET    | `/api/v1/feedback?page=&size=` | — | `Page<FeedbackEntryDto>` | 200 |
| GET    | `/api/v1/feedback/{feedbackId}` | — | `FeedbackEntryDto` | 200 / 404 |
| POST   | `/api/v1/feedback/{feedbackId}/routes/{routingId}/correct` | `CorrectionRequest` | `SubmitFeedbackResponse` | 200 / 400 / 404 / 422 |
| GET    | `/api/v1/feedback/corrections?page=&size=` | — | `Page<MisclassificationCorrectionDto>` | 200 |

`POST /feedback` returns **202 Accepted** because classification and routing are async after commit. The body is the eager receipt — `submissionStatus = RECEIVED` and `routes = []`. The client polls `GET /feedback/{feedbackId}` for the populated routes (or, in v2, subscribes via SSE).

**400** on the submit covers: empty `text`, oversized `text` (> 4000 chars), unknown `screen`, malformed `recipeId` UUID. **422** on correction covers: corrected destination matches the original (no-op), the original routing is in a non-correctable terminal state (e.g. already `CORRECTED_AWAY`), the misclassification correction would target a destination unsupported by the feedback's structured payload.

### `ClarificationQueryController`

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| GET    | `/api/v1/feedback/clarifications?status=&page=&size=` | — | `Page<ClarificationQueryDto>` | 200 |
| GET    | `/api/v1/feedback/clarifications/{queryId}` | — | `ClarificationQueryDto` | 200 / 404 |
| POST   | `/api/v1/feedback/clarifications/{queryId}/answer` | `AnswerClarificationRequest` | `SubmitFeedbackResponse` | 200 / 400 / 404 / 410 |

**410 Gone** on `answer` covers an expired query — the client got there too late and must re-submit the original feedback. The body of the 410 is a ProblemDetail with the original feedback id so the client can show "this conversation expired — would you like to re-submit?".

### Error responses

All error responses use RFC 9457 `ProblemDetail`. Module-specific exceptions and their mappings (handled in the project-wide `GlobalExceptionHandler`):

| Exception | Status | `type` URI |
|---|---|---|
| `FeedbackEntryNotFoundException` | 404 | `https://mealprep.example.com/problems/feedback-entry-not-found` |
| `RoutingDecisionNotFoundException` | 404 | `https://mealprep.example.com/problems/routing-decision-not-found` |
| `ClarificationQueryNotFoundException` | 404 | `https://mealprep.example.com/problems/clarification-query-not-found` |
| `ClarificationQueryExpiredException` | 410 | `https://mealprep.example.com/problems/clarification-query-expired` |
| `InvalidCorrectionTargetException` | 422 | `https://mealprep.example.com/problems/invalid-correction-target` |
| `ClassificationFailedException` | 502 | `https://mealprep.example.com/problems/classification-failed` |
| `OptimisticLockException` (JPA) | 409 | `https://mealprep.example.com/problems/optimistic-lock` |
| `MethodArgumentNotValidException` | 400 | `errors[]` extension on ProblemDetail |

Module root: `FeedbackException extends MealPrepException`.

`ClassificationFailedException` is only raised on synchronous-path classifications that the framework chooses to surface (e.g. a forced-synchronous integration test); ordinary user submissions never see it because the classifier runs after commit.

---

## Validation

Standard Jakarta annotations applied at request-record level: `@NotNull`, `@NotBlank`, `@Size`, `@Min`/`@Max`, `@DecimalMin`/`@DecimalMax`, `@Valid` for nested records.

Custom validators in `validation/`:

- **`@ValidUiContext`** (class-level on `UiContextDto`) — asserts that the screen-implied IDs are present where the screen demands them: `RECIPE_DETAIL` requires `recipeId`; `PLAN_MEAL_DETAIL` requires `planId` and `mealSlotId`; `GENERAL` and `SETTINGS` require none. Also asserts `recipeVersion` is `null` when `recipeId` is `null`. The validator is a class-level `ConstraintValidator<ValidUiContext, UiContextDto>`.
- **`@ValidDestination`** — applied on `CorrectionRequest.newDestination`. Asserts the destination is a recognised enum value (Jakarta's enum binding handles unknown strings as 400; this validator additionally rejects destinations that are structurally impossible given the feedback's existing `structured_payload` — e.g. correcting to `RECIPE` requires a `recipeId` somewhere in the entry's UI context or extracted payload).

`ClassificationResult` validation runs inside the AI module's parsing path ([ai.md §Flow 1 step 8](ai.md#flow-1-dispatch-aiserviceexecute)) — the JSR-303 annotations on `ClassificationResult` and `ClassificationOutput` drive a single corrective re-prompt before the dispatcher gives up.

Validation failures bubble up as `MethodArgumentNotValidException` mapped to 400 ProblemDetails by the global advice.

---

## Events

Per [technical-architecture.md §Event catalogue](../design/technical-architecture.md#event-catalogue):

### Published

```java
public record FeedbackSubmittedEvent(
    UUID feedbackId, UUID userId,
    Screen screen,                                    // for telemetry
    UUID traceId, Instant occurredAt
) {}

public record FeedbackProcessedEvent(
    UUID feedbackId, UUID userId,
    Set<Destination> destinationsTouched,             // empty when classifier returned zero classifications
    boolean partialFailure,                           // true if any route ended FAILED
    boolean clarificationPending,                     // true if a < 0.5 query was queued
    UUID traceId, Instant occurredAt
) {}

public record FeedbackMisclassificationCorrectedEvent(
    UUID feedbackId, UUID originalRoutingId, UUID replayRoutingId,
    Destination originalDestination, Destination correctedDestination,
    BigDecimal originalConfidence,
    UUID userId, UUID traceId, Instant occurredAt
) {}
```

`FeedbackSubmittedEvent` fires after the submission transaction commits and is the trigger for the async classification pipeline (the listener is in this module — [Flow 2](#flow-2-classification)). The HLD does not enumerate this event in the catalogue because it is internal to the module's async plumbing; the listener never reaches outside the package. Listed here so the publication site is grep-able.

`FeedbackProcessedEvent` is the catalogued event consumed by the Notification module ([technical-architecture.md §Event catalogue](../design/technical-architecture.md#event-catalogue)). One event per entry, regardless of how many destinations were routed to (per the catalogue's "**one event per feedback entry**" debounce note). Fires after every routing transaction has either committed or terminally failed.

`FeedbackMisclassificationCorrectedEvent` lets the destinations' update services act on the correction without polling. The HLD doesn't enumerate it ([feedback-system.md §Correction flow](../design/feedback-system.md#correction-flow) is service-call-driven), but the addition lets:

- The preference module purge a delta if the ground truth was that the feedback was *not* preference signal.
- The notification module patch its earlier confirmation toast.
- A future quality-monitoring module re-tally the correction-rate metric without scanning the corrections table.

**Worth user review** — adding an event the HLD didn't catalogue.

Published via the standard `ApplicationEventPublisher` after the relevant write transaction. Listeners use `@TransactionalEventListener(phase = AFTER_COMMIT)` per the style guide.

### Consumed

The feedback module is the publisher of its main events; it does **not** listen to its own. It does, however, consume one cross-module event:

```java
@TransactionalEventListener(phase = AFTER_COMMIT)
@Async
public void onAiCallSucceeded(AiCallSucceededEvent event) { ... }
```

This is purely for **telemetry**: when the classifier call lands, the listener stamps the `lastClassifiedAt` field on the feedback entry by joining `event.traceId()` against `feedback_entries.trace_id`. The listener never alters classification outcome — that path runs synchronously inside Flow 2.

---

## Business Logic Flows

The five flows below cover the lifecycle: submission, classification, routing, correction with replay, and clarification answer. Each is `@Transactional` at the service-impl method unless explicitly noted.

### Flow 1: Submission

`POST /api/v1/feedback` → `submitFeedback(userId, request)`. `@Transactional` (default REQUIRED).

1. Validate `request` (Jakarta + `@ValidUiContext`).
2. Allocate `feedbackId = UUID.randomUUID()` and `traceId = UUID.randomUUID()` in service code (style guide: application-side IDs).
3. Construct `FeedbackEntry` with `submissionStatus = RECEIVED`, `text = request.text()`, `uiContext = request.context()`. Persist.
4. Publish `FeedbackSubmittedEvent` (after commit).
5. Return `SubmitFeedbackResponse(feedbackId, traceId, submissionStatus = RECEIVED, routes = [], pendingClarificationQueryId = null)`. Status 202.

The submission transaction is short and contains no AI calls — per [style-guide.md §Concurrency](style-guide.md#transaction-boundaries) and the project-wide rule that AI calls run outside transactions ([lld/README.md §Tier 1 architectural decisions](README.md#tier-1-architectural-decisions-locked)).

### Flow 2: Classification

Triggered by `FeedbackSubmittedEvent` via `@TransactionalEventListener(phase = AFTER_COMMIT) @Async`. Runs on the `feedback-classification-pool` (a `@Bean ThreadPoolTaskExecutor` configured in `FeedbackAsyncConfig` — 4 threads, queue size 100; rejection policy = `CallerRunsPolicy` so a saturated pool degrades to inline classification rather than dropping work).

The listener is **not transactional at entry** — it spans an external AI call. It opens short transactions around each DB write.

1. Mark the entry `submissionStatus = CLASSIFYING` (short tx, increments `classification_attempts`).
2. Build `FeedbackClassificationContext` from the entry. Append any `userClarificationText` if the entry was re-classified after a clarification answer.
3. Invoke `aiService.execute(new FeedbackClassificationTask(context))`. Catch:
   - `AiUnavailableException` → revert status to `RECEIVED`. End. The 2-min sweep retries.
   - `AiCircuitOpenException` → as above.
   - `AiResponseInvalidException` → mark entry `FAILED` (terminal); publish a degenerate `FeedbackProcessedEvent(destinationsTouched = ∅, partialFailure = true, clarificationPending = false)`. End.
   - `AiCallFailedException` after retries → as `AiResponseInvalidException`.
4. On success, the dispatcher returns a `ClassificationResult`. Apply `ConfidenceGate`:
   - **All classifications ≥ 0.5** → mark entry `CLASSIFIED`. Hand to **Flow 3**.
   - **Any classification < 0.5** → write one `ClarificationQuery` (combining all low-confidence options into one shortlist). Mark entry `CLARIFICATION_PENDING`. Set `pendingClarificationQueryId`. Publish `FeedbackProcessedEvent(clarificationPending = true)`. End.
   - **Empty classifications list** → entry transitions to `ROUTED` (with `routes = []`). Publish `FeedbackProcessedEvent(destinationsTouched = ∅, partialFailure = false)`.
5. Update `last_classified_at` timestamp.

The `ConfidenceGate` decision is per-classification, not aggregate. The HLD's 0.5-0.8 band fires the route automatically *with a flag* — the entry still moves forward and the user sees a "I think you meant X" annotation on the confirmation view. The < 0.5 path is mutually exclusive — if any single classification dips below 0.5, the **entire** entry pauses for clarification, even if other classifications were high-confidence. This matches the HLD's framing: a single feedback text is one user intent; partial routing without resolving the ambiguous part risks losing the user's full meaning. **Worth user review** — alternative is to route the high-confidence parts and queue a clarification only for the uncertain part.

### Flow 3: Routing (multi-destination split)

Called from Flow 2 step 4 when all classifications are ≥ 0.5. The router fans out across N destinations.

**Critical rule from the HLD ([feedback-system.md §How multi-destination works](../design/feedback-system.md#how-multi-destination-works))**: each destination write is its **own transaction**. A failure in one destination does not roll back the others. Partial success is acceptable, logged, and surfaced to the user.

Per classification:

1. Open a fresh transaction (`@Transactional(propagation = REQUIRES_NEW)` on `FeedbackRouter.route(routingLogId, classification)`).
2. Allocate `routingLogId = UUID.randomUUID()`. Persist the `RoutingLogEntry` with `status = PENDING`, `routing_decision = AUTO_ROUTED` or `ROUTED_WITH_FLAG` (per `ConfidenceGate`), `classification_attempt = entry.classificationAttempts`.
3. Look up the `DestinationDispatcher` for `classification.destination` from the injected `Map<Destination, DestinationDispatcher>`.
4. Call `dispatcher.dispatch(...)`:
   - **`RECIPE`** → `optimiserService.handleRecipeFeedback(recipeId, RecipeFeedbackInput)`. The optimiser may produce a proposed adaptation requiring user approval — set `status = AWAITING_USER_APPROVAL` rather than `APPLIED` when the result indicates pending approval. Recipe id resolution: prefer `classification.structuredPayload.recipeId`, fall back to `entry.uiContext.recipeId`. If neither is present, fail this routing with `failure_kind = DESTINATION_VALIDATION` and a message of "no recipe attached to this feedback".
   - **`PREFERENCE`** → `preferenceUpdateService.applyFeedback(userId, PreferenceFeedbackCommand)`.
   - **`NUTRITION`** → `nutritionUpdateService.applyFeedback(userId, NutritionFeedbackRequest)`.
   - **`PROVISIONS`** → `provisionUpdateService.applyFeedback(userId, ProvisionsFeedbackCommand)`.
5. The destination's call returns either a typed result (success) or throws. On return: stamp `status = APPLIED` (or `AWAITING_USER_APPROVAL` for recipe), `action_taken`, `destination_result_json`, `completed_at`. Commit.
6. On exception: classify by exception type into `RoutingFailureKind`, persist `status = FAILED`, `failure_kind`, `failure_message` (truncated 512 chars). Commit. **Do not re-throw** — the next destination must still run.

After all N destinations complete (or fail):

7. In a final short transaction, recompute the entry's `submissionStatus`:
   - All routes `APPLIED` or `AWAITING_USER_APPROVAL` → `ROUTED`.
   - Any `FAILED` and at least one not-failed → `PARTIALLY_FAILED`.
   - All `FAILED` → `FAILED`.
8. Publish `FeedbackProcessedEvent(feedbackId, userId, destinationsTouched, partialFailure, clarificationPending = false, ...)`. **One event per entry**, per [technical-architecture.md §Event debouncing](../design/technical-architecture.md#event-debouncing).

The hard rule is "feedback NEVER writes to `RecipeUpdateService` directly" ([feedback-system.md §Service Interface](../design/feedback-system.md#service-interface), [system-overview.md §Recipe Optimiser](../design/system-overview.md#recipe-optimiser)). The recipe path goes through `OptimiserService.handleRecipeFeedback` exclusively. The optimiser's LLD ([adaptation-pipeline.md](README.md#llds-still-to-write-6-docs), pending) owns the call's full signature; this LLD assumes it accepts at minimum `(recipeId, userId, extractedFeedback, traceId)` and returns an `AdaptationResult`-shaped record. **Worth user review** when the optimiser LLD lands.

`DestinationDispatcher.dispatch` is **synchronous** — the router waits for each destination call to return before moving on. The four destination calls run sequentially, not in parallel: the per-call latency is dominated by each destination's own AI work (preference deltas, optimiser adaptation), and parallel dispatch would hammer `AiService` with concurrent calls for the same trace, complicating cost-cap accounting. Sequential keeps the ordering predictable for the confirmation message and allows a future "stop on first cost-cap breach" optimisation. **Worth user review** — sequential is the conservative default; parallel is a follow-up if latency becomes a concern.

#### Routing failure-mode coverage

| Failure | `failure_kind` | Status | Recovery |
|---|---|---|---|
| Destination throws a Jakarta `ConstraintViolationException` | `DESTINATION_VALIDATION` | `FAILED` | User sees the specific message; no retry |
| Destination throws a business exception (e.g. `RecipeNotFoundException`) | `DESTINATION_BUSINESS` | `FAILED` | User can correct the route to a different destination |
| Destination throws a transient `DataAccessResourceFailureException` | `TRANSIENT` | `FAILED` initially | A `@Scheduled(fixedDelay = 5min)` sweep retries `FAILED + TRANSIENT` rows up to 3 times before final terminal failure |
| Destination throws `AiUnavailableException` (downstream) | `AI_UNAVAILABLE` | `FAILED` | The transient sweep retries once `AiService` recovers |
| Destination throws anything else | `UNKNOWN` | `FAILED` | No retry; admin investigation |

The transient retry loop matches the project-wide defer-and-pending pattern.

### Flow 4: Misclassification correction with replay

`POST /api/v1/feedback/{feedbackId}/routes/{routingId}/correct` → `correctMisclassification(userId, feedbackId, routingId, request)`. `@Transactional` (default REQUIRED) for the bookkeeping; the new routing fires in `REQUIRES_NEW`.

1. Load the entry (`findWithRoutingByIdAndUserId`). 404 if missing. 404 if `routingId` doesn't belong.
2. Validate `request.newDestination`:
   - Must differ from the original (`InvalidCorrectionTargetException` 422 otherwise).
   - Original routing must not already be `CORRECTED_AWAY` or `REPLAYED` (422 — corrections are not chained in v1).
3. **Best-effort undo of the original route.** The "undo" is destination-specific:
   - `RECIPE` (optimiser): cancel the pending adaptation if `AWAITING_USER_APPROVAL`. If already approved or applied, the correction is a "log only" — the user is told the previous suggestion was kept; the system still records the ground truth.
   - `PREFERENCE`: call `preferenceUpdateService.revertFeedback(userId, originalRoutingId)`. If the delta has already been applied to the taste profile and rolled into a new `documentVersion`, the revert is best-effort (rollback may be partial, per [feedback-system.md §Correction limitations](../design/feedback-system.md#correction-limitations)).
   - `NUTRITION`: if the original routing was a *proposal* that the user hadn't accepted ([nutrition.md §Flow 10](nutrition.md)), cancel the proposal. If it was a journal append, leave the journal entry; the correction signals the routing was wrong, not that the journal is wrong.
   - `PROVISIONS`: call `provisionUpdateService.revertFeedback(userId, originalRoutingId)` for cost-concern logs and supplier-cache writes; equipment changes and waste log entries are immutable per the provisions LLD and the correction is log-only.
   These revert methods are **forward dependencies** — none of them exist in the current LLDs. The correction flow must accept that v1 is "best-effort, log-only on undo" until each destination's revert API lands. **Worth user review** — a clean v1 might log the correction without attempting any undo, deferring revert hooks to v2.
4. Mark the original `RoutingLogEntry.status = CORRECTED_AWAY`, `completed_at` updated.
5. Persist a `MisclassificationCorrection` row with `actor_user_id = userId`, `original_destination`, `original_confidence` copied from the original routing, `replay_status` initially `PENDING_REPLAY`. Increment a metric counter (correction-rate gauge).
6. Re-fire routing for the corrected destination. Build a synthetic `ClassificationOutput`:
   - `destination = newDestination`,
   - `confidence = 1.0` (user-attested ground truth),
   - `extractedFeedback = entry.text` (full text — the original split is no longer authoritative),
   - `structuredPayload = derived from entry.uiContext` (best-effort — the new destination's dispatcher may need to do its own field extraction).
   Call the same `FeedbackRouter.route(...)` path used in Flow 3 step 1-6. Capture the new `routingLogId`.
7. Stamp the original row's `superseded_by = newRoutingId`. Stamp the correction row's `replay_routing_id = newRoutingId` and `replay_status = APPLIED` / `FAILED` / `DESTINATION_REJECTED` based on the replay outcome.
8. Recompute the entry's `submissionStatus` (Flow 3 step 7 logic, with the corrected route counting).
9. Publish `FeedbackMisclassificationCorrectedEvent(feedbackId, originalRoutingId, replayRoutingId, ...)`.

The HLD is explicit that "in practice, most corrections are simple re-routes, not complex undo chains" ([feedback-system.md §Correction limitations](../design/feedback-system.md#correction-limitations)). The flow above honours this: undo is best-effort and the routing log preserves both the original and the corrected paths.

Repeated correction (correcting a correction) is **not supported in v1** — step 2 rejects with 422. The user gets one shot at re-routing per original misclassification; after that, the only path is to submit a new feedback. **Worth user review.**

### Flow 5: Clarification answer

`POST /api/v1/feedback/clarifications/{queryId}/answer` → `answerClarificationQuery(userId, queryId, request)`. `@Transactional` (default REQUIRED).

1. Load the `ClarificationQuery` by `(queryId, userId)`. 404 if missing.
2. Reject if `status != PENDING` (`ClarificationQueryExpiredException` 410 if `EXPIRED`, generic 422 if already `ANSWERED`).
3. Validate `request` — at least one of `selectedDestination` / `userClarificationText` provided (asserted by `AnswerClarificationRequest.isAtLeastOneProvided`).
4. Mark the query `status = ANSWERED`, `selected_destination`, `user_clarification_text`, `answered_at`.
5. Look up the parent `FeedbackEntry`. Append the user's clarification text to the in-flight `FeedbackClassificationContext` (carried via the `userClarificationText` and `userSelectedHint` fields on the next attempt).
6. Mark the entry `submissionStatus = RECEIVED` (re-eligible for classification). Increment `classification_attempts`.
7. Publish a fresh `FeedbackSubmittedEvent` to re-trigger Flow 2. The async listener picks it up; classification runs again with the augmented context.
8. Return the entry's current `SubmitFeedbackResponse` snapshot (still pre-classification — the response body shows `submissionStatus = RECEIVED`, `routes = []`, `pendingClarificationQueryId = null`).

Re-classification reuses the same prompt template; only the context map changes. The classifier sees the original text plus the appended clarification. The cost is one additional cheap-tier classification call per clarification answer — accepted given the alternative is a misclassification.

If the second classification *also* yields a `< 0.5` confidence, the system queues a second `ClarificationQuery` and the cycle repeats. There is no hard cap on clarification rounds in v1, but the entry's `classification_attempts` field is exposed to the user — the UI can encourage them to give up and submit a new feedback after, say, three rounds. **Worth user review** — alternative is a hard cap that escalates to `FAILED`.

---

## Concurrency and Transactions

| Concern | Decision |
|---|---|
| `@Transactional` placement | All service-impl methods. Repositories never. |
| Read-method propagation | `@Transactional(readOnly = true)`. |
| Submission write-method propagation | Default REQUIRED; the submission tx ends before classification starts. |
| Classification | Runs **outside** any transaction (async listener). Each DB touch inside Flow 2 is its own short transaction (`REQUIRES_NEW` from inside the listener). |
| Routing | Per-destination routing runs in `REQUIRES_NEW` so a failed destination does not roll back peers (HLD: independent transactions per destination). |
| Optimistic locking | `@Version` on `FeedbackEntry` and `ClarificationQuery`. The routing log and corrections table are append-only-with-status-update — concurrent updates on a single row are guarded by `RoutingFailureKind`-aware service code. |
| Pessimistic locking | None. |
| Concurrent submit by same user | Allowed and expected (rapid taps on the submit button). Each call gets its own `feedbackId` and `traceId`. The router's per-destination `REQUIRES_NEW` keeps two concurrent feedbacks from sharing a transaction. |
| Concurrent answer to same clarification | Optimistic lock on `ClarificationQuery` resolves: second writer gets 409 (already answered). |
| Concurrent correction | Same — guarded by the routing log entry's status check (must not be `CORRECTED_AWAY` already). |
| AI cost-cap behaviour | `AiUnavailableException` triggers defer-and-pending per [style-guide.md §AI Service — Graceful Degradation](style-guide.md#ai-service--graceful-degradation). |

### Why classification is async after the submission commit

Three reasons:

1. **User-perceived latency.** The classifier is a network call to Anthropic — typical 500ms to 2s. Holding the submit transaction across that is wrong; the user wants an instant receipt.
2. **Transaction-AI separation.** The project-wide rule is AI calls don't run inside DB transactions ([lld/README.md §Tier 1 architectural decisions](README.md#tier-1-architectural-decisions-locked)).
3. **Graceful-degrade composability.** A submit that fails to classify (cost cap, AI unavailable) must still be persisted. The submission transaction completes regardless; the classifier path is best-effort with retry.

The trade-off is that the API is asynchronous from the client's perspective: a submit returns 202 with empty `routes`. The client polls or, in v2, subscribes via SSE. The HLD does not explicitly call this out, but the cited "confirmation message appears immediately after feedback" UX is preserved — "immediately" here means "within seconds of submission" and the UI handles the polling / streaming detail. **Worth user review** — alternative is a synchronous submit that holds the request until classification + routing complete (~3-10s). Sync is simpler for v1 if the latency is acceptable; async is more resilient at scale.

---

## Quality Monitoring (mechanical view)

The HLD ([feedback-system.md §Quality Monitoring](../design/feedback-system.md#quality-monitoring)) lists four metrics. The mechanical hooks are:

- **Correction rate** — `MisclassificationCorrectionRepository.countByOccurredAtBetween` over the matching `RoutingLogRepository.aggregateByDestination`. Both queries hit indexed columns.
- **Confidence distribution** — `RoutingLogRepository.aggregateByDestination` returns `(destination, count, avg(confidence))` rolled up over a date range. A future histogram view groups by 0.1-wide buckets.
- **Destination distribution** — same query, count column.
- **Low-confidence clarification rate** — `count(ClarificationQuery)` over the same window divided by `count(FeedbackEntry)`.

A dedicated `FeedbackMetricsService` (admin-only) exposing these as a single `FeedbackQualityDashboardDto` is **out of scope for this LLD** — folded into a future `quality-monitoring` cross-cutting service. The hooks above are sufficient for v1's manual review process: prompt-engineering reviewers pull the corrections table and inspect patterns.

---

## Test Plan

Unit tests use `@ExtendWith(MockitoExtension.class)`. Integration tests are `*IT.java` with Testcontainers Postgres. The AI module's `TestAiService` ([ai.md §Test Plan](ai.md#test-plan)) is the canned-response substitute — feedback ITs `@BeforeEach` register a `ClassificationResult` keyed on `TaskType.FEEDBACK_CLASSIFICATION` with the relevant confidences. Names follow `methodName_scenario_expected`.

### Unit

| Class | Verifies |
|---|---|
| `FeedbackServiceImplTest` | Submit happy path returns 202 receipt; correction flow validates pre-conditions; clarification answer requires at least one input; query-by-id 404 when entry belongs to another user. Mocks all collaborators. |
| `FeedbackClassifierTest` | Builds `FeedbackClassificationTask` with the expected context; happy path returns parsed `ClassificationResult`; `AiUnavailableException` propagates; `AiResponseInvalidException` propagates. Verifies the task carries the correct `traceId` and `userId`. |
| `ConfidenceGateTest` | All-≥0.8 routes auto. Mixed 0.5-0.8 routes with `ROUTED_WITH_FLAG`. Any-<0.5 queues clarification. Empty list yields zero-route entry. Edge case: exactly 0.5 routes (boundary inclusive on the lower side per HLD). |
| `FeedbackRouterTest` | Fans out to N destinations sequentially. One destination throwing does not skip subsequent. Each destination's success/failure persists the right routing-log fields. Recipe path goes through `OptimiserService.handleRecipeFeedback` and not through `RecipeUpdateService` (architectural assertion). |
| `DestinationDispatcherTest` (×4) | Each dispatcher translates the classifier's `structuredPayload` into the destination's specific call. Recipe id resolution falls back from `structuredPayload` to `uiContext`. Missing recipe id triggers `DESTINATION_VALIDATION`. |
| `CorrectionReplayerTest` | Builds a synthetic `ClassificationOutput` with `confidence = 1.0`. Original row stamped `CORRECTED_AWAY`; correction row carries the original confidence. Re-fire failure yields `replay_status = FAILED`. |
| `FeedbackEntryMapperTest`, `RoutingLogMapperTest`, `ClarificationQueryMapperTest`, `MisclassificationCorrectionMapperTest` | MapStruct round-trips preserve all fields including JSONB structured payloads. |
| `UiContextValidatorTest` | `RECIPE_DETAIL` requires `recipeId`; `PLAN_MEAL_DETAIL` requires both `planId` and `mealSlotId`; `GENERAL` requires nothing. Rejects `recipeVersion` without `recipeId`. |
| `AnswerClarificationRequestTest` | The `@AssertTrue` on `isAtLeastOneProvided` accepts either field set, rejects both null/blank. |

### Integration

| Class | Verifies |
|---|---|
| `FeedbackControllerIT` | Full HTTP cycle over MockMvc: POST returns 202 with empty routes; GET-by-id reflects progressive enrichment as the async listener completes; correction endpoint returns 422 on no-op corrections; ProblemDetail shape on errors. Verifies `FeedbackSubmittedEvent` is published exactly once per submit. |
| `ClarificationQueryControllerIT` | List/get/answer endpoints. 410 on expired query. POST answer with `selectedDestination` re-publishes `FeedbackSubmittedEvent`. |
| `FeedbackPipelineIT` | End-to-end with `TestAiService` registered for `FEEDBACK_CLASSIFICATION`: submit → wait for async classification → routing log populated → all four destination services invoked the right number of times (`recordedCalls()` assertion against AI; per-destination Mockito spies for the four update services). Covers the multi-destination split and partial-failure path. |
| `RoutingFailureIT` | One destination dispatcher configured to throw a `DataAccessResourceFailureException`; entry transitions to `PARTIALLY_FAILED`; the 5-min retry sweep moves it to `ROUTED` once the destination is healthy. Verifies the failed routing's status updates atomically with its own `REQUIRES_NEW` transaction. |
| `MisclassificationCorrectionIT` | Submit feedback that routes to PREFERENCE; user corrects to PROVISIONS. Original row `CORRECTED_AWAY`, new row `APPLIED`. `feedback_misclassification_corrections` row carries the ground truth. `FeedbackMisclassificationCorrectedEvent` published exactly once. |
| `ClarificationFlowIT` | Submit feedback that the test classifier rates 0.4. `ClarificationQuery` written. Answer the query with a destination. Re-classification produces 0.9 confidence (test classifier registered with two responses keyed by attempt). Routing completes. |
| `FeedbackEventPublicationIT` | `FeedbackProcessedEvent` fires exactly once per entry, regardless of destination count. Empty-classification entries still publish (with `destinationsTouched = ∅`). |
| `FeedbackAsyncSweepIT` | Stuck `RECEIVED` entries (created > 5 min ago, `AiUnavailable` simulated) re-enter the classifier on the next sweep. Stuck > 24 hours escalate to `FAILED`. |
| `ClarificationExpiryIT` | `@Scheduled` sweep at 04:00 sets `EXPIRED` on past-TTL queries; parent entry becomes `FAILED`. |
| `FlywayMigrationIT` | Boots Postgres, runs all `feedback_*` migrations, validates schema matches the JPA mapping (`spring.jpa.hibernate.ddl-auto=validate`). Catches drift early. |

### Cross-module integration tests

| Class | Verifies |
|---|---|
| `FeedbackPreferenceIntegrationIT` | Real preference module bean. PREFERENCE-classified feedback lands in `PreferenceUpdateService.applyFeedback`; the preference module persists its delta-pending state. Verifies feedback never reaches `RecipeUpdateService` even when `uiContext.screen = RECIPE_DETAIL`. |
| `FeedbackProvisionsIntegrationIT` | PROVISIONS-classified feedback lands in `ProvisionUpdateService.applyFeedback` with the `ProvisionsFeedbackCommand` mapped from `structuredPayload.type`. |
| `FeedbackOptimiserIntegrationIT` | RECIPE-classified feedback lands in `OptimiserService.handleRecipeFeedback`. Pending until the optimiser LLD lands; today the test runs against a `@MockBean OptimiserService` and asserts call signatures. |

---

## Out of Scope

Deferred deliberately — these belong elsewhere or to a later phase:

- **The classification prompt content** (system message, user template, eval set, default token cap, default timeout). Owned by the prompt-engineering work track per [lld/README.md §LLM prompts to design](README.md#llm-prompts-to-design-9-distinct-prompt-engineering-exercises). The template will land at `src/main/resources/prompts/feedback/classify-feedback.txt` and be loaded by [ai.md §Flow 5](ai.md#flow-5-prompt-template-loading). This LLD specifies only the `AiTask` shape (`FeedbackClassificationTask`) and the structured-output type (`ClassificationResult`).
- **AI cost budgeting per call.** Per-task token cap and per-call budget for `FEEDBACK_CLASSIFICATION` live in `AiConfig.taskTypeTokenCaps` ([ai.md §Configuration](ai.md#configuration)). Defaults are an implementation-phase decision aligned with the cheap-tier pricing — not pinned here.
- **Frontend / conversational feedback UI.** The submit input, the confirmation toast that presents `RoutingDecisionDto.actionTaken` strings, the misclassification "this isn't right" affordance, the clarification-query inbox UI — all Figma-phase work, then frontend LLD.
- **Streaming the routing pipeline to the client.** v1 polls; v2 considers SSE on `GET /feedback/{feedbackId}` so the client sees routes appear as each destination completes. Hooks for this are present (the `AiCallSucceededEvent` listener) but the SSE controller is not.
- **Per-user prompt variants** for the classifier (A/B tests, fine-tuned per-user few-shots). The classifier prompt is global today.
- **Quality-monitoring dashboard service.** `FeedbackMetricsService` wrapping the rollup queries is a future cross-cutting addition; v1 ships the underlying queries but not a stable public DTO.
- **Proactive feedback prompts** ([feedback-system.md §Open Questions](../design/feedback-system.md#open-questions)). The system is reactive only in v1.
- **Implicit positive feedback signals** ([feedback-system.md §Open Questions](../design/feedback-system.md#open-questions)). v1 logs only explicit corrections; v2 may consume the absence of correction as a weak positive signal.
- **Hard-constraint feedback safety net.** Per the HLD, "I'm now allergic to nuts" must go through manual hard-constraint editing, not through AI-classified feedback. The classifier is expected (via prompt design) to refuse hard-constraint changes. Until the prompt enforces this, the dispatcher does not — **assumed prompt responsibility**, with a fallback assertion in `PreferenceDestinationDispatcher` that rejects any `structuredPayload` containing hard-constraint fields. **Worth user review.**
- **Batched submission.** v1 submits one feedback per request. Batched / file-upload submission is a future addition.
- **Cross-destination atomic rollback.** Already documented in HLD — destinations write independently; full atomicity is not promised. Not adding two-phase commit support in v1.
- **Multi-actor corrections** (household admin correcting a member's feedback). v1 routes `actor_user_id = userId` only; multi-actor uses the same audit pattern as preference's hard-constraint edits when household roles arrive.

---

## Decisions where the HLD is silent (worth user review)

1. **7-day TTL on clarification queries** — chosen to bound the open-question inbox. Not in HLD.
2. **`<0.5` confidence pauses the entire entry**, even when other classifications were high — alternative is partial-route plus targeted clarification. Chose the conservative path because mixing routes with an unresolved part risks losing user intent.
3. **Sequential per-destination routing**, not parallel — simpler cost-cap accounting, predictable confirmation ordering. Parallel is a follow-up if latency becomes an issue.
4. **Async classification after submission commit** — the API is async (202 + poll). Alternative is sync submit that holds the request 3-10s.
5. **Single-shot correction** (correcting a correction is rejected) — keeps the audit trail linear in v1. Multi-step corrections are a v2 ergonomics question.
6. **`FeedbackMisclassificationCorrectedEvent` introduced beyond the HLD catalogue** — lets destinations react without polling.
7. **Best-effort destination undo on correction** — the destinations' revert APIs are forward dependencies; v1 may need to log corrections without full undo until those land.
8. **`actor_user_id == userId`** assumption — household-admin corrections come later.
9. **Hard-constraint refusal is prompt responsibility** — until the prompt enforces it, a fallback assertion in the preference dispatcher is the safety net.
10. **Single `ClarificationQuery` per `<0.5` event** — combined shortlist across all low-confidence options. Alternative was one query per ambiguous classification.
