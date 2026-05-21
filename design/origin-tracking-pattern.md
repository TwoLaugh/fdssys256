# Origin-Tracking Pattern

A cross-cutting architectural pattern for how AI-driven and other non-user-originated traffic interacts with the system's REST API surface.

## The pattern in one paragraph

When the system needs to mutate user data on the user's behalf — feedback application, AI-driven preference learning, system-triggered re-optimisation — **it calls the same REST endpoints a human user would call**, with a header (and parallel audit metadata) identifying the origin as non-user. The endpoint applies origin-specific policies on top of its usual behaviour: confidence-floor checks, separate audit trail, rate limiting, recursion guard. Tests of the endpoint cover both code paths simultaneously.

## The problem this solves

Without this pattern, the natural drift is to build a parallel "internal-only" API surface for system-driven mutations. The result is two implementations of every concept — `/api/v1/preferences/...` for the user, `internal/preferences/...` for AI feedback. The two diverge over time. Validation gets duplicated. Tests duplicate. Bugs fix one side and not the other. The Spring proxy + transaction semantics differ. A new contributor has to know which side to modify for any given change.

The pattern collapses this to ONE implementation. The endpoint is the contract. Origin is a request attribute that the endpoint's behaviour can branch on.

## When to apply

Use this pattern whenever a system component needs to mutate user-scoped state on the user's behalf. Specifically:

- Feedback bridges applying classifier output to preference / nutrition / provisions / recipe state
- AI-driven adaptations writing pending changes
- Scheduled jobs that perform user-scoped mutations (price refresh, system catalogue prune, etc.)
- Mid-week re-optimisation triggered by external events

Do NOT use this pattern when the operation is purely system-internal and has no user-facing equivalent — schema migrations, background USDA refresh job, anything purely operational.

## Mechanism

### Origin header

Every non-user-originated HTTP request carries a header:

```
X-Origin: <origin-kind>
X-Origin-Trace: <originating-event-or-trace-id>
```

Origin kinds (initial set; extend as needed):
- `ai-feedback` — feedback classifier's downstream apply
- `ai-adaptation` — recipe adaptation pipeline writing pending changes
- `system-scheduled` — scheduled scanners (expiry, defrost, price refresh)
- `system-reopt` — mid-week re-optimisation
- `system-discovery` — discovery pipeline saving an imported recipe

Absence of `X-Origin` (or the header's omission) means **direct user origin**. This is the safe default: anything calling the API without identifying itself is treated as a user, which is the most-restricted permission set.

### Authentication

The system must still know *which user* the mutation acts on behalf of. Two patterns work:

**Pattern A: Inherited session (preferred for in-process triggers)**

Feedback bridges, adaptation pipeline, reopt — these fire as `@TransactionalEventListener(AFTER_COMMIT)` on user-action events. They inherit the user's session/security context via Spring's `SecurityContextHolder.MODE_INHERITABLETHREADLOCAL` or by explicit propagation. No separate authentication needed; the caller is the same authenticated user, just acting through a system component.

**Pattern B: Service token + acting-as header (required for scheduled jobs)**

For scheduled jobs that don't have an originating user session, the system authenticates with a service token (separate `auth_service_tokens` table, hashed). The request carries:

```
Authorization: Bearer <service-token>
X-Acting-As: <userId>
X-Origin: system-scheduled
```

The service token has a fixed set of permitted `X-Origin` values (a token tagged `system-scheduled` cannot make `ai-feedback` calls). This limits blast radius.

### Authorization differential

Inside controllers (or, better, a filter), origin-tracking applies these policies:

1. **Confidence floor for AI origins.** `ai-feedback` and `ai-adaptation` calls must include a confidence value in the request payload. Below a per-origin threshold (default 0.5), the call is rejected with HTTP 422 — the system has decided not to apply low-confidence AI inferences directly. (User can still see the inference as a pending-change suggestion via a different path.)

2. **Rate limiting.** Per-origin limits independent of user limits. `ai-feedback` capped at e.g. 20 calls/user/day to prevent runaway loops. `system-scheduled` capped at e.g. 100/min globally.

3. **Audit attribution.** Audit log rows record `actor_type` (USER / AI / SYSTEM) and `origin_trace`. Decision-log rows similarly. This makes "who changed this" answerable.

4. **Event metadata.** Domain events fired downstream carry the original `X-Origin` and `X-Origin-Trace`. Listeners can filter (e.g. notification service might suppress "we updated your preferences" notifications when origin was the user themselves directly editing).

### Recursion guard

The most dangerous failure mode: AI feedback fires → triggers an endpoint call → which fires more events → which triggers more AI feedback → infinite loop.

Defence in depth:
- Every origin call carries `X-Origin-Depth: N` (default 0). The handler increments on outbound system-driven calls. At depth ≥ 3, reject with 422 and log loudly.
- Idempotency: AI-originated mutations include an idempotency key (e.g. feedback_id). If the same key arrives twice within 5 minutes, the second call is a no-op.
- Trace-ID propagation: the original `trace_id` flows through. If a trace_id has already seen N mutations within a time window, alert and pause that trace.

## Where origin information is persisted

| Data | Where |
|---|---|
| Audit log rows (per-module) | `actor_type` column (USER / AI / SYSTEM), `origin_trace` column |
| Decision log | `triggered_by` field (already exists per `core.audit.DecisionLog`); extend with `origin` if not already represented |
| Domain events | Add `origin` and `originTrace` to base event interface |
| Notification log | `origin` field — distinguishes "AI updated your preferences" from "Partner updated your preferences" |

## Concrete examples

### Example 1: Feedback applies a nutrition target change

1. User sends free-text feedback: "I'm trying to cut sodium."
2. `FeedbackController` records the feedback, fires `FeedbackReceivedEvent`.
3. `FeedbackClassifier` runs, classifies → destination `NUTRITION`, extracted: `{target: sodium_mg_max, direction: decrease, magnitude: moderate}`, confidence 0.84.
4. `NutritionFeedbackBridge` (after-commit listener) constructs an HTTP request to `PUT /api/v1/nutrition/targets/micro/sodium`. Headers:
   ```
   X-Origin: ai-feedback
   X-Origin-Trace: feedback-{feedback_id}
   X-Origin-Depth: 1
   Authorization: <user's session>
   Content-Type: application/json
   ```
   Body: `{ "value": 2000, "reason": "user feedback: cutting sodium", "confidence": 0.84 }`
5. Spring routes the request through the same `MicroTargetsController.updateSodium(...)` a user calls.
6. Origin filter sees `X-Origin: ai-feedback` + confidence 0.84 > threshold 0.5 → allowed.
7. Service applies the change, writes audit row with `actor_type=AI`, `origin_trace=feedback-{id}`.
8. `NutritionTargetChangedEvent` fires with origin metadata.
9. Notification service receives it, sees `origin=ai-feedback`, queues an "we adjusted your sodium target — review?" notification.

The endpoint is unchanged from how a user would call it. The differential lives in the filter and the audit metadata.

### Example 2: System rejects a low-confidence AI feedback

Same shape as above, but classifier confidence is 0.42. Origin filter sees `X-Origin: ai-feedback` + confidence 0.42 < threshold 0.5 → rejects with HTTP 422 `{ "error": "confidence_below_threshold", "min": 0.5, "actual": 0.42 }`. Feedback bridge catches the rejection, surfaces the feedback as a pending-suggestion-for-user-review instead. The user sees "we *think* you want to cut sodium — apply?" but the system doesn't apply automatically.

### Example 3: Scheduled price refresh

1. `@Scheduled(cron = "0 0 6 * * MON")` triggers `PriceRefreshScheduler.refreshAllTopIngredients()`.
2. For each user with a configured Tesco connection (looped server-side), the scheduler constructs:
   ```
   POST /api/v1/grocery/prices/refresh
   Authorization: Bearer <service-token-for-system-scheduled>
   X-Acting-As: {userId}
   X-Origin: system-scheduled
   X-Origin-Trace: scheduled-price-refresh-{cronInstance}
   ```
3. Origin filter validates the service token, checks the token's permitted origins include `system-scheduled`, sets the security context to act as `{userId}`.
4. Endpoint runs the price refresh.
5. Audit: `actor_type=SYSTEM`, `origin_trace=scheduled-...`.

## Implementation outline

A minimal implementation needs:

1. **`OriginFilter`** — Spring servlet filter that reads `X-Origin`, validates against permitted origins, applies confidence-floor + rate-limit + depth-check, populates a `OriginContext` request attribute.
2. **`OriginContext`** — request-scoped bean holding origin kind, trace, depth, confidence. Services read this when writing audit rows.
3. **`@OriginAware` annotation (optional)** — on controllers to mark "this endpoint can receive non-user origin"; absence means user-only (defense-in-depth).
4. **`AuditLogWriter`** — extended to accept the OriginContext and persist actor_type + origin_trace.
5. **Service token table + auth provider** — for Pattern B authentication.
6. **Event-base interface extension** — `OriginAwareEvent { Origin getOrigin(); String getOriginTrace(); }` that domain events implement.

Roughly 3-5 days of work for the foundation; then each feedback bridge / scheduler / SPI applies it.

## Where this pattern interacts with existing design

- **`design/feedback-system.md`** — the feedback bridges (B1.4 in the roadmap) will be the first consumers of this pattern. Update that doc to reference this pattern when bridges land.
- **`design/technical-architecture.md`** — event catalogue should be updated to note that all events carry origin metadata.
- **`lld/core.md`** — `OriginContext` belongs in the core module (cross-cutting).
- **`lld/style-guide.md`** — add a section on origin-tracking conventions (header names, default values, etc.) so future contributors don't reinvent.
- **`lld/notification.md`** — when notifications gets built (Tier B2), it should consume origin metadata to distinguish "you changed this" vs "AI changed this" vs "partner changed this" UX.

## What this pattern doesn't do

- It's not a replacement for proper authentication. A request still needs to be authenticated as *some* identity (user session or service token); origin is a layer on top of identity, not a substitute.
- It's not a replacement for the existing audit log. Origin metadata extends audit, doesn't replace it.
- It doesn't auto-magically solve concurrency. If AI and user both try to edit the same Taste Profile simultaneously, `@Version` optimistic concurrency still does the work. Origin is metadata, not coordination.

## Alternative considered: internal-only API surface

The natural alternative is a parallel set of internal-only methods or controllers — `PreferenceInternalService.applyAiDelta(...)`, `internal/preferences/...`. We considered this and rejected it because:

1. **Behavioural divergence is inevitable** — over time, the internal and external paths drift, tests fragment, and a bug fixed on one side doesn't reach the other.
2. **The interesting policy differences are small and isolatable** — confidence floor, rate limit, audit attribution. These fit in a filter, not a parallel service.
3. **Tests double** — every endpoint needs two test suites if the impl is duplicated. With origin-tracking, one test suite covers both with the origin varied.
4. **Spring proxy semantics differ** between internal calls (no proxy, no `@Transactional`) and external calls (full proxy chain). Origin-tracking keeps the full chain, avoiding the self-invocation trap that bit PRs #104 and #106.

## Open questions for implementation

These are decisions deferred to the LLD / implementation PR:

- Exact threshold values per origin kind for confidence floor and rate limit
- Whether to use a header (`X-Origin`) or a request body field for origin — header chosen here for cleanliness, but trade-offs exist
- How to handle "AI suggests but user must confirm" UX flows — likely a separate `pending_suggestions` table that the origin-tracking flow inserts into, rather than directly mutating
- Whether to fail-closed (reject if origin filter can't determine) or fail-open (treat as user) on edge cases — likely fail-closed
- Concrete service-token format — JWT vs opaque token + lookup table

These belong in the LLD for the OriginFilter implementation, not this pattern doc.

## Bottom line

One endpoint surface, two callers (user + system), one set of tests, differential policies enforced by a single filter. The pattern collapses what would otherwise be a parallel API duplication problem. First consumer: feedback bridges (Tier B1.4 in the frontend-readiness roadmap). Subsequent consumers: scheduled jobs, AI adaptation pipeline, future automation surfaces.
