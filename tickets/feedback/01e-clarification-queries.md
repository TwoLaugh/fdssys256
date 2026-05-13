# Ticket: feedback — 01e ClarificationQueryController + Answer Flow + 7-Day TTL Expiry Sweep + Re-fire

## Summary

Layer the **clarification queue interaction** (Flow 5 of the LLD) on top of feedback-01c/01d: the `ClarificationQueryController` for `GET /api/v1/feedback/clarifications`, `GET /api/v1/feedback/clarifications/{queryId}`, and `POST /api/v1/feedback/clarifications/{queryId}/answer`; the `answerClarificationQuery` impl on `FeedbackUpdateService` (replacing 01b's `UnsupportedOperationException` stub); the `AnswerClarificationRequest` body validation including the `@AssertTrue` "at least one of `selectedDestination` or `userClarificationText` provided" cross-field rule; the **`@Scheduled(cron = "0 0 4 * * *")` daily expiry sweep** that flips PENDING queries past their `expires_at` to `EXPIRED` and transitions the parent feedback entry to `FAILED + UNRESOLVED_CLARIFICATION`; the **re-fire path** that publishes a fresh `FeedbackSubmittedEvent` so 01c's classification listener picks up the entry again with the appended `userClarificationText` and `userSelectedHint`; the two new module exceptions `ClarificationQueryNotFoundException` (404) and `ClarificationQueryExpiredException` (410); and the **`listClarificationQueries` + `getClarificationQuery` query methods** (filling the 01b interface stubs). Per [`lld/feedback.md §Flow 5 lines 818-833`](../../lld/feedback.md), [`lld/feedback.md §ClarificationQueryController lines 606-613`](../../lld/feedback.md), [`lld/feedback.md §V20260501130300 lines 174-200`](../../lld/feedback.md), [`lld/feedback.md §AnswerClarificationRequest lines 343-351`](../../lld/feedback.md).

**LLD divergence note** — **re-fire mechanism**: per [LLD Flow 5 step 7 line 829](../../lld/feedback.md), "Publish a fresh `FeedbackSubmittedEvent` to re-trigger Flow 2. The async listener picks it up; classification runs again with the augmented context." **The challenge**: 01c's `FeedbackClassificationContext` takes `userClarificationText` and `userSelectedHint` as fields, but the `FeedbackSubmittedEvent` (from 01b) carries no such fields. **Two options**:
- **(A)** Extend `FeedbackSubmittedEvent` with two optional fields. Breaks the event's HLD-aligned shape and requires Notification module awareness.
- **(B)** Persist `userClarificationText` + `userSelectedHint` on the `FeedbackEntry` (as transient/short-lived fields), republish the unchanged `FeedbackSubmittedEvent`, and have 01c's listener read them from the entry on each invocation. The fields are cleared after a successful classification attempt to keep the schema clean.
- **(C)** Persist them on the `ClarificationQuery` row itself (already done: LLD line 183 — `user_clarification_text`, line 182 — `selected_destination`). The classification listener queries the latest answered `ClarificationQuery` for the entry and appends to context.

**Choice: (C)** — no schema changes, no event-shape changes, leverages existing 01a columns. The classification listener (01c) was specced as "first attempt: empty `userClarificationText`/`userSelectedHint`"; 01e amends 01c's helper `buildContext` to look up `ClarificationQueryRepository.findFirstByFeedbackEntryIdAndStatus(feedbackId, ANSWERED)` and pass through the answered query's `selected_destination` + `user_clarification_text`. **The 01c helper modification is a small mutation: append, not restructure.** **Worth user review** — alternative is to keep 01c untouched and pass via a thread-local or new repo on the `FeedbackEntry` (transient fields, not persisted), which feels worse.

**LLD divergence note** — **the `userClarificationText` accumulator**: re-fire on a second-round clarification answer (the LLD admits clarification can repeat, no hard cap — LLD line 833) means the second answer's text shouldn't displace the first answer's text; both should be appended to context. **Strategy**: when persisting an answer to a clarification, **also append the prior answers** by reading every ANSWERED `ClarificationQuery` for this entry. 01c's `buildContext` then iterates all ANSWERED queries (oldest first) and concatenates their `user_clarification_text`. **The "first answer" is preserved across repeated clarification rounds.** **Worth user review** — alternative (simpler) is to use only the most-recent answer; v1's simplest behaviour. **Going with the simpler "most-recent only"** in 01e — the LLD doesn't explicitly mandate accumulation; document the choice clearly in the answer-flow Javadoc and revisit if eval-set feedback suggests accumulation is needed.

**LLD divergence note** — **expiry sweep timing**: per [LLD §Service Interfaces line 515](../../lld/feedback.md), `expireOldClarificationQueries()` runs daily 04:00 UTC. **01e ships the cron schedule** (`@Scheduled(cron = "0 0 4 * * *")`); 01g may consolidate all sweeps onto a single `@Configuration` if many cron methods accumulate. **For now**, the cron annotation lives on the `expireOldClarificationQueries` impl method directly in `FeedbackServiceImpl`. **Verify**: project-wide `@EnableScheduling` is configured (auth-01a likely ships it; if not, 01e appends a `@EnableScheduling` annotation to `FeedbackServiceImpl` or the application class).

**Defers** (still out of scope after 01e):

- Misclassification correction + replay → **feedback-01f**
- `@Scheduled retryStuckClassifications` (stuck-in-CLASSIFYING sweep) + transient-failure retry → **feedback-01g**
- `@TransactionalEventListener` on `AiCallSucceededEvent` for `lastClassifiedAt` telemetry → **feedback-01g**
- Cross-module integration tests → **feedback-01g**
- Hard cap on clarification rounds (LLD line 833 — "no hard cap in v1; UI may encourage giving up after 3 rounds") → **frontend-phase**

01e completes the **<0.5 confidence loop**: a user can answer a pending clarification, re-classify happens, and the routing path runs again. Without 01e, classification-pending entries accumulate forever.

## Behavioural spec

### `AnswerClarificationRequest` body validation

1. **`AnswerClarificationRequest`** at `feedback/api/dto/AnswerClarificationRequest.java`. Replace 01b's placeholder record with the full LLD shape per [LLD lines 342-351](../../lld/feedback.md):
   ```java
   public record AnswerClarificationRequest(
       Destination selectedDestination,                // exactly one of the four; null if user types instead
       @Size(max = 4000) String userClarificationText  // optional free-text refinement
   ) {
     @AssertTrue(message = "Either selectedDestination or userClarificationText must be provided")
     public boolean isAtLeastOneProvided() {
       return selectedDestination != null
           || (userClarificationText != null && !userClarificationText.isBlank());
     }
   }
   ```
   **Both fields can be set simultaneously** — user picks a destination AND adds clarifying text. The re-classifier sees both.

### `ClarificationQueryController`

2. **`ClarificationQueryController`** at `feedback/api/controller/ClarificationQueryController.java`. `@RestController`, `@RequestMapping("/api/v1/feedback/clarifications")`, `@Tag(name = "Feedback — clarifications")`. Constructor-inject `FeedbackQueryService`, `FeedbackUpdateService`, `CurrentUserResolver`.

3. **`GET /api/v1/feedback/clarifications?status=&page=&size=`** per [LLD line 609](../../lld/feedback.md):
   - `status` query param: `ClarificationStatus` enum (`PENDING | ANSWERED | EXPIRED`); **default behaviour: list all statuses** (LLD doesn't pin a default; pick "all"). When omitted, the repository method `findByUserIdOrderByCreatedAtDesc` is called (NEW method — see §10 below). When present, `findByFeedbackEntryUserIdAndStatusOrderByCreatedAtAsc` from 01a.
   - `page`, `size` per the wave-2 pagination conventions (default 0/20, max 100).
   - Returns `Page<ClarificationQueryDto>`.

4. **`GET /api/v1/feedback/clarifications/{queryId}`** per [LLD line 610](../../lld/feedback.md). 404 if missing or belongs to another user. Returns `ClarificationQueryDto`.

5. **`POST /api/v1/feedback/clarifications/{queryId}/answer`** per [LLD line 611](../../lld/feedback.md). Body: `AnswerClarificationRequest`. Returns `SubmitFeedbackResponse` (showing the pre-re-classification state — entry status `RECEIVED`, routes `[]`, `pendingClarificationQueryId` null). **Status codes**: 200 / 400 / 404 / **410** (expired). The 410 body is a ProblemDetail with the original `feedbackId` as an extension field so the client can re-submit (LLD line 613).

### `answerClarificationQuery` impl

6. **`FeedbackServiceImpl.answerClarificationQuery`** replaces 01b's `UnsupportedOperationException` stub. Per [LLD Flow 5 lines 818-833](../../lld/feedback.md):
   1. `@Transactional` (default REQUIRED).
   2. Load the `ClarificationQuery` by `(queryId, userId)` via `findByIdAndFeedbackEntryUserId`. **404** if missing (LLD line 822). Note: this read joins on `FeedbackEntry.user_id` — caller can't answer another user's clarification.
   3. **Status gate**:
      - `status == EXPIRED` → throw `ClarificationQueryExpiredException` (410); body carries `feedbackEntryId` so the client can re-submit.
      - `status == ANSWERED` → throw a generic `IllegalStateException("clarification already answered")` mapped to **422** by the global handler (or 409 — pick 422 since LLD line 823 says "generic 422").
   4. Validate `request` — Jakarta `@Valid` already handles `@AssertTrue isAtLeastOneProvided`. Implicit guard here defensively (e.g. record reconstructed via reflection): if both are null/blank, throw `IllegalArgumentException` mapped to 400.
   5. Mark the query `status = ANSWERED`, `selectedDestination = request.selectedDestination`, `userClarificationText = request.userClarificationText`, `answeredAt = Instant.now()`. **`@Version`** on `ClarificationQuery` enforces concurrent-answer safety: two simultaneous answer attempts → second one gets `OptimisticLockingFailureException` → 409.
   6. Load the parent `FeedbackEntry`. Mark `submissionStatus = RECEIVED` (re-eligible for classification per LLD line 826). **Do NOT** increment `classificationAttempts` here — 01c's listener increments on its `RECEIVED → CLASSIFYING` step.
   7. Save entry.
   8. **Publish `FeedbackSubmittedEvent` AFTER COMMIT** (reuses 01b's event shape):
      ```java
      eventPublisher.publishEvent(new FeedbackSubmittedEvent(
          feedbackId, entry.getUserId(), entry.getUiContext().screen(),
          entry.getTraceId(),    // SAME traceId across all attempts
          Instant.now()));
      ```
      Same `traceId` as the original submission — the decision-log chain stays linked.
   9. Return `SubmitFeedbackResponse(feedbackId, traceId, submissionStatus = RECEIVED, routes = [], pendingClarificationQueryId = null)`.

7. **01c integration**: per the LLD divergence note above, 01e amends 01c's `buildContext` helper to look up the most-recent ANSWERED `ClarificationQuery` for the entry and pass through its `selectedDestination` (as `Optional<Destination> userSelectedHint`) and `userClarificationText` (as `Optional<String>`). **This is a small mutation to 01c's `FeedbackClassificationListener.buildContext`**, documented in the "Files this ticket touches" section.

8. **`classificationAttempts` semantics on re-fire**: the entry is at `CLARIFICATION_PENDING` when answered. After answer, it goes to `RECEIVED`. 01c's listener picks it up: step 1 transitions to `CLASSIFYING` and increments `classificationAttempts` (e.g. from 1 → 2). The clarification query stays in the DB with `status = ANSWERED` for audit + ground-truth purposes (LLD line 826: "increment classification_attempts" — actually the listener does that; the LLD's wording at line 827 is slightly ambiguous; **01e's resolution**: the listener owns the increment; 01e leaves `classificationAttempts` untouched).

### Listing + lookup query methods

9. **`FeedbackServiceImpl.listClarificationQueries(userId, status, pageable)`** replaces 01b's stub. Uses:
   - `status != null` → `clarificationQueryRepository.findByFeedbackEntryUserIdAndStatusOrderByCreatedAtAsc(userId, status, pageable)` (from 01a)
   - `status == null` → `clarificationQueryRepository.findByFeedbackEntryUserIdOrderByCreatedAtAsc(userId, pageable)` — **NEW method** (see §10).
10. **`ClarificationQueryRepository.findByFeedbackEntryUserIdOrderByCreatedAtAsc`** — append to the 01a repo:
    ```java
    Page<ClarificationQuery> findByFeedbackEntryUserIdOrderByCreatedAtAsc(UUID userId, Pageable p);
    ```
    Used by `listClarificationQueries` when `status` is unspecified.
11. **`FeedbackServiceImpl.getClarificationQuery(userId, queryId)`** replaces 01b's stub. Uses `findByIdAndFeedbackEntryUserId` from 01a. Returns `Optional<ClarificationQueryDto>`.

### Daily expiry sweep

12. **`FeedbackServiceImpl.expireOldClarificationQueries()`** replaces 01b's stub. Annotated:
    ```java
    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    @Override
    public void expireOldClarificationQueries() {
      Instant cutoff = Instant.now();
      List<ClarificationQuery> expired = clarificationQueryRepository
          .findByStatusAndExpiresAtBefore(ClarificationStatus.PENDING, cutoff);
      for (ClarificationQuery q : expired) {
        expireOne(q);   // small REQUIRES_NEW tx
      }
    }
    ```
    **The cron annotation lives on the impl method** (not on the interface — Spring's `@Scheduled` requires it on the concrete bean method or a `@Configuration @Bean` proxy).

13. **`expireOne(ClarificationQuery)` helper**:
    - `@Transactional(propagation = REQUIRES_NEW)` (per-query isolation so one failure doesn't drop peers).
    - Mark `query.status = EXPIRED`. Save.
    - Load parent `FeedbackEntry`. Mark `submissionStatus = FAILED`. Save.
    - **Optionally** set a `failureKind` on the entry — but `FeedbackEntry` has no `failureKind` column (the routing log carries failures, not the entry). The LLD's `UNRESOLVED_CLARIFICATION` (line 200) is mentioned as a value to record but doesn't map to a single column. **Resolution**: the entry's `submissionStatus = FAILED` is sufficient; the UI inspects the latest `ClarificationQuery.status == EXPIRED` to display the reason. **Worth user review** — alternative is to add a `failure_reason varchar(64)` column to `feedback_entries`; deferred to a schema-evolution ticket if needed.
    - **No event published** — expiry is silent (the LLD doesn't define a `ClarificationExpiredEvent`); the Notification module can poll if it needs the user-facing toast.

14. **Idempotency**: an entry that's already `EXPIRED` shouldn't be touched again. The repo query `findByStatusAndExpiresAtBefore(PENDING, ...)` filters to PENDING only — already idempotent.

### New module exceptions

15. **`ClarificationQueryNotFoundException`** at `feedback/exception/ClarificationQueryNotFoundException.java`. Extends `FeedbackException`. Maps to 404 `.../clarification-query-not-found` per [LLD line 623](../../lld/feedback.md).
16. **`ClarificationQueryExpiredException`** at `feedback/exception/ClarificationQueryExpiredException.java`. Extends `FeedbackException`. Maps to **410** `.../clarification-query-expired` per [LLD line 624](../../lld/feedback.md). Carries `feedbackEntryId` as an extension field on the ProblemDetail.
17. **Append `@ExceptionHandler` methods** to `FeedbackExceptionHandler` (the 01b-shipped advice). Keep `@Order(Ordered.HIGHEST_PRECEDENCE)`. **Do NOT modify `config/GlobalExceptionHandler.java`.**

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/feedback.yaml`

Add three new path-items below 01b's existing entries. Do NOT touch the existing 01b path-items.

```yaml
feedbackClarifications:
  get:
    tags: [Feedback - clarifications]
    operationId: listClarificationQueries
    summary: 'Paginated list of the caller''s clarification queries; optional status filter.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: query
        name: status
        required: false
        schema: { type: string, enum: [PENDING, ANSWERED, EXPIRED] }
      - in: query
        name: page
        schema: { type: integer, minimum: 0, default: 0 }
      - in: query
        name: size
        schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
    responses:
      '200':
        description: 'Page of clarification queries.'
        content:
          application/json:
            schema: { $ref: '../schemas/feedback.yaml#/ClarificationQueryDtoPage' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

feedbackClarificationById:
  get:
    tags: [Feedback - clarifications]
    operationId: getClarificationQuery
    summary: 'Fetch a single clarification query.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: queryId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200':
        description: 'The clarification query.'
        content:
          application/json:
            schema: { $ref: '../schemas/feedback.yaml#/ClarificationQueryDto' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Not found / belongs to another user', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

feedbackClarificationAnswer:
  post:
    tags: [Feedback - clarifications]
    operationId: answerClarificationQuery
    summary: 'Answer a pending clarification; re-classification fires automatically afterwards.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: queryId
        required: true
        schema: { type: string, format: uuid }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/feedback.yaml#/AnswerClarificationRequest' }
    responses:
      '200':
        description: 'Answer accepted; re-classification queued.'
        content:
          application/json:
            schema: { $ref: '../schemas/feedback.yaml#/SubmitFeedbackResponse' }
      '400': { description: 'Validation error (neither field provided, or oversized text)', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Query not found / belongs to another user', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '410': { description: 'Query expired; client should re-submit the original feedback', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: 'Query already answered', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/feedback.yaml`

```yaml
ClarificationStatus:
  type: string
  enum: [PENDING, ANSWERED, EXPIRED]
ClarificationOptionDto:
  type: object
  required: [destination, snippet]
  properties:
    destination: { $ref: '#/Destination' }
    snippet: { type: string }
    classifierJustification: { type: string, nullable: true }
ClarificationQueryDto:
  type: object
  required: [id, feedbackEntryId, questionText, options, status, expiresAt, createdAt]
  properties:
    id: { type: string, format: uuid }
    feedbackEntryId: { type: string, format: uuid }
    questionText: { type: string, maxLength: 512 }
    options:
      type: array
      items: { $ref: '#/ClarificationOptionDto' }
    status: { $ref: '#/ClarificationStatus' }
    expiresAt: { type: string, format: date-time }
    createdAt: { type: string, format: date-time }
ClarificationQueryDtoPage:
  type: object
  additionalProperties: true
  required: [content, totalElements, totalPages, number, size]
  properties:
    content:
      type: array
      items: { $ref: '#/ClarificationQueryDto' }
    totalElements: { type: integer, format: int64 }
    totalPages: { type: integer }
    number: { type: integer }
    size: { type: integer }
    first: { type: boolean }
    last: { type: boolean }
    empty: { type: boolean }
    numberOfElements: { type: integer }
AnswerClarificationRequest:
  type: object
  properties:
    selectedDestination:
      type: string
      enum: [RECIPE, PREFERENCE, NUTRITION, PROVISIONS]
      nullable: true
    userClarificationText:
      type: string
      maxLength: 4000
      nullable: true
```

**Gotcha applied**: every nullable scalar / object uses **inline** `nullable: true`. `selectedDestination` is inlined (NOT `$ref + nullable: true`) — the `Destination` schema exists from 01b but reusing it via `$ref` with sibling `nullable: true` is silently dropped per swagger-parser. Inline the enum.

**Gotcha applied**: `ClarificationQueryDtoPage` uses **flat** Page<T> shape with `additionalProperties: true`.

**Gotcha applied**: every YAML description with `,` `:` `'` is single-quoted.

### Append to entry `src/main/resources/openapi/openapi.yaml`

**Location**: under `paths:`, in the `# feedback` block (under 01b's two entries):
```yaml
  /api/v1/feedback/clarifications:
    $ref: 'paths/feedback.yaml#/feedbackClarifications'
  /api/v1/feedback/clarifications/{queryId}:
    $ref: 'paths/feedback.yaml#/feedbackClarificationById'
  /api/v1/feedback/clarifications/{queryId}/answer:
    $ref: 'paths/feedback.yaml#/feedbackClarificationAnswer'
```

**Location**: under `components.schemas:`, in the `# feedback` block (alphabetical):
```yaml
    AnswerClarificationRequest: { $ref: 'schemas/feedback.yaml#/AnswerClarificationRequest' }
    ClarificationOptionDto: { $ref: 'schemas/feedback.yaml#/ClarificationOptionDto' }
    ClarificationQueryDto: { $ref: 'schemas/feedback.yaml#/ClarificationQueryDto' }
    ClarificationQueryDtoPage: { $ref: 'schemas/feedback.yaml#/ClarificationQueryDtoPage' }
    ClarificationStatus: { $ref: 'schemas/feedback.yaml#/ClarificationStatus' }
```

## Verbatim shape snippets

### `ClarificationQueryController` skeleton

```java
@RestController
@RequestMapping("/api/v1/feedback/clarifications")
@Tag(name = "Feedback - clarifications")
@RequiredArgsConstructor
public class ClarificationQueryController {

  private final FeedbackQueryService queryService;
  private final FeedbackUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  @GetMapping
  public Page<ClarificationQueryDto> list(
      @RequestParam(required = false) ClarificationStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = currentUserResolver.requireCurrentUserId();
    return queryService.listClarificationQueries(userId, status, PageRequest.of(page, size));
  }

  @GetMapping("/{queryId}")
  public ClarificationQueryDto getById(@PathVariable UUID queryId) {
    UUID userId = currentUserResolver.requireCurrentUserId();
    return queryService.getClarificationQuery(userId, queryId)
        .orElseThrow(() -> new ClarificationQueryNotFoundException(queryId));
  }

  @PostMapping("/{queryId}/answer")
  public SubmitFeedbackResponse answer(@PathVariable UUID queryId,
                                       @Valid @RequestBody AnswerClarificationRequest request) {
    UUID userId = currentUserResolver.requireCurrentUserId();
    return updateService.answerClarificationQuery(userId, queryId, request);
  }
}
```

### `answerClarificationQuery` impl

```java
@Override
@Transactional
public SubmitFeedbackResponse answerClarificationQuery(
    UUID userId, UUID queryId, AnswerClarificationRequest request) {

  ClarificationQuery query = clarificationQueryRepository
      .findByIdAndFeedbackEntryUserId(queryId, userId)
      .orElseThrow(() -> new ClarificationQueryNotFoundException(queryId));

  if (query.getStatus() == ClarificationStatus.EXPIRED) {
    throw new ClarificationQueryExpiredException(queryId, query.getFeedbackEntry().getId());
  }
  if (query.getStatus() == ClarificationStatus.ANSWERED) {
    throw new ClarificationQueryAlreadyAnsweredException(queryId);  // mapped to 422
  }
  if (!request.isAtLeastOneProvided()) {
    throw new IllegalArgumentException("answer must provide selectedDestination or userClarificationText");
  }

  query.setStatus(ClarificationStatus.ANSWERED);
  query.setSelectedDestination(request.selectedDestination());
  query.setUserClarificationText(request.userClarificationText());
  query.setAnsweredAt(clock.instant());
  clarificationQueryRepository.save(query);

  FeedbackEntry entry = query.getFeedbackEntry();
  entry.setSubmissionStatus(SubmissionStatus.RECEIVED);
  feedbackEntryRepository.save(entry);

  eventPublisher.publishEvent(new FeedbackSubmittedEvent(
      entry.getId(), entry.getUserId(),
      entry.getUiContext().screen(),
      entry.getTraceId(),
      clock.instant()));

  return new SubmitFeedbackResponse(
      entry.getId(), entry.getTraceId(),
      SubmissionStatus.RECEIVED, List.of(),
      /* pendingClarificationQueryId */ null);
}
```

### `expireOldClarificationQueries` impl

```java
@Override
@Scheduled(cron = "0 0 4 * * *", zone = "UTC")
public void expireOldClarificationQueries() {
  Instant now = clock.instant();
  List<ClarificationQuery> expired = clarificationQueryRepository
      .findByStatusAndExpiresAtBefore(ClarificationStatus.PENDING, now);
  for (ClarificationQuery q : expired) {
    try {
      expireOne(q.getId());
    } catch (RuntimeException ex) {
      log.warn("Failed to expire clarification {}; will retry next sweep", q.getId(), ex);
    }
  }
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
void expireOne(UUID queryId) {
  ClarificationQuery q = clarificationQueryRepository.findById(queryId)
      .orElse(null);
  if (q == null || q.getStatus() != ClarificationStatus.PENDING) return;   // raced; no-op
  q.setStatus(ClarificationStatus.EXPIRED);
  clarificationQueryRepository.save(q);

  FeedbackEntry entry = q.getFeedbackEntry();
  entry.setSubmissionStatus(SubmissionStatus.FAILED);
  feedbackEntryRepository.save(entry);
}
```

**`expireOne` placement**: technically the `expireOne` method is called from a non-`@Transactional` method (the cron entry) — `@Transactional` works because Spring's proxy intercepts the call; **BUT** if `expireOne` is private OR called within the same bean, the proxy is bypassed and the annotation is ignored. **Resolution**: either make `expireOne` `protected` and let Spring's CGLIB proxy intercept, OR extract `expireOne` to a sibling `@Component` (e.g. `ClarificationExpirer`). **Prefer the sibling-component pattern** — cleaner and avoids the self-invocation gotcha. **Worth implementer note**.

### Updated `FeedbackClassificationListener.buildContext` (mutation to 01c)

```java
// In feedback/domain/service/internal/FeedbackClassificationListener.java — modified by 01e
private FeedbackClassificationContext buildContext(FeedbackEntry entry,
                                                    Optional<String> _unused,    // legacy param; ignored
                                                    Optional<Destination> _unused2) {
  // Look up the latest ANSWERED clarification, if any, for re-classification context.
  Optional<ClarificationQuery> answeredOpt = clarificationQueryRepository
      .findFirstByFeedbackEntryIdAndStatusOrderByAnsweredAtDesc(
          entry.getId(), ClarificationStatus.ANSWERED);
  Optional<String> userClarificationText = answeredOpt
      .map(ClarificationQuery::getUserClarificationText)
      .filter(Predicate.not(String::isBlank));
  Optional<Destination> userSelectedHint = answeredOpt
      .map(ClarificationQuery::getSelectedDestination);

  return new FeedbackClassificationContext(
      entry.getUserId(), entry.getTraceId(),
      entry.getText(),
      mapDocumentToDto(entry.getUiContext()),
      userClarificationText, userSelectedHint,
      entry.getClassificationAttempts());
}
```

**NEW repo method** `ClarificationQueryRepository.findFirstByFeedbackEntryIdAndStatusOrderByAnsweredAtDesc` — append to the repo. Returns the most-recent answered clarification (in case the entry has been through multiple clarification rounds).

## Edge-case checklist

- [ ] `POST /clarifications/{id}/answer` happy path with `selectedDestination = PREFERENCE`: clarification → ANSWERED; entry → RECEIVED; `FeedbackSubmittedEvent` published with the SAME `traceId` as the original submission
- [ ] `POST /clarifications/{id}/answer` happy path with only `userClarificationText`: same as above; `selectedDestination` on the clarification stays null
- [ ] `POST /clarifications/{id}/answer` with BOTH fields → 200; both persisted; classifier re-fires with both context bits
- [ ] `POST /clarifications/{id}/answer` with NEITHER field → 400 via `@AssertTrue isAtLeastOneProvided` (Jakarta validation)
- [ ] `POST /clarifications/{id}/answer` with blank `userClarificationText` and null `selectedDestination` → 400 (the `@AssertTrue` checks `isBlank()`)
- [ ] `POST /clarifications/{id}/answer` on EXPIRED query → 410 with `feedbackEntryId` in ProblemDetail
- [ ] `POST /clarifications/{id}/answer` on ANSWERED query → 422 `clarification-query-already-answered` (or the chosen status)
- [ ] `POST /clarifications/{id}/answer` on non-existent query → 404
- [ ] `POST /clarifications/{id}/answer` on another user's query → 404 (no leak)
- [ ] `POST /clarifications/{id}/answer` concurrent answers → first wins; second gets 409 via `OptimisticLockingFailureException`
- [ ] After successful answer, the re-classification listener (01c) loads context with the answered query's `userClarificationText` and `userSelectedHint` populated
- [ ] After successful answer, `classificationAttempts` on entry stays at its current value (1) — 01c's listener increments it on the next CLASSIFYING transition
- [ ] `GET /clarifications` lists caller's clarifications only; supports `?status=PENDING`, `?status=ANSWERED`, `?status=EXPIRED`, omitted (all)
- [ ] `GET /clarifications` ordered by `createdAt ASC` per LLD line 188 ("user-facing inbox: open clarifications for this user, oldest first")
- [ ] `GET /clarifications/{id}` for owner → 200; for non-owner → 404; for missing → 404
- [ ] `ClarificationQueryDto.options` array is the deserialised `classifierOptionsJson` from the entity (via `ClarificationQueryMapper` from 01a)
- [ ] Daily expiry sweep: a PENDING query with `expires_at < now` → status flipped to EXPIRED; parent entry → FAILED
- [ ] Daily expiry sweep: a PENDING query with `expires_at > now` → untouched
- [ ] Daily expiry sweep: an already-EXPIRED query → idempotent (no UPDATE; the filter excludes it)
- [ ] Daily expiry sweep: per-query failure (e.g. parent entry deleted concurrently) doesn't drop peer expirations — `expireOne` REQUIRES_NEW isolates them
- [ ] `@Scheduled(cron = "0 0 4 * * *", zone = "UTC")` annotation present on `expireOldClarificationQueries` (or sibling `@Component`)
- [ ] `expireOne` not subject to Spring proxy self-invocation gotcha (verified: either extracted to sibling component OR `protected` and tested for CGLIB interception)
- [ ] `ClarificationQueryNotFoundException` → 404 ProblemDetail `.../clarification-query-not-found`
- [ ] `ClarificationQueryExpiredException` → 410 ProblemDetail `.../clarification-query-expired` with `feedbackEntryId` extension field
- [ ] `FeedbackExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the new handlers
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter in ITs)
- [ ] `ClarificationQueryDtoPage` flat shape validates
- [ ] No regression on 01b/01c/01d tests
- [ ] No N+1 — `answerClarificationQuery` performs 2 SELECTs (query + entry) + 2 UPDATEs (query + entry); `expireOne` performs 2 SELECTs + 2 UPDATEs

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/feedback/api/controller/ClarificationQueryController.java
NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/ClarificationExpirer.java     (sibling @Component to avoid Spring self-invocation gotcha for @Transactional expireOne)

NEW   src/main/java/com/example/mealprep/feedback/exception/ClarificationQueryNotFoundException.java
NEW   src/main/java/com/example/mealprep/feedback/exception/ClarificationQueryExpiredException.java
NEW   src/main/java/com/example/mealprep/feedback/exception/ClarificationQueryAlreadyAnsweredException.java    (NEW — not in LLD; 422 mapping; LLD line 823 just says "generic 422" — give it a named type)

MOD   src/main/java/com/example/mealprep/feedback/api/dto/AnswerClarificationRequest.java               (replace 01b's placeholder with the full @AssertTrue body)
MOD   src/main/java/com/example/mealprep/feedback/api/FeedbackExceptionHandler.java                     (append 3 @ExceptionHandler methods; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java               (replace 4 UnsupportedOperationException stubs: answerClarificationQuery, listClarificationQueries, getClarificationQuery, expireOldClarificationQueries)
MOD   src/main/java/com/example/mealprep/feedback/domain/repository/ClarificationQueryRepository.java   (append findByFeedbackEntryUserIdOrderByCreatedAtAsc, findFirstByFeedbackEntryIdAndStatusOrderByAnsweredAtDesc)
MOD   src/main/java/com/example/mealprep/feedback/domain/service/internal/FeedbackClassificationListener.java   (amend buildContext to inject answered-clarification context — see Behavioural §7)

MOD   src/main/resources/openapi/paths/feedback.yaml                                                    (append 3 path-items below 01b's)
MOD   src/main/resources/openapi/schemas/feedback.yaml                                                  (append 5 schemas)
MOD   src/main/resources/openapi/openapi.yaml                                                          (3 lines under paths:; 5 lines under components.schemas:)

NEW   src/test/java/com/example/mealprep/feedback/AnswerClarificationRequestTest.java                   (@AssertTrue body: both-null rejected, one-set accepted, blank text rejected)
NEW   src/test/java/com/example/mealprep/feedback/ClarificationQueryControllerIT.java                   (GET list + by-id, POST answer happy/410/422/404, OpenAPI validation, FeedbackSubmittedEvent published)
NEW   src/test/java/com/example/mealprep/feedback/ClarificationFlowIT.java                              (end-to-end: submit → classified 0.4 confidence via TestAiService → ClarificationQuery written → answer → re-classified at 0.9 → routed; round-trip with TestAiService canned-responses keyed by attempt number)
NEW   src/test/java/com/example/mealprep/feedback/ClarificationExpiryIT.java                            (cron sweep against Postgres: TestData seeds queries with expires_at in the past; @Scheduled annotation invoked manually via Spring's scheduler test helper; status flipped, parent entry FAILED)
NEW   src/test/java/com/example/mealprep/feedback/ClarificationExpirerTest.java                         (unit: expireOne happy + raced-to-EXPIRED already, parent entry update)
MOD   src/test/java/com/example/mealprep/feedback/testdata/FeedbackTestData.java                       (append clarification + answer + answered-clarification builders)
```

Count: ~10 new + 8 modified. Estimated agent runtime 45-55 min.

**Files this ticket does NOT modify**:
- 01a's entities, migrations — schema additions handled within the existing tables
- `config/GlobalExceptionHandler.java`
- `archunit/ModuleBoundaryTest.java`
- Other modules' files

## Dependencies

- **Hard dependency**: `feedback-01a` (merged) — `ClarificationQuery` entity + repo, `ClarificationStatus` enum, `ClarificationQueryDto`, `ClarificationOptionDto`, `ClarificationQueryMapper`, all the partial indexes the expiry sweep relies on.
- **Hard dependency**: `feedback-01b` (merged) — `FeedbackUpdateService` interface (with the `answerClarificationQuery` stub), `FeedbackExceptionHandler`, `FeedbackSubmittedEvent` (re-published by 01e), `AnswerClarificationRequest` placeholder, OpenAPI yaml files.
- **Hard dependency**: `feedback-01c` (merged) — `FeedbackClassificationListener` (which 01e amends), `FeedbackClassificationContext`. The re-classification path works because 01c's listener fires on the fresh `FeedbackSubmittedEvent`.
- **Soft dependency**: `feedback-01d` (merged) — without 01d, the re-classified entry routes through 01c's Noop router and stays at `CLASSIFIED`. The clarification flow still works (answer → re-classify → CLASSIFIED), just doesn't route to destinations. If 01d isn't merged, the integration test ClarificationFlowIT asserts only up to `CLASSIFIED`, not `ROUTED`.
- **Sibling tickets running in parallel** (Wave 3 round 3 of feedback): `feedback-01f` (correction). 01e and 01f both amend `FeedbackServiceImpl` and `FeedbackExceptionHandler` and the OpenAPI files — **flag as a merge-conflict zone**. The two tickets are independent in behaviour but collide in files. **Coordinate**: 01e ships first (clarification is "lighter" — no destination revert dependency); 01f rebases.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit)
- [ ] All edge-case items above ticked
- [ ] `FeedbackExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the three new handlers
- [ ] OpenAPI 3.0 nullable fields use **inline** `nullable: true` (selectedDestination is inlined, NOT `$ref + nullable: true`)
- [ ] `ClarificationQueryDtoPage` uses flat Page<T> shape with `additionalProperties: true`
- [ ] All YAML description strings with `,` `:` `'` single-quoted
- [ ] No regression on existing tests
- [ ] `@Scheduled(cron = "0 0 4 * * *", zone = "UTC")` annotation present
- [ ] `@EnableScheduling` confirmed on the application class (or appended to `FeedbackServiceImpl` if missing project-wide)
- [ ] `expireOne` proxied correctly (sibling `ClarificationExpirer` `@Component` confirmed)
- [ ] Re-classification path tested: answer → 01c's listener picks up answered-clarification context

## What's NOT in scope

- Misclassification correction + replay → **feedback-01f**
- `@Scheduled retryStuckClassifications` sweep + transient retry → **feedback-01g**
- `@TransactionalEventListener` on `AiCallSucceededEvent` for `lastClassifiedAt` telemetry stamping → **feedback-01g**
- Cross-module integration tests → **feedback-01g**
- Hard cap on clarification rounds → frontend / UX phase (LLD line 833)
- `failure_reason` column on `feedback_entries` for `UNRESOLVED_CLARIFICATION` → schema-evolution future ticket (worth user review)
- Accumulator behaviour for `userClarificationText` across multiple clarification rounds → out of scope (using most-recent-only per LLD divergence)
- Notification fan-out on clarification expiry → **notification module** when it lands
- SSE streaming of re-classification result → out of scope (LLD §Out of Scope line 928 — v2)

Squash-merge with: `feat(feedback): 01e — ClarificationQueryController + answer flow + 7-day TTL expiry sweep + re-classification re-fire`
