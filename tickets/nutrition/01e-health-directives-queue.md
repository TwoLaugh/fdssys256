# Ticket: nutrition — 01e Health Directives Queue + Inbound / Accept / Reject + DirectiveSafetyGate + DirectiveApplier

## Summary

Layer the **propose-not-apply health-directives queue** on top of the 01a/01b/01c/01d nutrition module per [LLD §V20260502120700 (lines 290-324)](../../lld/nutrition.md), [LLD §Flow 8 (lines 998-1022)](../../lld/nutrition.md), [LLD §`HealthDirectivesController` (lines 833-841)](../../lld/nutrition.md). Ships the `HealthDirective` aggregate root (`nutrition_health_directives` table + JSONB `instruction_payload` / `safety_gate_findings` / `user_modification_json`) per LLD lines 295-324; the inbound delivery endpoint `POST /api/v1/nutrition/health-directives/inbound` (LLD line 839); the accept / reject endpoints `POST /{directiveId}/accept` and `POST /{directiveId}/reject` (LLD lines 840-841); the listing endpoints `GET /?status=&page=&size=` and `GET /{directiveId}` (LLD lines 837-838); the **`DirectiveSafetyGate`** `@Component` running the v1 deterministic ruleset (LLD line 1008-1012); and the **`DirectiveApplier`** routing accepted-directive deltas through to `NutritionTargets` writes via the existing 01a `updateTargets` path (LLD line 1015) — **`nutrition_model` route only in 01e**; the `preference_model` route (LLD line 1016 — `PreferenceUpdateService.updateHardConstraints`) is **stubbed** behind a `DirectiveApplyTarget` SPI looked up via `@Autowired(required = false)`. Plus the two events `HealthDirectiveReceivedEvent` (LLD line 901) and `HealthDirectiveAcceptedEvent` (LLD line 904) published `AFTER_COMMIT`; the `HealthDirectiveMapper` (LLD line 590); the `HealthDirectiveRepository` (LLD line 646-650); the targets-audit row append per LLD §V20260502120800 (`actor_kind = HEALTH_DIRECTIVE`, `source_directive_id` populated) — **note: 01e relies on 01a's existing `nutrition_targets_audit` table to be migrated**; if 01a deferred it, 01e ships the migration. New module exception subclasses `HealthDirectiveNotFoundException` (404), `HealthDirectiveAlreadyDecidedException` (409), `HealthDirectiveSafetyGateBlockedException` (422) per LLD lines 853-855. The `@ValidDirectiveInstruction` custom validator per LLD line 869.

**LLD divergence note** — **`preference_model` route stubbed**: per LLD line 1016 the `DirectiveApplier` for `mapsToModel = preference_model` directives calls `PreferenceUpdateService.updateHardConstraints(...)` to install a temporary hard-constraint with `expiresAt = autoExpiresAt`. **`PreferenceUpdateService.updateHardConstraints` exists** (preference-01a shipped it for non-temporary constraints) but **does not yet accept a `temporary` + `autoExpiresAt` shape**; that's preference-01c's territory. 01e ships an in-module SPI `DirectiveApplyTarget` (`preference/spi/`-style — actually `nutrition/spi/` since nutrition owns the SPI here) with the contract:

```java
public interface DirectiveApplyTarget {
  /** Apply a directive routed to mapsToModel="preference_model". Implementation joins caller's tx. */
  void applyPreferenceDirective(UUID userId, DirectiveInstructionDocument instruction,
                                boolean temporary, Instant autoExpiresAt, UUID directiveId, UUID actorUserId);
}
```

`NoopDirectiveApplyTarget` `@Component @ConditionalOnMissingBean(DirectiveApplyTarget.class)` logs WARN and **throws `DirectiveApplyTargetUnavailableException` (422)** so a `preference_model`-routed directive accept-flow surfaces a clear "preference module not wired for this yet" error rather than silently no-op'ing. When preference-01c lands, it implements `DirectiveApplyTarget` and the accept flow works end-to-end.

**Worth user review**: alternative is to defer `preference_model` accepts to a later ticket and reject inbound `preference_model` directives at the inbound endpoint. Rejected because:

- The inbound endpoint is a **passive queue** (LLD line 1000 — "Directives are NEVER auto-applied"). Rejecting at inbound time would mean the health platform can't propose preference-model directives at all, which violates the "propose, then user reviews" model.
- The accept flow's 422 with a clear message ("preference-model directive routes need preference-01c") is more actionable than dropping inbound silently.

**LLD divergence note** — **AI-augmented safety gate deferred**: LLD line 1008 says "deterministic checks; pure code; no AI" — that's exactly 01e's scope. The v1 ruleset is verbatim from LLD lines 1009-1012:
1. `INGREDIENT_RESTRICTION` / `ELIMINATION_TRIAL` colliding with a favourite in the preference taste profile → **warn, don't block** (the gate emits a `SafetyFindingDto` with `severity = WARN`).
2. `TARGET_ADJUSTMENT` raising a daily floor > 20% above the current daily target, or lowering it below 50% of the current floor → **block**.
3. `MACRO_REBALANCE` whose post-apply per-meal sums diverge from the daily target by > 100 kcal → **block**.
4. `INGREDIENT_RESTRICTION` of staples (water, salt, "all protein") without alternative → **block**.

Rule 1 requires reading the user's taste profile — **01e cannot do this** because the soft-preferences shape isn't in preference yet (same blocker as household-01e). **Workaround**: 01e implements rule 1 as a **no-op** (returns no findings, no warnings) with a TODO comment pointing at preference-01c. **Rules 2, 3, 4 ship fully working.** Rule 4 hard-codes a static staples list (`water`, `salt`, `"all protein"`, `"all carbs"`, `"all fats"`) — when preference-01c lands, the staples list could be augmented from the user's taste profile, but the v1 static list is sufficient for the safety-gate's purpose.

**Worth user review**: rule 1 as a no-op could be argued to leave a safety hole. Counter-argument: rule 1 is a "warn, don't block" rule — even with full implementation, it doesn't change accept/reject outcomes; the user sees the warning in the UI. Skipping it in 01e means the warning isn't surfaced, but the directive still applies cleanly. **Document this clearly in the gate's Javadoc.**

**Defers** (still out of scope after 01e):

- `preference_model` route for directive accepts → **preference-01c** wires the `DirectiveApplyTarget` impl
- Rule 1 of the safety gate (favourite collision) → requires preference soft-prefs; ships as no-op
- ML / AI-rich safety gate (LLD line 1008 says deterministic only for v1; AI-rich gate not in scope anywhere) → no ticket yet
- `@Scheduled` auto-expiry sweep (LLD line 1022) → **nutrition-01f or later** (cron `0 0 4 * * *` reverts expired ACCEPTED directives; ships when preference-01c is ready since revert needs `PreferenceUpdateService.removeTemporaryConstraint`)
- `applyFeedback(NutritionFeedbackRequest)` directive sub-flow → **nutrition-01m** (LLD Flow 10)
- Notification module fan-out on `HealthDirectiveReceivedEvent` → **notification-01a** (not a nutrition concern)

01e unblocks the **safety-net** for nutrition: health platforms can push directives, users review and accept/reject. Without 01e, the propose/accept loop has no on-ramp and the user has no path to apply (e.g.) a doctor-recommended low-sodium target.

## Behavioural spec

### Aggregate shape — `HealthDirective`

1. `HealthDirective` is a **standalone aggregate root** per [LLD §Entities line 364](../../lld/nutrition.md): "Aggregate root. `(sourcePlatform, externalDirectiveId)` unique for idempotent inbound. `@Version`. JSONB for instruction payload, modifications, findings."
2. Fields per [LLD V20260502120700 lines 295-319](../../lld/nutrition.md):
   - `id (UUID, application-set)`
   - `userId (UUID NOT NULL — target user receiving the directive)`
   - `externalDirectiveId (varchar 128 NOT NULL — source platform's id; unique per `(sourcePlatform, externalDirectiveId)`)`
   - `sourcePlatform (varchar 64 NOT NULL — "apple-health" / "fitbit" / "doctor-portal" / etc)`
   - `receivedAt (Instant NOT NULL)`
   - `status (DirectiveStatus enum: PENDING_REVIEW | ACCEPTED | REJECTED | SUPERSEDED | EXPIRED; lowercase in DB matching LLD)`
   - `directiveType (DirectiveType enum: INGREDIENT_RESTRICTION | TARGET_ADJUSTMENT | MACRO_REBALANCE | ELIMINATION_TRIAL | REINTRODUCTION_PROTOCOL | SENSITIVITY_DOWNGRADE; lowercase in DB)`
   - `evidenceSummary (text — nullable)`
   - `evidenceConfidence (DirectiveConfidence enum: LOW | MODERATE | HIGH; nullable; varchar 16)`
   - `instructionPayload (jsonb NOT NULL — `DirectiveInstructionDocument`)`
   - `mapsToModel (varchar 24 NOT NULL — `nutrition_model` | `preference_model`)`
   - `mapsToTier (varchar 48 nullable — e.g. `protein_floor_g`)`
   - `temporary (boolean NOT NULL DEFAULT true)`
   - `autoExpiresAt (Instant nullable — required when `temporary = true`)`
   - `decidedAt (Instant nullable)`
   - `decidedByUserId (UUID nullable)`
   - `userModificationJson (jsonb nullable — `DirectiveInstructionDocument` override supplied on accept)`
   - `rejectionReason (varchar 255 nullable)`
   - `safetyGateVerdict (SafetyGateVerdict enum: PASSED | BLOCKED | PASSED_WITH_WARNINGS; nullable; varchar 16)`
   - `safetyGateFindings (jsonb nullable — `List<SafetyFindingDto>`)`
   - `optimisticVersion (@Version Long, column `optimistic_version` per LLD line 316)`
   - `createdAt (@CreatedDate)`, `updatedAt (@LastModifiedDate)`
3. **DB constraints / indexes** per LLD lines 319-324:
   - `UNIQUE (source_platform, external_directive_id)` — idempotent inbound.
   - `CREATE INDEX idx_nutr_directives_user_status ON ... (user_id, status, received_at DESC)`.
   - `CREATE INDEX idx_nutr_directives_auto_expires ON ... (auto_expires_at) WHERE auto_expires_at IS NOT NULL`.
4. **`DirectiveInstructionDocument` JSONB inner shape** per [LLD lines 541-544](../../lld/nutrition.md):
   ```java
   public record DirectiveInstructionDocument(String action, String target, String scope,
                                              DirectiveDurationDto duration, Map<String, JsonNode> extras) {}
   public record DirectiveDurationDto(String type, List<DirectivePhaseDto> phases, Integer durationWeeks) {}
   public record DirectivePhaseDto(String phase, Integer durationWeeks, String rule) {}
   ```
5. **`SafetyFindingDto`** per LLD line 545:
   ```java
   public record SafetyFindingDto(String code, String message, String severity) {}
   ```
   `severity` ∈ `BLOCK | WARN | INFO`. `code` is short kebab-case for UI mapping (`target-raise-exceeds-20pct`, `staple-restriction`, `meal-sum-divergence-exceeds-100kcal`).

### `receiveInboundDirective` flow (LLD line 1002)

6. `POST /api/v1/nutrition/health-directives/inbound`. Authenticated (the health-platform's call may use a service account or an API token — auth shape is out of scope per LLD line 843; 01e accepts any authenticated caller for v1). Body: `InboundHealthDirectiveRequest` verbatim from [LLD lines 547-554](../../lld/nutrition.md).
7. **Single `@Transactional` write**:
   - **Idempotency**: `findBySourcePlatformAndExternalDirectiveId(...)`. If found → **409** `DuplicateHealthDirectiveException` (NEW exception — LLD line 1002 says "Re-delivery returns 409 with existing row's status"; 01e creates a dedicated exception so the response carries the existing row's id + status as ProblemDetail extension fields).
   - **Validation**: `@Valid @ValidDirectiveInstruction` on the request — invalid → 400. Server-side check: `temporary == true ⇒ autoExpiresAt != null` (else 400, `MethodArgumentNotValidException`).
   - **Persist**: new row, `status = PENDING_REVIEW`, `receivedAt = Instant.now()`. `safetyGateVerdict / safetyGateFindings = null` (only set on accept).
   - **Note**: `userId` comes from the request — the health platform tells us which user the directive targets. The authenticated caller may or may not be the same user (a clinician acting on behalf of a patient).
8. **Event**: publish `HealthDirectiveReceivedEvent(userId, directiveId, directiveType, sourcePlatform, receivedAt, traceId, occurredAt)` `AFTER_COMMIT` per LLD line 901.
9. Return 201 with `HealthDirectiveDto`. `Location: /api/v1/nutrition/health-directives/{directiveId}`.

### `acceptHealthDirective` flow (LLD line 1004)

10. `POST /api/v1/nutrition/health-directives/{directiveId}/accept`. Authenticated. Server resolves `actorUserId` via `CurrentUserResolver`. Body: `AcceptDirectiveRequest { DirectiveInstructionDocument userModification /* nullable; @Valid @ValidDirectiveInstruction when present */, long expectedVersion }`.
11. **404 ladder**: directive not found → 404 `HealthDirectiveNotFoundException`. Caller's `actorUserId != directive.userId` AND caller is not in an admin role → 404 (don't leak; v1 has no admin role so this collapses to "caller must be the directive's target user" — **worth user review**).
12. **Status gate**: `status != PENDING_REVIEW` → 409 `HealthDirectiveAlreadyDecidedException` (LLD line 1006).
13. **Stale `expectedVersion`** → 409 via `OptimisticLockingFailureException`.
14. **Effective instruction**: `userModification != null ? userModification : instructionPayload`. The modified version is re-validated against `@ValidDirectiveInstruction` (LLD line 1007 — "modification cannot bypass the schema gate").
15. **`DirectiveSafetyGate.evaluate(effectiveInstruction, directive, currentTargets)`** returns `SafetyGateResult { SafetyGateVerdict verdict, List<SafetyFindingDto> findings }`. **Pure deterministic** — no AI, no I/O beyond reading the user's existing `NutritionTargets`.
    - **Rule 1 — favourite collision** (LLD line 1009): no-op in 01e per LLD divergence note; emits no findings. TODO comment points at preference-01c.
    - **Rule 2 — target adjustment bounds** (LLD line 1010): when `effectiveInstruction.action == "adjust_target"` and `directiveType == TARGET_ADJUSTMENT`, compute the post-apply delta. If new floor > 1.2 × current daily target OR new floor < 0.5 × current floor → `BLOCK` with finding code `target-raise-exceeds-20pct` or `target-lower-below-50pct`.
    - **Rule 3 — macro rebalance** (LLD line 1011): when `directiveType == MACRO_REBALANCE`, sum per-meal calorie targets after applying the instruction; if `|sum - dailyTarget| > 100 kcal` → `BLOCK` with finding `meal-sum-divergence-exceeds-100kcal`.
    - **Rule 4 — staple restriction** (LLD line 1012): when `directiveType == INGREDIENT_RESTRICTION` and `effectiveInstruction.target ∈ {water, salt, "all protein", "all carbs", "all fats"}` AND no alternative supplied in `effectiveInstruction.extras["alternative"]` → `BLOCK` with finding `staple-restriction-no-alternative`.
16. **Persist verdict / findings** on the directive row regardless of outcome (LLD line 1013): `safetyGateVerdict = verdict`, `safetyGateFindings = findings`. `BLOCKED` → throw `HealthDirectiveSafetyGateBlockedException` (422) carrying the findings list; **status stays `PENDING_REVIEW`** (LLD line 1013 — "the user can modify and retry"). `PASSED` or `PASSED_WITH_WARNINGS` → continue.
17. **Apply via `DirectiveApplier`** (LLD line 1014):
    - `mapsToModel == nutrition_model`: invoke internal target-update path (the same private helper that backs `updateTargets` from 01a). `actorKind = HEALTH_DIRECTIVE`, `sourceDirectiveId = directive.id`. Writes audit rows to `nutrition_targets_audit`. Publishes `NutritionTargetsChangedEvent`.
    - `mapsToModel == preference_model`: invoke `directiveApplyTarget.applyPreferenceDirective(...)`. With `NoopDirectiveApplyTarget` wired → throws `DirectiveApplyTargetUnavailableException` (422). With a real impl (preference-01c) → joins this tx.
    - Unknown `mapsToModel` → 422 `InvalidDirectiveRoutingException` (NEW exception).
18. Update directive: `status = ACCEPTED`, `decidedAt = Instant.now()`, `decidedByUserId = actorUserId`, `userModificationJson = userModification` (nullable). `@Version` bumps.
19. **Event**: publish `HealthDirectiveAcceptedEvent(userId, directiveId, directiveType, mapsToModel, mapsToTier, userModified = (userModification != null), traceId, occurredAt)` `AFTER_COMMIT` per LLD line 904.
20. Return 200 with the updated `HealthDirectiveDto`.

### `rejectHealthDirective` flow (LLD line 1020)

21. `POST /api/v1/nutrition/health-directives/{directiveId}/reject`. Authenticated. Body: `RejectDirectiveRequest { @Size(max = 255) String rejectionReason /* nullable */, long expectedVersion }`.
22. **404 / 409** ladder same as `accept`.
23. **No safety gate** — rejection doesn't write to targets.
24. Single `@Transactional` write: `status = REJECTED`, `decidedAt = Instant.now()`, `decidedByUserId = actorUserId`, `rejectionReason = request.rejectionReason()`. `@Version` bumps.
25. **No event** — LLD §Events doesn't declare `HealthDirectiveRejectedEvent`. Document on the impl Javadoc.
26. Return 200 with the updated `HealthDirectiveDto`.

### `GET /api/v1/nutrition/health-directives?status=&page=&size=`

27. Authenticated. Server-resolved `userId` filters to the caller's own directives. `status` query param optional (`PENDING_REVIEW`/`ACCEPTED`/etc). Repository: `findByUserIdAndStatusOrderByReceivedAtDesc` when status is set, else `findByUserIdOrderByReceivedAtDesc` (LLD lines 648-649). Default size 20, max 100. Returns `Page<HealthDirectiveDto>`.

### `GET /api/v1/nutrition/health-directives/{directiveId}`

28. Authenticated. Returns `HealthDirectiveDto`. 404 if missing OR `directive.userId != actorUserId` (don't leak).

### Service interfaces — append-only

29. Append to existing `NutritionQueryService` (already declares `lookupIngredient` from 01d):
    ```java
    Page<HealthDirectiveDto> getDirectives(UUID userId, DirectiveStatus filter, Pageable p);
    Optional<HealthDirectiveDto> getDirective(UUID directiveId);
    ```
    Verbatim from [LLD lines 693-694](../../lld/nutrition.md).
30. Append to `NutritionUpdateService`:
    ```java
    HealthDirectiveDto receiveInboundDirective(UUID userId, InboundHealthDirectiveRequest request);
    HealthDirectiveDto acceptHealthDirective(UUID userId, UUID directiveId, AcceptDirectiveRequest request);
    HealthDirectiveDto rejectHealthDirective(UUID userId, UUID directiveId, RejectDirectiveRequest request);
    ```
    Verbatim from [LLD lines 733-735](../../lld/nutrition.md).

### Repository — new

31. ```java
    interface HealthDirectiveRepository extends JpaRepository<HealthDirective, UUID> {
      Optional<HealthDirective> findBySourcePlatformAndExternalDirectiveId(String platform, String externalId);
      Page<HealthDirective> findByUserIdAndStatusOrderByReceivedAtDesc(UUID u, DirectiveStatus s, Pageable p);
      Page<HealthDirective> findByUserIdOrderByReceivedAtDesc(UUID userId, Pageable p);
      List<HealthDirective> findByStatusAndAutoExpiresAtBefore(DirectiveStatus status, Instant cutoff);
    }
    ```
    Verbatim from [LLD lines 646-650](../../lld/nutrition.md). Package-private. The last method is for the **deferred** auto-expiry sweep (LLD line 1022); 01e ships it for completeness, but no caller in 01e.

### Errors

32. New module exception subclasses extending the existing `NutritionException` from 01a:
    - `HealthDirectiveNotFoundException` (404, `.../health-directive-not-found`) — LLD line 853.
    - `HealthDirectiveAlreadyDecidedException` (409, `.../health-directive-already-decided`) — LLD line 854.
    - `HealthDirectiveSafetyGateBlockedException` (422, `.../health-directive-safety-gate-blocked`) — LLD line 855. Carries `List<SafetyFindingDto>` as ProblemDetail extension `findings[]`.
    - `DuplicateHealthDirectiveException` (409, `.../duplicate-health-directive`) — NEW; carries existing row's id + status as ProblemDetail extensions.
    - `InvalidDirectiveRoutingException` (422, `.../invalid-directive-routing`) — NEW; unknown `mapsToModel`.
    - `DirectiveApplyTargetUnavailableException` (422, `.../directive-apply-target-unavailable`) — NEW; thrown by `NoopDirectiveApplyTarget` so callers see a clear "preference module not wired" error.
33. **Append the new `@ExceptionHandler` methods** to the existing `NutritionExceptionHandler` `@RestControllerAdvice` from 01a/01b/01c/01d (already `@Order(Ordered.HIGHEST_PRECEDENCE)`). Do **NOT** create a second handler class. Do **NOT** modify `config/GlobalExceptionHandler.java`.

### Validation

34. **`@ValidDirectiveInstruction`** custom class-level annotation on `DirectiveInstructionDocument` per LLD line 869:
    - `action` ∈ known set: `{"restrict_ingredient", "adjust_target", "rebalance_macros", "eliminate_then_reintroduce", "downgrade_sensitivity"}`.
    - `target` non-blank for `restrict_ingredient` / `adjust_target`.
    - `duration.type == "staged_protocol"` → `phases` ordered, non-overlapping, weeks sum > 0.
    - Lives in `nutrition/validation/`.

## Database

```
src/main/resources/db/migration/V20260601601200__nutrition_create_health_directives.sql   new
```

Schema mirrors [LLD V20260502120700 lines 294-324](../../lld/nutrition.md), renumbered to the nutrition timestamp range (`V20260601601200` is the next free slot after 01d's `V20260601601100__nutrition_create_ingredient_mapping.sql`):

```sql
-- V20260601601200
CREATE TABLE nutrition_health_directives (
    id                              uuid PRIMARY KEY,
    user_id                         uuid NOT NULL,
    external_directive_id           varchar(128) NOT NULL,
    source_platform                 varchar(64) NOT NULL,
    received_at                     timestamptz NOT NULL,
    status                          varchar(24) NOT NULL DEFAULT 'pending_review',
    directive_type                  varchar(48) NOT NULL,
    evidence_summary                text,
    evidence_confidence             varchar(16),
    instruction_payload             jsonb NOT NULL,
    maps_to_model                   varchar(24) NOT NULL,
    maps_to_tier                    varchar(48),
    temporary                       boolean NOT NULL DEFAULT true,
    auto_expires_at                 timestamptz,
    decided_at                      timestamptz,
    decided_by_user_id              uuid,
    user_modification_json          jsonb,
    rejection_reason                varchar(255),
    safety_gate_verdict             varchar(16),
    safety_gate_findings            jsonb,
    optimistic_version              bigint NOT NULL DEFAULT 0,
    created_at                      timestamptz NOT NULL,
    updated_at                      timestamptz NOT NULL,
    UNIQUE (source_platform, external_directive_id)
);
CREATE INDEX idx_nutr_directives_user_status
    ON nutrition_health_directives (user_id, status, received_at DESC);
CREATE INDEX idx_nutr_directives_auto_expires
    ON nutrition_health_directives (auto_expires_at) WHERE auto_expires_at IS NOT NULL;
```

**`nutrition_targets_audit` table**: 01e relies on the audit table from LLD §V20260502120800 (lines 329-340). **Verify 01a's migration list** — if `nutrition_targets_audit` isn't already created by 01a/01b/01c/01d, **01e adds it as a second migration**: `V20260601601201__nutrition_create_targets_audit.sql`. **Expected to already exist** (audit infrastructure typically ships with the targets aggregate from 01a); if so, this second migration is dropped. The agent must check and report.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/nutrition.yaml`

(Append five new path-items below 01d's ingredient-lookup blocks. Do NOT touch existing path-items.)

```yaml
nutritionHealthDirectives:
  get:
    tags: [Nutrition]
    operationId: listHealthDirectives
    summary: 'Paginated list of the caller''s health directives; optional status filter.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: query
        name: status
        required: false
        schema: { type: string, enum: [PENDING_REVIEW, ACCEPTED, REJECTED, SUPERSEDED, EXPIRED] }
      - in: query
        name: page
        schema: { type: integer, minimum: 0, default: 0 }
      - in: query
        name: size
        schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
    responses:
      '200':
        description: 'Page of directives.'
        content:
          application/json:
            schema: { $ref: '../schemas/nutrition.yaml#/HealthDirectiveDtoPage' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
nutritionHealthDirective:
  get:
    tags: [Nutrition]
    operationId: getHealthDirective
    summary: 'Fetch a single directive.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: directiveId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200':
        description: 'The directive.'
        content:
          application/json:
            schema: { $ref: '../schemas/nutrition.yaml#/HealthDirectiveDto' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Directive not found / belongs to another user', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
nutritionHealthDirectivesInbound:
  post:
    tags: [Nutrition]
    operationId: receiveInboundHealthDirective
    summary: 'Health platform pushes a new directive. Idempotent on (sourcePlatform, externalDirectiveId).'
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/nutrition.yaml#/InboundHealthDirectiveRequest' }
    responses:
      '201':
        description: 'Directive persisted as PENDING_REVIEW.'
        headers:
          Location:
            schema: { type: string, format: uri }
        content:
          application/json:
            schema: { $ref: '../schemas/nutrition.yaml#/HealthDirectiveDto' }
      '400': { description: 'Validation error', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: 'Re-delivery of an existing directive', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
nutritionHealthDirectiveAccept:
  post:
    tags: [Nutrition]
    operationId: acceptHealthDirective
    summary: 'Accept a pending directive; runs safety gate then applies the deltas.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: directiveId
        required: true
        schema: { type: string, format: uuid }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/nutrition.yaml#/AcceptDirectiveRequest' }
    responses:
      '200':
        description: 'Directive accepted and applied.'
        content:
          application/json:
            schema: { $ref: '../schemas/nutrition.yaml#/HealthDirectiveDto' }
      '400': { description: 'Validation error', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Directive not found / belongs to another user', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: 'Already decided (not PENDING_REVIEW) or stale expectedVersion', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: 'Safety gate blocked / preference-model target unavailable / invalid routing', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
nutritionHealthDirectiveReject:
  post:
    tags: [Nutrition]
    operationId: rejectHealthDirective
    summary: 'Reject a pending directive; records the rejection reason.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: directiveId
        required: true
        schema: { type: string, format: uuid }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/nutrition.yaml#/RejectDirectiveRequest' }
    responses:
      '200':
        description: 'Directive rejected.'
        content:
          application/json:
            schema: { $ref: '../schemas/nutrition.yaml#/HealthDirectiveDto' }
      '400': { description: 'Validation error', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Directive not found', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: 'Already decided or stale expectedVersion', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/nutrition.yaml`

```yaml
DirectiveStatus:
  type: string
  enum: [PENDING_REVIEW, ACCEPTED, REJECTED, SUPERSEDED, EXPIRED]
DirectiveType:
  type: string
  enum: [INGREDIENT_RESTRICTION, TARGET_ADJUSTMENT, MACRO_REBALANCE, ELIMINATION_TRIAL, REINTRODUCTION_PROTOCOL, SENSITIVITY_DOWNGRADE]
DirectiveConfidence:
  type: string
  enum: [LOW, MODERATE, HIGH]
SafetyGateVerdict:
  type: string
  enum: [PASSED, BLOCKED, PASSED_WITH_WARNINGS]
SafetyFindingDto:
  type: object
  required: [code, message, severity]
  properties:
    code: { type: string, maxLength: 64 }
    message: { type: string, maxLength: 512 }
    severity: { type: string, enum: [BLOCK, WARN, INFO] }
DirectivePhaseDto:
  type: object
  required: [phase]
  properties:
    phase: { type: string, maxLength: 32 }
    durationWeeks: { type: integer, minimum: 1, nullable: true }
    rule: { type: string, maxLength: 256, nullable: true }
DirectiveDurationDto:
  type: object
  required: [type]
  properties:
    type: { type: string, maxLength: 32 }
    phases:
      type: array
      nullable: true
      items: { $ref: '#/DirectivePhaseDto' }
    durationWeeks: { type: integer, minimum: 1, nullable: true }
DirectiveInstructionDocument:
  type: object
  required: [action]
  properties:
    action: { type: string, maxLength: 64 }
    target: { type: string, maxLength: 160, nullable: true }
    scope: { type: string, maxLength: 64, nullable: true }
    duration:
      type: object
      nullable: true
      properties:
        type: { type: string, maxLength: 32 }
        phases:
          type: array
          items: { $ref: '#/DirectivePhaseDto' }
        durationWeeks: { type: integer, minimum: 1, nullable: true }
    extras:
      type: object
      additionalProperties: true
HealthDirectiveDto:
  type: object
  required: [id, userId, externalDirectiveId, sourcePlatform, receivedAt, status, directiveType, instruction, mapsToModel, temporary, optimisticVersion]
  properties:
    id: { type: string, format: uuid }
    userId: { type: string, format: uuid }
    externalDirectiveId: { type: string, maxLength: 128 }
    sourcePlatform: { type: string, maxLength: 64 }
    receivedAt: { type: string, format: date-time }
    status: { $ref: '#/DirectiveStatus' }
    directiveType: { $ref: '#/DirectiveType' }
    evidenceSummary: { type: string, nullable: true }
    evidenceConfidence:
      type: string
      enum: [LOW, MODERATE, HIGH]
      nullable: true
    instruction: { $ref: '#/DirectiveInstructionDocument' }
    mapsToModel: { type: string, maxLength: 24 }
    mapsToTier: { type: string, maxLength: 48, nullable: true }
    temporary: { type: boolean }
    autoExpiresAt:
      type: string
      format: date-time
      nullable: true
    decidedAt:
      type: string
      format: date-time
      nullable: true
    decidedByUserId:
      type: string
      format: uuid
      nullable: true
    userModification:
      type: object
      nullable: true
      description: 'Same shape as instruction; supplied when the user accepted with a modification.'
      properties:
        action: { type: string }
        target: { type: string, nullable: true }
        scope: { type: string, nullable: true }
    rejectionReason: { type: string, maxLength: 255, nullable: true }
    safetyGateVerdict:
      type: string
      enum: [PASSED, BLOCKED, PASSED_WITH_WARNINGS]
      nullable: true
    safetyGateFindings:
      type: array
      nullable: true
      items: { $ref: '#/SafetyFindingDto' }
    optimisticVersion: { type: integer, format: int64 }
InboundHealthDirectiveRequest:
  type: object
  required: [externalDirectiveId, sourcePlatform, userId, directiveType, instruction, mapsToModel, temporary]
  properties:
    userId: { type: string, format: uuid }
    externalDirectiveId: { type: string, minLength: 1, maxLength: 128 }
    sourcePlatform: { type: string, minLength: 1, maxLength: 64 }
    directiveType: { $ref: '#/DirectiveType' }
    evidenceSummary:
      type: string
      maxLength: 4000
      nullable: true
    evidenceConfidence:
      type: string
      enum: [LOW, MODERATE, HIGH]
      nullable: true
    instruction: { $ref: '#/DirectiveInstructionDocument' }
    mapsToModel: { type: string, minLength: 1, maxLength: 24 }
    mapsToTier: { type: string, maxLength: 48, nullable: true }
    temporary: { type: boolean }
    autoExpiresAt:
      type: string
      format: date-time
      nullable: true
AcceptDirectiveRequest:
  type: object
  required: [expectedVersion]
  properties:
    userModification:
      type: object
      nullable: true
      properties:
        action: { type: string }
        target: { type: string, nullable: true }
        scope: { type: string, nullable: true }
    expectedVersion: { type: integer, format: int64, minimum: 0 }
RejectDirectiveRequest:
  type: object
  required: [expectedVersion]
  properties:
    rejectionReason: { type: string, maxLength: 255, nullable: true }
    expectedVersion: { type: integer, format: int64, minimum: 0 }
HealthDirectiveDtoPage:
  type: object
  additionalProperties: true
  required: [content, totalElements, totalPages, number, size]
  properties:
    content:
      type: array
      items: { $ref: '#/HealthDirectiveDto' }
    totalElements: { type: integer, format: int64 }
    totalPages: { type: integer }
    number: { type: integer }
    size: { type: integer }
    first: { type: boolean }
    last: { type: boolean }
    empty: { type: boolean }
    numberOfElements: { type: integer }
```

**Gotcha applied**: every nullable scalar / object uses **inline** `nullable: true`. `userModification` and `instruction.duration` are inlined nested objects (NOT `$ref + nullable: true`).

**Gotcha applied**: `HealthDirectiveDtoPage` uses the **flat** Page<T> shape with `additionalProperties: true`.

**Gotcha applied**: every YAML description with `,` `:` `'` is single-quoted.

### Append to entry `src/main/resources/openapi/openapi.yaml`

**Location**: under the existing `# nutrition` block in `paths:`. Append five new path-item refs:

```yaml
  /api/v1/nutrition/health-directives:
    $ref: 'paths/nutrition.yaml#/nutritionHealthDirectives'
  /api/v1/nutrition/health-directives/{directiveId}:
    $ref: 'paths/nutrition.yaml#/nutritionHealthDirective'
  /api/v1/nutrition/health-directives/inbound:
    $ref: 'paths/nutrition.yaml#/nutritionHealthDirectivesInbound'
  /api/v1/nutrition/health-directives/{directiveId}/accept:
    $ref: 'paths/nutrition.yaml#/nutritionHealthDirectiveAccept'
  /api/v1/nutrition/health-directives/{directiveId}/reject:
    $ref: 'paths/nutrition.yaml#/nutritionHealthDirectiveReject'
```

**Location**: under `components.schemas:`, append twelve new schema refs in the existing `# nutrition` block (alphabetical):

```yaml
    AcceptDirectiveRequest: { $ref: 'schemas/nutrition.yaml#/AcceptDirectiveRequest' }
    DirectiveConfidence: { $ref: 'schemas/nutrition.yaml#/DirectiveConfidence' }
    DirectiveDurationDto: { $ref: 'schemas/nutrition.yaml#/DirectiveDurationDto' }
    DirectiveInstructionDocument: { $ref: 'schemas/nutrition.yaml#/DirectiveInstructionDocument' }
    DirectivePhaseDto: { $ref: 'schemas/nutrition.yaml#/DirectivePhaseDto' }
    DirectiveStatus: { $ref: 'schemas/nutrition.yaml#/DirectiveStatus' }
    DirectiveType: { $ref: 'schemas/nutrition.yaml#/DirectiveType' }
    HealthDirectiveDto: { $ref: 'schemas/nutrition.yaml#/HealthDirectiveDto' }
    HealthDirectiveDtoPage: { $ref: 'schemas/nutrition.yaml#/HealthDirectiveDtoPage' }
    InboundHealthDirectiveRequest: { $ref: 'schemas/nutrition.yaml#/InboundHealthDirectiveRequest' }
    RejectDirectiveRequest: { $ref: 'schemas/nutrition.yaml#/RejectDirectiveRequest' }
    SafetyFindingDto: { $ref: 'schemas/nutrition.yaml#/SafetyFindingDto' }
    SafetyGateVerdict: { $ref: 'schemas/nutrition.yaml#/SafetyGateVerdict' }
```

## Verbatim shape snippets

### Entity

```java
@Entity
@Table(name = "nutrition_health_directives",
       uniqueConstraints = @UniqueConstraint(columnNames = {"source_platform", "external_directive_id"}))
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class HealthDirective {

  @Id @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "external_directive_id", nullable = false, updatable = false, length = 128)
  private String externalDirectiveId;

  @Column(name = "source_platform", nullable = false, updatable = false, length = 64)
  private String sourcePlatform;

  @Column(name = "received_at", nullable = false, updatable = false)
  private Instant receivedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 24)
  private DirectiveStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "directive_type", nullable = false, updatable = false, length = 48)
  private DirectiveType directiveType;

  @Column(name = "evidence_summary", columnDefinition = "text")
  private String evidenceSummary;

  @Enumerated(EnumType.STRING)
  @Column(name = "evidence_confidence", length = 16)
  private DirectiveConfidence evidenceConfidence;

  @Type(JsonType.class)
  @Column(name = "instruction_payload", nullable = false, columnDefinition = "jsonb")
  private DirectiveInstructionDocument instructionPayload;

  @Column(name = "maps_to_model", nullable = false, length = 24)
  private String mapsToModel;

  @Column(name = "maps_to_tier", length = 48)
  private String mapsToTier;

  @Column(name = "temporary", nullable = false)
  private boolean temporary;

  @Column(name = "auto_expires_at")
  private Instant autoExpiresAt;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "decided_by_user_id")
  private UUID decidedByUserId;

  @Type(JsonType.class)
  @Column(name = "user_modification_json", columnDefinition = "jsonb")
  private DirectiveInstructionDocument userModificationJson;

  @Column(name = "rejection_reason", length = 255)
  private String rejectionReason;

  @Enumerated(EnumType.STRING)
  @Column(name = "safety_gate_verdict", length = 16)
  private SafetyGateVerdict safetyGateVerdict;

  @Type(JsonType.class)
  @Column(name = "safety_gate_findings", columnDefinition = "jsonb")
  private List<SafetyFindingDto> safetyGateFindings;

  @Version @Column(name = "optimistic_version", nullable = false)
  private long optimisticVersion;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
```

### `DirectiveSafetyGate` skeleton

```java
@Component
public class DirectiveSafetyGate {
  private static final Set<String> STAPLES = Set.of("water", "salt", "all protein", "all carbs", "all fats");

  public SafetyGateResult evaluate(DirectiveInstructionDocument effective,
                                    HealthDirective directive,
                                    NutritionTargets currentTargets) {
    List<SafetyFindingDto> findings = new ArrayList<>();
    // Rule 1 — favourite collision: no-op in 01e; pending preference-01c soft-prefs.
    // TODO(preference-01c): inject SoftPreferencesReader and check effective.target() against taste profile favourites.

    // Rule 2 — target adjustment bounds
    if (directive.getDirectiveType() == DirectiveType.TARGET_ADJUSTMENT
        && "adjust_target".equals(effective.action())) {
      BigDecimal proposedFloor = parseProposedFloor(effective);
      BigDecimal currentDaily = readCurrentDailyTarget(currentTargets, directive.getMapsToTier());
      BigDecimal currentFloor = readCurrentFloor(currentTargets, directive.getMapsToTier());
      if (currentDaily != null && proposedFloor.compareTo(currentDaily.multiply(BigDecimal.valueOf(1.2))) > 0) {
        findings.add(new SafetyFindingDto("target-raise-exceeds-20pct",
            "Proposed floor exceeds current daily target by more than 20%", "BLOCK"));
      }
      if (currentFloor != null && proposedFloor.compareTo(currentFloor.multiply(BigDecimal.valueOf(0.5))) < 0) {
        findings.add(new SafetyFindingDto("target-lower-below-50pct",
            "Proposed floor below 50% of current floor", "BLOCK"));
      }
    }

    // Rule 3 — macro rebalance bounds
    if (directive.getDirectiveType() == DirectiveType.MACRO_REBALANCE) {
      BigDecimal mealSum = computePostApplyMealSum(effective, currentTargets);
      BigDecimal dailyTarget = currentTargets.getCalorieTarget();
      if (dailyTarget != null && mealSum.subtract(dailyTarget).abs().compareTo(BigDecimal.valueOf(100)) > 0) {
        findings.add(new SafetyFindingDto("meal-sum-divergence-exceeds-100kcal",
            "Post-apply per-meal sum diverges from daily target by more than 100 kcal", "BLOCK"));
      }
    }

    // Rule 4 — staple restriction
    if (directive.getDirectiveType() == DirectiveType.INGREDIENT_RESTRICTION
        && effective.target() != null
        && STAPLES.contains(effective.target().toLowerCase())
        && !hasAlternative(effective)) {
      findings.add(new SafetyFindingDto("staple-restriction-no-alternative",
          "Restricting a staple ('" + effective.target() + "') without an alternative", "BLOCK"));
    }

    SafetyGateVerdict verdict;
    if (findings.stream().anyMatch(f -> "BLOCK".equals(f.severity()))) verdict = SafetyGateVerdict.BLOCKED;
    else if (findings.isEmpty())                                       verdict = SafetyGateVerdict.PASSED;
    else                                                               verdict = SafetyGateVerdict.PASSED_WITH_WARNINGS;
    return new SafetyGateResult(verdict, findings);
  }
}
```

### `DirectiveApplier` skeleton

```java
@Component
public class DirectiveApplier {
  private final NutritionTargetsRepository targetsRepo;
  private final NutritionTargetsAuditRepository auditRepo;
  private final ObjectProvider<DirectiveApplyTarget> preferenceTarget;
  // constructor omitted

  void apply(HealthDirective directive, DirectiveInstructionDocument effective, UUID actorUserId) {
    switch (directive.getMapsToModel()) {
      case "nutrition_model" -> applyToNutritionTargets(directive, effective, actorUserId);
      case "preference_model" -> preferenceTarget.getIfAvailable(NoopDirectiveApplyTarget::new)
          .applyPreferenceDirective(directive.getUserId(), effective,
              directive.isTemporary(), directive.getAutoExpiresAt(),
              directive.getId(), actorUserId);
      default -> throw new InvalidDirectiveRoutingException(directive.getMapsToModel());
    }
  }
}
```

## Edge-case checklist

- [ ] `POST /inbound` happy path → 201; row persisted with `status = PENDING_REVIEW`; `HealthDirectiveReceivedEvent` published
- [ ] `POST /inbound` re-delivery of same `(sourcePlatform, externalDirectiveId)` → 409 `duplicate-health-directive` with existing row's id + status in ProblemDetail
- [ ] `POST /inbound` validation: missing `externalDirectiveId` → 400; `temporary = true` with `autoExpiresAt = null` → 400; `@ValidDirectiveInstruction` rejects unknown action → 400
- [ ] `POST /{id}/accept` happy path for `nutrition_model` `TARGET_ADJUSTMENT` within bounds → 200; safety verdict `PASSED`; targets row updated; audit row written with `actor_kind = HEALTH_DIRECTIVE` + `source_directive_id`; directive `status = ACCEPTED`; `HealthDirectiveAcceptedEvent` published
- [ ] `POST /{id}/accept` with `userModification` → effective instruction = override; modified version re-validated; persisted in `userModificationJson`
- [ ] `POST /{id}/accept` rule 2 — proposed floor > 1.2 × daily → 422 `safety-gate-blocked` with finding `target-raise-exceeds-20pct`; directive STILL `PENDING_REVIEW`; verdict/findings persisted on row
- [ ] `POST /{id}/accept` rule 2 — proposed floor < 0.5 × current floor → 422 with finding `target-lower-below-50pct`
- [ ] `POST /{id}/accept` rule 3 — macro rebalance with > 100 kcal divergence → 422 with finding `meal-sum-divergence-exceeds-100kcal`
- [ ] `POST /{id}/accept` rule 4 — `INGREDIENT_RESTRICTION` of "water" without alternative → 422 with finding `staple-restriction-no-alternative`
- [ ] `POST /{id}/accept` rule 4 — `INGREDIENT_RESTRICTION` of "water" WITH alternative in `extras.alternative` → PASSED
- [ ] `POST /{id}/accept` rule 1 (favourite collision) — currently no-op; verify by setting up a directive that would have collided and asserting verdict = PASSED (with a TODO comment in the test pointing at preference-01c)
- [ ] `POST /{id}/accept` for `mapsToModel = preference_model` with `NoopDirectiveApplyTarget` → 422 `directive-apply-target-unavailable`
- [ ] `POST /{id}/accept` for unknown `mapsToModel` → 422 `invalid-directive-routing`
- [ ] `POST /{id}/accept` for already-decided directive → 409 `health-directive-already-decided`
- [ ] `POST /{id}/accept` stale `expectedVersion` → 409
- [ ] `POST /{id}/accept` for directive not owned by caller → 404 (don't leak)
- [ ] `POST /{id}/reject` happy path → 200; `status = REJECTED`; `rejectionReason` persisted; NO event
- [ ] `POST /{id}/reject` for already-decided → 409
- [ ] `GET /` returns only caller's directives; filters by `status` when set; paginated; sorted `received_at DESC`
- [ ] `GET /{id}` for other-user's directive → 404
- [ ] `HealthDirectiveDtoPage` flat shape validates against swagger-request-validator
- [ ] `instructionPayload` JSONB round-trips: persist a 3-phase `staged_protocol` → re-read → all fields intact
- [ ] `safetyGateFindings` JSONB round-trips: a directive with 2 findings re-reads with both findings intact
- [ ] Test-scoped `@TestConfiguration` providing a fake `DirectiveApplyTarget @Bean` overrides Noop; an IT verifies the preference-model path then succeeds
- [ ] `findByStatusAndAutoExpiresAtBefore` repository method works (no caller in 01e, but the method exists for auto-expiry sweep in a later ticket)
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in IT)
- [ ] `NutritionBoundaryTest` (from 01a) still passes — new repo in `domain/repository/`; new `spi/` and `spi/internal/` subpackages added to the allow-list if the rule whitelists
- [ ] `NutritionExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the new handler methods
- [ ] `nutrition_targets_audit` schema state confirmed (already exists or created by 01e); audit row written on every accept
- [ ] No N+1 — accept flow: 1 SELECT directive, 1 SELECT targets, 1 INSERT audit, 1 UPDATE directive (4 statements)

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601601200__nutrition_create_health_directives.sql
NEW   src/main/resources/db/migration/V20260601601201__nutrition_create_targets_audit.sql            (OPTIONAL — only if 01a hasn't shipped it; verify first)

NEW   src/main/java/com/example/mealprep/nutrition/api/controller/HealthDirectivesController.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/AcceptDirectiveRequest.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/DirectiveConfidence.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/DirectiveDurationDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/DirectiveInstructionDocument.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/DirectivePhaseDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/DirectiveStatus.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/DirectiveType.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/HealthDirectiveDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/InboundHealthDirectiveRequest.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/RejectDirectiveRequest.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/SafetyFindingDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/SafetyGateVerdict.java
NEW   src/main/java/com/example/mealprep/nutrition/api/mapper/HealthDirectiveMapper.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/HealthDirective.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/repository/HealthDirectiveRepository.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/service/internal/DirectiveSafetyGate.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/service/internal/DirectiveApplier.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/service/internal/SafetyGateResult.java       (record: verdict + findings)
NEW   src/main/java/com/example/mealprep/nutrition/spi/DirectiveApplyTarget.java
NEW   src/main/java/com/example/mealprep/nutrition/spi/internal/NoopDirectiveApplyTarget.java
NEW   src/main/java/com/example/mealprep/nutrition/event/HealthDirectiveReceivedEvent.java
NEW   src/main/java/com/example/mealprep/nutrition/event/HealthDirectiveAcceptedEvent.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/HealthDirectiveNotFoundException.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/HealthDirectiveAlreadyDecidedException.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/HealthDirectiveSafetyGateBlockedException.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/DuplicateHealthDirectiveException.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/InvalidDirectiveRoutingException.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/DirectiveApplyTargetUnavailableException.java
NEW   src/main/java/com/example/mealprep/nutrition/validation/ValidDirectiveInstruction.java          (custom annotation)
NEW   src/main/java/com/example/mealprep/nutrition/validation/DirectiveInstructionValidator.java       (ConstraintValidator impl)

MOD   src/main/java/com/example/mealprep/nutrition/api/NutritionExceptionHandler.java                  (append 6 @ExceptionHandler methods; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/NutritionQueryService.java          (append getDirectives, getDirective)
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/NutritionUpdateService.java         (append receiveInboundDirective, acceptHealthDirective, rejectHealthDirective)
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/internal/NutritionServiceImpl.java  (implement the five new methods; wire DirectiveSafetyGate + DirectiveApplier + HealthDirectiveRepository)

MOD   src/main/resources/openapi/paths/nutrition.yaml      (append 5 new path-items below 01d's; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/nutrition.yaml    (append 13 new schemas)
MOD   src/main/resources/openapi/openapi.yaml              (5 lines under paths: in the `# nutrition` block; 13 lines under components.schemas: in the `# nutrition` block)

NEW   src/test/java/com/example/mealprep/nutrition/DirectiveSafetyGateTest.java                       (each rule rejects matching shapes; rule 1 explicitly returns no findings with TODO note)
NEW   src/test/java/com/example/mealprep/nutrition/HealthDirectiveServiceTest.java                    (inbound idempotency, accept happy path, accept blocked, reject)
NEW   src/test/java/com/example/mealprep/nutrition/HealthDirectivesFlowIT.java                         (full HTTP: inbound 201 + idempotent 409; accept happy + 422 safety-block; reject 200; preference_model with Noop → 422)
MOD   src/test/java/com/example/mealprep/nutrition/testdata/NutritionTestData.java                    (append directive request + DTO builders)
```

**Files this ticket does NOT modify**:

- `config/GlobalExceptionHandler.java`; `archunit/ModuleBoundaryTest.java`.
- Other modules' files (preference, household, provisions, recipe) — **explicitly not touched**. The `DirectiveApplyTarget` SPI lives in nutrition; preference-01c will implement against it later.
- 01a's `NutritionTargets` entity — used as-is; the apply path reuses the existing internal `updateTargets` helper.
- 01a/01b/01c/01d's tests — none modified; only `NutritionTestData.java` gets new builder methods.
- `NutritionBoundaryTest` — verify the rule permits the new `spi/` and `spi/internal/` subpackages; if it whitelists, append them. **Document the choice in the report.**

## Dependencies

- **Hard dependency**: `nutrition-01a` (merged) — `NutritionTargets` aggregate + internal `updateTargets` helper (the `DirectiveApplier` reuses it), `NutritionTargetsAuditLog` entity + repo + `nutrition_targets_audit` table (verify shipped — see Database section), `NutritionTargetsChangedEvent`, `NutritionActorKind` enum (`HEALTH_DIRECTIVE` value), `NutritionQueryService`, `NutritionUpdateService`, `NutritionExceptionHandler`, `NutritionBoundaryTest`, `NutritionException`, hypersistence JSONB plumbing.
- **Hard dependency**: `nutrition-01b` (merged) — extends the same two service interfaces; the per-module YAML / advice append-only convention.
- **Hard dependency**: `nutrition-01c` (merged) — same as above; per-module event package convention.
- **Hard dependency**: `nutrition-01d` (merged) — `NutritionExceptionHandler` already has appended methods; `paths/nutrition.yaml` and `schemas/nutrition.yaml` already have appended blocks; 01e appends below.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **No hard dependency on preference module** — 01e ships independently. The SPI's `NoopDirectiveApplyTarget` returns 422 cleanly.
- **Sibling tickets running in parallel** (Wave 2 round 5): `household-01e`, `provisions-01e`, `recipe-01e`. Only collision is the entry `openapi.yaml` (per-module blocks).

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `NutritionExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the six new methods
- [ ] OpenAPI 3.0 nullable fields use **inline** `nullable: true` (NOT `$ref + nullable: true`)
- [ ] `HealthDirectiveDtoPage` uses the **flat** Page<T> shape with `additionalProperties: true`
- [ ] All YAML description strings containing `,` `:` `'` are single-quoted (round-4 lesson)
- [ ] JSONB round-trip for `instructionPayload`, `userModificationJson`, `safetyGateFindings` — write entity, re-read, equality (including null branches)
- [ ] Idempotent inbound — re-delivery returns 409 with existing row's id + status in ProblemDetail
- [ ] Safety gate is deterministic — same input twice → same output
- [ ] `NoopDirectiveApplyTarget` autowires when no other bean exists; a test-scoped `@TestConfiguration` impl wins
- [ ] Audit row written on every accept that proceeds past the safety gate
- [ ] No regression on existing tests, including 01d's `IngredientLookupControllerIT`, 01c's `FoodMoodJournalFlowIT`
- [ ] No pom.xml dependency adds
- [ ] No file outside the nutrition module touched (incl. no preference / household / provisions / recipe file)

## What's NOT in scope

- **Real `preference_model` directive route** — `preference-01c` wires `DirectiveApplyTarget`. Until then, 422 with clear message.
- Rule 1 of the safety gate (favourite collision) — ships as no-op; needs preference soft-prefs.
- `@Scheduled` auto-expiry sweep (LLD line 1022) — **nutrition-01f or later**.
- `applyFeedback(NutritionFeedbackRequest)` directive sub-flow (LLD line 1034 — proposal-not-write) → **nutrition-01m**.
- Notification fan-out on `HealthDirectiveReceivedEvent` — owned by **notification module** when it lands; 01e publishes the event but no listener.
- AI-augmented safety gate — none planned; LLD line 1008 mandates deterministic.
- Admin role gate on `GET /` / `GET /{id}` — open to any authenticated user (their own only); LLD doesn't specify clinician-role read access.
- `HealthDirectiveRejectedEvent` — LLD §Events doesn't declare it; v1 omits.
- Relocation of `DirectiveApplyTarget` SPI to `core/` — flagged worth user review.

Squash-merge with: `feat(nutrition): 01e — health directives queue + inbound / accept / reject + DirectiveSafetyGate + DirectiveApplier + 6 exceptions`
