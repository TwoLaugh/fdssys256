# AUDIT — MEDIUM findings (72)

Grouped by module. Source: design/audits/2026-05-29-v1-backend-conformance-audit.md

## adaptation

- **adaptation-3** [FIX] — Trigger payloads never persisted to job.inputs, so all source-bias logic (ratingDelta, directive, feedbackText) is dead at runtime
  - _where:_ src/main/java/com/example/mealprep/adaptation/domain/service/AdaptationServiceImpl.java:313,359,427 (emptyInputs()), 832-846 (triggerInputsFromJob)
  - _fix:_ Serialize the trigger request payload into AdaptationJob.inputs at enqueue (ratingDelta+feedbackText for FEEDBACK, directive+constraints for PLAN_TIME, changeSummary for DATA_MODEL_CHANGE), and have triggerInputsFromJob deserialize them. Add a flow IT asserting a 'too salty' feedback biases the candidate set / change_dimension and a COST_DELTA directive narrows Stage A.
- **adaptation-4** [FIX] — RebaseOrchestrator is fully implemented but never injected or invoked — WriteApi conflict-retry / REBASE_EXHAUSTED path is dead
  - _where:_ src/main/java/com/example/mealprep/adaptation/domain/service/internal/RebaseOrchestrator.java (whole class)
  - _fix:_ Wire RebaseOrchestrator into the DIRECT apply path (finding adaptation-1) and into acceptPendingChange's saveAdaptedVersion call so RecipeVersionConflictException is retried/rebased rather than surfacing raw; verify the REBASE_EXHAUSTED terminal path with an IT.
- **adaptation-5** [FIX] — acceptPendingChange always writes a VERSION, ignoring a pending change classified as BRANCH
  - _where:_ src/main/java/com/example/mealprep/adaptation/domain/service/AdaptationServiceImpl.java:481-496
  - _fix:_ Switch on pc.getProposedClassification() in acceptPendingChange: BRANCH → saveAdaptedBranch (+ FingerprintRefresher), VERSION → saveAdaptedVersion; route both through RebaseOrchestrator.
- **adaptation-6** [FIX] — PendingChangeStore hard-codes impactScore = 0.5, defeating the impact-ranked 3-per-week budget
  - _where:_ src/main/java/com/example/mealprep/adaptation/domain/service/internal/PendingChangeStore.java:122,144
  - _fix:_ Derive impactScore from the chosen candidate's rollup (e.g. normalised magnitude of macro/cost/time deltas × confidence) rather than a literal 0.5, so the ranking pool actually orders by impact.
- **adaptation-7** [FIX] — No per-trigger flow IT covering the apply paths; LLD test-plan integration tests are largely absent
  - _where:_ src/test/java/com/example/mealprep/adaptation/ (test suite)
  - _fix:_ Add the LLD's missing flow ITs against the real catalogue, asserting: SYSTEM import → version written; Trigger 4 COST_DELTA → recipe_substitutions row; rating-delta-biased dimension; conflict→rebase→REBASE_EXHAUSTED.

## ai

- **ai-4** [DESCOPE?] — Per-task token cap (and the Stage-C 'can't shove the pool in' guard) is absent
  - _where:_ lld/ai.md Flow 1 step 4 + Flow 6
  - _fix:_ Add per-task input token caps and the pre-dispatch check, or document that the Stage-C context-shape safeguard is now purely a calling-module responsibility with no AI-module enforcement.
- **ai-5** [DESCOPE?] — API key rotation audit (table, entity, startup detector) entirely absent
  - _where:_ lld/ai.md §V20260501110200, §Flow 7, §Decisions 6
  - _fix:_ Implement the rotation-log audit per the LLD, or move it to Out-of-Scope in lld/ai.md if it is intentionally deferred past v1.
- **ai-6** [FIX] — Cost-cap model diverged: single rolling-window hard block vs. LLD's soft-daily + hard-monthly two scopes
  - _where:_ src/main/java/com/example/mealprep/ai/domain/service/internal/CostBudgetGuard.java:60-86
  - _fix:_ Reconcile: implement the soft-daily / hard-monthly split and the MONTHLY_TOTAL system scope, or update lld/ai.md to the actual single-hard-window model. Note the exception+status also diverged (AiCostBudgetExceeded/429 vs AiUnavailable/503).
- **ai-7** [DOC-ONLY] — lld/ai.md describes a package/SPI/DTO surface that does not match the shipped module
  - _where:_ lld/ai.md §Package Layout, §SPI, §DTOs, §Service Interfaces, §REST Controllers vs src/main/java/com/example/mealprep/ai/**
  - _fix:_ Do a single reconciliation pass on lld/ai.md (or add a 'shipped vs designed / deferred' delta section) so the authoritative LLD reflects the 01a/01b/01d subset that actually shipped — otherwise every future reader re-discovers this divergence.
- **ai-8** [DOC-ONLY] — PromptTemplateLoader javadoc + recipe-adaptation.txt imply a render path/format that doesn't match the loader
  - _where:_ src/main/java/com/example/mealprep/ai/domain/service/internal/PromptTemplateLoader.java:36-48 + normalisePattern:164-171
  - _fix:_ Decide the single source of prompt truth (md wiring docs vs per-module .txt), make the loader glob match it, and fix the loader javadoc reference to the non-existent getSystemPrompt(). If Handlebars-style prompts are intended, the renderer needs #if support (the LLD notes handlebars=false for v1).

## auth

- **auth-1** [FIX] — Lockout counter never reset on lockout → account re-locks on first failed attempt after window expiry
  - _where:_ src/main/java/com/example/mealprep/auth/domain/service/internal/AuthServiceImpl.java:242-257
  - _fix:_ Reset failedLoginCount to 0 when setting lockedUntil (as the LLD specifies), OR clear failedLoginCount when the lockout window has expired before evaluating the next attempt. Add an IT that drives a failure after lockedUntil has passed and asserts the account is not immediately re-locked and that a correct password succeeds.
- **auth-2** [FIX] — Password-change wrong-current-password does not record a LoginAttempt (escapes the throttle window)
  - _where:_ src/main/java/com/example/mealprep/auth/domain/service/internal/AuthServiceImpl.java:332-334
  - _fix:_ Record a BAD_PASSWORD LoginAttempt on wrong current-password in changePassword and ensure it commits (e.g. record in a REQUIRES_NEW tx or use noRollbackFor like login does), so PUT /password shares the login throttle surface as the LLD intends.
- **auth-3** [DESCOPE?] — Session reaper (Flow 6) is not implemented — deleteExpiredAndRevokedBefore is dead code
  - _where:_ src/main/java/com/example/mealprep/auth/domain/repository/SessionRepository.java:38-43 (query defined but unused)
  - _fix:_ Either implement the scheduled reaper + SessionReaperIT and add retain-revoked-for to AuthProperties, or, if deferred, move Flow 6 to the LLD 'Out of Scope' list and remove the unused repository query + stale 'Reaper job' index comment to avoid implying a capability that isn't shipped.
- **auth-4** [DESCOPE?] — Username reserved-name list and start/end-separator rule not enforced; pattern narrower than LLD
  - _where:_ src/main/java/com/example/mealprep/auth/validation/ValidUsernameValidator.java:10
  - _fix:_ Implement the reserved-name check (configurable list in AuthProperties) inside ValidUsernameValidator or service-side, and add a test. Reconcile the dot/length divergence by updating lld/auth.md to match the agreed OpenAPI contract (or restore the dot/64 if intended).
- **auth-5** [FIX] — Service-side weak-password rejection throws raw IllegalArgumentException → generic 400 leaking reason names, not the specified reasons[] shape
  - _where:_ src/main/java/com/example/mealprep/auth/domain/service/internal/AuthServiceImpl.java:155-157 and :339-341
  - _fix:_ Introduce WeakPasswordException carrying the List<Reason>, map it in AuthExceptionHandler to 400 with a reasons[] extension (codes only, no raw password echo), and throw it from both register and changePassword instead of IllegalArgumentException.

## core

- **core-1** [DESCOPE?] — Trace-ID propagation (TraceIdFilter + TraceContext) — an entire in-scope LLD section — is unimplemented
  - _where:_ lld/core.md §Trace ID Propagation (lines 402-428) and §Business Logic Flows Flow 4 (lines 468-477)
  - _fix:_ Either implement TraceIdFilter (MDC seed + response echo + finally cleanup) and TraceContext as the LLD specifies, or — if the project has deliberately deferred MDC trace propagation — strike §Trace ID Propagation, Flow 4, the package-layout entries, and the TraceContextTest/TraceIdFilterIT rows from lld/core.md so the design no longer advertises a capability the system lacks.
- **core-3** [FIX] — Decision-log write dropped its idempotency contract and the parent-existence check
  - _where:_ src/main/java/com/example/mealprep/core/audit/domain/service/internal/DecisionLogServiceImpl.java:39-65 vs lld/core.md §Flow 1 (l.434-444) and §Concurrency table (l.490)
  - _fix:_ Decide explicitly: if caller-supplied-id idempotency is wanted (LLD intent), restore the decisionId field + findById short-circuit; if service-generated ids are the accepted design (ticket intent), update the LLD §Flow 1 and §Concurrency idempotency row, and confirm with the planner team that decision-log retries producing duplicate rows is acceptable.
- **core-4** [FIX] — DecisionLogService.write uses REQUIRES_NEW; the LLD mandates REQUIRED (join caller's tx, roll back together)
  - _where:_ src/main/java/com/example/mealprep/core/audit/domain/service/internal/DecisionLogServiceImpl.java:40 vs lld/core.md §Service Interfaces (l.252) and §Concurrency table (l.487)
  - _fix:_ Pick the intended semantic and make the LLD and code agree. If REQUIRES_NEW is correct (audit-survives-rollback, matching ai.md), fix the two LLD statements that say REQUIRED; if REQUIRED is correct, change the annotation. They currently contradict on a transaction-boundary decision.
- **core-5** [DESCOPE?] — DecisionLogTokenBudgetGuard (64 KB payload cap) and its 422 mapping are absent
  - _where:_ lld/core.md §Validation (l.343-347), §Flow 1 step 2 (l.439), §Error responses (l.331)
  - _fix:_ If the 64 KB cap is still desired, implement the guard + exception + 422 handler mapping. Otherwise remove §Validation budget bullets, the Flow-1 guard step, and the DecisionLogPayloadOversizedException row from the LLD error table so the design doesn't promise a guardrail the audit log doesn't have.
- **core-6** [FIX] — LockService interface diverges from LLD: no acquire()/LockHandle/LockAcquisitionException; single boolean tryAcquire
  - _where:_ src/main/java/com/example/mealprep/core/lock/LockService.java:22-33 + LockKey.java vs lld/core.md §LockService (l.278-301) and §Error responses (l.332)
  - _fix:_ Update lld/core.md §LockService + the LockAcquisitionException/409 error-table row to match the shipped boolean-tryAcquire shape (the simpler shape is arguably better and is what every caller — planner, adaptation — uses). If the throwing acquire()/409 path was genuinely wanted for user-facing single-flight, it is missing and should be added.

## discovery

- **discovery-3** [FIX] — AI candidate filter diverges from LLD's v1 pass-through + skip-and-flag failure contract
  - _where:_ src/main/java/com/example/mealprep/discovery/domain/service/internal/AiCandidateAiFilter.java:40-83
  - _fix:_ Either update the LLD §Failure Modes to reflect 'real AI filter, per-candidate drop on outage', or change AiCandidateAiFilter so that an AI dispatch failure passes the candidate THROUGH (keep) to honour the skip-and-flag contract. Confirm which is intended with the design owner before the v1 test pass.
- **discovery-4** [FIX] — AI-filter rejections are never logged as AI_FILTER_REJECTED scrape rows (silent drop, no audit)
  - _where:_ src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryJobRunner.java:426-441
  - _fix:_ Have the runner compute the dropped set (candidates present pre-filter, absent post-filter) and write one SKIPPED/AI_FILTER_REJECTED scrape row per dropped candidate, or surface a rejected-list from CandidateAiFilter so the runner can log it. Then the AI_FILTER_REJECTED enum value becomes live and observability matches the design.
- **discovery-5** [FIX] — Search and fetch phases run fully sequentially despite LLD specifying parallel-across-sources
  - _where:_ src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryJobRunner.java:347-415 (searchPhase), 445-700 (fetchPhase)
  - _fix:_ Either implement cross-source parallelism within search/fetch (bounded by the existing pool) per the LLD, or annotate the LLD §Concurrency / Flow 2 that v1 ships single-threaded per job by design (acceptable at 2 sources). Make the doc and code agree before the v1 test pass.
- **discovery-6** [FIX] — cancelJob QUEUED branch issues an unguarded UPDATE that can clobber a job the runner just claimed RUNNING
  - _where:_ src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryServiceImpl.java:195-205
  - _fix:_ Add a status guard to the cancellation UPDATE (e.g. `... WHERE j.id = :id AND j.status = 'QUEUED'`) and treat rows==0 as 'already claimed/terminal' — fall through to the RUNNING cancellation-flag path instead of throwing NotFound. This makes the QUEUED→FAILED flip genuinely atomic against the runner's claim.

## feedback

- **feedback-1** [FIX] — overallConfidence forced @NotNull contradicts LLD 'optional aggregate the classifier MAY emit'
  - _where:_ src/main/java/com/example/mealprep/feedback/api/dto/ClassificationResult.java:21 and ToolDefinitions.java:84-86
  - _fix:_ Drop @NotNull on overallConfidence and remove it from the tool schema's `required` list to match the LLD's 'optional' contract, or update the LLD/prompt spec to mandate the field. Confirm the prompt-engineering template always emits it before relying on the current strictness.
- **feedback-2** [FIX] — Correction-to-RECIPE precondition accepts recipeId from original payload, but replay synthetic only copies recipeId from uiContext
  - _where:_ src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java:274-284 (validatePreconditions) vs internal/CorrectionReplayer.java:40-52 (buildSynthetic)
  - _fix:_ In buildSynthetic, also lift recipeId (and any other useful structured fields) from the original RoutingLogEntry.structuredPayload into the synthetic payload, so the precondition and the replay agree on where the recipeId can come from.
- **feedback-4** [DOC-ONLY] — FeedbackServiceImpl class javadoc still describes a half-built 01b skeleton with UnsupportedOperationException stubs
  - _where:_ src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java:70-72
  - _fix:_ Rewrite the class javadoc to reflect the shipped reality (all query + update methods implemented, scheduled sweeps wired) and drop the UnsupportedOperationException claim.
- **feedback-6** [FIX-OR-ACCEPT] — Preference-delta AI pipeline (delta computation, AI task, budget orchestration) lives inside the feedback module package
  - _where:_ src/main/java/com/example/mealprep/feedback/ai/** (PreferenceDeltaBatchTrigger, TasteProfileDeltaOrchestrator, PreferenceTasteProfileDeltaTask, AiToApplyDeltaMapper, PreferenceDeltaCursorService, etc.)
  - _fix:_ Document this cross-cutting placement in lld/feedback.md (it is currently undescribed there) and reconcile it with the HLD boundary statement, or relocate the delta-computation pipeline behind the preference module's own seam if the boundary is intended to be strict. At minimum, flag for user review that 'feedback delivers the signal only' no longer holds for the PREFERENCE destination.

## grocery

- **grocery-1** [FIX] — Tier-3 order reconciliation never updates the source shopping_list_lines (fulfilment_status / bought_via=ORDER / grocery_order_id)
  - _where:_ src/main/java/com/example/mealprep/grocery/domain/service/internal/OrderReconciler.java (reconcileInternal, deliveredLines, writePaidObservation ~lines 114-230)
  - _fix:_ In OrderReconciler (or markUserConfirmed/reconcile path), load the ShoppingListLines referenced by the delivered/substituted order lines and set fulfilment_status (BOUGHT or SUBSTITUTED, DROPPED on rejected substitution), bought_via=ORDER, bought_* fields, and grocery_order_id, bumping the parent list @Version. Add an IT covering 'order reconciled → source list lines marked bought via ORDER'.
- **grocery-2** [DESCOPE?] — refreshOnDemand(useProviderQuote=true) is a permanent stub — never quotes the provider despite the provider SPI (01e) having shipped
  - _where:_ src/main/java/com/example/mealprep/grocery/domain/service/internal/GroceryServiceImpl.java refreshOnDemand (lines 842-855)
  - _fix:_ Wire the on-demand provider-quote leg now that 01e exists: when useProviderQuote=true and an enabled provider state exists, assemble a 1-pack-per-key BasketDraft, call provider.quote, write QUOTE observations (reuse quoteAndWriteObservations), and let AiUnavailableException surface as 503 with aiUnavailableFallbackUsed=true. Update the test to assert observations are written / 503 on AI-unavailable.
- **grocery-3** [DOC-ONLY] — GroceryServiceImpl class javadoc still describes it as a skeleton throwing UnsupportedOperationException
  - _where:_ src/main/java/com/example/mealprep/grocery/domain/service/internal/GroceryServiceImpl.java class javadoc (lines 73-90)
  - _fix:_ Rewrite the class javadoc to describe the shipped behaviour (Tier 1/2/4 impl; sibling GroceryOrderServiceImpl for Tier 3 due to the erasure clash) and drop the 'ZERO behaviour / throws UnsupportedOperationException' paragraph.
- **grocery-4** [FIX] — placeOrder always auto-advances to AWAITING_USER_CONFIRMATION; the LLD 'delivery slot fails → pause at PLACED' failure mode is not implemented
  - _where:_ src/main/java/com/example/mealprep/grocery/domain/service/internal/GroceryOrderServiceImpl.java placeOrder (lines 378-387)
  - _fix:_ Either model a slot-missing signal on PlaceOrderResult and keep the order at PLACED when no slot is secured (matching the LLD), or update the LLD/HLD to record that v1 always auto-advances and the user resolves the slot in the provider UI from the AWAITING_USER_CONFIRMATION state.
- **grocery-5** [FIX] — Single-flight lock diverges from the LLD's tryAcquire(scope, ttl) contract and builds a collision-prone scope id
  - _where:_ src/main/java/com/example/mealprep/grocery/domain/service/internal/GroceryOrderServiceImpl.java acquireSingleFlight (lines 697-705)
  - _fix:_ Derive the lock scope from a hash over both full UUIDs (e.g. UUID.nameUUIDFromBytes of userId+shoppingListId) rather than splicing MSB/LSB, and either honour singleFlightLockTtlSeconds via a ttl-bearing tryAcquire overload or drop the unused config field and note the lock is xact-scoped.
- **grocery-6** [DOC-ONLY] — LLD Tier 4 / Flow 5 still describe time-decay + Bayesian priorStrength + InflationIndexer as the price-aggregation model, but v1 ships none of them
  - _where:_ lld/grocery.md Flow 5 (lines 926-939) & design/grocery.md Tier 4 (lines 275-290) vs src/main/java/com/example/mealprep/grocery/domain/service/internal/PriceAggregator.java (lines 17-67)
  - _fix:_ Add a 'v1 cut' note to lld/grocery.md Flow 5 and the Tier 4 HLD section documenting that v1 ships the simple source-weighted aggregator + ReferencePriceSource cold-start, and that decay/Bayesian/InflationIndexer are deferred to a v2 follow-up (mirroring ticket 01c), so the LLD stops implying machinery that does not exist.

## household

- **household-1** [FIX] — Merge service skips LLD-mandated eater-membership validation for supplied eaterUserIds
  - _where:_ src/main/java/com/example/mealprep/household/domain/service/internal/HouseholdServiceImpl.java:840-851 (mergeSoftPreferencesForSlot)
  - _fix:_ In mergeSoftPreferencesForSlot, after resolving members, validate that every supplied eaterUserId is in the household's member set and throw HouseholdMemberNotFoundException otherwise, per LLD Flow 7 step 1. This guards the planner's actual call path, not just the debug REST seam.
- **household-2** [DESCOPE?] — Custom validators @ValidSlotKey / @ValidHeadcount never implemented; custom slot keys and headcounts unconstrained
  - _where:_ src/main/java/com/example/mealprep/household/ (no validation/ package)
  - _fix:_ Implement the two custom validators per the LLD (or, if descoped, update the LLD to remove them from in-scope validation). At minimum add a numeric bound on headcount and a collision check on custom slot keys, since both feed the planner's slot composition.
- **household-3** [FIX-OR-ACCEPT] — Default settings time budgets are a flat 30 for all slots, ignoring planner HLD per-kind defaults
  - _where:_ src/main/java/com/example/mealprep/household/domain/service/internal/HouseholdServiceImpl.java:989-992 (buildDefaultSettings)
  - _fix:_ Seed per-kind time budgets (breakfast 15, lunch 20, dinner 45, snack 5) per the planner HLD, or document an explicit decision in the LLD overriding those values for v1.
- **household-4** [FIX] — Invite-accept publishes HouseholdInviteAcceptedEvent, not the LLD-specified HouseholdMemberAddedEvent
  - _where:_ src/main/java/com/example/mealprep/household/domain/service/internal/HouseholdServiceImpl.java:516-523 (acceptInvite)
  - _fix:_ Either emit HouseholdMemberAddedEvent on accept (matching the LLD), or update the LLD §Events and Flow 3 to record the deliberate split and ensure downstream consumers subscribe to both event types.
- **household-5** [FIX] — REST surface uses /households/current/... and /invites/accept, diverging from the LLD's /households/{id}/... contract
  - _where:_ HouseholdMembersController.java:58-108
  - _fix:_ Pick one URL convention and apply it consistently, then update the LLD REST table to match the shipped + OpenAPI surface (including the now-exposed merge and planner-view endpoints). The contract has test coverage via the *IT swagger-validator tests, so this is primarily a doc-vs-design reconciliation.
- **household-6** [DOC-ONLY] — LLD no longer matches shipped reality across REST table, events, repositories, query-service methods, and MergedSoftPreferencesDto
  - _where:_ lld/household.md:165-353 (DTOs/REST/events), 232-253 (repositories), 266-285 (HouseholdQueryService)
  - _fix:_ Refresh lld/household.md to describe the methods, repository finders, event set, URL scheme, and DTO record-reuse strategy that actually shipped, so the LLD is a reliable baseline for the v1 test pass and future work.
- **household-7** [FIX-OR-ACCEPT] — Planner never reacts to household membership/role changes despite LLD intent and a filter built to match them
  - _where:_ src/main/java/com/example/mealprep/planner/.../listeners/PlannerEventListener.java:230-260
  - _fix:_ Decide ownership: either the planner adds listeners for the member events (and the household module emits HouseholdMemberAddedEvent on the invite-accept path per finding household-4), or the LLD explicitly descopes member-driven re-opt for v1. The dead 'members' prefixes in HouseholdMaterialityFilter should be removed or wired to a real source.

## notification

- **notification-2** [DOC-ONLY] — Authoritative LLD omits the entire scanner subsystem, two NotificationKinds, the feedback listener, and the dispatcher facade that shipped
  - _where:_ lld/notification.md (§Package Layout lines 15-40, §Notification Kinds lines 140-151, §Events Consumed table lines 437-451, §Service Interfaces lines 360-371)
  - _fix:_ Update lld/notification.md: add a Scanners section (or reference 01b/01c), extend the NotificationKind list and the consumed-event table with STAPLE_REPLENISHMENT_NEEDED and FEEDBACK_CONFIRMATION, document the FeedbackEventListener gate, and note the NotificationDispatcherFacade seam. Alternatively add a 'superseded by tickets X/Y' banner pointing at the tickets.

## nutrition

- **nutrition-3** [DESCOPE?] — Directive auto-expiry sweep (@Scheduled) is not implemented
  - _where:_ src/main/java/com/example/mealprep/nutrition (no @Scheduled anywhere)
  - _fix:_ Implement the scheduled sweep that transitions expired ACCEPTED directives to EXPIRED and instructs the source module to revert temporary effects, or formally re-scope auto-expiry out of v1 in the HLD/LLD.
- **nutrition-4** [FIX] — Floor gate uses floorG-presence instead of the LLD's per-target isHardFloor flag
  - _where:_ NutritionServiceImpl.java:2400-2435 (collectMacroFloors/collectMicroFloors)
  - _fix:_ Add the is_hard_floor column + DTO field per the LLD (macros default true, micros default false, user-overridable), and key collectMacroFloors/collectMicroFloors off it; or update the LLD to record the floorG-presence convention as the agreed v1 simplification and drop the isHardFloor references.
- **nutrition-5** [DESCOPE?] — getDailyAggregate query method and GET /{date}/aggregate endpoint are absent
  - _where:_ NutritionQueryService.java (no getDailyAggregate / getRecentIntakeTotals)
  - _fix:_ Expose getDailyAggregate (and the GET /{date}/aggregate endpoint) and getRecentIntakeTotals on NutritionQueryService, or amend the LLD to remove them and document how the planner obtains recent intake totals instead.
- **nutrition-6** [FIX] — Daily/weekly 'remaining' computed from planned, not the user's target, and not floored at zero
  - _where:_ IntakeAggregator.java:114-122, 163-167 (macroAgg / remaining)
  - _fix:_ Decide the intended semantic. If the LLD's target-based, zero-floored remaining is desired, load targets and compute remaining = max(0, target - actualSoFar). If the planned-based semantic is the v1 decision, update the LLD/HLD daily_totals definition to match.

## planner

- **planner-10** [DESCOPE?] — Re-opt suggestion expiry sweep (PENDING→EXPIRED) not implemented
  - _where:_ src/main/java/com/example/mealprep/planner/domain/repository/MealPrepPlanReoptSuggestionRepository.java:35 (sweep query exists, no scheduler)
  - _fix:_ Add the daily @Scheduled expiry sweep, and reconcile the suggestion TTL (24h in code vs weekStartDate+7d in LLD).
- **planner-11** [FIX-OR-ACCEPT] — Stage D refine-directive routing is effectively dead — Phase 2 always emits an empty directive list
  - _where:_ src/main/java/com/example/mealprep/planner/domain/service/internal/stagec/Phase2AugmenterImpl.java:142-187 (parseRefineDirectives / isRefineDirectiveDtoOnClasspath)
  - _fix:_ Reconcile the cross-module RefineDirectiveDto contract so Phase 2 can emit real directives, or explicitly mark Stage D as v1-deferred in the LLD. Note the composer also bounds by maxRefineDirectives but never enforces the 3-cycle iterationBudget (no fixed-point/cycle loop exists).
- **planner-3** [DESCOPE?] — Weekly PlanCompleted sweep not implemented — COMPLETED state is unreachable
  - _where:_ src/main/java/com/example/mealprep/planner/ (no @Scheduled anywhere)
  - _fix:_ Implement the @Scheduled Monday sweep that loads prior-week ACTIVE plans, checks all slots are EATEN/SKIPPED, transitions them to COMPLETED via PlanStateMachine, and publishes PlanCompletedEvent. The PlanCompletedEvent.java javadoc already promises this ('Published when the weekly sweep transitions a finished ACTIVE plan to COMPLETED ... 01k alongside the @Scheduled sweep').
- **planner-4** [FIX] — Cold-start catalogue threshold uses distinct slot-KINDS, not slot COUNT (off by ~7x)
  - _where:_ src/main/java/com/example/mealprep/planner/domain/service/internal/composer/ColdStartGate.java:142-145 (threshold)
  - _fix:_ Change the threshold to multiply by the slot COUNT (ctx.slotSkeletons().size()) per the HLD heuristic, or get explicit user sign-off that 'per distinct kind' is the intended v1 sizing. Update the misleading javadoc either way.
- **planner-6** [DESCOPE?] — Constraint feasibility check + checkFeasibility query + GET /feasibility never implemented
  - _where:_ src/main/java/com/example/mealprep/planner/domain/service/PlanQueryService.java:18-19 (javadoc deferral)
  - _fix:_ Implement ConstraintFeasibilityCheck (pool-size + conflict classification + resolution ranking) and the checkFeasibility query + GET /feasibility endpoint, or explicitly descope it for v1 in the LLD so the unmet HLD capability is tracked rather than silently dropped.
- **planner-7** [FIX] — Slot-state write does not force-increment the parent Plan.version, so concurrent re-opt cannot detect mid-flight slot changes
  - _where:_ src/main/java/com/example/mealprep/planner/domain/service/internal/lifecycle/PlanWriteServiceImpl.java:229-241 (changeSlotState)
  - _fix:_ In changeSlotState, load the parent Plan and call entityManager.lock(plan, LockModeType.OPTIMISTIC_FORCE_INCREMENT) (or save the touched parent) so the version bump fires per the LLD; or, if the guard is deliberately dropped for v1, correct the Plan entity javadoc (planner-8 below).
- **planner-9** [FIX] — Double-active accept surfaces as a 500, not the LLD's 409 — no DataIntegrityViolationException handler
  - _where:_ src/main/java/com/example/mealprep/planner/domain/service/internal/lifecycle/PlanWriteServiceImpl.java:116-134 (acceptPlan)
  - _fix:_ Add a DataIntegrityViolationException → 409 handler (planner or global), and/or have acceptPlan supersede any existing ACTIVE plan for the same (household, week) inside its transaction so the conflict is handled gracefully per the LLD.

## preference

- **preference-2** [FIX] — Hard-constraint filter never produces AMBIGUOUS results; the LLD makes ambiguity-flagging the default
  - _where:_ src/main/java/com/example/mealprep/preference/domain/service/internal/HardConstraintFilterServiceImpl.java:269-341
  - _fix:_ Decide whether ambiguity flagging is in scope for v1. If yes, add an AMBIGUOUS violation kind/flag and the decision logic for the under-determined cases (e.g. allergen-derivative uncertainty, exception-without-tag). If it was intentionally dropped, update lld/preference.md Flow 2 and the DTO/error tables so the design no longer claims AMBIGUOUS is the default.
- **preference-3** [FIX] — Dietary-identity exceptions widen the base diet regardless of context; LLD requires context-matching
  - _where:_ src/main/java/com/example/mealprep/preference/domain/service/internal/HardConstraintFilterServiceImpl.java:190-195,297-302,331-333
  - _fix:_ If context-conditional exceptions are a v1 requirement, add a call-context parameter to the filter API and evaluate exception.context against it (the column and DTO field already exist). Otherwise update lld/preference.md Flow 2 step 5 to state that exceptions widen the base unconditionally at filter time and that per-context enforcement is the planner's responsibility.
- **preference-4** [DESCOPE?] — @ValidDietaryIdentity custom validator specified by the LLD is never implemented or applied
  - _where:_ src/main/java/com/example/mealprep/preference/validation/ (only ValidNoveltyTolerance + NoveltyToleranceValidator exist)
  - _fix:_ Implement @ValidDietaryIdentity + validator and apply it to DietaryIdentityDto/UpdateHardConstraintsRequest, or descope it explicitly and remove the LLD requirement and the 'will be added' comments. At minimum add the allergy/intolerance collision check, which the LLD frames as a safety guard.
- **preference-5** [DESCOPE?] — Taste-vector embedding pipeline deferred despite LLD presenting it as locked/in-scope
  - _where:_ src/main/java/com/example/mealprep/preference/domain/entity/TasteProfile.java:36-39,77-88
  - _fix:_ Reconcile the design and code: update lld/preference.md to mark the embedding pipeline (Flow 3 step 10, the vector column/index) as deferred to a named follow-up, or implement the listener + column if 'recommendations' / taste-vector planner scoring is required for v1.

## provisions

- **provisions-1** [DESCOPE?] — Batch-cook fridge/freezer split is a throwing stub, not implemented
  - _where:_ src/main/java/com/example/mealprep/provisions/domain/service/internal/ProvisionServiceImpl.java:1169-1171
  - _fix:_ Either implement BatchCookSplitter for v1 (the HLD/LLD treat batch cook as in-scope), or, if the deferral to 01j is accepted, update lld/provisions.md Flow 1 step 4 + the test plan to mark batch-cook splitting out-of-scope for v1 so the design and shipped reality agree.
- **provisions-2** [DESCOPE?] — pantry_tracking_enabled gating is not implemented anywhere in the module
  - _where:_ src/main/java/com/example/mealprep/provisions/ (no PreferenceQueryService reference)
  - _fix:_ Implement the flag read (inject PreferenceQueryService) and gate getBundle's inventory list, the cook/grocery write paths, and the staples read as the LLD specifies — or, if intentionally deferred, record that deferral in the LLD and the capability inventory so the 'optional pantry tracking' HLD promise isn't silently unmet.
- **provisions-4** [DOC-ONLY] — LLD Flow 8 still claims the @Scheduled expiry sweep lives in provisions; it was moved to notification
  - _where:_ lld/provisions.md:586,672-674,724 (Flow 8 + ExpirySweepIT) vs src/main/java/com/example/mealprep/provisions/domain/service/internal/ExpiryInferenceService.java:11
  - _fix:_ Update lld/provisions.md Flow 8, the events section, and the test plan to reflect that scanning/alerting ownership moved to notification/01b and provisions only exposes the read helpers; drop or relocate the ExpirySweepIT entry.

## recipe

- **recipe-1** [DESCOPE?] — Shared five-layer RecipeExtractionService (extraction-pipeline LLD) not implemented in the recipe module
  - _where:_ lld/recipe-extraction-pipeline.md lines 14, 42-167, 240-265 vs src/main/java/com/example/mealprep/recipe/ (no extraction sub-package)
  - _fix:_ Either (a) build `RecipeExtractionService` in `recipe.extraction` and have both the recipe import controller and discovery consume it as the LLD intends, or (b) if the deliberate decision is that discovery owns extraction and the recipe module keeps the lighter HtmlImportParser, update recipe-extraction-pipeline.md to reflect the actual ownership and the reduced v1 scope so the doc stops describing an un-built recipe-module pipeline.
- **recipe-2** [DESCOPE?] — Recipe deduplication on import/create is an un-implemented TODO
  - _where:_ src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeServiceImpl.java:361 
  - _fix:_ Implement the ingredient-set-hash dedup helper and wire it into createRecipe and the import confirm path with a 422 duplicate response carrying the candidate recipe id, or explicitly defer it in the LLD/HLD for v1 and remove the in-flow TODO so the design no longer claims dedup is part of the create/import flows.
- **recipe-3** [DESCOPE?] — Paprika-style preview-then-confirm import flow not implemented (only one-shot URL import)
  - _where:_ lld/recipe.md §Service Interfaces lines 556-560 & Flow 2 lines 727-739
  - _fix:_ If the in-app-browser frontend is a v1 target, build the preview/confirm endpoints and FromHtml path; otherwise mark the preview-then-confirm flow and FromHtml mode as explicitly deferred in recipe.md so the v1 baseline matches the shipped one-shot import.
- **recipe-4** [DOC-ONLY] — LLD still says recipe images are deferred with 'no image_url column in v1' but image storage is fully shipped
  - _where:_ lld/recipe.md §Out of Scope line 836
  - _fix:_ Update recipe.md to move recipe images from Out-of-Scope to an implemented section describing the image_url column and the upload/serve endpoints; the HLD §Recipe images (line 523) is now satisfied.

## xcut-architecture

- **xcut-1** [FIX-OR-ACCEPT] — GroceryOrderConfirmedListener registers as an empty no-op bean now that grocery has shipped — dangling event with no consumer
  - _where:_ src/main/java/com/example/mealprep/provisions/domain/service/internal/GroceryOrderConfirmedListener.java:25-31
  - _fix:_ Either give GroceryOrderConfirmedListener its intended @TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW) body (per its own javadoc, design Flow 3) and remove the redundant direct reconciliation path, OR delete the now-meaningless empty listener and stop publishing GroceryOrderConfirmedEvent (or document it as fire-for-notification-only). Leaving a registered no-op bean plus a published-but-unconsumed event is a latent trap: the next contributor will assume the event-driven seam works.
- **xcut-2** [DOC-ONLY] — GroceryOrderConfirmedEvent + GroceryOrderConfirmedListener javadocs claim an active provisions-consumes-event seam that does not exist
  - _where:_ src/main/java/com/example/mealprep/grocery/event/GroceryOrderConfirmedEvent.java:6-15
  - _fix:_ Update both javadocs to reflect reality: the inventory-add is performed by OrderReconciler via a direct applyGroceryOrder service call, not by an event listener. If the event-driven seam is the intended end state, track it as an explicit open item rather than narrating it as done.
- **xcut-3** [DESCOPE?] — Design Flow 4 cook-event seam (MealCookedEvent fan-out) unimplemented — no nutrition-logger auto-confirm on cook
  - _where:_ design/technical-architecture.md:646-675 (Flow 4) + event catalogue line 90
  - _fix:_ Decide the v1 cook architecture explicitly: if the direct provisions REST endpoint is the intended design, update the architecture doc (Flow 4, event catalogue) and delete the dormant CookEventListener + its MealCookedEvent reference. If the event-seam is in-scope for v1, the nutrition-logger auto-confirm-on-cook leg must be implemented (currently a missing capability for planned-vs-actual tracking).
- **xcut-4** [FIX-OR-ACCEPT] — Origin-tracking pattern's first consumer (feedback bridges) bypasses the OriginFilter — foundation has no production traffic
  - _where:_ design/origin-tracking-pattern.md:5-14, 98-118, 187-189
  - _fix:_ This is a documented, defensible v1 simplification (in-process call avoids the self-HTTP round-trip), but it contradicts the pattern doc's central claim of 'one endpoint, two callers, one filter.' Reconcile the docs: either update origin-tracking-pattern.md to record that v1 bridges use in-process calls with explicit AuditMetadata (filter reserved for future external automation), or migrate at least one bridge to the HTTP-self-call so the filter's policies are actually load-bearing. Otherwise the floor/rate-limit/depth guarantees the pattern advertises are not enforced for the only system-driven mutations that exist.

## xcut-completeness

- **xcut-2** [DOC-ONLY] — DiscoveryJobRunner javadoc still describes 'skeleton mode' with a non-existent saveImportedRecipe SPI, but the path is now fully wired
  - _where:_ src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryJobRunner.java:64-70 (class javadoc) vs line 632
  - _fix:_ Update the DiscoveryJobRunner class javadoc to describe the live hand-off (saveImportedRecipe is implemented; EXTRACTION_FAILED rows are now only written on genuine scrape/extraction/persist failures, not as a stub). Remove the 'skeleton mode' / 'discovery-01d-real-handoff flips the stub' wording.
- **xcut-3** [DESCOPE?] — Health-platform export of intake/composition/journal/adherence (and the GAP-37 export-consent gate ruled BUILD) is absent
  - _where:_ design/nutrition-model.md:296-305 (What MealPrep AI exports) and e2e/pathways/hld-gaps.md GAP-37 ruling vs src/main/java/com/example/mealprep/nutrition/api/controller/ (no export/consent controller)
  - _fix:_ Either implement the export surface with the ruled explicit-consent gate, or — if the export side is genuinely deferred — record that deferral explicitly in design/nutrition-model.md / the gap doc so the GAP-37 'Build' ruling is not left silently unmet.

## xcut-contract

- **xcut-contract-1** [DOC-ONLY] — Primary OpenAPI-reconciliation HLD is stale by ~51 endpoints and pre-dates whole modules
  - _where:_ design/audits/2026-05-21-openapi-reconciliation.md:10-23, 65-97
  - _fix:_ Add a dated 'superseded / point-in-time snapshot' banner to the top of 2026-05-21-openapi-reconciliation.md, or write a fresh reconciliation note reflecting the current 185-path spec (add notification, grocery, recipe-image, recipe-rating, taste-profile, lifestyle-config, preference-archive to the table). The set-difference itself is still 0 in both directions, so only the document's counts/coverage table are wrong, not the spec.
- **xcut-contract-2** [FIX] — RecipeImageController endpoints have an IT but no swagger-request-validator (OpenApiValidationMatchers) assertion
  - _where:_ src/test/java/com/example/mealprep/recipe/RecipeImageControllerIT.java (whole file) vs src/main/java/com/example/mealprep/recipe/api/controller/RecipeImageController.java:69-119
  - _fix:_ Add OpenApiValidatorConfig to RecipeImageControllerIT's @Import and apply .andExpect(openApi().isValid(openApiValidator)) on the 200 upload response (upload_returns200...) and the 200 serve response (serve_returns200...). The serve case is the valuable one — it confirms the image/* binary response shape declared in the spec actually round-trips through the validator.
