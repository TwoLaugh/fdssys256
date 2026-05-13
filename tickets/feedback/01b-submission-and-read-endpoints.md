# Ticket: feedback — 01b Submission Flow + Service Interfaces + Read Endpoints + Events + Async Config

## Summary

Layer the **submission front door** on top of feedback-01a's schema: the `FeedbackQueryService` + `FeedbackUpdateService` public interfaces, single `FeedbackServiceImpl` (partial — only the submit + read methods), the **`FeedbackController`** for `POST /api/v1/feedback` (returns 202 receipt; classification runs async after commit), `GET /api/v1/feedback?page=&size=`, `GET /api/v1/feedback/{feedbackId}`, the `SubmitFeedbackRequest` / `SubmitFeedbackResponse` request DTOs, the `@ValidUiContext` custom validator, the **`FeedbackSubmittedEvent`** (after-commit trigger for the async classification listener that 01c adds) and **`FeedbackProcessedEvent`** (catalogued event consumed by Notification module — declared here, published in 01c/01d once routing completes), the **`FeedbackAsyncConfig`** thread-pool bean for the classification executor, the **`FeedbackExceptionHandler`** `@RestControllerAdvice` with `@Order(Ordered.HIGHEST_PRECEDENCE)`. Per [`lld/feedback.md §Flow 1 (Submission) lines 712-722`](../../lld/feedback.md), [`lld/feedback.md §Service Interfaces lines 462-521`](../../lld/feedback.md), [`lld/feedback.md §REST Controllers — FeedbackController lines 591-604`](../../lld/feedback.md), [`lld/feedback.md §Events lines 651-705`](../../lld/feedback.md), [`lld/feedback.md §Validation lines 638-647`](../../lld/feedback.md).

**LLD divergence note** — **`FeedbackProcessedEvent` declared but not published in 01b**: the event record ships here so 01c/01d can publish it without an event-record dependency. **No publication in 01b** — submission alone doesn't process. The empty-route path (`classifier returns zero classifications`) WOULD publish in 01c per LLD line 740, but 01b's submission stops at `RECEIVED`. Document this clearly.

**LLD divergence note** — **`FeedbackAsyncConfig` thread pool location**: the LLD §Package Layout line 47 lists `FeedbackAsyncConfig` under `config/`. The bean is declared as `@Bean ThreadPoolTaskExecutor feedbackClassificationPool()` with the four-thread / queue-100 / `CallerRunsPolicy` config from [LLD §Flow 2 line 726](../../lld/feedback.md). **In 01b the bean ships configured but UNUSED** — no `@Async` annotation references it yet. 01c wires the classification listener with `@Async("feedbackClassificationPool")`.

**LLD divergence note** — **batch query sibling**: per [style-guide.md §Service Interfaces line 204](../../lld/style-guide.md), every single-id query has a batch sibling. The LLD only lists `getByIds(userId, feedbackIds)` (LLD line 470). Ship it.

**Defers** (still out of scope after 01b):

- Async classification (Flow 2) — `FeedbackClassifier`, `FeedbackClassificationTask`, `ConfidenceGate`, graceful degrade → **feedback-01c**
- Multi-destination routing (Flow 3) → **feedback-01d**
- `ClarificationQueryController` + answer flow + 7-day TTL → **feedback-01e**
- Misclassification correction endpoint + `correctMisclassification` service method → **feedback-01f**
- `@Scheduled` sweeps + cross-module ITs + quality-monitoring rollup → **feedback-01g**
- `correctMisclassification`, `answerClarificationQuery`, `retryStuckClassifications`, `expireOldClarificationQueries` on `FeedbackUpdateService` — interface DECLARATIONS land in 01b (so 01c-01g implement against them without breaking the interface mid-wave); **impl bodies throw `UnsupportedOperationException("feedback-01<x> impl pending")`**. 01b's controller doesn't expose them.

01b unblocks the rest of the module. Without it, 01c can't wire its `@TransactionalEventListener` (the `FeedbackSubmittedEvent` doesn't exist); 01d has no router insertion point; 01e/01f have no service interface to add to.

## Behavioural spec

### Service interfaces

1. **`FeedbackQueryService`** (PUBLIC interface) per [LLD lines 467-485](../../lld/feedback.md). 01b ships **all** the methods in the interface declaration — implementations vary:

   ```java
   public interface FeedbackQueryService {
     Optional<FeedbackEntryDto> getById(UUID userId, UUID feedbackId);                       // 01b impl
     List<FeedbackEntryDto> getByIds(UUID userId, List<UUID> feedbackIds);                   // 01b impl
     Page<FeedbackEntryDto> listByUser(UUID userId, Pageable pageable);                      // 01b impl
     Optional<RoutingDecisionDto> getRoutingDecision(UUID userId, UUID routingId);           // 01b impl

     Page<ClarificationQueryDto> listClarificationQueries(
         UUID userId, ClarificationStatus status, Pageable pageable);                        // 01e impl
     Optional<ClarificationQueryDto> getClarificationQuery(UUID userId, UUID queryId);       // 01e impl

     Page<MisclassificationCorrectionDto> listCorrections(UUID userId, Pageable pageable);   // 01f impl
   }
   ```

2. **`FeedbackUpdateService`** (PUBLIC interface) per [LLD lines 490-516](../../lld/feedback.md):

   ```java
   public interface FeedbackUpdateService {
     SubmitFeedbackResponse submitFeedback(UUID userId, SubmitFeedbackRequest request);     // 01b impl

     SubmitFeedbackResponse correctMisclassification(
         UUID userId, UUID feedbackId, UUID routingId, CorrectionRequest request);          // 01f impl
     SubmitFeedbackResponse answerClarificationQuery(
         UUID userId, UUID queryId, AnswerClarificationRequest request);                    // 01e impl

     void retryStuckClassifications();                                                       // 01g impl
     void expireOldClarificationQueries();                                                   // 01g impl
   }
   ```

3. **`FeedbackServiceImpl`** (NEW; package-private; in `domain/service/`) implements **both** interfaces (style guide pattern). 01b implements only the four `FeedbackQueryService` methods listed and `submitFeedback`. The four other methods throw `UnsupportedOperationException("feedback-01<x> impl pending — see ticket")` so subsequent tickets can drop in their impl without modifying the interface.
4. **Cross-module impl note**: `FeedbackQueryService.getRoutingDecision` is reached via `RoutingLogRepository.findByIdAndFeedbackEntryUserId(routingId, userId)`. The query already 404-safes other-user routing rows (returns Optional empty for wrong user).
5. **`CorrectionRequest`, `AnswerClarificationRequest`** request records — declared in 01b (so the interface compiles) but **the `@ValidDestination` validator referenced from `CorrectionRequest.newDestination`** is a **placeholder annotation in 01b**: a `@Valid @NotNull` only (no class-level validator). The full `@ValidDestination` lands in feedback-01f alongside the correction flow.
6. **`SubmitFeedbackRequest`** per [LLD lines 236-239](../../lld/feedback.md). Carries `text` (`@NotBlank @Size(max=4000)`) and `context` (`@NotNull @Valid @ValidUiContext UiContextDto`).
7. **`SubmitFeedbackResponse`** per [LLD lines 307-313](../../lld/feedback.md).

### Submission flow (Flow 1)

8. `POST /api/v1/feedback` → `FeedbackController.submitFeedback`. Authenticated. Server resolves `userId` via `CurrentUserResolver`. Body: `SubmitFeedbackRequest`.
9. **Single `@Transactional` write** in `FeedbackServiceImpl.submitFeedback`:
   1. Allocate `feedbackId = UUID.randomUUID()` and `traceId = UUID.randomUUID()` (style guide: application-side IDs).
   2. Map `request.context()` (`UiContextDto`) to `UiContextDocument` (storage shape) via a small in-service helper (NOT a MapStruct mapper — record-to-record copies are trivial). Reason: the `UiContextDocument` lives in `domain/document/` not `api/dto/`; keeping the conversion in the service avoids polluting the mapper layer.
   3. Construct `FeedbackEntry` with `submissionStatus = RECEIVED`, `text = request.text()`, `uiContext = doc`, `traceId = traceId`, `classificationAttempts = 0`.
   4. `feedbackEntryRepository.save(entry)`.
   5. **Publish `FeedbackSubmittedEvent` AFTER COMMIT** via `applicationEventPublisher.publishEvent(...)`. Spring's `@TransactionalEventListener` consumers (01c) see it only after the row is durable.
10. **Build `SubmitFeedbackResponse`**: `feedbackId`, `traceId`, `submissionStatus = RECEIVED`, `routes = []`, `pendingClarificationQueryId = null`.
11. Return **HTTP 202 Accepted** per [LLD line 601](../../lld/feedback.md). 202 (not 201) because the resource is created BUT processing is incomplete — the routes will populate asynchronously.
12. **`Location: /api/v1/feedback/{feedbackId}`** header on the 202.

### Read flow

13. `GET /api/v1/feedback?page=&size=` → `FeedbackController.list`. Authenticated. Uses `FeedbackEntryRepository.findByUserIdOrderByCreatedAtDesc`. Returns `Page<FeedbackEntryDto>`. Defaults: page=0, size=20, max=100.
14. `GET /api/v1/feedback/{feedbackId}` → `FeedbackController.getById`. Authenticated. Uses `FeedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId)` — the **`@EntityGraph`-eager** query from 01a. 404 if missing or belongs to another user (don't leak — return 404 not 403). The DTO's `pendingClarificationQueryId` field is populated by service code: a `ClarificationQueryRepository.findFirstByFeedbackEntryIdAndStatus(feedbackId, PENDING)` lookup (NEW repo method — see §22).
15. **DTO progressive enrichment**: in 01b, `routes = []` and `pendingClarificationQueryId = null` (always — classification hasn't run). The same endpoint reflects 01c/01d/01e progress as those tickets land — no controller change needed.

### Events

16. **`FeedbackSubmittedEvent`** per [LLD lines 658-662](../../lld/feedback.md). Record:
    ```java
    public record FeedbackSubmittedEvent(
        UUID feedbackId, UUID userId, Screen screen,
        UUID traceId, Instant occurredAt) {}
    ```
    Published `AFTER_COMMIT` from `submitFeedback`. The `Screen` field is for Notification-module telemetry (LLD line 661). 01b publishes; 01c listens.
17. **`FeedbackProcessedEvent`** record DECLARED per [LLD lines 664-670](../../lld/feedback.md) — but **NOT published from 01b**:
    ```java
    public record FeedbackProcessedEvent(
        UUID feedbackId, UUID userId,
        Set<Destination> destinationsTouched,
        boolean partialFailure, boolean clarificationPending,
        UUID traceId, Instant occurredAt) {}
    ```
    Reason: 01b's submission stops at `RECEIVED`; routing/clarification belongs to 01c/01d. Declaring the record in 01b avoids subsequent tickets adding a new event record (clean dependency edge).
18. **`FeedbackMisclassificationCorrectedEvent`** — DEFERRED to feedback-01f (lands alongside correction publication site).
19. **No event listener in 01b.** The cross-module `@TransactionalEventListener` on `AiCallSucceededEvent` for `lastClassifiedAt` telemetry stamping (LLD lines 699-704) lands in **01g** — it requires the AI module to be a wave-2 hard dependency, which 01b doesn't take on.

### Async config

20. **`FeedbackAsyncConfig`** at `feedback/config/FeedbackAsyncConfig.java`. Single `@Bean` method:
    ```java
    @Configuration
    public class FeedbackAsyncConfig {
      public static final String CLASSIFICATION_POOL = "feedbackClassificationPool";

      @Bean(CLASSIFICATION_POOL)
      public ThreadPoolTaskExecutor feedbackClassificationPool() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(100);
        exec.setThreadNamePrefix("feedback-classify-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
      }
    }
    ```
    The constant `CLASSIFICATION_POOL` is exposed so 01c's listener can reference it via `@Async(FeedbackAsyncConfig.CLASSIFICATION_POOL)`. **Verify**: project-wide `@EnableAsync` is configured (auth-01a or project-setup ships it). If NOT, append `@EnableAsync` here as a class-level annotation on `FeedbackAsyncConfig` — but **prefer keeping it at the application class** (verify in the existing `MealPrepApplication` class). Document in the agent's report which configuration option was chosen.

### Validation

21. **`@ValidUiContext`** custom class-level annotation on `UiContextDto` per [LLD line 642](../../lld/feedback.md). Class-level `ConstraintValidator<ValidUiContext, UiContextDto>`:
    - `screen == RECIPE_DETAIL` → `recipeId` non-null
    - `screen == PLAN_MEAL_DETAIL` → `planId` non-null AND `mealSlotId` non-null
    - `screen == GENERAL || SETTINGS` → no field requirements (all may be null)
    - `recipeVersion != null` implies `recipeId != null` (LLD line 642 — "asserts `recipeVersion` is `null` when `recipeId` is `null`")
    Lives in `feedback/validation/`. Standard `@Constraint(validatedBy = UiContextValidator.class)` + `@Target(TYPE)` + `@Retention(RUNTIME)`.

### NEW repo method

22. **`ClarificationQueryRepository.findFirstByFeedbackEntryIdAndStatus`** — append to the repo from 01a. Used by `getById` (§14) to populate `pendingClarificationQueryId` on the DTO. **Method signature**:
    ```java
    Optional<ClarificationQuery> findFirstByFeedbackEntryIdAndStatus(
        UUID feedbackEntryId, ClarificationStatus status);
    ```
    Naming follows Spring Data convention. **Note**: 01a's `ClarificationQuery` has `@OneToOne` to `FeedbackEntry` — by convention only one PENDING query exists per entry, but `@OneToOne` doesn't enforce uniqueness on PENDING-only; the partial unique index would (deferred to 01e if needed).

### REST controller

23. **`FeedbackController`** at `feedback/api/controller/FeedbackController.java`. `@RestController`, `@RequestMapping("/api/v1/feedback")`, `@Tag(name = "Feedback")`. Endpoints — see OpenAPI section below. Constructor-inject the two service interfaces + `CurrentUserResolver`. **No `@Transactional` on the controller** — service methods handle their own.
24. Endpoint methods carry `@Operation(summary = "...", description = "...")` per springdoc convention; OpenAPI YAML is the source of truth (LLD line 605 doesn't have a Springdoc-vs-handwritten preference, but the wave-2 modules write OpenAPI by hand — follow suit).
25. **Missing-feedback path resolution**: when `findWithRoutingByIdAndUserId` returns Optional.empty, throw `FeedbackEntryNotFoundException` (404). The `@RestControllerAdvice` maps it via ProblemDetail.

### Exception handling

26. **`FeedbackExceptionHandler`** at `feedback/api/FeedbackExceptionHandler.java`. `@RestControllerAdvice`, **`@Order(Ordered.HIGHEST_PRECEDENCE)`** (gotcha from [agent-prompt-template.md §Gotchas line 129](../../../ai-workflow/templates/agent-prompt-template.md)). Methods for:
    - `FeedbackEntryNotFoundException` → 404 with `type = .../feedback-entry-not-found`
    - `RoutingDecisionNotFoundException` → 404 with `type = .../routing-decision-not-found`
    The other five module exceptions land in their respective tickets (01c/01e/01f) by appending `@ExceptionHandler` methods to THIS handler. **Do NOT modify `config/GlobalExceptionHandler.java`**.

### Module facade

27. Replace 01a's stub `FeedbackModule.java` with the real facade re-exporting the two service interfaces:
    ```java
    public final class FeedbackModule {
      private FeedbackModule() {}
      public interface QueryService extends FeedbackQueryService {}
      public interface UpdateService extends FeedbackUpdateService {}
    }
    ```
    **Optional alternative** (verify against existing modules' convention) — `FeedbackModule` simply has Javadoc pointing at the two interfaces without nesting. The wave-2 recipe module nests; copy that pattern. **Document the choice in the agent's report.**

## OpenAPI updates

### NEW `src/main/resources/openapi/paths/feedback.yaml`

```yaml
feedback:
  post:
    tags: [Feedback]
    operationId: submitFeedback
    summary: 'Submit a free-text feedback entry. Classification + routing run asynchronously after commit.'
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/feedback.yaml#/SubmitFeedbackRequest' }
    responses:
      '202':
        description: 'Feedback received; classification + routing pending.'
        headers:
          Location:
            schema: { type: string, format: uri }
        content:
          application/json:
            schema: { $ref: '../schemas/feedback.yaml#/SubmitFeedbackResponse' }
      '400': { description: 'Validation error', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
  get:
    tags: [Feedback]
    operationId: listFeedback
    summary: 'Paginated list of the caller''s feedback entries, newest first.'
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
        description: 'Page of feedback entries.'
        content:
          application/json:
            schema: { $ref: '../schemas/feedback.yaml#/FeedbackEntryDtoPage' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

feedbackById:
  get:
    tags: [Feedback]
    operationId: getFeedback
    summary: 'Fetch a single feedback entry with its routing log.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: feedbackId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200':
        description: 'Feedback entry.'
        content:
          application/json:
            schema: { $ref: '../schemas/feedback.yaml#/FeedbackEntryDto' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Not found / belongs to another user', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### NEW `src/main/resources/openapi/schemas/feedback.yaml`

Schemas required for 01b: `Screen`, `SubmissionStatus`, `Destination`, `RoutingDecision`, `RoutingStatus`, `RoutingFailureKind`, `UiContextDto`, `RoutingDecisionDto`, `FeedbackEntryDto`, `FeedbackEntryDtoPage`, `SubmitFeedbackRequest`, `SubmitFeedbackResponse`. **Drop**: `ClarificationQueryDto`, `MisclassificationCorrectionDto`, `CorrectionRequest`, `AnswerClarificationRequest`, `ClarificationOptionDto` — those ship in 01e/01f. **Drop also**: `ClassificationResultDto` — that's a 01c concern.

```yaml
Screen:
  type: string
  enum: [RECIPE_DETAIL, PLAN_MEAL_DETAIL, PLAN_VIEW, GROCERY, NUTRITION_DASHBOARD, SETTINGS, GENERAL]
SubmissionStatus:
  type: string
  enum: [RECEIVED, CLASSIFYING, CLASSIFIED, CLARIFICATION_PENDING, ROUTED, PARTIALLY_FAILED, FAILED, CORRECTED]
Destination:
  type: string
  enum: [RECIPE, PREFERENCE, NUTRITION, PROVISIONS]
RoutingDecision:
  type: string
  enum: [AUTO_ROUTED, ROUTED_WITH_FLAG, CLARIFICATION_QUEUED]
RoutingStatus:
  type: string
  enum: [PENDING, APPLIED, FAILED, CORRECTED_AWAY, REPLAYED, AWAITING_USER_APPROVAL]
RoutingFailureKind:
  type: string
  enum: [TRANSIENT, DESTINATION_VALIDATION, DESTINATION_BUSINESS, AI_UNAVAILABLE, UNKNOWN]
UiContextDto:
  type: object
  required: [screen]
  properties:
    screen: { $ref: '#/Screen' }
    recipeId:
      type: string
      format: uuid
      nullable: true
    recipeVersion:
      type: integer
      nullable: true
    mealSlotId:
      type: string
      format: uuid
      nullable: true
    planId:
      type: string
      format: uuid
      nullable: true
    referenceDate:
      type: string
      format: date
      nullable: true
RoutingDecisionDto:
  type: object
  required: [id, destination, confidence, decision, status, extractedFeedback]
  properties:
    id: { type: string, format: uuid }
    destination: { $ref: '#/Destination' }
    confidence: { type: number, format: float, minimum: 0, maximum: 1 }
    decision: { $ref: '#/RoutingDecision' }
    status: { $ref: '#/RoutingStatus' }
    extractedFeedback: { type: string }
    actionTaken: { type: string, maxLength: 512, nullable: true }
    destinationResult:
      type: object
      nullable: true
      additionalProperties: true
      description: 'Destination-typed result shell; shape depends on destination.'
    failureMessage: { type: string, maxLength: 512, nullable: true }
FeedbackEntryDto:
  type: object
  required: [id, userId, text, context, submissionStatus, classificationAttempts, traceId, routes, createdAt, updatedAt]
  properties:
    id: { type: string, format: uuid }
    userId: { type: string, format: uuid }
    text: { type: string }
    context: { $ref: '#/UiContextDto' }
    submissionStatus: { $ref: '#/SubmissionStatus' }
    classificationAttempts: { type: integer }
    lastClassifiedAt:
      type: string
      format: date-time
      nullable: true
    traceId: { type: string, format: uuid }
    routes:
      type: array
      items: { $ref: '#/RoutingDecisionDto' }
    pendingClarificationQueryId:
      type: string
      format: uuid
      nullable: true
    createdAt: { type: string, format: date-time }
    updatedAt: { type: string, format: date-time }
SubmitFeedbackRequest:
  type: object
  required: [text, context]
  properties:
    text: { type: string, minLength: 1, maxLength: 4000 }
    context: { $ref: '#/UiContextDto' }
SubmitFeedbackResponse:
  type: object
  required: [feedbackId, traceId, submissionStatus, routes]
  properties:
    feedbackId: { type: string, format: uuid }
    traceId: { type: string, format: uuid }
    submissionStatus: { $ref: '#/SubmissionStatus' }
    routes:
      type: array
      items: { $ref: '#/RoutingDecisionDto' }
    pendingClarificationQueryId:
      type: string
      format: uuid
      nullable: true
FeedbackEntryDtoPage:
  type: object
  additionalProperties: true
  required: [content, totalElements, totalPages, number, size]
  properties:
    content:
      type: array
      items: { $ref: '#/FeedbackEntryDto' }
    totalElements: { type: integer, format: int64 }
    totalPages: { type: integer }
    number: { type: integer }
    size: { type: integer }
    first: { type: boolean }
    last: { type: boolean }
    empty: { type: boolean }
    numberOfElements: { type: integer }
```

**Gotcha applied**: every nullable scalar / object uses **inline** `nullable: true`. `Screen` is used via `$ref` because the field is required (non-nullable); for nullable enum fields elsewhere we'd inline. The `destinationResult` is an inlined `additionalProperties: true` object — keeps Jackson's polymorphic write flexible.

**Gotcha applied**: `FeedbackEntryDtoPage` uses the **flat** Page<T> shape with `additionalProperties: true`.

**Gotcha applied**: every YAML description with `,` `:` `'` is single-quoted.

### Append to entry `src/main/resources/openapi/openapi.yaml`

**Location**: under `paths:`. Add:
```yaml
  /api/v1/feedback:
    $ref: 'paths/feedback.yaml#/feedback'
  /api/v1/feedback/{feedbackId}:
    $ref: 'paths/feedback.yaml#/feedbackById'
```

**Location**: under `components.schemas:`. Add 12 lines (alphabetical):
```yaml
    Destination: { $ref: 'schemas/feedback.yaml#/Destination' }
    FeedbackEntryDto: { $ref: 'schemas/feedback.yaml#/FeedbackEntryDto' }
    FeedbackEntryDtoPage: { $ref: 'schemas/feedback.yaml#/FeedbackEntryDtoPage' }
    RoutingDecision: { $ref: 'schemas/feedback.yaml#/RoutingDecision' }
    RoutingDecisionDto: { $ref: 'schemas/feedback.yaml#/RoutingDecisionDto' }
    RoutingFailureKind: { $ref: 'schemas/feedback.yaml#/RoutingFailureKind' }
    RoutingStatus: { $ref: 'schemas/feedback.yaml#/RoutingStatus' }
    Screen: { $ref: 'schemas/feedback.yaml#/Screen' }
    SubmissionStatus: { $ref: 'schemas/feedback.yaml#/SubmissionStatus' }
    SubmitFeedbackRequest: { $ref: 'schemas/feedback.yaml#/SubmitFeedbackRequest' }
    SubmitFeedbackResponse: { $ref: 'schemas/feedback.yaml#/SubmitFeedbackResponse' }
    UiContextDto: { $ref: 'schemas/feedback.yaml#/UiContextDto' }
```

## Verbatim shape snippets

### `FeedbackController` skeleton

```java
@RestController
@RequestMapping("/api/v1/feedback")
@Tag(name = "Feedback")
@RequiredArgsConstructor
public class FeedbackController {

  private final FeedbackUpdateService updateService;
  private final FeedbackQueryService queryService;
  private final CurrentUserResolver currentUserResolver;

  @PostMapping
  public ResponseEntity<SubmitFeedbackResponse> submitFeedback(
      @Valid @RequestBody SubmitFeedbackRequest request) {
    UUID userId = currentUserResolver.requireCurrentUserId();
    SubmitFeedbackResponse response = updateService.submitFeedback(userId, request);
    URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
        .path("/{id}").buildAndExpand(response.feedbackId()).toUri();
    return ResponseEntity.accepted().location(location).body(response);
  }

  @GetMapping
  public Page<FeedbackEntryDto> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = currentUserResolver.requireCurrentUserId();
    return queryService.listByUser(userId, PageRequest.of(page, size));
  }

  @GetMapping("/{feedbackId}")
  public FeedbackEntryDto getById(@PathVariable UUID feedbackId) {
    UUID userId = currentUserResolver.requireCurrentUserId();
    return queryService.getById(userId, feedbackId)
        .orElseThrow(() -> new FeedbackEntryNotFoundException(feedbackId));
  }
}
```

### `submitFeedback` impl

```java
@Override
@Transactional
public SubmitFeedbackResponse submitFeedback(UUID userId, SubmitFeedbackRequest request) {
  UUID feedbackId = UUID.randomUUID();
  UUID traceId = UUID.randomUUID();
  UiContextDocument doc = toDocument(request.context());      // tiny private helper
  FeedbackEntry entry = FeedbackEntry.builder()
      .id(feedbackId).userId(userId)
      .text(request.text()).uiContext(doc)
      .submissionStatus(SubmissionStatus.RECEIVED)
      .classificationAttempts(0)
      .traceId(traceId)
      .build();
  feedbackEntryRepository.save(entry);
  eventPublisher.publishEvent(new FeedbackSubmittedEvent(
      feedbackId, userId, doc.screen(), traceId, Instant.now()));
  return new SubmitFeedbackResponse(
      feedbackId, traceId, SubmissionStatus.RECEIVED,
      List.of(), /* pendingClarificationQueryId */ null);
}
```

### `getById` impl with pending-clarification lookup

```java
@Override
@Transactional(readOnly = true)
public Optional<FeedbackEntryDto> getById(UUID userId, UUID feedbackId) {
  return feedbackEntryRepository.findWithRoutingByIdAndUserId(feedbackId, userId)
      .map(entry -> {
        FeedbackEntryDto dto = entryMapper.toDto(entry);
        UUID pendingId = clarificationQueryRepository
            .findFirstByFeedbackEntryIdAndStatus(feedbackId, ClarificationStatus.PENDING)
            .map(ClarificationQuery::getId).orElse(null);
        return dto.withPendingClarificationQueryId(pendingId);   // record copy-with helper
      });
}
```

**Note on `withPendingClarificationQueryId`**: records don't have setters. Either (a) add a small `withX` static helper on the DTO record returning a copy, or (b) populate `pendingClarificationQueryId` via MapStruct's `@AfterMapping` callback that injects the repository — but `@AfterMapping` with injected beans is fiddly. **Prefer (a)**: a tiny static factory method on the record:
```java
public FeedbackEntryDto withPendingClarificationQueryId(UUID id) {
  return new FeedbackEntryDto(this.id, this.userId, this.text, this.context,
      this.submissionStatus, this.classificationAttempts, this.lastClassifiedAt,
      this.traceId, this.routes, id, this.createdAt, this.updatedAt);
}
```

### `@ValidUiContext` validator

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = UiContextValidator.class)
public @interface ValidUiContext {
  String message() default "ui context inconsistent with screen";
  Class<?>[] groups() default {};
  Class<? extends Payload>[] payload() default {};
}

public class UiContextValidator implements ConstraintValidator<ValidUiContext, UiContextDto> {
  @Override public boolean isValid(UiContextDto v, ConstraintValidatorContext ctx) {
    if (v == null || v.screen() == null) return true;     // @NotNull handles null elsewhere
    if (v.recipeVersion() != null && v.recipeId() == null) return false;
    return switch (v.screen()) {
      case RECIPE_DETAIL    -> v.recipeId() != null;
      case PLAN_MEAL_DETAIL -> v.planId() != null && v.mealSlotId() != null;
      default               -> true;
    };
  }
}
```

## Edge-case checklist

- [ ] `POST /feedback` happy path → 202 Accepted, `Location` header set, body contains `submissionStatus = RECEIVED`, `routes = []`, `pendingClarificationQueryId = null`
- [ ] `POST /feedback` persists exactly one row in `feedback_entries`, no rows in `feedback_routing_log` / `feedback_clarification_queries` / `feedback_misclassification_corrections`
- [ ] `POST /feedback` publishes exactly one `FeedbackSubmittedEvent` AFTER COMMIT (verified via test event listener)
- [ ] `POST /feedback` validation: blank `text` → 400; `text > 4000 chars` → 400; missing `context` → 400
- [ ] `POST /feedback` with `screen = RECIPE_DETAIL` and null `recipeId` → 400 via `@ValidUiContext`
- [ ] `POST /feedback` with `screen = PLAN_MEAL_DETAIL` and null `planId` → 400 via `@ValidUiContext`
- [ ] `POST /feedback` with `recipeVersion = 3` and null `recipeId` → 400 via `@ValidUiContext`
- [ ] `POST /feedback` with `screen = GENERAL` and ALL context fields null → 202 (accepted; no constraints on GENERAL)
- [ ] `POST /feedback` without cookie → 401
- [ ] `GET /feedback?page=0&size=20` returns caller's own entries only (other user's entry filtered out by `userId` match in repo query)
- [ ] `GET /feedback/{id}` for owner → 200 with hydrated DTO; routing log eager-loaded (no N+1 — verified via Hibernate stats: 1 SELECT entry+routing-log join, 1 SELECT for pending-clarification)
- [ ] `GET /feedback/{id}` for non-existent id → 404 `feedback-entry-not-found` ProblemDetail
- [ ] `GET /feedback/{id}` for another user's entry → 404 (don't leak)
- [ ] `GET /feedback/{id}` without cookie → 401
- [ ] `traceId` allocated server-side per submit (each submit gets a fresh UUID)
- [ ] `FeedbackSubmittedEvent.screen` carries the screen from the request (used by Notification telemetry per LLD line 661)
- [ ] `FeedbackProcessedEvent` record DECLARED but NOT published by 01b (no test asserts publication)
- [ ] `FeedbackAsyncConfig` bean wires; `ThreadPoolTaskExecutor` named `feedbackClassificationPool` resolves at startup
- [ ] `FeedbackExceptionHandler` carries `@Order(Ordered.HIGHEST_PRECEDENCE)`
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter in ITs)
- [ ] `FeedbackEntryDtoPage` flat shape validates
- [ ] `FeedbackBoundaryTest` (from 01a) still passes
- [ ] No regression on existing tests
- [ ] `FeedbackServiceImpl` is package-private; only the two interfaces are public
- [ ] `correctMisclassification`, `answerClarificationQuery`, `retryStuckClassifications`, `expireOldClarificationQueries` throw `UnsupportedOperationException` with a clear "feedback-01<x> impl pending" message

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/feedback/api/controller/FeedbackController.java
NEW   src/main/java/com/example/mealprep/feedback/api/FeedbackExceptionHandler.java                   (@Order(Ordered.HIGHEST_PRECEDENCE))
NEW   src/main/java/com/example/mealprep/feedback/api/dto/SubmitFeedbackRequest.java
NEW   src/main/java/com/example/mealprep/feedback/api/dto/SubmitFeedbackResponse.java
NEW   src/main/java/com/example/mealprep/feedback/api/dto/CorrectionRequest.java                       (declared here; full @ValidDestination lands in 01f)
NEW   src/main/java/com/example/mealprep/feedback/api/dto/AnswerClarificationRequest.java             (declared here; full @AssertTrue body lands in 01e)
NEW   src/main/java/com/example/mealprep/feedback/domain/service/FeedbackQueryService.java
NEW   src/main/java/com/example/mealprep/feedback/domain/service/FeedbackUpdateService.java
NEW   src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java             (impl of both; partial — 01c/01d/01e/01f/01g extend)
NEW   src/main/java/com/example/mealprep/feedback/event/FeedbackSubmittedEvent.java
NEW   src/main/java/com/example/mealprep/feedback/event/FeedbackProcessedEvent.java                   (declared; published by 01c/01d)
NEW   src/main/java/com/example/mealprep/feedback/config/FeedbackAsyncConfig.java
NEW   src/main/java/com/example/mealprep/feedback/validation/ValidUiContext.java
NEW   src/main/java/com/example/mealprep/feedback/validation/UiContextValidator.java

MOD   src/main/java/com/example/mealprep/feedback/FeedbackModule.java                                  (replace 01a's stub with the facade re-exporting both service interfaces)
MOD   src/main/java/com/example/mealprep/feedback/domain/repository/ClarificationQueryRepository.java (append findFirstByFeedbackEntryIdAndStatus)
MOD   src/main/java/com/example/mealprep/feedback/api/dto/FeedbackEntryDto.java                        (add withPendingClarificationQueryId helper)

NEW   src/main/resources/openapi/paths/feedback.yaml
NEW   src/main/resources/openapi/schemas/feedback.yaml
MOD   src/main/resources/openapi/openapi.yaml                                                          (2 lines under paths:; 12 lines under components.schemas:)

NEW   src/test/java/com/example/mealprep/feedback/FeedbackServiceImplTest.java                        (submit happy path; 422 / 400 paths; 404 path; getByIds; getRoutingDecision)
NEW   src/test/java/com/example/mealprep/feedback/FeedbackControllerIT.java                            (full HTTP: POST 202, GET 200/404; OpenAPI validation; FeedbackSubmittedEvent published once)
NEW   src/test/java/com/example/mealprep/feedback/UiContextValidatorTest.java
MOD   src/test/java/com/example/mealprep/feedback/testdata/FeedbackTestData.java                       (append request + response builders)
```

Count: ~22 new + 3 modified. Estimated agent runtime 40-55 min.

**Files this ticket does NOT modify**:
- `config/GlobalExceptionHandler.java`
- `archunit/ModuleBoundaryTest.java`
- Other modules' files (preference, nutrition, provisions, recipe) — feedback-01b is purely self-contained
- 01a's entities, migrations, mappers, enums — read-only

## Dependencies

- **Hard dependency**: `feedback-01a` (merged) — entities, migrations, repositories, mappers, enums, DTOs, `FeedbackEntryDto`, `RoutingDecisionDto`, `FeedbackEntryNotFoundException`, `RoutingDecisionNotFoundException`.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: project-setup (merged) — `@EnableAsync`, `@EnableJpaAuditing`, Springdoc / OpenAPI lint, swagger-request-validator config.
- **Hard dependency**: refactor-01-split-merge-zones (merged).
- **No hard dependencies on wave-2 modules** (preference / nutrition / provisions / recipe) — 01b's submission flow doesn't call any cross-module service.
- **No hard dependency on AI module** — 01b's submission is purely DB + event; AI dispatch lands in 01c.
- **Sibling tickets running in parallel** (Wave 3): `adaptation-pipeline-01b`, `planner-01b`, `discovery-01b`. Only collision is the entry `openapi.yaml` (per-module blocks) — keep additions in the `# feedback` block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit + Flyway migration test)
- [ ] All edge-case items above ticked
- [ ] `FeedbackExceptionHandler` carries `@Order(Ordered.HIGHEST_PRECEDENCE)`
- [ ] OpenAPI 3.0 nullable fields use **inline** `nullable: true` (NOT `$ref + nullable: true`)
- [ ] `FeedbackEntryDtoPage` uses **flat** Page<T> shape with `additionalProperties: true`
- [ ] All YAML description strings with `,` `:` `'` single-quoted
- [ ] No regression on existing tests
- [ ] `FeedbackSubmittedEvent` published AFTER COMMIT (verified via `@TransactionalEventListener(phase = AFTER_COMMIT)` test bean)
- [ ] All four 01c/01e/01f/01g-deferred service methods throw `UnsupportedOperationException` with a clear pending message
- [ ] No N+1 on `GET /feedback/{id}` path (1 join SELECT + 1 clarification lookup)

## What's NOT in scope

- `FeedbackClassificationTask`, `FeedbackClassifier`, `ConfidenceGate`, `ClassificationResult`, `ClassificationOutput`, `ClassificationResultDto`, the async listener on `FeedbackSubmittedEvent`, AI graceful-degrade (Flow 2) → **feedback-01c**
- `FeedbackRouter`, `DestinationDispatcher` SPI + four destination impls, Flow 3 multi-destination split, `@Transactional(REQUIRES_NEW)` per-route → **feedback-01d**
- `FeedbackProcessedEvent` publication site → **feedback-01c** (clarification + zero-route path) and **01d** (post-routing path)
- `ClarificationQueryController`, answer endpoint, 7-day TTL, `ClarificationQueryRepository.findByStatusAndExpiresAtBefore` sweep usage → **feedback-01e**
- Misclassification correction endpoint, `correctMisclassification` impl, `CorrectionReplayer`, destination revert SPIs → **feedback-01f**
- `@Scheduled` sweeps (`retryStuckClassifications`, `expireOldClarificationQueries`, transient-failure retry) → **feedback-01g**
- Cross-module integration tests (FeedbackPreferenceIntegrationIT, FeedbackOptimiserIntegrationIT, FeedbackProvisionsIntegrationIT) → **feedback-01g**
- `@TransactionalEventListener` on `AiCallSucceededEvent` for `lastClassifiedAt` telemetry stamping → **feedback-01g**
- Quality-monitoring metrics service / dashboard DTO → out of LLD scope (LLD §Quality Monitoring line 873)
- Multi-actor corrections (`actorUserId != userId`) — LLD line 521 keeps single-actor for v1
- SSE streaming of routing decisions to the client — LLD §Out of Scope line 928 (v2)

Squash-merge with: `feat(feedback): 01b — submission flow + read endpoints + service interfaces + FeedbackSubmittedEvent + async pool config`
