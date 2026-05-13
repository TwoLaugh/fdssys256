# Ticket: feedback — 01c FeedbackClassifier + FeedbackClassificationTask + ConfidenceGate + Async Classification Listener + Graceful Degrade

## Summary

Layer the **AI classification pipeline** (Flow 2 of the LLD) on top of feedback-01b: the `FeedbackClassificationTask implements AiTask<ClassificationResult>` (the AI module SPI implementation), the `FeedbackClassifier` internal helper that dispatches the task through `AiService.execute`, the `FeedbackClassificationContext` carrier record, the `ClassificationResult` + `ClassificationOutput` structured-output types, the `ConfidenceGate` that implements the **three-way confidence fork** (≥0.8 auto-route / 0.5-0.8 route-with-flag / <0.5 queue clarification), the `@TransactionalEventListener(phase = AFTER_COMMIT) @Async("feedbackClassificationPool")` listener on `FeedbackSubmittedEvent` that drives Flow 2, the **`ClarificationQuery` write path** when any classification dips below 0.5 (LLD's locked rule: any-<0.5 pauses the entire entry, single shortlist combining all low-confidence options), the **graceful-degrade defer-and-pending** behaviour on `AiUnavailableException` / `AiCircuitOpenException`, the terminal-failure path on `AiResponseInvalidException` / `AiCallFailedException`, and the **`FeedbackProcessedEvent` publication site** for the empty-classifications and clarification-queued branches. Per [`lld/feedback.md §Flow 2 lines 724-744`](../../lld/feedback.md), [`lld/feedback.md §Classifier (AiTask) lines 525-582`](../../lld/feedback.md), [`lld/feedback.md §DTOs §Classification result lines 271-291`](../../lld/feedback.md), [`lld/feedback.md §Events §FeedbackProcessedEvent line 664`](../../lld/feedback.md), [`design/feedback-system.md §Confidence handling lines 199-207`](../../design/feedback-system.md), [`lld/ai.md §SPI lines 144-173`](../../lld/ai.md).

**LLD divergence note** — **classification prompt content is DEFERRED**: per [LLD line 568](../../lld/feedback.md) the prompt body (system message, user template, eval set, token cap defaults) is owned by the prompt-engineering work track. The prompt itself is **drafted** at [`lld/prompts/04-feedback-classification.md`](../../lld/prompts/04-feedback-classification.md) and the template file will land at `src/main/resources/prompts/feedback/classify-feedback.txt` per [LLD §Out of Scope line 925](../../lld/feedback.md). **01c ships the `AiTask` shape, the `getUserPromptRef()` reference (`new PromptRef("feedback/classify-feedback", Optional.empty())`), the `getToolSchema()` JSON schema generated from `ClassificationResult.class`, and the context-rendering map — but NOT the prompt body file.** If the file is missing at runtime, `AiService.execute` throws `PromptTemplateNotFoundException` per [ai.md §Errors line 371](../../lld/ai.md) — caller bug; the operator must ship the prompt before enabling classification. **Soft dependency on prompt-pilot-validation** (the prompt-engineering work track validating prompt 04 against eval set).

**LLD divergence note** — **`TaskType.FEEDBACK_CLASSIFICATION` enum membership**: per [LLD line 562](../../lld/feedback.md) the task type "is already enumerated in [ai.md §SPI]" — verify against the AI module's actual `TaskType` enum (it's the cheap-tier slot per [ai.md line 164](../../lld/ai.md)). If the enum value is NOT present, **append** it as a single-line additive change to the AI module's `TaskType.java`. 01c's behavioural spec assumes the value exists; the agent's first verify step is to confirm. **Worth user review** — taking a single-line change against the AI module from a wave-3 ticket; alternative is a forward-only enum patch from a follow-up AI module ticket.

**LLD divergence note** — **`ConfidenceGate` boundary inclusivity**: LLD line 738 says "All classifications ≥ 0.5 → mark entry CLASSIFIED" and HLD [§Confidence handling lines 202-205](../../design/feedback-system.md) says `≥ 0.8` → auto, `0.5 – 0.8` → flag, `< 0.5` → clarify. **Locked 2026-05-07** per parent guidance. **01c interprets boundaries as**: `confidence >= 0.8` → `AUTO_ROUTED`; `confidence >= 0.5 && confidence < 0.8` → `ROUTED_WITH_FLAG`; `confidence < 0.5` → `CLARIFICATION_QUEUED`. The lower bound `0.5` is **inclusive on the flag side** (a 0.500 confidence is `ROUTED_WITH_FLAG`, not clarification). The `0.8` boundary is **inclusive on the auto side** (a 0.800 is `AUTO_ROUTED`, not flagged). Per LLD line 888's edge-case test: "Edge case: exactly 0.5 routes (boundary inclusive on the lower side per HLD)."

**LLD divergence note** — **routing log writes in 01c vs 01d**: the gate-decision step (LLD Flow 2 step 4) determines `RoutingDecision` for each classification. The **actual routing log row insertion** (`feedback_routing_log` rows with `status = PENDING`) is **DEFERRED to feedback-01d's Flow 3**. Reason: per LLD Flow 3 step 2 the router opens a fresh `REQUIRES_NEW` transaction per destination AND allocates the routing log id alongside the dispatcher call. Pre-creating PENDING rows in 01c would force 01d to either (a) UPDATE rather than INSERT, or (b) leave orphan PENDING rows on the clarification-queued branch. **01c thus only writes**: the `FeedbackEntry.submissionStatus` transition (`RECEIVED` → `CLASSIFYING` → `CLASSIFIED` / `CLARIFICATION_PENDING` / `FAILED`), the `ClarificationQuery` row (clarification-queued branch only), and the `lastClassifiedAt` timestamp. **The `ConfidenceGate.Result` carries the per-classification decision in memory** for 01d's router to consume.

**LLD divergence note** — **how 01c hands off to 01d's router**: two implementation options:
- **(A) In-process call from listener to router** — the same async classification listener (after writing the `CLASSIFIED` state) calls `FeedbackRouter.routeAll(entryId, gateResult)` directly. The router opens its own `REQUIRES_NEW` transactions per destination. This is the LLD's framing (Flow 2 step 4 hands to Flow 3).
- **(B) A second internal event (`FeedbackClassifiedEvent`)** — 01c publishes; 01d listens. Decouples the two but adds another async hop.
**Choice: (A)**. Reason: avoids a second async hop; the LLD's flow is sequential within the same listener; simpler tests. **01c declares an `@Autowired(required = false) FeedbackRouter router`** so 01c can ship and verify before 01d lands. When `router == null`, 01c **falls back** to a `NoopFeedbackRouter` that logs WARN and leaves the entry at `CLASSIFIED` with `routes = []`. **01d ships the real `FeedbackRouter`** which auto-wires once on the classpath. This SPI-with-Noop pattern follows the [agent-prompt-template.md §SPI-with-Noop pattern gotcha cluster](../../../ai-workflow/templates/agent-prompt-template.md) **exception**: `FeedbackRouter` is INTERNAL to the feedback module (not cross-module), so the `@Configuration + @Bean + @ConditionalOnMissingBean` pattern is used — see Behavioural §17.

**Defers** (still out of scope after 01c):

- `FeedbackRouter` real impl + `DestinationDispatcher` SPI + four destination impls + Flow 3 multi-destination routing → **feedback-01d**
- Clarification answer endpoint, `answerClarificationQuery` impl, 7-day TTL sweep → **feedback-01e**
- Misclassification correction + replay → **feedback-01f**
- `@Scheduled` sweep `retryStuckClassifications` + transient retry → **feedback-01g**
- `@TransactionalEventListener` on `AiCallSucceededEvent` stamping `lastClassifiedAt` → **feedback-01g**

01c unblocks 01d (which now has a populated `gateResult` to consume) and 01e (which now has a `ClarificationQuery` writer to test against).

## Behavioural spec

### `ClassificationResult` + `ClassificationOutput` structured-output records

1. **`ClassificationResult`** per [LLD lines 274-278](../../lld/feedback.md). Verbatim record:
   ```java
   public record ClassificationResult(
       @NotNull @Size(min = 0, max = 4) List<@Valid ClassificationOutput> classifications,
       @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal overallConfidence,
       String classifierNotes) {}
   ```
   `@Size(min = 0, max = 4)` — empty list is allowed (LLD line 290 — "a feedback that the classifier decided was non-actionable routes to nothing"). `max = 4` because the universe is exactly the four destinations.

2. **`ClassificationOutput`** per [LLD lines 280-285](../../lld/feedback.md):
   ```java
   public record ClassificationOutput(
       @NotNull Destination destination,
       @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
       @NotBlank String extractedFeedback,
       @NotNull JsonNode structuredPayload) {}
   ```
   `structuredPayload` is the destination-specific JSON the classifier emits (LLD line 144 — "loose because each destination uses different fields"). The destination dispatcher in 01d parses it.

3. **`ClassificationResultDto`** per [LLD lines 357-363](../../lld/feedback.md) — the **public-facing** read-side view (vs `ClassificationResult` which is internal to the flow):
   ```java
   public record ClassificationResultDto(
       int attempt, Instant performedAt,
       List<RoutingDecisionDto> classifications,
       BigDecimal overallConfidence,
       String classifierNotes) {}
   ```
   **Not exposed by any endpoint in 01c** — declared here because it's part of the LLD's API surface; 01d/01e/01g may surface it.

4. Lives in `feedback/api/dto/` (the structured-output type is internal but `ClassificationResultDto` is wire-facing; keep them together for cohesion).

5. **JSR-303 validation** on the records triggers the AI module's semantic-retry path per [ai.md §Flow 3 — Structured output parsing](../../lld/ai.md) and [LLD line 288](../../lld/feedback.md): a classifier emitting `confidence: 1.5` triggers one corrective re-prompt before failing. 01c doesn't need to handle this — `AiService.execute` does.

### `FeedbackClassificationContext`

6. **`FeedbackClassificationContext`** per [LLD lines 549-560](../../lld/feedback.md). Record carrier:
   ```java
   public record FeedbackClassificationContext(
       UUID userId, UUID traceId,
       String feedbackText,
       UiContextDto uiContext,
       Optional<String> userClarificationText,        // appended on re-classification after a < 0.5 answer
       Optional<Destination> userSelectedHint,        // set when the clarification answer picked an option
       int attemptNumber) {
     public Map<String, Object> toRendererMap() { /* ... */ }
   }
   ```
7. **`toRendererMap()`** body — the keys must match the placeholders in [`prompts/04-feedback-classification.md`](../../lld/prompts/04-feedback-classification.md). Per the prompt doc, the placeholders are `feedback_text`, `screen_context`, `current_meal_context`, `recent_classifications`. **01c implements**:
   - `feedback_text` ← `context.feedbackText()`
   - `screen_context` ← mapping per the prompt doc: `RECIPE_DETAIL → "recipe_page"`, `PLAN_MEAL_DETAIL` and `PLAN_VIEW → "plan_view"`, `GROCERY → "shopping_list"`, `NUTRITION_DASHBOARD → "nutrition_dashboard"`, `SETTINGS` and `GENERAL → "general"`. The mapping lives on `Screen` itself (NEW static method `Screen.toPromptToken()`) or in `FeedbackClassificationContext`'s helper — **prefer the latter** (keeps `Screen` clean of prompt-engineering concerns).
   - `current_meal_context` ← derived from `uiContext` if `mealSlotId` / `recipeId` present; else `null`. Shape: `{ recipeId, mealSlotId, eatenAt: null }` (the LLD's `MealContext` from the prompt doc).
   - `recent_classifications` ← **empty list in 01c**. The prompt doc says "last 5 of this user's classifications" — 01c defers populating this until feedback-01g adds the cross-module query (or a future enhancement). **TODO comment** with a pointer to feedback-01g.
   - `userClarificationText` and `userSelectedHint` ← appended when present (re-classification path); empty for first attempt. Used as additional context lines.

### `FeedbackClassificationTask`

8. **`FeedbackClassificationTask`** per [LLD lines 530-547](../../lld/feedback.md). Lives in `feedback/domain/service/internal/`. Verbatim impl:
   ```java
   public final class FeedbackClassificationTask implements AiTask<ClassificationResult> {
     private final FeedbackClassificationContext context;
     public FeedbackClassificationTask(FeedbackClassificationContext context) { this.context = context; }

     @Override public TaskType    getTaskType()      { return TaskType.FEEDBACK_CLASSIFICATION; }
     @Override public String      getSystemPrompt()  { return null;  /* template carries it */ }
     @Override public PromptRef   getUserPromptRef() { return new PromptRef("feedback/classify-feedback", Optional.empty()); }
     @Override public Map<String, Object> getContext() { return context.toRendererMap(); }
     @Override public ToolDefinition getToolSchema()   { return ToolDefinitions.classifyFeedback(); }
     @Override public Class<ClassificationResult> getResponseType() { return ClassificationResult.class; }
     @Override public UUID getUserId()  { return context.userId(); }
     @Override public UUID getTraceId() { return context.traceId(); }
     @Override public Optional<Duration> getTimeoutOverride() { return Optional.empty(); }
   }
   ```
9. **`ToolDefinitions.classifyFeedback()`** — a NEW class in `feedback/domain/service/internal/ToolDefinitions.java` exposing one static method:
   ```java
   public static ToolDefinition classifyFeedback() {
     return TOOL_DEF;
   }
   private static final ToolDefinition TOOL_DEF = generate();
   private static ToolDefinition generate() {
     // Uses victools/jsonschema-generator per [ai.md §Flow 3 line 566] to derive JSON schema from ClassificationResult.class.
     JsonNode schema = new SchemaGenerator(/* ... config ... */).generateSchema(ClassificationResult.class);
     return new ToolDefinition("classify_feedback", "Classify free-text feedback into routing destinations.", schema);
   }
   ```
   **Cache the result statically** — generated once at class load. The schema is derived from the record's Jakarta annotations + types.
   **Verify the project already has `victools/jsonschema-generator` on the classpath** (ai.md ships with it as a hard dep — confirm via `grep` of `pom.xml`).

### `FeedbackClassifier`

10. **`FeedbackClassifier`** at `feedback/domain/service/internal/FeedbackClassifier.java`. `@Component`, package-private. Wraps `AiService.execute`. Single public method:
    ```java
    public ClassificationResult classify(FeedbackClassificationContext context) {
      return aiService.execute(new FeedbackClassificationTask(context));
    }
    ```
    Why a wrapper rather than calling `aiService.execute` directly? Two reasons: (a) the LLD calls it out as a named seam (LLD line 527 — "the classifier is invoked exclusively from `FeedbackClassifier`"); (b) lets unit tests mock the classifier outcome without going through `AiService` — keeps the test isolation.
11. **Exception types passed through, not wrapped** — `AiUnavailableException`, `AiCircuitOpenException`, `AiResponseInvalidException`, `AiCallFailedException` propagate as-is. The listener catches and maps.

### `ConfidenceGate`

12. **`ConfidenceGate`** at `feedback/domain/service/internal/ConfidenceGate.java`. `@Component`, package-private. Pure function — no I/O. Single public method:
    ```java
    public GateResult evaluate(ClassificationResult result) {
      // Returns a per-classification list with the gate decision attached.
    }

    public record GateResult(
        List<ScoredClassification> classifications,    // empty when input was empty
        boolean anyBelowThreshold,                     // true if any classification.confidence < 0.5
        boolean allEmpty                               // true if input classifications.isEmpty()
    ) {}

    public record ScoredClassification(
        ClassificationOutput classification,
        RoutingDecision decision                       // AUTO_ROUTED | ROUTED_WITH_FLAG | CLARIFICATION_QUEUED
    ) {}
    ```
13. **Decision logic**:
    - For each `ClassificationOutput`: `confidence >= 0.8` → `AUTO_ROUTED`; `confidence >= 0.5` → `ROUTED_WITH_FLAG`; else → `CLARIFICATION_QUEUED`.
    - `anyBelowThreshold` is true if any element has `decision == CLARIFICATION_QUEUED`. Per LLD line 743 — "The < 0.5 path is mutually exclusive — if any single classification dips below 0.5, the entire entry pauses for clarification, even if other classifications were high-confidence."
    - `allEmpty` is true if `result.classifications().isEmpty()`. Routes to nothing; entry transitions to `ROUTED` with empty routes (LLD line 740).

### Async classification listener (Flow 2)

14. **`FeedbackClassificationListener`** at `feedback/domain/service/internal/FeedbackClassificationListener.java`. `@Component`, package-private. **Single listener method** triggered on `FeedbackSubmittedEvent`:
    ```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async(FeedbackAsyncConfig.CLASSIFICATION_POOL)
    public void onFeedbackSubmitted(FeedbackSubmittedEvent event) {
      classifyEntry(event.feedbackId());
    }
    ```
    **Note**: per [agent-prompt-template.md §`@TransactionalEventListener` + `@Transactional` propagation](../../../ai-workflow/templates/agent-prompt-template.md), the listener method is **NOT annotated `@Transactional`**. The body opens its own short transactions via injected service methods (`@Transactional(propagation = REQUIRES_NEW)`).
15. **`classifyEntry(UUID feedbackId)` flow** (LLD Flow 2 steps 1-5):
    1. **Step 1** — open `REQUIRES_NEW` short tx: load `FeedbackEntry`, mark `submissionStatus = CLASSIFYING`, increment `classificationAttempts`. Save. This commits before the AI call. (Reason: per [style-guide.md §Transaction-AI separation](../../lld/style-guide.md) and [LLD line 858](../../lld/feedback.md), AI calls run outside DB transactions.)
    2. **Step 2** — build `FeedbackClassificationContext` from the entry. `feedbackText = entry.text`; `uiContext = mapDocumentToDto(entry.uiContext)` (NEW small helper; the inverse of 01b's `toDocument`); `userClarificationText = Optional.empty()` and `userSelectedHint = Optional.empty()` on first attempt — feedback-01e will pass through the pending-clarification answer when re-firing; `attemptNumber = entry.classificationAttempts` (post-increment value from step 1).
    3. **Step 3** — call `feedbackClassifier.classify(context)`. **Catch in this order**:
       - **`AiUnavailableException` | `AiCircuitOpenException`** → open `REQUIRES_NEW` tx: revert entry `submissionStatus = RECEIVED` (so the sweep in feedback-01g picks it up). **Decrement** `classificationAttempts` (the failed attempt doesn't count). Log INFO with traceId. **End**. Do not publish `FeedbackProcessedEvent`.
       - **`AiResponseInvalidException` | `AiCallFailedException`** → open `REQUIRES_NEW` tx: mark entry `submissionStatus = FAILED` (terminal); set `lastClassifiedAt = Instant.now()` (the attempt DID happen). Publish a degenerate `FeedbackProcessedEvent(feedbackId, userId, destinationsTouched = ∅, partialFailure = true, clarificationPending = false, traceId, occurredAt)`. **End**.
       - **`AiTokenCapExceededException`** → same as `AiCallFailedException` path (terminal; user can't usefully retry an oversized context).
    4. **Step 4** — on success: gate via `confidenceGate.evaluate(result)`:
       - **`gate.anyBelowThreshold() == true`** → open `REQUIRES_NEW` tx: write **one** `ClarificationQuery` (per LLD line 951 — "single ClarificationQuery per <0.5 event — combined shortlist across all low-confidence options"). Build `classifierOptionsJson` as a JSON array of `{destination, snippet, classifierJustification}` from each low-confidence `ScoredClassification` (the `snippet` is `extractedFeedback`; the `classifierJustification` is composed as `result.classifierNotes()` or, if null, a canned `"confidence " + classification.confidence().toPlainString()`). Set `questionText = "I'm not sure what to do with this. Did you mean..."` (the canned phrasing from [feedback-system.md §Confidence handling line 205](../../design/feedback-system.md)). `expiresAt = Instant.now().plus(Duration.ofDays(7))` per the **locked 7-day TTL** (LLD line 200, parent guidance). Mark entry `submissionStatus = CLARIFICATION_PENDING`. Set `lastClassifiedAt = Instant.now()`. Publish `FeedbackProcessedEvent(... clarificationPending = true)`. **End**.
       - **`gate.allEmpty() == true`** → open `REQUIRES_NEW` tx: mark entry `submissionStatus = ROUTED` (with empty routes). Set `lastClassifiedAt = Instant.now()`. Publish `FeedbackProcessedEvent(... destinationsTouched = ∅, partialFailure = false, clarificationPending = false)`. **End**.
       - **`else`** (gate decision per classification ∈ {AUTO_ROUTED, ROUTED_WITH_FLAG}; none below 0.5) → open `REQUIRES_NEW` tx: mark entry `submissionStatus = CLASSIFIED`. Set `lastClassifiedAt = Instant.now()`. **Hand to `FeedbackRouter.routeAll(feedbackId, gate.classifications())`** (or the Noop if router missing — see §17). **The router publishes `FeedbackProcessedEvent` when done** (01d's responsibility).
    5. **Step 5** — `lastClassifiedAt` is updated in each terminal-state transition above. (Belt-and-braces: an `AiCallSucceededEvent` listener in 01g cross-stamps `lastClassifiedAt` by joining on `traceId` — independent of this happy path.)

16. **Error semantics**: the listener catches the four AI exceptions but lets **anything else** (RuntimeException, ConstraintViolationException, etc.) propagate. Spring's `@Async` infrastructure logs to the standard logger; the entry stays in `CLASSIFYING` and the sweep in 01g picks it up (sweep treats "stuck in CLASSIFYING for > 5 min" the same as `RECEIVED`).

### `FeedbackRouter` SPI + Noop default — internal SPI

17. **`FeedbackRouter`** interface in `feedback/domain/service/internal/FeedbackRouter.java`. **Internal SPI** (not in `spi/` — that subpackage is for cross-module SPIs; this one is within the module). Signature:
    ```java
    interface FeedbackRouter {
      /** Routes all (≥0.5) classifications. Each destination dispatcher runs in its own REQUIRES_NEW tx. */
      void routeAll(UUID feedbackId, List<ScoredClassification> classifications);
    }
    ```
18. **`NoopFeedbackRouter`** as the default impl in 01c. `@Configuration` + `@Bean @ConditionalOnMissingBean(FeedbackRouter.class)`:
    ```java
    @Configuration
    public class NoopFeedbackRouterConfiguration {
      @Bean @ConditionalOnMissingBean(FeedbackRouter.class)
      FeedbackRouter defaultFeedbackRouter() { return new NoopFeedbackRouter(); }

      static class NoopFeedbackRouter implements FeedbackRouter {
        @Override public void routeAll(UUID feedbackId, List<ScoredClassification> classifications) {
          // Stays at CLASSIFIED with empty routes. Logs WARN. feedback-01d ships the real impl.
        }
      }
    }
    ```
    **SPI-with-Noop pattern recipe applied** (per [agent-prompt-template.md §SPI-with-Noop pattern gotcha cluster](../../../ai-workflow/templates/agent-prompt-template.md)):
    - **`@Bean` factory in a `@Configuration` class** — NOT `@Component @ConditionalOnMissingBean` on the impl class.
    - **Method name DIFFERENT** from the configuration class name (avoid `BeanDefinitionOverrideException`).
    - Config class lives in `feedback/config/` to keep the impl-package boundary clean.
19. **Note**: when feedback-01d's real `FeedbackRouter` ships as a `@Component`, the Noop steps aside via `@ConditionalOnMissingBean`. **However**, the Noop's `@ConditionalOnMissingBean` fires on bean evaluation order — if 01d's `@Component` registers AFTER the Noop's evaluation, the Noop wins (round-5 bug). **Mitigation**: 01d ships its real `FeedbackRouter` as a `@Bean` in a `@Configuration` class (matching the SPI pattern) rather than `@Component` — this ensures the conditional fires in the correct order. **Document this in 01d's ticket** (already-flagged).
20. **`ScoredClassification`** record lives in `feedback/domain/service/internal/ConfidenceGate.java` (as a nested public record) so both `ConfidenceGate` and the router share it without circular packaging.

### State machine invariants

21. **Entry `submissionStatus` transitions admitted by 01c**:
    - `RECEIVED → CLASSIFYING` (step 1)
    - `CLASSIFYING → RECEIVED` (defer-and-pending on AiUnavailable / AiCircuitOpen)
    - `CLASSIFYING → FAILED` (terminal on AiResponseInvalid / AiCallFailed / AiTokenCapExceeded)
    - `CLASSIFYING → CLARIFICATION_PENDING` (any-<0.5)
    - `CLASSIFYING → ROUTED` (empty classifications)
    - `CLASSIFYING → CLASSIFIED` (all ≥0.5; hands to router)
22. **Entry `submissionStatus` transitions NOT admitted by 01c**: `CLASSIFIED → ROUTED` / `PARTIALLY_FAILED` / `FAILED` (those are 01d post-routing); `CLARIFICATION_PENDING → RECEIVED` (01e on answer); `ROUTED → CORRECTED` (01f).

## OpenAPI updates

**Zero OpenAPI changes.** 01c ships an internal listener + classifier + gate — no HTTP endpoints. Do NOT touch `paths/feedback.yaml`, `schemas/feedback.yaml`, or the entry `openapi.yaml`. `ClassificationResultDto` is declared but not exposed via any endpoint yet.

## Verbatim shape snippets

### Listener method

```java
@Component
@RequiredArgsConstructor
public class FeedbackClassificationListener {

  private final FeedbackClassifier classifier;
  private final ConfidenceGate confidenceGate;
  private final FeedbackEntryRepository entryRepository;
  private final ClarificationQueryRepository clarificationRepository;
  private final FeedbackRouter router;       // Noop or 01d's real impl
  private final ApplicationEventPublisher eventPublisher;
  private final TransactionTemplate requiresNewTxTemplate;   // injected with PROPAGATION_REQUIRES_NEW
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Async(FeedbackAsyncConfig.CLASSIFICATION_POOL)
  public void onFeedbackSubmitted(FeedbackSubmittedEvent event) {
    classifyEntry(event.feedbackId(), event.userId(), event.traceId());
  }

  void classifyEntry(UUID feedbackId, UUID userId, UUID traceId) {
    // step 1 — mark CLASSIFYING + increment attempts
    FeedbackEntry entry = requiresNewTxTemplate.execute(status -> {
      FeedbackEntry e = entryRepository.findById(feedbackId)
          .orElseThrow(() -> new FeedbackEntryNotFoundException(feedbackId));
      e.setSubmissionStatus(SubmissionStatus.CLASSIFYING);
      e.setClassificationAttempts(e.getClassificationAttempts() + 1);
      return entryRepository.save(e);
    });

    // step 2 — context build (out of tx)
    FeedbackClassificationContext context = buildContext(entry, /* userClarificationText */ Optional.empty(), /* hint */ Optional.empty());

    // step 3 — AI call
    ClassificationResult result;
    try {
      result = classifier.classify(context);
    } catch (AiUnavailableException | AiCircuitOpenException defer) {
      revertToReceived(feedbackId);
      log.info("AI unavailable, reverting to RECEIVED; traceId={}", traceId);
      return;
    } catch (AiResponseInvalidException | AiCallFailedException | AiTokenCapExceededException terminal) {
      markFailed(feedbackId, userId, traceId);
      return;
    }

    // step 4 — gate + persist + hand-off
    ConfidenceGate.GateResult gate = confidenceGate.evaluate(result);
    if (gate.anyBelowThreshold()) {
      queueClarification(entry, gate, traceId);
    } else if (gate.allEmpty()) {
      markRoutedEmpty(entry, traceId);
    } else {
      markClassifiedAndHandOff(entry, gate);
    }
  }
  // helper methods omitted — each is a small REQUIRES_NEW tx + (optionally) an event publish.
}
```

### `ConfidenceGate.evaluate` impl

```java
@Component
public class ConfidenceGate {
  private static final BigDecimal AUTO_THRESHOLD = new BigDecimal("0.800");
  private static final BigDecimal FLAG_THRESHOLD = new BigDecimal("0.500");

  public GateResult evaluate(ClassificationResult result) {
    List<ClassificationOutput> raw = result.classifications();
    if (raw == null || raw.isEmpty()) {
      return new GateResult(List.of(), false, true);
    }
    List<ScoredClassification> scored = raw.stream()
        .map(c -> new ScoredClassification(c, decide(c.confidence())))
        .toList();
    boolean anyBelow = scored.stream().anyMatch(s -> s.decision() == RoutingDecision.CLARIFICATION_QUEUED);
    return new GateResult(scored, anyBelow, false);
  }

  private RoutingDecision decide(BigDecimal confidence) {
    if (confidence.compareTo(AUTO_THRESHOLD) >= 0) return RoutingDecision.AUTO_ROUTED;
    if (confidence.compareTo(FLAG_THRESHOLD) >= 0) return RoutingDecision.ROUTED_WITH_FLAG;
    return RoutingDecision.CLARIFICATION_QUEUED;
  }
}
```

### Clarification-query write helper

```java
private void queueClarification(FeedbackEntry entry, ConfidenceGate.GateResult gate, UUID traceId) {
  requiresNewTxTemplate.executeWithoutResult(status -> {
    List<JsonNode> options = gate.classifications().stream()
        .filter(s -> s.decision() == RoutingDecision.CLARIFICATION_QUEUED)
        .map(s -> {
          ObjectNode opt = objectMapper.createObjectNode();
          opt.put("destination", s.classification().destination().name());
          opt.put("snippet", s.classification().extractedFeedback());
          opt.put("classifierJustification", "confidence " + s.classification().confidence().toPlainString());
          return (JsonNode) opt;
        }).toList();

    ClarificationQuery query = ClarificationQuery.builder()
        .id(UUID.randomUUID())
        .feedbackEntry(entry)
        .classifierOptionsJson(objectMapper.valueToTree(options))
        .questionText("I'm not sure what to do with this. Did you mean...")  // canned per HLD
        .status(ClarificationStatus.PENDING)
        .expiresAt(clock.instant().plus(Duration.ofDays(7)))
        .build();
    clarificationRepository.save(query);

    entry.setSubmissionStatus(SubmissionStatus.CLARIFICATION_PENDING);
    entry.setLastClassifiedAt(clock.instant());
    entryRepository.save(entry);
  });

  // Event publish AFTER COMMIT of the requires-new tx
  eventPublisher.publishEvent(new FeedbackProcessedEvent(
      entry.getId(), entry.getUserId(),
      Set.of(),    // destinationsTouched is empty — nothing routed
      /* partialFailure */ false,
      /* clarificationPending */ true,
      traceId, clock.instant()));
}
```

**`TransactionTemplate` bean** — a NEW configuration:
```java
@Configuration
public class FeedbackTxTemplateConfig {
  @Bean(name = "feedbackRequiresNewTxTemplate")
  TransactionTemplate requiresNewTxTemplate(PlatformTransactionManager tm) {
    TransactionTemplate t = new TransactionTemplate(tm);
    t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return t;
  }
}
```
The listener auto-wires `@Qualifier("feedbackRequiresNewTxTemplate") TransactionTemplate`. **Alternative**: annotate each helper method with `@Transactional(propagation = REQUIRES_NEW)` and break out to a sibling `@Component` service — works, but adds a class. The `TransactionTemplate` approach keeps the helpers inline.

### `Screen.toPromptToken()` mapping helper

```java
public static String toPromptToken(Screen screen) {
  return switch (screen) {
    case RECIPE_DETAIL -> "recipe_page";
    case PLAN_MEAL_DETAIL, PLAN_VIEW -> "plan_view";
    case GROCERY -> "shopping_list";
    case NUTRITION_DASHBOARD -> "nutrition_dashboard";
    case SETTINGS, GENERAL -> "general";
  };
}
```
Lives as a `static` method on `FeedbackClassificationContext` (NOT on `Screen` — keeps the enum prompt-engineering-free).

## Edge-case checklist

- [ ] `FeedbackSubmittedEvent` triggers the async listener on the `feedbackClassificationPool` executor (verified via thread-name assertion or `@SpyBean` on the listener with `await().atMost(...)` for the async completion)
- [ ] `classifyEntry` happy path (3 classifications all ≥0.5, none ≥0.8 are AUTO — mix of AUTO_ROUTED + ROUTED_WITH_FLAG): entry status `CLASSIFYING → CLASSIFIED`; `lastClassifiedAt` set; gate result passed to router; `FeedbackProcessedEvent` NOT published by 01c (01d publishes after routing)
- [ ] `classifyEntry` happy path with all 3 classifications ≥0.8: all decisions `AUTO_ROUTED`; entry → `CLASSIFIED`
- [ ] `classifyEntry` happy path with mixed (one 0.92 → AUTO, one 0.65 → FLAG): both route; entry → `CLASSIFIED`
- [ ] `classifyEntry` with one classification at exactly `0.500` → `ROUTED_WITH_FLAG` (lower boundary inclusive)
- [ ] `classifyEntry` with one classification at exactly `0.800` → `AUTO_ROUTED` (upper boundary inclusive)
- [ ] `classifyEntry` with one classification at `0.499` → `CLARIFICATION_QUEUED`; ENTIRE entry pauses (even if other 2 classifications are 0.95)
- [ ] `classifyEntry` clarification path: entry status → `CLARIFICATION_PENDING`; ONE `ClarificationQuery` row written with N options (where N = count of <0.5 classifications); `expires_at = createdAt + 7 days`; `FeedbackProcessedEvent` published with `clarificationPending = true`, `destinationsTouched = ∅`
- [ ] `classifyEntry` empty-classifications path: entry status → `ROUTED` with empty routes; `lastClassifiedAt` set; `FeedbackProcessedEvent` published with `destinationsTouched = ∅`, `partialFailure = false`, `clarificationPending = false`
- [ ] `classifyEntry` AiUnavailableException: entry status reverted to `RECEIVED`; `classificationAttempts` decremented; NO `FeedbackProcessedEvent` published; INFO log emitted
- [ ] `classifyEntry` AiCircuitOpenException: same defer behaviour as AiUnavailable
- [ ] `classifyEntry` AiResponseInvalidException: entry status → `FAILED`; `lastClassifiedAt` set; degenerate `FeedbackProcessedEvent` published with `partialFailure = true, destinationsTouched = ∅`
- [ ] `classifyEntry` AiCallFailedException: same terminal behaviour as AiResponseInvalid
- [ ] `classifyEntry` AiTokenCapExceededException: same terminal behaviour
- [ ] `classificationAttempts` increments correctly on first attempt (0 → 1)
- [ ] `classificationAttempts` decrements on defer (1 → 0, then sweep retries; ends up at 1 after the eventual successful classification)
- [ ] `FeedbackClassificationTask.getUserPromptRef()` returns `new PromptRef("feedback/classify-feedback", Optional.empty())`
- [ ] `FeedbackClassificationTask.getToolSchema()` returns a `ToolDefinition` named `classify_feedback` with a valid JSON schema (not null, has `type` property)
- [ ] `FeedbackClassificationTask.getResponseType()` returns `ClassificationResult.class`
- [ ] `FeedbackClassificationContext.toRendererMap()` maps `Screen.RECIPE_DETAIL → "recipe_page"`, `PLAN_MEAL_DETAIL → "plan_view"`, `GENERAL → "general"`, etc. (per [prompts/04-feedback-classification.md](../../lld/prompts/04-feedback-classification.md))
- [ ] `FeedbackClassificationContext.toRendererMap()` emits `recent_classifications = []` (TODO comment noted)
- [ ] `ConfidenceGate.evaluate` is a pure function — no I/O; same input twice → same output (idempotent)
- [ ] `ConfidenceGate.evaluate` on empty `ClassificationResult` → `GateResult(classifications = [], anyBelowThreshold = false, allEmpty = true)`
- [ ] `NoopFeedbackRouter` wires when no other `FeedbackRouter` bean is on the classpath; logs WARN; entry stays at `CLASSIFIED` with `routes = []`
- [ ] A test-scoped `@TestConfiguration` providing a fake `FeedbackRouter` bean overrides the Noop via `@ConditionalOnMissingBean`
- [ ] Listener method NOT annotated `@Transactional` (gotcha — would force `REQUIRES_NEW` else context-load fails); helper methods open `REQUIRES_NEW` txs via `TransactionTemplate`
- [ ] `FeedbackClassificationListener` runs on the `feedbackClassificationPool` executor (verified via `Thread.currentThread().getName().startsWith("feedback-classify-")`)
- [ ] `ClassificationResult` JSR-303 validation: confidence > 1 in mock → AI module's semantic re-prompt (verified at the AI module's seam, not directly in feedback)
- [ ] TestAiService (from ai.md §Test Plan) registers a canned `ClassificationResult` keyed on `TaskType.FEEDBACK_CLASSIFICATION` for the integration test
- [ ] Unit test on `FeedbackClassifier`: mocks `AiService.execute`; happy path returns parsed result; `AiUnavailableException` propagates
- [ ] No N+1 — `classifyEntry` performs at most 1 SELECT + 2 UPDATEs (+ 1 INSERT on clarification path); verified via Hibernate stats
- [ ] Async race-test pattern (from [decision 0011 §async-listener test races](../../../ai-workflow/decisions/0011-wave2-round8-pgvector-stale-state-and-async-race.md)): tests asserting post-state after submit use `Awaitility.await().atMost(5, SECONDS).untilAsserted(...)`; do NOT block-poll on `Thread.sleep`

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/feedback/api/dto/ClassificationResult.java
NEW   src/main/java/com/example/mealprep/feedback/api/dto/ClassificationOutput.java
NEW   src/main/java/com/example/mealprep/feedback/api/dto/ClassificationResultDto.java                 (read-side; not exposed yet)

NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/FeedbackClassificationTask.java
NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/FeedbackClassificationContext.java
NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/FeedbackClassifier.java
NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/ToolDefinitions.java
NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/ConfidenceGate.java         (record GateResult + ScoredClassification nested)
NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/FeedbackRouter.java         (internal SPI interface)
NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/FeedbackClassificationListener.java

NEW   src/main/java/com/example/mealprep/feedback/config/NoopFeedbackRouterConfiguration.java         (SPI-with-Noop pattern)
NEW   src/main/java/com/example/mealprep/feedback/config/FeedbackTxTemplateConfig.java                 (REQUIRES_NEW TransactionTemplate)

MOD   src/main/java/com/example/mealprep/feedback/api/dto/Screen.java                                  (optional — only if Screen.toPromptToken() lives on the enum; preferred location is on FeedbackClassificationContext, see §7)

NEW   src/test/java/com/example/mealprep/feedback/FeedbackClassifierTest.java
NEW   src/test/java/com/example/mealprep/feedback/ConfidenceGateTest.java                              (boundary tests at 0.0, 0.499, 0.500, 0.799, 0.800, 1.000; empty input; mixed input)
NEW   src/test/java/com/example/mealprep/feedback/FeedbackClassificationListenerTest.java              (mocks classifier + gate + router + repos; the 6 outcome paths above)
NEW   src/test/java/com/example/mealprep/feedback/FeedbackClassificationContextTest.java               (Screen → prompt-token mapping; recent_classifications = [])
NEW   src/test/java/com/example/mealprep/feedback/FeedbackClassificationFlowIT.java                    (TestAiService canned response keyed on FEEDBACK_CLASSIFICATION; full async flow; CLASSIFYING → CLARIFICATION_PENDING + ClarificationQuery row written; CLASSIFYING → FAILED on bad result; CLASSIFYING → ROUTED on empty result)
```

Count: ~15 new files. Estimated agent runtime 50-60 min — the listener method is the densest piece.

**Files this ticket does NOT modify**:
- `FeedbackServiceImpl` from 01b — classification is a separate listener, not part of the service impl
- 01a's entities or migrations
- 01b's controllers, request DTOs, OpenAPI files (no endpoints change)
- `config/GlobalExceptionHandler.java`, `archunit/ModuleBoundaryTest.java`
- AI module files — UNLESS `TaskType.FEEDBACK_CLASSIFICATION` is missing. If so, append one line to `ai/spi/TaskType.java` (single-line additive change, document in report)

## Dependencies

- **Hard dependency**: `feedback-01a` (merged) — entities, migrations, repositories, enums, `ClarificationQuery`, `ClarificationStatus`, `Destination`, `RoutingDecision`, `SubmissionStatus`.
- **Hard dependency**: `feedback-01b` (merged) — `FeedbackSubmittedEvent`, `FeedbackAsyncConfig.CLASSIFICATION_POOL`, `FeedbackProcessedEvent` (record only; published in 01c), `FeedbackServiceImpl`, `UiContextDto`, `Screen`.
- **Hard dependency**: `ai-01a` (or whatever ticket id ships the AI module) — `AiService.execute(AiTask<T>)`, `AiTask<T>` SPI, `TaskType` enum (must include `FEEDBACK_CLASSIFICATION`), `PromptRef`, `ToolDefinition`, `AiUnavailableException`, `AiCircuitOpenException`, `AiResponseInvalidException`, `AiCallFailedException`, `AiTokenCapExceededException`, `TestAiService`. Per [LLD line 562](../../lld/feedback.md), this is locked.
- **Soft dependency**: `prompt-pilot-validation` — the `feedback/classify-feedback` prompt body landing at `src/main/resources/prompts/feedback/classify-feedback.txt` per [`lld/prompts/04-feedback-classification.md`](../../lld/prompts/04-feedback-classification.md). **01c ships without the file** (tests use TestAiService which short-circuits the template load); production deploy requires it.
- **No hard dependency on feedback-01d** — the SPI-with-Noop pattern lets 01c ship before 01d. The router stays Noop until 01d merges.
- **No hard dependency on feedback-01e** — 01c writes `ClarificationQuery` rows; 01e adds the answer endpoint. The two ship independently.
- **Sibling tickets running in parallel** (Wave 3 round 2 of feedback): `feedback-01d` (router). Touches the same listener handoff point — `FeedbackRouter` SPI — but via the Noop the integration is safe.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + ArchUnit)
- [ ] All edge-case items above ticked
- [ ] `FeedbackClassificationListener` listener method is **not** `@Transactional` (round-7 lesson); body uses `TransactionTemplate(REQUIRES_NEW)` for each short tx
- [ ] `NoopFeedbackRouter` wires correctly when no real `FeedbackRouter` bean exists; uses the `@Configuration + @Bean + @ConditionalOnMissingBean` recipe (NOT `@Component @ConditionalOnMissingBean`)
- [ ] Bean method name in `NoopFeedbackRouterConfiguration` differs from the enclosing class name (round-5 lesson)
- [ ] Async race-pattern: ITs use Awaitility, not Thread.sleep, for async assertions
- [ ] `ClassificationResult.classifications` validation: `@Size(min=0, max=4)` admits empty list
- [ ] Per-test `@SpyBean FeedbackClassificationListener` overrides the production listener so tests don't actually fire `AiService` (or test uses `TestAiService` which short-circuits the call)
- [ ] No regression on existing tests

## What's NOT in scope

- Real `FeedbackRouter` impl + four `DestinationDispatcher`s + Flow 3 multi-destination routing → **feedback-01d**
- Routing log row INSERTs (PENDING → APPLIED / FAILED) → **feedback-01d**
- `FeedbackProcessedEvent` publication on the post-routing path (Flow 3 step 8) → **feedback-01d**
- Clarification answer endpoint + `answerClarificationQuery` impl + 7-day TTL sweep → **feedback-01e**
- Misclassification correction + replay + `CorrectionReplayer` → **feedback-01f**
- `@Scheduled` `retryStuckClassifications` sweep + transient retry → **feedback-01g**
- `@TransactionalEventListener` on `AiCallSucceededEvent` cross-stamping `lastClassifiedAt` → **feedback-01g**
- The classification prompt body file (`classify-feedback.txt`) → **prompt-pilot-validation** workstream
- `recent_classifications` actually populated in `toRendererMap()` → **future enhancement** (TODO comment); 01c uses empty list
- AI cost-cap default for `FEEDBACK_CLASSIFICATION` task type → **AI module configuration** ([ai.md §Configuration](../../lld/ai.md))

Squash-merge with: `feat(feedback): 01c — FeedbackClassificationTask + FeedbackClassifier + ConfidenceGate + async listener + graceful degrade + clarification-queue write path`
