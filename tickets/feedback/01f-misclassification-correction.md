# Ticket: feedback — 01f Misclassification Correction + Replay + Destination Revert SPI + FeedbackMisclassificationCorrectedEvent

## Summary

Layer the **user-driven correction loop** (Flow 4 of the LLD) on top of feedback-01a-01e: the `POST /api/v1/feedback/{feedbackId}/routes/{routingId}/correct` endpoint, the `correctMisclassification` impl on `FeedbackUpdateService` (replacing 01b's `UnsupportedOperationException` stub), the `CorrectionReplayer` internal helper that builds a synthetic `ClassificationOutput` (`confidence = 1.0`, `extractedFeedback = entry.text`) and re-fires through 01d's `FeedbackRouter`, the **best-effort destination undo** via four new `feedback.spi.*FeedbackReverter` cross-module SPIs (one per destination) — each with a `Noop` default following the SPI-with-Noop pattern — so the correction flow can ship before the wave-2 destinations grow their `revertFeedback` methods, the `listCorrections` query method (filling 01b's stub), the `@ValidDestination` custom class-level validator on `CorrectionRequest`, the `MisclassificationCorrectionMapper` use site, the new module exceptions `InvalidCorrectionTargetException` (422), and the **`FeedbackMisclassificationCorrectedEvent`** publication site for cross-module signalling. Per [`lld/feedback.md §Flow 4 lines 788-816`](../../lld/feedback.md), [`lld/feedback.md §FeedbackController correct row line 598`](../../lld/feedback.md), [`lld/feedback.md §Events §FeedbackMisclassificationCorrectedEvent lines 672-690`](../../lld/feedback.md), [`lld/feedback.md §Validation lines 638-647`](../../lld/feedback.md).

**LLD divergence note** — **destination revert is "best-effort, log-only on undo" in v1**: per [LLD lines 796-801 and §Decisions §7 line 948](../../lld/feedback.md), each destination's revert API (`PreferenceUpdateService.revertFeedback`, `ProvisionUpdateService.revertFeedback`, optimiser cancel, etc.) is a **forward dependency** — none of those revert methods exist in the merged wave-2 LLDs yet. **Strategy in 01f**:
- Define **four feedback-owned SPIs** in `feedback/spi/`: `PreferenceFeedbackReverter`, `NutritionFeedbackReverter`, `ProvisionsFeedbackReverter`, `RecipeFeedbackReverter` — each with a single `void revert(RevertContext ctx)` method.
- Ship **Noop defaults** for each via the standard `@Configuration + @Bean + @ConditionalOnMissingBean` pattern. The Noops log a WARN "revert path not implemented in destination module yet; correction is log-only" — they do NOT throw. The correction flow proceeds to write the `MisclassificationCorrection` row + fire the synthetic replay; the original routing log row is marked `CORRECTED_AWAY` regardless.
- When each wave-2 destination grows its revert method, that module ships a `@Component` impl of the appropriate `*FeedbackReverter` SPI. The Noop steps aside via `@ConditionalOnMissingBean`.
- **Special case for RECIPE**: per LLD line 797, the optimiser's "cancel pending adaptation" is the revert; the optimiser/adaptation-pipeline module's interface ships a `cancelAdaptation(routingId)` method. Same SPI pattern.

**LLD divergence note** — **"correcting a correction" rejected in v1**: per [LLD Flow 4 step 2 lines 794-795](../../lld/feedback.md) and §Decisions §5 (line 946), 01f rejects with 422 when:
- `request.newDestination == originalRouting.destination` (no-op correction)
- `originalRouting.status ∈ {CORRECTED_AWAY, REPLAYED}` (chain rejection)
The new exception type is `InvalidCorrectionTargetException` (422). Multi-step corrections are a v2 ergonomics question.

**LLD divergence note** — **the replay's synthetic `ClassificationOutput`**: per [LLD Flow 4 step 6 lines 804-810](../../lld/feedback.md), the replay builds:
- `destination = newDestination`
- `confidence = BigDecimal.ONE` (user-attested ground truth)
- `extractedFeedback = entry.text` (full text; the original split is no longer authoritative)
- `structuredPayload = derived from entry.uiContext`
The `structuredPayload` derivation: 01f composes a minimal JSON `{ "recipeId": uiContext.recipeId, "mealSlotId": uiContext.mealSlotId, "planId": uiContext.planId }` filtered to non-null fields. The dispatcher in 01d does its own field extraction; the destination may fall back to its own field inference. **Worth user review** — alternative is to preserve the original `structuredPayload` for the new destination, but that's likely wrong (the original was destination-specific). The minimal-uiContext-derived payload is the conservative default.

**LLD divergence note** — **`actorUserId == userId`**: per [LLD §Decisions §8 line 949](../../lld/feedback.md), v1 assumes the feedback owner is the only one who corrects their own feedback. 01f honours this — the controller doesn't accept an `actorUserId` override. The `actor_user_id` column on `feedback_misclassification_corrections` is always populated with the authenticated user. Multi-actor (household-admin override) comes later.

**LLD divergence note** — **`replay_status` initial value**: per [01a's enum extension](01a-feedback-entities-migrations.md), `CorrectionReplayStatus` admits `PENDING_REPLAY | APPLIED | FAILED | DESTINATION_REJECTED`. The `MisclassificationCorrection` row is INSERTed with `replay_status = PENDING_REPLAY` (LLD line 803); the replayer updates after the synthetic dispatch completes:
- Dispatcher returns `APPLIED` or `AWAITING_USER_APPROVAL` → `replay_status = APPLIED`
- Dispatcher returns `FAILED` with kind ∈ `{TRANSIENT, AI_UNAVAILABLE}` → `replay_status = FAILED` (the transient sweep in 01g may retry the replay's *new* routing row but doesn't update the correction's `replay_status`)
- Dispatcher returns `FAILED` with kind == `DESTINATION_VALIDATION` or `DESTINATION_BUSINESS` → `replay_status = DESTINATION_REJECTED` (the destination refused; the user's correction was structurally wrong for that destination)

**Defers** (still out of scope after 01f):

- `@Scheduled retryStuckClassifications` + transient-failure retry sweeps → **feedback-01g**
- `@TransactionalEventListener` on `AiCallSucceededEvent` for telemetry → **feedback-01g**
- Cross-module integration tests (incl. the real revert impls when wave-2 ships them) → **feedback-01g**
- Multi-actor correction (household-admin) → out of LLD scope (LLD §Decisions §8)
- Multi-step correction (correcting a correction) → out of LLD scope (LLD §Decisions §5)

01f closes the **ground-truth labelling loop**: every user correction writes a labelled row to `feedback_misclassification_corrections`, which the future prompt-engineering work track consumes (LLD line 170 — "deliberately copies original_destination, original_confidence, and the feedback id so a future fine-tune or few-shot pipeline can pull labelled examples without joining back to the routing log every time").

## Behavioural spec

### `CorrectionRequest` body validation

1. **`CorrectionRequest`** at `feedback/api/dto/CorrectionRequest.java`. Replace 01b's placeholder with the full validation:
   ```java
   public record CorrectionRequest(
       @NotNull @ValidDestination Destination newDestination,
       @Size(max = 512) String userCorrectionNote) {}
   ```
2. **`@ValidDestination`** custom annotation at `feedback/validation/ValidDestination.java`. Per [LLD line 643](../../lld/feedback.md): the validator rejects destinations that are "structurally impossible given the feedback's existing structured_payload — e.g. correcting to RECIPE requires a recipeId somewhere in the entry's UI context or extracted payload". **The validator is field-level** but needs **routing-row context** to do the structural check — that's a controller-or-service-level concern, not a Bean-Validation concern. **Resolution**: ship `@ValidDestination` as a **simple value-presence guard** (`destination ∈ {RECIPE, PREFERENCE, NUTRITION, PROVISIONS}`), and do the **structural validation in the service layer** as a separate `InvalidCorrectionTargetException` throw (mapped to 422). Jakarta's enum binding already rejects unknown strings as 400; the explicit `@ValidDestination` annotation in 01f is **largely belt-and-braces** — it documents intent + rejects null. **Worth user review** — alternative is a class-level validator on a wrapper record carrying both the request AND the loaded routing log, but the structural check is more clearly placed in the service flow.

### Endpoint

3. **`POST /api/v1/feedback/{feedbackId}/routes/{routingId}/correct`** — declared in `FeedbackController` (the existing controller from 01b). Append a new handler method:
   ```java
   @PostMapping("/{feedbackId}/routes/{routingId}/correct")
   public SubmitFeedbackResponse correct(
       @PathVariable UUID feedbackId,
       @PathVariable UUID routingId,
       @Valid @RequestBody CorrectionRequest request) {
     UUID userId = currentUserResolver.requireCurrentUserId();
     return updateService.correctMisclassification(userId, feedbackId, routingId, request);
   }
   ```
4. **Status codes** per [LLD line 599](../../lld/feedback.md): 200 / 400 / 404 / 422.

### `correctMisclassification` impl

5. **`FeedbackServiceImpl.correctMisclassification`** replaces 01b's stub. Per [LLD Flow 4 lines 790-813](../../lld/feedback.md):
   1. `@Transactional` (default REQUIRED).
   2. Load the entry: `feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId)` (uses the 01a `@EntityGraph`). **404** `FeedbackEntryNotFoundException` if missing.
   3. Find the routing row inside the entry's routing log: `entry.getRoutingLog().stream().filter(r -> r.getId().equals(routingId)).findFirst()`. **404** `RoutingDecisionNotFoundException` if not found. (Don't query `routingLogRepository.findById(routingId)` — that bypasses the user-ownership check.)
   4. **Pre-condition validations** (each → `InvalidCorrectionTargetException` 422):
      - `request.newDestination == original.destination` → "new destination matches original (no-op)"
      - `original.status ∈ {CORRECTED_AWAY, REPLAYED}` → "original routing already corrected; chains not supported in v1"
      - Structural check: if `request.newDestination == RECIPE` AND `entry.uiContext.recipeId == null` AND `original.structuredPayload.path("recipeId").asText() == null` → "cannot correct to RECIPE; no recipe attached to this feedback"
   5. **Best-effort destination undo** of the original route (LLD line 796):
      ```java
      RevertContext revertCtx = new RevertContext(
          original.getId(), entry.getUserId(), entry.getTraceId(),
          original.getDestination(),
          original.getStructuredPayload(),
          original.getDestinationResultJson());   // for the destination's correlation
      try {
        switch (original.getDestination()) {
          case RECIPE     -> recipeReverter.revert(revertCtx);
          case PREFERENCE -> preferenceReverter.revert(revertCtx);
          case NUTRITION  -> nutritionReverter.revert(revertCtx);
          case PROVISIONS -> provisionsReverter.revert(revertCtx);
        }
      } catch (Exception revertFail) {
        // Best-effort — log WARN and continue. The correction proceeds even if undo failed.
        log.warn("Revert of original routing {} failed; proceeding with correction record",
            original.getId(), revertFail);
      }
      ```
      The four reverters are injected via Spring (Noop by default; real impls when wave-2 destinations grow them).
   6. **Mark original `RoutingLogEntry`** `status = CORRECTED_AWAY`, `completed_at = Instant.now()`. Save.
   7. **Persist `MisclassificationCorrection`** row:
      ```java
      MisclassificationCorrection correction = MisclassificationCorrection.builder()
          .id(UUID.randomUUID())
          .feedbackEntry(entry)
          .originalRoutingId(original.getId())
          .correctedDestination(request.newDestination())
          .userCorrectionNote(request.userCorrectionNote())
          .actorUserId(userId)
          .originalConfidence(original.getConfidence())
          .originalDestination(original.getDestination())
          .replayRoutingId(null)
          .replayStatus(CorrectionReplayStatus.PENDING_REPLAY)
          .occurredAt(Instant.now())
          .build();
      misclassificationCorrectionRepository.save(correction);
      ```
   8. **Increment a metric counter** (LLD line 803) — for now ship the counter via Micrometer's `Counter.builder("feedback.corrections.recorded").register(meterRegistry).increment()`. **Verify** the project has Micrometer wired — if not, ship without the counter and add a TODO. **Worth implementer judgement.**
   9. **Re-fire routing** for the corrected destination (LLD line 804). Build a synthetic `ClassificationOutput`:
      ```java
      JsonNode syntheticPayload = buildSyntheticPayload(entry.getUiContext(), request.newDestination());
      ClassificationOutput synthetic = new ClassificationOutput(
          request.newDestination(),
          BigDecimal.ONE,                    // user-attested
          entry.getText(),                   // full original text
          syntheticPayload);
      ConfidenceGate.ScoredClassification scored =
          new ConfidenceGate.ScoredClassification(synthetic, RoutingDecision.AUTO_ROUTED);
      ```
   10. **Call `FeedbackRouter.routeAll(...)`** (the same router 01d ships). The router opens its own `REQUIRES_NEW` tx for the synthetic dispatch. **Capture the new `routingLogId`** by querying after the dispatch (the router doesn't return it — see §11).
   11. **`FeedbackRouter` API extension** — the `routeAll` signature from 01d returns `void`. **01f extends `FeedbackRouter` with a `routeOneForReplay(feedbackId, scored)` method** that returns the new `routingLogId` AND the dispatch's `RoutingStatus`:
       ```java
       interface FeedbackRouter {
         void routeAll(UUID feedbackId, List<ScoredClassification> classifications);   // unchanged
         RouteReplayResult routeOneForReplay(UUID feedbackId, ScoredClassification scored);   // NEW in 01f
       }
       public record RouteReplayResult(UUID newRoutingLogId, RoutingStatus status, RoutingFailureKind failureKind) {}
       ```
       The real impl reuses `routeOne` (the package-private REQUIRES_NEW method from 01d) and returns the resulting status. The Noop returns `(UUID.randomUUID(), RoutingStatus.FAILED, RoutingFailureKind.UNKNOWN)`. **This is a soft mutation to 01d's interface** — append a method, no signature changes. Document in 01d-01f handoff.
   12. **Stamp original `RoutingLogEntry.supersededBy`** with the new routing log id (LLD line 810): `original.setSupersededById(replayResult.newRoutingLogId())`. Save.
   13. **Update `MisclassificationCorrection`** with the replay outcome (LLD line 810):
       - `replayRoutingId = replayResult.newRoutingLogId()`
       - `replayStatus` = mapped from the replay status:
         - `APPLIED` or `AWAITING_USER_APPROVAL` → `CorrectionReplayStatus.APPLIED`
         - `FAILED` with kind ∈ `{DESTINATION_VALIDATION, DESTINATION_BUSINESS}` → `CorrectionReplayStatus.DESTINATION_REJECTED`
         - `FAILED` with kind ∈ `{TRANSIENT, AI_UNAVAILABLE, UNKNOWN}` → `CorrectionReplayStatus.FAILED`
       Save.
   14. **Recompute entry `submissionStatus`** per [LLD line 811](../../lld/feedback.md): the corrected route counts toward the new aggregate. **Strategy**:
       - Refresh `entry.getRoutingLog()` from the DB (Spring's session may have a stale snapshot post-replay).
       - Status reconciliation per Flow 3 step 7 logic:
         - All non-CORRECTED_AWAY routes APPLIED / AWAITING_USER_APPROVAL → `CORRECTED` (LLD's `SubmissionStatus.CORRECTED` value; LLD line 218)
         - Any FAILED + any non-FAILED → `PARTIALLY_FAILED`
         - All FAILED → `FAILED`
       - **Use `SubmissionStatus.CORRECTED`** (not `ROUTED`) for the all-applied happy path — the LLD enum admits it specifically for correction (LLD line 218).
   15. **Publish `FeedbackMisclassificationCorrectedEvent`** AFTER COMMIT (LLD line 812):
       ```java
       eventPublisher.publishEvent(new FeedbackMisclassificationCorrectedEvent(
           feedbackId, original.getId(), replayResult.newRoutingLogId(),
           original.getDestination(), request.newDestination(),
           original.getConfidence(),
           userId, entry.getTraceId(), Instant.now()));
       ```
   16. **Return `SubmitFeedbackResponse`** populated from the refreshed entry:
       ```java
       return new SubmitFeedbackResponse(
           feedbackId, entry.getTraceId(),
           entry.getSubmissionStatus(),
           routingLogMapper.toDtos(entry.getRoutingLog()),
           /* pendingClarificationQueryId */ null);
       ```

### `listCorrections` impl

17. **`FeedbackServiceImpl.listCorrections`** replaces 01b's stub. Uses `MisclassificationCorrectionRepository.findByFeedbackEntryUserIdOrderByOccurredAtDesc(userId, pageable)` from 01a. Maps via `MisclassificationCorrectionMapper`. Returns `Page<MisclassificationCorrectionDto>`.

### Endpoint — corrections list

18. **`GET /api/v1/feedback/corrections?page=&size=`** per [LLD line 599](../../lld/feedback.md). Appended to `FeedbackController`:
    ```java
    @GetMapping("/corrections")
    public Page<MisclassificationCorrectionDto> listCorrections(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
      UUID userId = currentUserResolver.requireCurrentUserId();
      return queryService.listCorrections(userId, PageRequest.of(page, size));
    }
    ```

### Cross-module SPIs (reverters)

19. **`feedback/spi/RevertContext.java`** — shared record across the four reverters:
    ```java
    public record RevertContext(
        UUID originalRoutingId,
        UUID userId,
        UUID traceId,
        Destination originalDestination,
        JsonNode structuredPayload,
        JsonNode destinationResultJson) {}
    ```

20. **`feedback/spi/PreferenceFeedbackReverter.java`** — PUBLIC interface:
    ```java
    public interface PreferenceFeedbackReverter {
      void revert(RevertContext ctx);
    }
    ```
21. **`feedback/spi/NutritionFeedbackReverter.java`** — analogous.
22. **`feedback/spi/ProvisionsFeedbackReverter.java`** — analogous.
23. **`feedback/spi/RecipeFeedbackReverter.java`** — analogous. For the recipe destination the revert is "cancel pending adaptation" (LLD line 797); the adaptation-pipeline module's impl handles the routing log id → adaptation id lookup.

24. **`feedback/config/NoopFeedbackRevertersConfiguration.java`** — SPI-with-Noop defaults for all four. Single `@Configuration` class with four `@Bean @ConditionalOnMissingBean` methods. Each Noop logs WARN and returns:
    ```java
    @Configuration
    public class NoopFeedbackRevertersConfiguration {
      @Bean @ConditionalOnMissingBean(PreferenceFeedbackReverter.class)
      PreferenceFeedbackReverter defaultPreferenceReverter() {
        return ctx -> log.warn("revert not implemented in preference module yet; correction is log-only for routing {}", ctx.originalRoutingId());
      }
      @Bean @ConditionalOnMissingBean(NutritionFeedbackReverter.class)
      NutritionFeedbackReverter defaultNutritionReverter() { return ctx -> log.warn(...); }
      // ... and so on for provisions + recipe
    }
    ```
    **Method names ALL DIFFERENT from the class name** (round-5 gotcha avoidance). **`@Configuration` + `@Bean` not `@Component @ConditionalOnMissingBean`** (round-5 gotcha avoidance).

### New module exception

25. **`InvalidCorrectionTargetException`** at `feedback/exception/InvalidCorrectionTargetException.java`. Extends `FeedbackException`. Maps to **422** `.../invalid-correction-target` per [LLD line 625](../../lld/feedback.md). Carries a descriptive reason in the message; the ProblemDetail surfaces the reason as the `detail` field.

26. **Append `@ExceptionHandler` method** to `FeedbackExceptionHandler` for `InvalidCorrectionTargetException`. Keep `@Order(Ordered.HIGHEST_PRECEDENCE)`. **Do NOT modify `config/GlobalExceptionHandler.java`.**

### Boundary additions

27. The four `*FeedbackReverter` SPIs are PUBLIC (cross-module). `FeedbackBoundaryTest` (from 01a) already permits `feedback.spi..` to be public — no change needed.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/feedback.yaml`

```yaml
feedbackCorrectRoute:
  post:
    tags: [Feedback]
    operationId: correctMisclassification
    summary: 'User-driven correction of a single routing decision. Cancels the original route best-effort, re-fires the corrected destination, records ground truth.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: feedbackId
        required: true
        schema: { type: string, format: uuid }
      - in: path
        name: routingId
        required: true
        schema: { type: string, format: uuid }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/feedback.yaml#/CorrectionRequest' }
    responses:
      '200':
        description: 'Correction accepted; replay outcome reflected in routes.'
        content:
          application/json:
            schema: { $ref: '../schemas/feedback.yaml#/SubmitFeedbackResponse' }
      '400': { description: 'Validation error', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Feedback or routing not found / belongs to another user', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: 'Correction target invalid (no-op / chain / structural mismatch)', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

feedbackCorrections:
  get:
    tags: [Feedback]
    operationId: listCorrections
    summary: 'Paginated list of the caller''s misclassification corrections; ground-truth audit log.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: query
        name: page
        schema: { type: integer, minimum: 0, default: 0 }
      - in: query
        name: size
        schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
    responses:
      '200':
        description: 'Page of corrections.'
        content:
          application/json:
            schema: { $ref: '../schemas/feedback.yaml#/MisclassificationCorrectionDtoPage' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/feedback.yaml`

```yaml
CorrectionReplayStatus:
  type: string
  enum: [PENDING_REPLAY, APPLIED, FAILED, DESTINATION_REJECTED]
CorrectionRequest:
  type: object
  required: [newDestination]
  properties:
    newDestination: { $ref: '#/Destination' }
    userCorrectionNote: { type: string, maxLength: 512, nullable: true }
MisclassificationCorrectionDto:
  type: object
  required: [id, feedbackEntryId, originalRoutingId, correctedDestination, originalDestination, originalConfidence, actorUserId, replayStatus, occurredAt, createdAt]
  properties:
    id: { type: string, format: uuid }
    feedbackEntryId: { type: string, format: uuid }
    originalRoutingId: { type: string, format: uuid }
    correctedDestination: { $ref: '#/Destination' }
    originalDestination: { $ref: '#/Destination' }
    originalConfidence: { type: number, format: float, minimum: 0, maximum: 1 }
    userCorrectionNote: { type: string, maxLength: 512, nullable: true }
    actorUserId: { type: string, format: uuid }
    replayRoutingId:
      type: string
      format: uuid
      nullable: true
    replayStatus: { $ref: '#/CorrectionReplayStatus' }
    occurredAt: { type: string, format: date-time }
    createdAt: { type: string, format: date-time }
MisclassificationCorrectionDtoPage:
  type: object
  additionalProperties: true
  required: [content, totalElements, totalPages, number, size]
  properties:
    content:
      type: array
      items: { $ref: '#/MisclassificationCorrectionDto' }
    totalElements: { type: integer, format: int64 }
    totalPages: { type: integer }
    number: { type: integer }
    size: { type: integer }
    first: { type: boolean }
    last: { type: boolean }
    empty: { type: boolean }
    numberOfElements: { type: integer }
```

**Gotcha applied**: nullable scalars (`replayRoutingId`, `userCorrectionNote`) use inline `nullable: true`. `MisclassificationCorrectionDtoPage` uses flat Page<T> with `additionalProperties: true`.

### Append to entry `src/main/resources/openapi/openapi.yaml`

**Location**: under `paths:`, in the `# feedback` block:
```yaml
  /api/v1/feedback/{feedbackId}/routes/{routingId}/correct:
    $ref: 'paths/feedback.yaml#/feedbackCorrectRoute'
  /api/v1/feedback/corrections:
    $ref: 'paths/feedback.yaml#/feedbackCorrections'
```

**Location**: under `components.schemas:`, in the `# feedback` block:
```yaml
    CorrectionReplayStatus: { $ref: 'schemas/feedback.yaml#/CorrectionReplayStatus' }
    CorrectionRequest: { $ref: 'schemas/feedback.yaml#/CorrectionRequest' }
    MisclassificationCorrectionDto: { $ref: 'schemas/feedback.yaml#/MisclassificationCorrectionDto' }
    MisclassificationCorrectionDtoPage: { $ref: 'schemas/feedback.yaml#/MisclassificationCorrectionDtoPage' }
```

## Verbatim shape snippets

### `correctMisclassification` impl skeleton

```java
@Override
@Transactional
public SubmitFeedbackResponse correctMisclassification(
    UUID userId, UUID feedbackId, UUID routingId, CorrectionRequest request) {

  FeedbackEntry entry = feedbackEntryRepository
      .findWithRoutingByIdAndUserId(feedbackId, userId)
      .orElseThrow(() -> new FeedbackEntryNotFoundException(feedbackId));

  RoutingLogEntry original = entry.getRoutingLog().stream()
      .filter(r -> r.getId().equals(routingId))
      .findFirst()
      .orElseThrow(() -> new RoutingDecisionNotFoundException(routingId));

  validatePreconditions(entry, original, request);

  // Best-effort revert of the original destination's write
  bestEffortRevert(entry, original);

  // Mark CORRECTED_AWAY
  original.setStatus(RoutingStatus.CORRECTED_AWAY);
  original.setCompletedAt(clock.instant());
  routingLogRepository.save(original);

  // Persist correction row (PENDING_REPLAY)
  MisclassificationCorrection correction = buildCorrectionRow(entry, original, request, userId);
  misclassificationCorrectionRepository.save(correction);
  meterRegistry.counter("feedback.corrections.recorded").increment();

  // Replay
  ConfidenceGate.ScoredClassification synthetic = buildSynthetic(entry, request);
  FeedbackRouter.RouteReplayResult replay = router.routeOneForReplay(entry.getId(), synthetic);

  // Stamp original.supersededBy, correction.replayRoutingId + replayStatus
  original.setSupersededById(replay.newRoutingLogId());
  routingLogRepository.save(original);
  correction.setReplayRoutingId(replay.newRoutingLogId());
  correction.setReplayStatus(mapReplayStatus(replay.status(), replay.failureKind()));
  misclassificationCorrectionRepository.save(correction);

  // Recompute entry.submissionStatus
  recomputeSubmissionStatus(entry, /* allowCORRECTED */ true);
  entryRepository.save(entry);

  // Event
  eventPublisher.publishEvent(new FeedbackMisclassificationCorrectedEvent(
      feedbackId, original.getId(), replay.newRoutingLogId(),
      original.getDestination(), request.newDestination(),
      original.getConfidence(),
      userId, entry.getTraceId(), clock.instant()));

  // Refresh + return
  entryRepository.flush();
  FeedbackEntry refreshed = feedbackEntryRepository
      .findWithRoutingByIdAndUserId(feedbackId, userId).orElseThrow();
  return new SubmitFeedbackResponse(
      feedbackId, entry.getTraceId(),
      refreshed.getSubmissionStatus(),
      routingLogMapper.toDtos(refreshed.getRoutingLog()),
      /* pendingClarificationQueryId */ null);
}
```

### Pre-condition validator

```java
private void validatePreconditions(FeedbackEntry entry, RoutingLogEntry original, CorrectionRequest request) {
  if (request.newDestination() == original.getDestination()) {
    throw new InvalidCorrectionTargetException(
        "new destination matches original (no-op)");
  }
  if (original.getStatus() == RoutingStatus.CORRECTED_AWAY
      || original.getStatus() == RoutingStatus.REPLAYED) {
    throw new InvalidCorrectionTargetException(
        "original routing already corrected; correction chains are not supported in v1");
  }
  if (request.newDestination() == Destination.RECIPE) {
    boolean hasRecipeId = (entry.getUiContext().recipeId() != null)
        || original.getStructuredPayload().path("recipeId").asText("").length() > 0;
    if (!hasRecipeId) {
      throw new InvalidCorrectionTargetException(
          "cannot correct to RECIPE; no recipe attached to this feedback");
    }
  }
}
```

### Synthetic classification builder

```java
private ConfidenceGate.ScoredClassification buildSynthetic(FeedbackEntry entry, CorrectionRequest request) {
  ObjectNode payload = objectMapper.createObjectNode();
  UiContextDocument doc = entry.getUiContext();
  if (doc.recipeId() != null) payload.put("recipeId", doc.recipeId().toString());
  if (doc.mealSlotId() != null) payload.put("mealSlotId", doc.mealSlotId().toString());
  if (doc.planId() != null) payload.put("planId", doc.planId().toString());

  ClassificationOutput synthetic = new ClassificationOutput(
      request.newDestination(),
      BigDecimal.ONE,
      entry.getText(),
      payload);
  return new ConfidenceGate.ScoredClassification(synthetic, RoutingDecision.AUTO_ROUTED);
}
```

### `routeOneForReplay` impl in `FeedbackRouterImpl` (mutation to 01d)

```java
@Override
public RouteReplayResult routeOneForReplay(UUID feedbackId, ScoredClassification scored) {
  FeedbackEntry entry = entryRepository.findById(feedbackId)
      .orElseThrow(() -> new FeedbackEntryNotFoundException(feedbackId));
  UUID routingLogId = UUID.randomUUID();
  DispatchResult result = routeOne(routingLogId, entry, entry.getUserId(), entry.getTraceId(),
      mapDocumentToDto(entry.getUiContext()), scored, entry.getClassificationAttempts());
  return new RouteReplayResult(routingLogId, result.status(), result.failureKind());
}
```

The replay does NOT update the entry's `submissionStatus` or publish `FeedbackProcessedEvent` — those are owned by the caller (`correctMisclassification`).

### `recomputeSubmissionStatus` helper

```java
private void recomputeSubmissionStatus(FeedbackEntry entry, boolean allowCorrected) {
  List<RoutingLogEntry> active = entry.getRoutingLog().stream()
      .filter(r -> r.getStatus() != RoutingStatus.CORRECTED_AWAY)
      .toList();
  if (active.isEmpty()) {
    entry.setSubmissionStatus(SubmissionStatus.FAILED);
    return;
  }
  boolean anyFailed = active.stream().anyMatch(r -> r.getStatus() == RoutingStatus.FAILED);
  boolean anyNonFailed = active.stream().anyMatch(r -> r.getStatus() != RoutingStatus.FAILED);
  SubmissionStatus next;
  if (anyFailed && anyNonFailed)      next = SubmissionStatus.PARTIALLY_FAILED;
  else if (anyFailed)                 next = SubmissionStatus.FAILED;
  else if (allowCorrected)            next = SubmissionStatus.CORRECTED;
  else                                next = SubmissionStatus.ROUTED;
  entry.setSubmissionStatus(next);
}
```

## Edge-case checklist

- [ ] `POST /correct` happy path: original was PREFERENCE → user corrects to PROVISIONS; original row CORRECTED_AWAY; new routing log row APPLIED; `MisclassificationCorrection` row with `replayStatus = APPLIED`, `replayRoutingId` set; entry `submissionStatus = CORRECTED`; `supersededBy` on original points to new routing id; `FeedbackMisclassificationCorrectedEvent` published exactly once AFTER COMMIT
- [ ] `POST /correct` with `newDestination == originalDestination` → 422 `invalid-correction-target` "new destination matches original (no-op)"
- [ ] `POST /correct` on a routing already CORRECTED_AWAY → 422 "chains not supported"
- [ ] `POST /correct` on a routing already REPLAYED → 422
- [ ] `POST /correct` to RECIPE with no `recipeId` in either context or payload → 422 "no recipe attached"
- [ ] `POST /correct` to RECIPE with `recipeId` only in `uiContext` → flows through (fallback works in synthetic payload)
- [ ] `POST /correct` to RECIPE with `recipeId` only in original `structuredPayload` → flows through (the validator counts the original's payload toward the structural check, even though the synthetic payload re-derives from uiContext)
- [ ] `POST /correct` with `userCorrectionNote > 512` → 400 via `@Size`
- [ ] `POST /correct` for non-existent feedback → 404
- [ ] `POST /correct` for routing belonging to another user → 404
- [ ] `POST /correct` for routing not belonging to the feedback (id mismatch) → 404
- [ ] `POST /correct` without cookie → 401
- [ ] Best-effort revert: Noop reverter logs WARN; correction proceeds; `MisclassificationCorrection` row written with PENDING_REPLAY → APPLIED transition
- [ ] Best-effort revert: a real reverter throws → log WARN; correction proceeds
- [ ] Replay APPLIED → correction.replayStatus = APPLIED
- [ ] Replay AWAITING_USER_APPROVAL (recipe path) → correction.replayStatus = APPLIED (LLD doesn't distinguish; treated the same)
- [ ] Replay FAILED + DESTINATION_VALIDATION → correction.replayStatus = DESTINATION_REJECTED
- [ ] Replay FAILED + DESTINATION_BUSINESS → correction.replayStatus = DESTINATION_REJECTED
- [ ] Replay FAILED + TRANSIENT → correction.replayStatus = FAILED (the sweep in 01g may retry the replay's NEW routing row but doesn't touch the correction row)
- [ ] Replay FAILED + AI_UNAVAILABLE → correction.replayStatus = FAILED
- [ ] `feedback.corrections.recorded` Micrometer counter increments by 1 per correction (verify via `MeterRegistry` query in test)
- [ ] `original_destination` and `original_confidence` on `MisclassificationCorrection` are COPIED from the original routing row (ground-truth labelling per LLD line 170)
- [ ] `FeedbackMisclassificationCorrectedEvent` published exactly once per correction, AFTER COMMIT
- [ ] `GET /corrections` lists caller's corrections only, ordered by `occurredAt DESC`, paginated
- [ ] `GET /corrections` paging defaults: page=0, size=20, max=100
- [ ] `GET /corrections` without cookie → 401
- [ ] `MisclassificationCorrectionDto` round-trips all fields including null `replayRoutingId` (when on PENDING_REPLAY) and null `userCorrectionNote`
- [ ] `routeOneForReplay` returns a valid `RouteReplayResult`; real impl reuses `routeOne` REQUIRES_NEW; Noop returns FAILED/UNKNOWN
- [ ] Four `*FeedbackReverter` SPIs are PUBLIC in `feedback/spi/`; Noops in `feedback/config/NoopFeedbackRevertersConfiguration.java`
- [ ] Each Noop bean method name DIFFERS from the configuration class name (round-5 gotcha)
- [ ] `FeedbackExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after the `InvalidCorrectionTargetException` handler appendage
- [ ] OpenAPI request/response shapes match (swagger-request-validator)
- [ ] `MisclassificationCorrectionDtoPage` flat Page<T> shape validates
- [ ] No regression on 01a-01e tests
- [ ] No N+1 — correction flow performs ~5 SELECTs (entry-with-routing, refresh) + ~5 INSERTs/UPDATEs (correction, original, replay row, correction-update, entry-update)

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/feedback/spi/RevertContext.java
NEW   src/main/java/com/example/mealprep/feedback/spi/PreferenceFeedbackReverter.java
NEW   src/main/java/com/example/mealprep/feedback/spi/NutritionFeedbackReverter.java
NEW   src/main/java/com/example/mealprep/feedback/spi/ProvisionsFeedbackReverter.java
NEW   src/main/java/com/example/mealprep/feedback/spi/RecipeFeedbackReverter.java

NEW   src/main/java/com/example/mealprep/feedback/config/NoopFeedbackRevertersConfiguration.java

NEW   src/main/java/com/example/mealprep/feedback/event/FeedbackMisclassificationCorrectedEvent.java

NEW   src/main/java/com/example/mealprep/feedback/exception/InvalidCorrectionTargetException.java

NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/CorrectionReplayer.java       (the synthetic-classification + routeOneForReplay-call wrapper; called from FeedbackServiceImpl.correctMisclassification)

NEW   src/main/java/com/example/mealprep/feedback/validation/ValidDestination.java
NEW   src/main/java/com/example/mealprep/feedback/validation/ValidDestinationValidator.java

MOD   src/main/java/com/example/mealprep/feedback/api/dto/CorrectionRequest.java                        (replace 01b's placeholder with full @Valid @ValidDestination)
MOD   src/main/java/com/example/mealprep/feedback/api/controller/FeedbackController.java                (append POST /correct, GET /corrections)
MOD   src/main/java/com/example/mealprep/feedback/api/FeedbackExceptionHandler.java                     (append InvalidCorrectionTargetException @ExceptionHandler; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java               (replace correctMisclassification + listCorrections stubs)
MOD   src/main/java/com/example/mealprep/feedback/domain/service/internal/FeedbackRouter.java           (append routeOneForReplay method + RouteReplayResult record)
MOD   src/main/java/com/example/mealprep/feedback/domain/service/internal/FeedbackRouterImpl.java       (implement routeOneForReplay)
MOD   src/main/java/com/example/mealprep/feedback/config/NoopFeedbackRouterConfiguration.java           (Noop impl returns RouteReplayResult(uuid, FAILED, UNKNOWN))

MOD   src/main/resources/openapi/paths/feedback.yaml                                                    (append 2 path-items below 01b/01e's)
MOD   src/main/resources/openapi/schemas/feedback.yaml                                                  (append 4 schemas: CorrectionReplayStatus, CorrectionRequest, MisclassificationCorrectionDto, MisclassificationCorrectionDtoPage)
MOD   src/main/resources/openapi/openapi.yaml                                                          (2 lines under paths:; 4 lines under components.schemas:)

NEW   src/test/java/com/example/mealprep/feedback/CorrectionReplayerTest.java                          (synthetic classification shape; routeOneForReplay mocked; replay-status mapping)
NEW   src/test/java/com/example/mealprep/feedback/CorrectMisclassificationServiceTest.java              (precondition validation; happy path; revert best-effort; status mapping; event published)
NEW   src/test/java/com/example/mealprep/feedback/MisclassificationCorrectionIT.java                    (full HTTP: submit + classify (TestAiService) + route to PREFERENCE + correct to PROVISIONS; original row CORRECTED_AWAY; new row APPLIED; correction row carries ground truth; FeedbackMisclassificationCorrectedEvent published; with NoopReverters wired)
NEW   src/test/java/com/example/mealprep/feedback/CorrectionReverterSpiTest.java                        (verify Noop reverters wire; a test-scoped @TestConfiguration @Bean fake reverter overrides Noop and is invoked)
MOD   src/test/java/com/example/mealprep/feedback/testdata/FeedbackTestData.java                       (append correction + revert builders)
```

Count: ~16 new + 8 modified. Estimated agent runtime 55-65 min.

**Files this ticket does NOT modify**:
- 01a's entities, migrations — `MisclassificationCorrection` shipped in 01a
- `config/GlobalExceptionHandler.java`, `archunit/ModuleBoundaryTest.java`
- Preference / nutrition / provisions / recipe / adaptation-pipeline module source code — UNLESS the wave-2 destinations ship `revertFeedback` methods at the same time, which they don't (forward dependency)

## Dependencies

- **Hard dependency**: `feedback-01a` (merged) — `MisclassificationCorrection` entity + repo, `CorrectionReplayStatus` enum, `RoutingStatus.CORRECTED_AWAY` value, `SubmissionStatus.CORRECTED` value.
- **Hard dependency**: `feedback-01b` (merged) — `FeedbackUpdateService.correctMisclassification` stub, `FeedbackController`, `FeedbackExceptionHandler`, `CorrectionRequest` placeholder, OpenAPI yaml files.
- **Hard dependency**: `feedback-01d` (merged) — `FeedbackRouter` interface (amended to add `routeOneForReplay`), `FeedbackRouterImpl`, `ConfidenceGate.ScoredClassification`, `DispatchResult`. The correction flow piggybacks on the same router/dispatcher infrastructure.
- **Soft dependency**: `feedback-01c` (merged) — uses `ClassificationOutput` record (declared in 01c) for the synthetic classification. If 01c isn't merged, ship the record locally in 01a-style; coordinate with the parent's sequencing.
- **No hard dependency on wave-2 destination revert methods** — the four `*FeedbackReverter` SPIs default to Noop. When `preference-01c` (or equivalent) ships `PreferenceUpdateService.revertFeedback`, that module adds a `@Component implements PreferenceFeedbackReverter` adapter and the Noop steps aside.
- **No hard dependency on adaptation-pipeline-<predicted-id>** for revert — the recipe path's cancel-pending-adaptation is the adaptation-pipeline's `@Component RecipeFeedbackReverter` impl, but ships independently.
- **Sibling tickets running in parallel** (Wave 3 round 3 of feedback): `feedback-01e` (clarification). 01e and 01f both amend `FeedbackServiceImpl` and `FeedbackExceptionHandler` and the OpenAPI files — **merge-conflict zone**. Per 01e's note, 01e ships first; 01f rebases.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green
- [ ] All edge-case items above ticked
- [ ] `FeedbackExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the new handler
- [ ] OpenAPI 3.0 nullable fields inline `nullable: true` (NOT `$ref + nullable: true`)
- [ ] `MisclassificationCorrectionDtoPage` uses flat Page<T> + `additionalProperties: true`
- [ ] All YAML descriptions with `,` `:` `'` single-quoted
- [ ] Four `*FeedbackReverter` Noops wire correctly via `@Configuration @Bean @ConditionalOnMissingBean` (NOT `@Component @ConditionalOnMissingBean`)
- [ ] All four Noop `@Bean` method names DIFFER from the enclosing configuration class name
- [ ] `FeedbackMisclassificationCorrectedEvent` published exactly once per correction, AFTER COMMIT
- [ ] No regression on existing tests
- [ ] Test-scoped `@TestConfiguration` providing a fake `PreferenceFeedbackReverter` overrides the Noop (verified)

## What's NOT in scope

- `@Scheduled retryStuckClassifications` + transient-failure retry sweep → **feedback-01g**
- `@TransactionalEventListener` on `AiCallSucceededEvent` for telemetry → **feedback-01g**
- Real `revertFeedback` impls in preference / nutrition / provisions / adaptation-pipeline modules → those modules' future tickets (preference-01c-ish, nutrition future ticket, etc.)
- Multi-step correction (correcting a correction) → out of LLD scope (§Decisions §5; v2 ergonomics)
- Multi-actor correction (household-admin) → out of LLD scope (§Decisions §8)
- `FeedbackMisclassificationCorrectedEvent` listeners in preference / notification / quality-monitoring modules → cross-module future tickets; this LLD just emits the event
- Hard-constraint refusal safety-net in `PreferenceDestinationDispatcher` (LLD §Out of Scope line 933) → leaving as TODO; not strictly a correction-flow concern
- Cross-module integration tests with REAL destination reverters wired → **feedback-01g**
- Notification fan-out on `FeedbackMisclassificationCorrectedEvent` → **notification module** when it lands

Squash-merge with: `feat(feedback): 01f — misclassification correction + replay + 4 destination revert SPIs (Noop default) + FeedbackMisclassificationCorrectedEvent`
