# Ticket: feedback — 01d FeedbackRouter + DestinationDispatcher SPI + 4 Destination Impls + Multi-Destination Split Flow

## Summary

Layer the **routing fan-out** (Flow 3 of the LLD) on top of feedback-01c: the real `FeedbackRouter` implementation (replacing 01c's Noop), the `DestinationDispatcher` strategy interface, four destination dispatcher implementations (`RecipeDestinationDispatcher` → `OptimiserService.handleRecipeFeedback`, `PreferenceDestinationDispatcher` → `PreferenceUpdateService.applyFeedback`, `NutritionDestinationDispatcher` → `NutritionUpdateService.applyFeedback`, `ProvisionsDestinationDispatcher` → `ProvisionUpdateService.applyFeedback`), the per-destination **`@Transactional(propagation = REQUIRES_NEW)`** isolation that lets one destination fail without rolling back its peers, the **routing log row INSERTs** with `status = PENDING → APPLIED | FAILED | AWAITING_USER_APPROVAL`, the failure-mode classification (`RoutingFailureKind`: `TRANSIENT | DESTINATION_VALIDATION | DESTINATION_BUSINESS | AI_UNAVAILABLE | UNKNOWN`), the **post-routing `submissionStatus` reconciliation** (`ROUTED` / `PARTIALLY_FAILED` / `FAILED`), and the **`FeedbackProcessedEvent` publication site** for the post-routing path (one event per entry per LLD's debounce rule). Per [`lld/feedback.md §Flow 3 lines 745-786`](../../lld/feedback.md), [`lld/feedback.md §Package Layout — DestinationDispatcher line 54`](../../lld/feedback.md), [`lld/feedback.md §Routing failure-mode coverage lines 776-784`](../../lld/feedback.md), [`design/feedback-system.md §Multi-Destination Routing lines 130-164`](../../design/feedback-system.md).

**Hard dependency on adaptation-pipeline-<predicted-id>**: per [LLD line 15](../../lld/feedback.md), the recipe destination dispatcher calls `OptimiserService.handleRecipeFeedback(recipeId, RecipeFeedbackInput)`. The optimiser module's LLD doesn't exist yet — `adaptation-pipeline.md` is being ticketed in parallel as a sibling wave-3 ticket. **The exact `OptimiserService` interface signature is a sibling-ticket forward dependency.** 01d ships against the **assumed** signature `(UUID recipeId, UUID userId, String extractedFeedback, UUID traceId, JsonNode structuredPayload) → AdaptationResult` per [LLD line 772](../../lld/feedback.md). If the sibling ticket finalises a different shape, 01d's `RecipeDestinationDispatcher` adapts via a single mapping function. **Mitigation pattern**: ship `OptimiserService` as a **cross-module SPI** that the adaptation-pipeline implements; the SPI lives in `feedback/spi/RecipeFeedbackHandler.java` (NOT `OptimiserService` itself — that's the adaptation-pipeline's own surface). The adaptation-pipeline's real `OptimiserServiceImpl` either implements `feedback.spi.RecipeFeedbackHandler` directly OR a sibling adapter does the translation. 01d ships a **`NoopRecipeFeedbackHandler`** as the SPI-with-Noop default so 01d can verify before adaptation-pipeline merges.

**LLD divergence note** — **the four cross-module dispatcher targets**: per [LLD lines 757-760](../../lld/feedback.md):
- **`RECIPE`** → `optimiserService.handleRecipeFeedback(recipeId, RecipeFeedbackInput)` — **NOT** `RecipeUpdateService.applyFeedback`. The LLD is explicit (LLD line 772) that "feedback NEVER writes to `RecipeUpdateService` directly".
- **`PREFERENCE`** → `preferenceUpdateService.applyFeedback(userId, PreferenceFeedbackCommand)`.
- **`NUTRITION`** → `nutritionUpdateService.applyFeedback(userId, NutritionFeedbackRequest)`.
- **`PROVISIONS`** → `provisionUpdateService.applyFeedback(userId, ProvisionsFeedbackCommand)`.
The exact method names and DTO shapes for the three wave-2 destinations need verification against the merged LLDs (preference.md / nutrition.md / provisions.md). **Each destination's `applyFeedback` is assumed to exist**; if a wave-2 LLD calls it something different (`applyFeedbackCommand`, `applyDelta`, etc.), the dispatcher adapts. **Worth user review** — verify each wave-2 LLD's update-service surface before the agent starts.

**LLD divergence note** — **sequential vs parallel dispatch**: per LLD line 774 and decision lines 944 ("Sequential per-destination routing, not parallel"), 01d implements **sequential** dispatch — each destination runs after the previous returns. Reason: predictable confirmation ordering + simpler cost-cap accounting. Worth user review (LLD acknowledges parallel as a follow-up); kept conservative for v1.

**LLD divergence note** — **on the recipe id resolution**: per [LLD line 757](../../lld/feedback.md), the `RECIPE` dispatcher first tries `classification.structuredPayload.recipeId`, falls back to `entry.uiContext.recipeId`. **If neither is present**, fail the routing with `failure_kind = DESTINATION_VALIDATION` and message `"no recipe attached to this feedback"`. The other three dispatchers do their own field extraction from `structuredPayload`; the recipe path is the only one requiring a UI-context fallback.

**LLD divergence note** — **`structured_payload` shape per destination**: per [HLD §How multi-destination works](../../design/feedback-system.md) and [LLD line 144](../../lld/feedback.md), the classifier emits per-destination JSON that varies. 01d's dispatchers parse `structuredPayload` defensively — the LLD says "destination dispatcher is responsible for parsing what it cares about". **Strategy in 01d**: each dispatcher has a small `parsePayload(JsonNode)` helper that returns a destination-specific record (e.g. `RecipePayload(UUID recipeId, Boolean affectsPlan)`); missing fields default to null/false; the dispatcher then composes the destination's request DTO. **Worth implementer review** — if classifier prompt 04 doesn't yet emit consistent payload keys, the dispatchers may need to tolerate variation; the prompt-engineering work track should align on per-destination payload keys before this stabilises.

**Defers** (still out of scope after 01d):

- Clarification answer endpoint + 7-day TTL sweep → **feedback-01e**
- Misclassification correction + replay → **feedback-01f**
- `@Scheduled` sweeps (transient retry of FAILED+TRANSIENT routings, stuck classification retry, daily expiry) → **feedback-01g**
- Cross-module integration tests → **feedback-01g**

01d unblocks the end-to-end happy path: a submitted feedback now classifies, routes, and lands real changes in the four destination modules' state. The clarification (01e) and correction (01f) flows can layer on top.

## Behavioural spec

### `DestinationDispatcher` strategy interface

1. **`DestinationDispatcher`** at `feedback/domain/service/internal/DestinationDispatcher.java`. Package-private interface:
   ```java
   interface DestinationDispatcher {
     Destination destination();    // declares which Destination this impl handles
     DispatchResult dispatch(DispatchContext ctx);
   }
   ```
2. **`DispatchContext`** carrier record:
   ```java
   public record DispatchContext(
       UUID feedbackId, UUID userId, UUID traceId,
       UUID routingLogId,                       // freshly allocated; pass through for downstream correlation
       UiContextDto uiContext,                  // for the recipe path's fallback recipeId lookup
       ClassificationOutput classification,     // destination + confidence + extractedFeedback + structuredPayload
       int attemptNumber                        // classification attempt that produced this routing
   ) {}
   ```
3. **`DispatchResult`** carrier record — what the dispatcher tells the router:
   ```java
   public record DispatchResult(
       RoutingStatus status,                    // APPLIED | AWAITING_USER_APPROVAL | FAILED
       String actionTaken,                      // human-readable summary (LLD line 118)
       JsonNode destinationResultJson,          // typed shell from the destination (nullable on FAILED)
       RoutingFailureKind failureKind,          // populated only on FAILED
       String failureMessage                    // populated only on FAILED; truncated to 512 chars
   ) {}
   ```
4. **Strategy registration** via Spring's `Map<Destination, DestinationDispatcher>` injection: each impl is a `@Component`; an aggregator (`DestinationDispatcherRegistry`) collects them into a `Map` keyed on `destination()`. Adding a fifth destination later requires adding the enum value AND a new `@Component` — no router-code change.

### `DestinationDispatcherRegistry`

5. **`DestinationDispatcherRegistry`** at `feedback/domain/service/internal/DestinationDispatcherRegistry.java`. `@Component`, constructor-injects `List<DestinationDispatcher>`. Builds an immutable `Map<Destination, DestinationDispatcher>`:
   ```java
   @Component
   public class DestinationDispatcherRegistry {
     private final Map<Destination, DestinationDispatcher> byDestination;
     public DestinationDispatcherRegistry(List<DestinationDispatcher> dispatchers) {
       Map<Destination, DestinationDispatcher> map = new EnumMap<>(Destination.class);
       for (DestinationDispatcher d : dispatchers) {
         DestinationDispatcher prev = map.put(d.destination(), d);
         if (prev != null) {
           throw new IllegalStateException(
             "duplicate DestinationDispatcher for " + d.destination()
             + ": " + prev.getClass().getName() + " and " + d.getClass().getName());
         }
       }
       this.byDestination = Map.copyOf(map);
       if (this.byDestination.keySet().size() != Destination.values().length) {
         throw new IllegalStateException("missing DestinationDispatcher for: "
             + Sets.difference(EnumSet.allOf(Destination.class), byDestination.keySet()));
       }
     }
     public DestinationDispatcher resolve(Destination d) { return byDestination.get(d); }
   }
   ```
   **Fail-fast at startup** if a destination has no dispatcher (catches a missed Spring `@Component` annotation) or has duplicates (catches accidental two-impls-for-one-destination).

### `FeedbackRouter` real impl (replacing 01c's Noop)

6. **`FeedbackRouterImpl`** at `feedback/domain/service/internal/FeedbackRouterImpl.java`. **Configuration-style registration** (NOT `@Component`) to preserve the SPI-with-Noop ordering per [01c §17](01c-classifier-and-confidence-gate.md):
   ```java
   @Configuration
   public class FeedbackRouterConfiguration {
     @Bean
     FeedbackRouter feedbackRouter(/* deps */) { return new FeedbackRouterImpl(/* deps */); }
   }
   ```
   When this bean is present, 01c's `NoopFeedbackRouter`'s `@ConditionalOnMissingBean` defers — the real router wins.
7. **`FeedbackRouterImpl.routeAll`** — the entry point called by 01c's listener. Per [LLD Flow 3 lines 745-770](../../lld/feedback.md):
   ```java
   @Override
   public void routeAll(UUID feedbackId, List<ScoredClassification> scored) {
     // (NOT @Transactional — each destination opens its own REQUIRES_NEW)
     FeedbackEntry entry = entryRepository.findById(feedbackId)
         .orElseThrow(() -> new FeedbackEntryNotFoundException(feedbackId));
     UUID userId = entry.getUserId();
     UUID traceId = entry.getTraceId();
     UiContextDto uiContext = mapDocumentToDto(entry.getUiContext());
     int attempt = entry.getClassificationAttempts();
     Set<Destination> touched = EnumSet.noneOf(Destination.class);
     boolean anyFailed = false, anyNonFailed = false;

     for (ScoredClassification s : scored) {
       Destination dest = s.classification().destination();
       UUID routingLogId = UUID.randomUUID();
       try {
         DispatchResult result = routeOne(routingLogId, entry, userId, traceId, uiContext, s, attempt);
         touched.add(dest);
         if (result.status() == RoutingStatus.FAILED) anyFailed = true;
         else anyNonFailed = true;
       } catch (RuntimeException unrecoverable) {
         // Catastrophic — the routeOne tx itself failed; persist FAILED row defensively.
         persistFailureLog(routingLogId, entry, s, attempt,
             RoutingFailureKind.UNKNOWN, unrecoverable.getMessage());
         anyFailed = true;
       }
     }

     // Step 7 — reconcile entry status (own REQUIRES_NEW tx)
     reconcileEntryStatus(feedbackId, anyFailed, anyNonFailed);

     // Step 8 — one event per entry (LLD line 770)
     eventPublisher.publishEvent(new FeedbackProcessedEvent(
         feedbackId, userId, touched, anyFailed, /* clarificationPending */ false,
         traceId, Instant.now()));
   }
   ```
8. **`routeOne` method** — runs in `REQUIRES_NEW` so one dispatcher's failure doesn't roll back peers:
   ```java
   @Transactional(propagation = Propagation.REQUIRES_NEW)
   protected DispatchResult routeOne(UUID routingLogId, FeedbackEntry entry,
                                     UUID userId, UUID traceId, UiContextDto uiContext,
                                     ScoredClassification scored, int attempt) {
     // Step 2 — write PENDING row (LLD line 754)
     RoutingLogEntry log = RoutingLogEntry.builder()
         .id(routingLogId)
         .feedbackEntry(entry)
         .destination(scored.classification().destination())
         .confidence(scored.classification().confidence())
         .extractedFeedback(scored.classification().extractedFeedback())
         .structuredPayload(scored.classification().structuredPayload())
         .routingDecision(scored.decision())
         .status(RoutingStatus.PENDING)
         .classificationAttempt(attempt)
         .routedAt(Instant.now())
         .build();
     routingLogRepository.save(log);

     // Step 3 — resolve dispatcher
     DestinationDispatcher dispatcher = registry.resolve(scored.classification().destination());

     // Step 4 — dispatch
     DispatchContext ctx = new DispatchContext(
         entry.getId(), userId, traceId, routingLogId, uiContext, scored.classification(), attempt);
     DispatchResult result;
     try {
       result = dispatcher.dispatch(ctx);
     } catch (Exception ex) {
       result = classifyException(ex);   // step 6 — exception → RoutingFailureKind
     }

     // Step 5/6 — stamp the row with the dispatcher's outcome
     log.setStatus(result.status());
     log.setActionTaken(truncate(result.actionTaken(), 512));
     log.setDestinationResultJson(result.destinationResultJson());
     log.setFailureKind(result.failureKind());
     log.setFailureMessage(truncate(result.failureMessage(), 512));
     log.setCompletedAt(Instant.now());
     routingLogRepository.save(log);

     return result;
   }
   ```
9. **`classifyException` exception → `RoutingFailureKind`** mapping per [LLD Routing failure-mode coverage lines 776-784](../../lld/feedback.md):
   - `ConstraintViolationException` (Jakarta) → `DESTINATION_VALIDATION`
   - Wave-2-module-specific business exceptions (e.g. `RecipeNotFoundException`, `PreferenceNotFoundException`, `NutritionTargetsNotFoundException`, etc.) → `DESTINATION_BUSINESS`. **Detected via `instanceof MealPrepException` AND NOT `instanceof DataAccessResourceFailureException`** — a small switch. **The full list of business exceptions per destination is forward dependency**; conservative default: anything in `*.exception` packages of the four destination modules.
   - `DataAccessResourceFailureException`, `CannotAcquireLockException`, `QueryTimeoutException` → `TRANSIENT`
   - `AiUnavailableException`, `AiCircuitOpenException`, `AiCallFailedException`, `AiResponseInvalidException` (when the destination itself does a downstream AI call) → `AI_UNAVAILABLE`
   - **Anything else** → `UNKNOWN`
10. **Step 7 — reconcile entry status** in a separate `REQUIRES_NEW` tx after the per-destination loop:
    ```java
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void reconcileEntryStatus(UUID feedbackId, boolean anyFailed, boolean anyNonFailed) {
      FeedbackEntry entry = entryRepository.findById(feedbackId)
          .orElseThrow(() -> new FeedbackEntryNotFoundException(feedbackId));
      SubmissionStatus next;
      if (anyFailed && anyNonFailed) next = SubmissionStatus.PARTIALLY_FAILED;
      else if (anyFailed)            next = SubmissionStatus.FAILED;
      else                           next = SubmissionStatus.ROUTED;
      entry.setSubmissionStatus(next);
      entryRepository.save(entry);
    }
    ```

### Four destination dispatchers

11. **`RecipeDestinationDispatcher`** at `feedback/domain/service/internal/dispatcher/RecipeDestinationDispatcher.java`. `@Component`, package-private. `destination() == Destination.RECIPE`. Calls `feedback.spi.RecipeFeedbackHandler` (the SPI defined in this ticket, see §16). Recipe-id resolution: prefer `classification.structuredPayload.path("recipeId").asText()`, fall back to `ctx.uiContext().recipeId()`. If neither present → return `DispatchResult(FAILED, ..., DESTINATION_VALIDATION, "no recipe attached to this feedback")`.
    ```java
    @Override
    public DispatchResult dispatch(DispatchContext ctx) {
      UUID recipeId = resolveRecipeId(ctx);
      if (recipeId == null) {
        return new DispatchResult(RoutingStatus.FAILED, null, null,
            RoutingFailureKind.DESTINATION_VALIDATION, "no recipe attached to this feedback");
      }
      RecipeFeedbackHandler.Input input = new RecipeFeedbackHandler.Input(
          recipeId, ctx.userId(), ctx.classification().extractedFeedback(),
          ctx.traceId(), ctx.classification().structuredPayload());
      RecipeFeedbackHandler.Result result = recipeHandler.handleRecipeFeedback(input);
      // Recipe path — the optimiser MAY produce a proposed-not-applied adaptation
      RoutingStatus status = result.requiresApproval()
          ? RoutingStatus.AWAITING_USER_APPROVAL
          : RoutingStatus.APPLIED;
      return new DispatchResult(status, result.summary(), objectMapper.valueToTree(result.payload()),
          null, null);
    }
    ```
12. **`PreferenceDestinationDispatcher`** at `.../dispatcher/PreferenceDestinationDispatcher.java`. `destination() == PREFERENCE`. Adapter to `PreferenceUpdateService.applyFeedback`. **Assumed signature** per [LLD line 758](../../lld/feedback.md): `void applyFeedback(UUID userId, PreferenceFeedbackCommand command)` where `PreferenceFeedbackCommand` is a record defined in the preference module's API. **If the assumed signature doesn't exist on the merged preference module**, ship a temporary `PreferenceFeedbackBridge` SPI in `feedback/spi/` with a Noop default — same pattern as RecipeFeedbackHandler. **Verify** during 01d's first agent verify step which surface the preference module actually exposes. **Worth user review.**
13. **`NutritionDestinationDispatcher`** at `.../dispatcher/NutritionDestinationDispatcher.java`. `destination() == NUTRITION`. Adapter to `NutritionUpdateService.applyFeedback` per [LLD line 759](../../lld/feedback.md). **Same verify+fallback-SPI pattern** if the assumed signature is absent.
14. **`ProvisionsDestinationDispatcher`** at `.../dispatcher/ProvisionsDestinationDispatcher.java`. `destination() == PROVISIONS`. Adapter to `ProvisionUpdateService.applyFeedback` per [LLD line 760](../../lld/feedback.md). **Same pattern.**
15. Each dispatcher catches its target service's exceptions in a thin layer — but only those that don't naturally bubble through `classifyException` cleanly. Default behaviour: let exceptions propagate; `FeedbackRouterImpl.routeOne` catches and classifies.

### `RecipeFeedbackHandler` SPI (cross-module — feedback owns the SPI)

16. **`RecipeFeedbackHandler`** at `feedback/spi/RecipeFeedbackHandler.java`. **Public interface** — the adaptation-pipeline module implements it. Following the pattern from [recipe-01f §Critical: cross-SPI coupling](../recipe/01f-write-api-and-events.md): **feedback owns the SPI**, the adaptation-pipeline module provides a `@Component` impl with `@ConditionalOnClass` so the cross-module dep is loose.
    ```java
    package com.example.mealprep.feedback.spi;

    public interface RecipeFeedbackHandler {
      Result handleRecipeFeedback(Input input);

      record Input(UUID recipeId, UUID userId, String extractedFeedback,
                   UUID traceId, JsonNode structuredPayload) {}
      record Result(boolean requiresApproval, String summary, Map<String, Object> payload) {}
    }
    ```
17. **`NoopRecipeFeedbackHandlerConfiguration`** at `feedback/config/NoopRecipeFeedbackHandlerConfiguration.java`. SPI-with-Noop pattern (per [agent-prompt-template.md §SPI-with-Noop](../../../ai-workflow/templates/agent-prompt-template.md)):
    ```java
    @Configuration
    public class NoopRecipeFeedbackHandlerConfiguration {
      @Bean
      @ConditionalOnMissingBean(RecipeFeedbackHandler.class)
      RecipeFeedbackHandler defaultRecipeFeedbackHandler() {
        return new NoopRecipeFeedbackHandlerImpl();
      }
      static class NoopRecipeFeedbackHandlerImpl implements RecipeFeedbackHandler {
        @Override
        public Result handleRecipeFeedback(Input input) {
          // Returns an inert result that the RECIPE dispatcher treats as a soft-failure with AI_UNAVAILABLE-ish semantics.
          throw new RecipeFeedbackHandlerUnavailableException(
              "adaptation-pipeline module not on classpath yet");
        }
      }
    }
    ```
18. **`RecipeFeedbackHandlerUnavailableException`** at `feedback/exception/RecipeFeedbackHandlerUnavailableException.java`. Extends `FeedbackException`. **Treated as `AI_UNAVAILABLE` in `classifyException`** — the routing logs `FAILED + AI_UNAVAILABLE`; the sweep in 01g retries when the real handler lands. Wave-3 sibling — the adaptation-pipeline ticket will implement `RecipeFeedbackHandler` and ship a real `@Component`.

### Routing-log writes

19. **All routing log writes from 01d happen inside `routeOne`'s `REQUIRES_NEW` tx**. The row is INSERTed with `status = PENDING` immediately (so a crash mid-dispatch leaves an audit trail), then UPDATEd to the terminal status after dispatch. **`@Version` not present on `RoutingLogEntry`** (LLD line 142) — single-writer-per-row guarded by router code; no OL concerns.
20. **`actionTaken` truncation**: cap at 512 chars per the column width (LLD line 118). Add a small `truncate(String, int)` static helper.
21. **`failure_message`**: same 512-char truncation. **Never include the AI API key** (LLD line 122) — the dispatchers' exception messages shouldn't carry it, but defensively strip `Authorization` / `x-api-key` substrings.

### Post-routing event

22. **`FeedbackProcessedEvent`** published `AFTER COMMIT` of the reconcile tx (step 8). **One event per entry** regardless of destination count — per [LLD §Event debouncing](../../lld/feedback.md). Verified in the test plan.

### Boundary additions

23. Append to `FeedbackBoundaryTest`:
    ```java
    @ArchTest
    static final ArchRule spiRecipeHandlerPublic = classes()
        .that().resideInAPackage("com.example.mealprep.feedback.spi..")
        .should().bePublic();   // already covered in 01a; restated
    // Recipe handler is intentionally cross-module — no rule forbidding imports of feedback.spi
    ```
24. **No new `@ExceptionHandler` methods on `FeedbackExceptionHandler`** — the new `RecipeFeedbackHandlerUnavailableException` is internal-only and never reaches HTTP (it's caught by `routeOne` and stamped onto the routing log; the controller path doesn't surface it).

## OpenAPI updates

**Zero OpenAPI changes.** 01d ships internal routing + dispatchers — no HTTP endpoints. The `RoutingDecisionDto` shape (from 01b) already accommodates `actionTaken`, `destinationResult`, `status`, `failureMessage`. The `GET /feedback/{id}` endpoint from 01b now surfaces the populated `routes[]` once routing completes (no controller change).

## Verbatim shape snippets

### `DestinationDispatcher` interface + registry

```java
package com.example.mealprep.feedback.domain.service.internal;

interface DestinationDispatcher {
  Destination destination();
  DispatchResult dispatch(DispatchContext ctx);
}

public record DispatchContext(UUID feedbackId, UUID userId, UUID traceId, UUID routingLogId,
                              UiContextDto uiContext, ClassificationOutput classification,
                              int attemptNumber) {}

public record DispatchResult(RoutingStatus status, String actionTaken,
                             JsonNode destinationResultJson,
                             RoutingFailureKind failureKind, String failureMessage) {

  public static DispatchResult applied(String actionTaken, JsonNode result) {
    return new DispatchResult(RoutingStatus.APPLIED, actionTaken, result, null, null);
  }
  public static DispatchResult awaitingApproval(String actionTaken, JsonNode result) {
    return new DispatchResult(RoutingStatus.AWAITING_USER_APPROVAL, actionTaken, result, null, null);
  }
  public static DispatchResult failed(RoutingFailureKind kind, String msg) {
    return new DispatchResult(RoutingStatus.FAILED, null, null, kind, msg);
  }
}
```

### `RecipeDestinationDispatcher` skeleton

```java
@Component
@RequiredArgsConstructor
class RecipeDestinationDispatcher implements DestinationDispatcher {

  private final RecipeFeedbackHandler recipeHandler;     // SPI; Noop or real
  private final ObjectMapper objectMapper;

  @Override public Destination destination() { return Destination.RECIPE; }

  @Override
  public DispatchResult dispatch(DispatchContext ctx) {
    UUID recipeId = resolveRecipeId(ctx);
    if (recipeId == null) {
      return DispatchResult.failed(RoutingFailureKind.DESTINATION_VALIDATION,
          "no recipe attached to this feedback");
    }
    RecipeFeedbackHandler.Input input = new RecipeFeedbackHandler.Input(
        recipeId, ctx.userId(),
        ctx.classification().extractedFeedback(),
        ctx.traceId(),
        ctx.classification().structuredPayload());
    RecipeFeedbackHandler.Result result = recipeHandler.handleRecipeFeedback(input);
    JsonNode payload = objectMapper.valueToTree(result.payload());
    return result.requiresApproval()
        ? DispatchResult.awaitingApproval(result.summary(), payload)
        : DispatchResult.applied(result.summary(), payload);
  }

  private UUID resolveRecipeId(DispatchContext ctx) {
    JsonNode fromPayload = ctx.classification().structuredPayload().path("recipeId");
    if (!fromPayload.isMissingNode() && !fromPayload.isNull()) {
      try { return UUID.fromString(fromPayload.asText()); }
      catch (IllegalArgumentException ignored) { /* fall through */ }
    }
    return ctx.uiContext() == null ? null : ctx.uiContext().recipeId();
  }
}
```

### `PreferenceDestinationDispatcher` skeleton

```java
@Component
@RequiredArgsConstructor
class PreferenceDestinationDispatcher implements DestinationDispatcher {

  private final PreferenceUpdateService preferenceUpdateService;
  private final ObjectMapper objectMapper;

  @Override public Destination destination() { return Destination.PREFERENCE; }

  @Override
  public DispatchResult dispatch(DispatchContext ctx) {
    // Compose the preference module's request DTO from structuredPayload + extractedFeedback.
    // Exact shape forward-dependent on the merged preference LLD — fill in per the actual surface.
    PreferenceFeedbackCommand cmd = new PreferenceFeedbackCommand(
        ctx.classification().extractedFeedback(),
        ctx.classification().structuredPayload(),
        ctx.traceId());
    PreferenceFeedbackAck ack = preferenceUpdateService.applyFeedback(ctx.userId(), cmd);
    return DispatchResult.applied(ack.summary(), objectMapper.valueToTree(ack));
  }
}
```

### Exception classification

```java
private DispatchResult classifyException(Exception ex) {
  if (ex instanceof ConstraintViolationException) {
    return DispatchResult.failed(RoutingFailureKind.DESTINATION_VALIDATION, ex.getMessage());
  }
  if (ex instanceof AiUnavailableException || ex instanceof AiCircuitOpenException
      || ex instanceof AiCallFailedException || ex instanceof AiResponseInvalidException
      || ex instanceof RecipeFeedbackHandlerUnavailableException) {
    return DispatchResult.failed(RoutingFailureKind.AI_UNAVAILABLE, ex.getMessage());
  }
  if (ex instanceof DataAccessResourceFailureException
      || ex instanceof CannotAcquireLockException
      || ex instanceof QueryTimeoutException) {
    return DispatchResult.failed(RoutingFailureKind.TRANSIENT, ex.getMessage());
  }
  if (ex instanceof MealPrepException) {
    return DispatchResult.failed(RoutingFailureKind.DESTINATION_BUSINESS, ex.getMessage());
  }
  return DispatchResult.failed(RoutingFailureKind.UNKNOWN, ex.getMessage());
}
```

## Edge-case checklist

- [ ] Single classification (PREFERENCE, 0.92) → one routing log row INSERTed as PENDING then UPDATEd to APPLIED; entry → `ROUTED`; `FeedbackProcessedEvent` published with `destinationsTouched = {PREFERENCE}`, `partialFailure = false`
- [ ] Two classifications (PREFERENCE 0.9 + PROVISIONS 0.85) → two routing log rows; entry → `ROUTED`; one event published (debounced, per LLD line 770)
- [ ] Three classifications, one fails with `RecipeNotFoundException` (DESTINATION_BUSINESS), other two APPLIED → entry → `PARTIALLY_FAILED`; event published with `partialFailure = true`, `destinationsTouched` = all three
- [ ] All classifications fail (transient DB issue in each) → entry → `FAILED`; event published with `partialFailure = true`
- [ ] Recipe path with `structuredPayload.recipeId = "<valid-uuid>"` → uses payload value
- [ ] Recipe path with `structuredPayload.recipeId` absent BUT `uiContext.recipeId` non-null → falls back to UI context
- [ ] Recipe path with both absent → `DispatchResult.failed(DESTINATION_VALIDATION, "no recipe attached to this feedback")`; routing log status `FAILED`, `failure_kind = DESTINATION_VALIDATION`, `failure_message = "no recipe attached to this feedback"`
- [ ] Recipe path with `NoopRecipeFeedbackHandler` (adaptation-pipeline not loaded) → `RecipeFeedbackHandlerUnavailableException` thrown → classified as `AI_UNAVAILABLE`; routing log FAILED
- [ ] Recipe path with REAL `RecipeFeedbackHandler` returning `requiresApproval = true` → routing log status `AWAITING_USER_APPROVAL` (NOT `APPLIED`)
- [ ] Preference / Nutrition / Provisions path with a `ConstraintViolationException` from the destination → routing log `FAILED, DESTINATION_VALIDATION`
- [ ] Preference / Nutrition / Provisions path with a destination-specific business exception (`PreferenceNotFoundException`, etc.) → `DESTINATION_BUSINESS`
- [ ] Preference / Nutrition / Provisions path with `DataAccessResourceFailureException` → `TRANSIENT`
- [ ] Per-destination `REQUIRES_NEW` isolation verified: one destination throws inside its tx → its routing log is FAILED, peers succeed and persist as APPLIED (test using a Mockito-thrown exception on one dispatcher; assert peers landed)
- [ ] Sequential dispatch ordering: routing log rows have `routed_at` timestamps in classification-list order (not parallel)
- [ ] `DestinationDispatcherRegistry` fails fast at startup if a destination has no dispatcher (missing `@Component`)
- [ ] `DestinationDispatcherRegistry` fails fast at startup if two `@Component`s claim the same `destination()`
- [ ] `FeedbackRouterImpl` replaces `NoopFeedbackRouter` when both are on the classpath (via `@Configuration + @Bean` + `@ConditionalOnMissingBean`)
- [ ] `actionTaken` truncated to 512 chars (test with a 600-char dispatcher result)
- [ ] `failureMessage` truncated to 512 chars
- [ ] `failureMessage` does NOT contain API key fragments (defensive strip — assert with a mocked exception carrying a key-like substring)
- [ ] `routeOne` writes the PENDING row even if `dispatcher.dispatch(...)` throws (the row is saved before the dispatch call, then updated; on dispatch-throw it's saved AGAIN with FAILED + classified kind)
- [ ] `routeOne` catastrophic catch path (the tx itself throws, e.g. unique-constraint violation on `id`) → `persistFailureLog` defensive INSERT outside the failed tx so the audit trail survives — **needs implementer judgement**; alternative is to let the exception propagate to `routeAll` which has its own try/catch. **Worth user review.**
- [ ] `reconcileEntryStatus` only writes ONCE per `routeAll` call (one UPDATE on `feedback_entries`)
- [ ] `FeedbackProcessedEvent` published exactly once per `routeAll` call, AFTER COMMIT
- [ ] `RecipeFeedbackHandler` SPI is public; `RecipeFeedbackHandlerImpl` (when adaptation-pipeline ships) wires as `@Component` and Noop defers via `@ConditionalOnMissingBean`
- [ ] No N+1 — `routeAll` does 1 SELECT (entry), N inserts + N updates (per destination), 1 UPDATE (reconcile)
- [ ] Async race-test pattern (Awaitility) used for ITs asserting routing-completed state

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/feedback/spi/RecipeFeedbackHandler.java                       (PUBLIC SPI — adaptation-pipeline implements)

NEW   src/main/java/com/example/mealprep/feedback/exception/RecipeFeedbackHandlerUnavailableException.java

NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/DestinationDispatcher.java   (package-private interface)
NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/DispatchContext.java
NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/DispatchResult.java
NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/DestinationDispatcherRegistry.java
NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/FeedbackRouterImpl.java

NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/dispatcher/RecipeDestinationDispatcher.java
NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/dispatcher/PreferenceDestinationDispatcher.java
NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/dispatcher/NutritionDestinationDispatcher.java
NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/dispatcher/ProvisionsDestinationDispatcher.java

NEW   src/main/java/com/example/mealprep/feedback/config/FeedbackRouterConfiguration.java               (@Configuration @Bean — replaces NoopFeedbackRouter from 01c)
NEW   src/main/java/com/example/mealprep/feedback/config/NoopRecipeFeedbackHandlerConfiguration.java    (@Configuration @Bean @ConditionalOnMissingBean for the cross-module SPI default)

NEW   src/test/java/com/example/mealprep/feedback/FeedbackRouterImplTest.java                          (unit; mocks dispatchers; verifies fan-out, exception classification, reconcile logic)
NEW   src/test/java/com/example/mealprep/feedback/RecipeDestinationDispatcherTest.java                  (recipe-id resolution: payload, fallback, missing; happy + failure paths)
NEW   src/test/java/com/example/mealprep/feedback/PreferenceDestinationDispatcherTest.java
NEW   src/test/java/com/example/mealprep/feedback/NutritionDestinationDispatcherTest.java
NEW   src/test/java/com/example/mealprep/feedback/ProvisionsDestinationDispatcherTest.java
NEW   src/test/java/com/example/mealprep/feedback/DestinationDispatcherRegistryTest.java              (startup fail-fast: missing + duplicate)
NEW   src/test/java/com/example/mealprep/feedback/FeedbackRoutingFlowIT.java                            (full flow: submit → classified (via TestAiService) → routed → routing log rows; one FAILED dispatcher → PARTIALLY_FAILED; one event per entry)
MOD   src/test/java/com/example/mealprep/feedback/testdata/FeedbackTestData.java                       (append routing-log + scored-classification builders)
```

Count: ~17 new files + 1 modified. Estimated agent runtime 55-65 min.

**Files this ticket does NOT modify**:
- `config/GlobalExceptionHandler.java`, `archunit/ModuleBoundaryTest.java`
- 01c's `FeedbackClassificationListener` — the listener calls `router.routeAll(...)` which transparently switches from Noop to real impl via bean conditional
- Preference / nutrition / provisions module source code — UNLESS the assumed `applyFeedback` surface is absent on any of them, in which case the dispatcher uses a `feedback/spi/*FeedbackBridge` SPI with a Noop default. **Document this clearly in the agent report.**
- Recipe module — never directly invoked from feedback (LLD line 772)
- OpenAPI files — no endpoint changes

## Dependencies

- **Hard dependency**: `feedback-01a` (merged) — entities, enums, `RoutingLogEntry`, `Destination`, `RoutingStatus`, `RoutingDecision`, `RoutingFailureKind`.
- **Hard dependency**: `feedback-01b` (merged) — `FeedbackEntry`, `FeedbackEntryRepository`, `UiContextDto`, `SubmissionStatus`, `FeedbackProcessedEvent`.
- **Hard dependency**: `feedback-01c` (merged) — `FeedbackRouter` SPI, `ScoredClassification` record, `NoopFeedbackRouter` bean it supplants, `FeedbackClassificationListener` consumer.
- **Hard dependency**: `preference-01a/b` (merged) — `PreferenceUpdateService.applyFeedback` + `PreferenceFeedbackCommand` + `PreferenceFeedbackAck` (verify shapes). If different, ship `feedback/spi/PreferenceFeedbackBridge` with a Noop fallback — **DOCUMENT IN REPORT**.
- **Hard dependency**: `nutrition-01a/b` (merged) — `NutritionUpdateService.applyFeedback` + request/result types. Same fallback strategy.
- **Hard dependency**: `provisions-01a/b` (merged) — `ProvisionUpdateService.applyFeedback` + request/result types. Same fallback.
- **Hard dependency**: `adaptation-pipeline-<predicted-id>` (sibling wave-3 ticket being authored in parallel) — `RecipeFeedbackHandler` SPI impl. 01d ships with `NoopRecipeFeedbackHandler` so it can verify standalone; the real impl wires when the sibling ticket merges.
- **No hard dependency on feedback-01e or 01f** — those layer on top of 01d's routing.
- **Sibling tickets running in parallel** (Wave 3 round 2): `feedback-01c` (classifier) — has the listener that calls into 01d's router. Already-handled via SPI-with-Noop.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green
- [ ] All edge-case items above ticked
- [ ] `FeedbackRouterImpl` wires as `@Bean` in `@Configuration` (preserves SPI-with-Noop ordering vs 01c's Noop bean)
- [ ] `DestinationDispatcherRegistry` fail-fast at startup verified
- [ ] `RecipeFeedbackHandler` SPI lives in `feedback/spi/` (PUBLIC); Noop default in `feedback/config/` via `@Configuration @Bean @ConditionalOnMissingBean`
- [ ] Per-destination `REQUIRES_NEW` isolation verified — one dispatcher's exception doesn't roll back peers
- [ ] `FeedbackProcessedEvent` published exactly once per `routeAll` invocation, AFTER COMMIT of the reconcile tx
- [ ] No regression on existing tests
- [ ] No N+1 on the routing path (1 SELECT + 2 writes per destination + 1 reconcile UPDATE)
- [ ] Async race-pattern observed in ITs (Awaitility, no Thread.sleep)

## What's NOT in scope

- Clarification answer endpoint + 7-day TTL sweep + re-classify on answer → **feedback-01e**
- Misclassification correction endpoint + replay flow + destination revert SPIs (`PreferenceUpdateService.revertFeedback`, etc.) → **feedback-01f**
- `@Scheduled` transient-retry sweep for FAILED+TRANSIENT routings → **feedback-01g**
- Cross-module integration tests (`FeedbackPreferenceIntegrationIT`, `FeedbackOptimiserIntegrationIT`, `FeedbackProvisionsIntegrationIT`) → **feedback-01g**
- Parallel per-destination dispatch — LLD §3 line 774 (sequential is the conservative v1)
- Two-phase commit / cross-destination atomic rollback — LLD §Out of Scope line 935 (explicitly out)
- Hard-constraint refusal in the preference dispatcher — LLD §Out of Scope line 933 (prompt's responsibility for now; safety-net assertion left as a TODO in `PreferenceDestinationDispatcher`)
- `recent_classifications` history populated for the classifier prompt (TODO from 01c)

Squash-merge with: `feat(feedback): 01d — FeedbackRouter + DestinationDispatcher SPI + 4 destination dispatchers + multi-destination split + RecipeFeedbackHandler SPI`
