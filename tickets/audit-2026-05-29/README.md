# v1 Backend Audit — triage tickets (2026-05-29)

From the conformance audit (44-agent workflow, adversarially verified). Full evidence: `design/audits/2026-05-29-v1-backend-conformance-audit.md`.

**Counts (post-verification, 4 false-positives excluded):** 3 CRITICAL · 9 HIGH · 72 MEDIUM · 41 LOW.

Triage flags are suggestions — edit them. **FIX** build it · **DESCOPE?** decide v1 vs defer (+doc the deferral) · **DOC-ONLY** stale doc/javadoc · **FIX-OR-ACCEPT** divergence where the agent offered implement-or-record-as-accepted.

CRITICAL+HIGH have one file each; MEDIUM/LOW in MEDIUM.md / LOW.md.

## Triage table

| id | sev | module | dimension | suggested | title |
|---|---|---|---|---|---|
| [adaptation-1](CRITICAL-adaptation-adaptation-1.md) | CRITICAL | adaptation | MISSING_CAPABILITY | FIX | Worker never applies DIRECT (system-catalogue) or PLAN_OVERLAY (plan-time substitution) outcomes — both record NO_OP |
| [adaptation-2](CRITICAL-adaptation-adaptation-2.md) | CRITICAL | adaptation | DESIGN_DIVERGENCE | FIX | Hard-constraint (allergy/dietary) safety-net filter is never invoked inside the worker pipeline |
| [discovery-1](CRITICAL-discovery-discovery-1.md) | CRITICAL | discovery | MISSING_CAPABILITY | FIX | ingredientMappingKey dropped end-to-end — ingredient-bearing discovered recipes cannot ingest |
| [ai-1](HIGH-ai-ai-1.md) | HIGH | ai | DESIGN_DIVERGENCE | FIX | Dispatcher never renders the prompt template and never sends a system prompt |
| [ai-2](HIGH-ai-ai-2.md) | HIGH | ai | DESIGN_DIVERGENCE | FIX-OR-ACCEPT | No circuit breaker / Resilience4j; retry classification collapsed to 4xx-vs-5xx |
| [core-2](HIGH-core-core-2.md) | HIGH | core | STALE_DOC | DOC-ONLY | lld/core.md is the authoritative design but is stale across ~8 decisions the implementation tickets superseded |
| [discovery-2](HIGH-discovery-discovery-2.md) | HIGH | discovery | STALE_DOC | DOC-ONLY | DiscoveryJobRunner class javadoc still describes the removed 'skeleton mode' saveImportedRecipe stub |
| [planner-1](HIGH-planner-planner-1.md) | HIGH | planner | DESIGN_DIVERGENCE | FIX | AI Stage C / Phase 2 / Stage D run INSIDE the persistence transaction, violating the locked Tier-1 rule |
| [planner-2](HIGH-planner-planner-2.md) | HIGH | planner | MISSING_CAPABILITY | DESCOPE? | No single-flight generation lock; ConcurrentGenerationInProgressException is dead code |
| [planner-5](HIGH-planner-planner-5.md) | HIGH | planner | DESIGN_DIVERGENCE | FIX-OR-ACCEPT | Revert is copy-forward of the current ACTIVE plan, not revert-to a chosen historical plan; missing ownership check, hard-constraint re-filter, and Phase-2 refill |
| [preference-1](HIGH-preference-preference-1.md) | HIGH | preference | MISSING_CAPABILITY | DESCOPE? | Profile Metadata tier entirely unimplemented (no table, entity, endpoints, or filter integration) |
| [xcut-1](HIGH-xcut-completeness-xcut-1.md) | HIGH | xcut-completeness | MISSING_CAPABILITY | DESCOPE? | Allergy / Tier-1 hard-constraint removal has no confirmation/safety interstitial (GAP-04 ruled BUILD, not implemented) |
| adaptation-3 | MEDIUM | adaptation | BAD_BEHAVIOUR | FIX | Trigger payloads never persisted to job.inputs, so all source-bias logic (ratingDelta, directive, feedbackText) is dead at runtime |
| adaptation-4 | MEDIUM | adaptation | BAD_BEHAVIOUR | FIX | RebaseOrchestrator is fully implemented but never injected or invoked — WriteApi conflict-retry / REBASE_EXHAUSTED path is dead |
| adaptation-5 | MEDIUM | adaptation | DESIGN_DIVERGENCE | FIX | acceptPendingChange always writes a VERSION, ignoring a pending change classified as BRANCH |
| adaptation-6 | MEDIUM | adaptation | DESIGN_DIVERGENCE | FIX | PendingChangeStore hard-codes impactScore = 0.5, defeating the impact-ranked 3-per-week budget |
| adaptation-7 | MEDIUM | adaptation | CONTRACT_GAP | FIX | No per-trigger flow IT covering the apply paths; LLD test-plan integration tests are largely absent |
| ai-4 | MEDIUM | ai | MISSING_CAPABILITY | DESCOPE? | Per-task token cap (and the Stage-C 'can't shove the pool in' guard) is absent |
| ai-5 | MEDIUM | ai | MISSING_CAPABILITY | DESCOPE? | API key rotation audit (table, entity, startup detector) entirely absent |
| ai-6 | MEDIUM | ai | DESIGN_DIVERGENCE | FIX | Cost-cap model diverged: single rolling-window hard block vs. LLD's soft-daily + hard-monthly two scopes |
| ai-7 | MEDIUM | ai | STALE_DOC | DOC-ONLY | lld/ai.md describes a package/SPI/DTO surface that does not match the shipped module |
| ai-8 | MEDIUM | ai | STALE_DOC | DOC-ONLY | PromptTemplateLoader javadoc + recipe-adaptation.txt imply a render path/format that doesn't match the loader |
| auth-1 | MEDIUM | auth | DESIGN_DIVERGENCE | FIX | Lockout counter never reset on lockout → account re-locks on first failed attempt after window expiry |
| auth-2 | MEDIUM | auth | DESIGN_DIVERGENCE | FIX | Password-change wrong-current-password does not record a LoginAttempt (escapes the throttle window) |
| auth-3 | MEDIUM | auth | MISSING_CAPABILITY | DESCOPE? | Session reaper (Flow 6) is not implemented — deleteExpiredAndRevokedBefore is dead code |
| auth-4 | MEDIUM | auth | MISSING_CAPABILITY | DESCOPE? | Username reserved-name list and start/end-separator rule not enforced; pattern narrower than LLD |
| auth-5 | MEDIUM | auth | DESIGN_DIVERGENCE | FIX | Service-side weak-password rejection throws raw IllegalArgumentException → generic 400 leaking reason names, not the specified reasons[] shape |
| core-1 | MEDIUM | core | MISSING_CAPABILITY | DESCOPE? | Trace-ID propagation (TraceIdFilter + TraceContext) — an entire in-scope LLD section — is unimplemented |
| core-3 | MEDIUM | core | DESIGN_DIVERGENCE | FIX | Decision-log write dropped its idempotency contract and the parent-existence check |
| core-4 | MEDIUM | core | DESIGN_DIVERGENCE | FIX | DecisionLogService.write uses REQUIRES_NEW; the LLD mandates REQUIRED (join caller's tx, roll back together) |
| core-5 | MEDIUM | core | MISSING_CAPABILITY | DESCOPE? | DecisionLogTokenBudgetGuard (64 KB payload cap) and its 422 mapping are absent |
| core-6 | MEDIUM | core | DESIGN_DIVERGENCE | FIX | LockService interface diverges from LLD: no acquire()/LockHandle/LockAcquisitionException; single boolean tryAcquire |
| discovery-3 | MEDIUM | discovery | DESIGN_DIVERGENCE | FIX | AI candidate filter diverges from LLD's v1 pass-through + skip-and-flag failure contract |
| discovery-4 | MEDIUM | discovery | BAD_BEHAVIOUR | FIX | AI-filter rejections are never logged as AI_FILTER_REJECTED scrape rows (silent drop, no audit) |
| discovery-5 | MEDIUM | discovery | DESIGN_DIVERGENCE | FIX | Search and fetch phases run fully sequentially despite LLD specifying parallel-across-sources |
| discovery-6 | MEDIUM | discovery | BAD_BEHAVIOUR | FIX | cancelJob QUEUED branch issues an unguarded UPDATE that can clobber a job the runner just claimed RUNNING |
| feedback-1 | MEDIUM | feedback | DESIGN_DIVERGENCE | FIX | overallConfidence forced @NotNull contradicts LLD 'optional aggregate the classifier MAY emit' |
| feedback-2 | MEDIUM | feedback | BAD_BEHAVIOUR | FIX | Correction-to-RECIPE precondition accepts recipeId from original payload, but replay synthetic only copies recipeId from uiContext |
| feedback-4 | MEDIUM | feedback | STALE_DOC | DOC-ONLY | FeedbackServiceImpl class javadoc still describes a half-built 01b skeleton with UnsupportedOperationException stubs |
| feedback-6 | MEDIUM | feedback | DESIGN_DIVERGENCE | FIX-OR-ACCEPT | Preference-delta AI pipeline (delta computation, AI task, budget orchestration) lives inside the feedback module package |
| grocery-1 | MEDIUM | grocery | DESIGN_DIVERGENCE | FIX | Tier-3 order reconciliation never updates the source shopping_list_lines (fulfilment_status / bought_via=ORDER / grocery_order_id) |
| grocery-2 | MEDIUM | grocery | MISSING_CAPABILITY | DESCOPE? | refreshOnDemand(useProviderQuote=true) is a permanent stub — never quotes the provider despite the provider SPI (01e) having shipped |
| grocery-3 | MEDIUM | grocery | STALE_DOC | DOC-ONLY | GroceryServiceImpl class javadoc still describes it as a skeleton throwing UnsupportedOperationException |
| grocery-4 | MEDIUM | grocery | DESIGN_DIVERGENCE | FIX | placeOrder always auto-advances to AWAITING_USER_CONFIRMATION; the LLD 'delivery slot fails → pause at PLACED' failure mode is not implemented |
| grocery-5 | MEDIUM | grocery | DESIGN_DIVERGENCE | FIX | Single-flight lock diverges from the LLD's tryAcquire(scope, ttl) contract and builds a collision-prone scope id |
| grocery-6 | MEDIUM | grocery | STALE_DOC | DOC-ONLY | LLD Tier 4 / Flow 5 still describe time-decay + Bayesian priorStrength + InflationIndexer as the price-aggregation model, but v1 ships none of them |
| household-1 | MEDIUM | household | DESIGN_DIVERGENCE | FIX | Merge service skips LLD-mandated eater-membership validation for supplied eaterUserIds |
| household-2 | MEDIUM | household | MISSING_CAPABILITY | DESCOPE? | Custom validators @ValidSlotKey / @ValidHeadcount never implemented; custom slot keys and headcounts unconstrained |
| household-3 | MEDIUM | household | DESIGN_DIVERGENCE | FIX-OR-ACCEPT | Default settings time budgets are a flat 30 for all slots, ignoring planner HLD per-kind defaults |
| household-4 | MEDIUM | household | DESIGN_DIVERGENCE | FIX | Invite-accept publishes HouseholdInviteAcceptedEvent, not the LLD-specified HouseholdMemberAddedEvent |
| household-5 | MEDIUM | household | CONTRACT_GAP | FIX | REST surface uses /households/current/... and /invites/accept, diverging from the LLD's /households/{id}/... contract |
| household-6 | MEDIUM | household | STALE_DOC | DOC-ONLY | LLD no longer matches shipped reality across REST table, events, repositories, query-service methods, and MergedSoftPreferencesDto |
| household-7 | MEDIUM | household | DESIGN_DIVERGENCE | FIX-OR-ACCEPT | Planner never reacts to household membership/role changes despite LLD intent and a filter built to match them |
| notification-2 | MEDIUM | notification | STALE_DOC | DOC-ONLY | Authoritative LLD omits the entire scanner subsystem, two NotificationKinds, the feedback listener, and the dispatcher facade that shipped |
| nutrition-3 | MEDIUM | nutrition | MISSING_CAPABILITY | DESCOPE? | Directive auto-expiry sweep (@Scheduled) is not implemented |
| nutrition-4 | MEDIUM | nutrition | DESIGN_DIVERGENCE | FIX | Floor gate uses floorG-presence instead of the LLD's per-target isHardFloor flag |
| nutrition-5 | MEDIUM | nutrition | MISSING_CAPABILITY | DESCOPE? | getDailyAggregate query method and GET /{date}/aggregate endpoint are absent |
| nutrition-6 | MEDIUM | nutrition | DESIGN_DIVERGENCE | FIX | Daily/weekly 'remaining' computed from planned, not the user's target, and not floored at zero |
| planner-10 | MEDIUM | planner | MISSING_CAPABILITY | DESCOPE? | Re-opt suggestion expiry sweep (PENDING→EXPIRED) not implemented |
| planner-11 | MEDIUM | planner | DESIGN_DIVERGENCE | FIX-OR-ACCEPT | Stage D refine-directive routing is effectively dead — Phase 2 always emits an empty directive list |
| planner-3 | MEDIUM | planner | MISSING_CAPABILITY | DESCOPE? | Weekly PlanCompleted sweep not implemented — COMPLETED state is unreachable |
| planner-4 | MEDIUM | planner | DESIGN_DIVERGENCE | FIX | Cold-start catalogue threshold uses distinct slot-KINDS, not slot COUNT (off by ~7x) |
| planner-6 | MEDIUM | planner | MISSING_CAPABILITY | DESCOPE? | Constraint feasibility check + checkFeasibility query + GET /feasibility never implemented |
| planner-7 | MEDIUM | planner | BAD_BEHAVIOUR | FIX | Slot-state write does not force-increment the parent Plan.version, so concurrent re-opt cannot detect mid-flight slot changes |
| planner-9 | MEDIUM | planner | BAD_BEHAVIOUR | FIX | Double-active accept surfaces as a 500, not the LLD's 409 — no DataIntegrityViolationException handler |
| preference-2 | MEDIUM | preference | DESIGN_DIVERGENCE | FIX | Hard-constraint filter never produces AMBIGUOUS results; the LLD makes ambiguity-flagging the default |
| preference-3 | MEDIUM | preference | DESIGN_DIVERGENCE | FIX | Dietary-identity exceptions widen the base diet regardless of context; LLD requires context-matching |
| preference-4 | MEDIUM | preference | MISSING_CAPABILITY | DESCOPE? | @ValidDietaryIdentity custom validator specified by the LLD is never implemented or applied |
| preference-5 | MEDIUM | preference | MISSING_CAPABILITY | DESCOPE? | Taste-vector embedding pipeline deferred despite LLD presenting it as locked/in-scope |
| provisions-1 | MEDIUM | provisions | MISSING_CAPABILITY | DESCOPE? | Batch-cook fridge/freezer split is a throwing stub, not implemented |
| provisions-2 | MEDIUM | provisions | MISSING_CAPABILITY | DESCOPE? | pantry_tracking_enabled gating is not implemented anywhere in the module |
| provisions-4 | MEDIUM | provisions | STALE_DOC | DOC-ONLY | LLD Flow 8 still claims the @Scheduled expiry sweep lives in provisions; it was moved to notification |
| recipe-1 | MEDIUM | recipe | MISSING_CAPABILITY | DESCOPE? | Shared five-layer RecipeExtractionService (extraction-pipeline LLD) not implemented in the recipe module |
| recipe-2 | MEDIUM | recipe | MISSING_CAPABILITY | DESCOPE? | Recipe deduplication on import/create is an un-implemented TODO |
| recipe-3 | MEDIUM | recipe | MISSING_CAPABILITY | DESCOPE? | Paprika-style preview-then-confirm import flow not implemented (only one-shot URL import) |
| recipe-4 | MEDIUM | recipe | STALE_DOC | DOC-ONLY | LLD still says recipe images are deferred with 'no image_url column in v1' but image storage is fully shipped |
| xcut-1 | MEDIUM | xcut-architecture | BAD_BEHAVIOUR | FIX-OR-ACCEPT | GroceryOrderConfirmedListener registers as an empty no-op bean now that grocery has shipped — dangling event with no consumer |
| xcut-2 | MEDIUM | xcut-architecture | STALE_DOC | DOC-ONLY | GroceryOrderConfirmedEvent + GroceryOrderConfirmedListener javadocs claim an active provisions-consumes-event seam that does not exist |
| xcut-3 | MEDIUM | xcut-architecture | MISSING_CAPABILITY | DESCOPE? | Design Flow 4 cook-event seam (MealCookedEvent fan-out) unimplemented — no nutrition-logger auto-confirm on cook |
| xcut-4 | MEDIUM | xcut-architecture | DESIGN_DIVERGENCE | FIX-OR-ACCEPT | Origin-tracking pattern's first consumer (feedback bridges) bypasses the OriginFilter — foundation has no production traffic |
| xcut-2 | MEDIUM | xcut-completeness | STALE_DOC | DOC-ONLY | DiscoveryJobRunner javadoc still describes 'skeleton mode' with a non-existent saveImportedRecipe SPI, but the path is now fully wired |
| xcut-3 | MEDIUM | xcut-completeness | MISSING_CAPABILITY | DESCOPE? | Health-platform export of intake/composition/journal/adherence (and the GAP-37 export-consent gate ruled BUILD) is absent |
| xcut-contract-1 | MEDIUM | xcut-contract | STALE_DOC | DOC-ONLY | Primary OpenAPI-reconciliation HLD is stale by ~51 endpoints and pre-dates whole modules |
| xcut-contract-2 | MEDIUM | xcut-contract | CONTRACT_GAP | FIX | RecipeImageController endpoints have an IT but no swagger-request-validator (OpenApiValidationMatchers) assertion |
| adaptation-8 | LOW | adaptation | STALE_DOC | DOC-ONLY | package-info and AdaptationServiceImpl class javadoc still describe trigger methods / service bodies as UOE skeletons |
| ai-10 | LOW | ai | CONTRACT_GAP | FIX | Admin AI endpoints carry @PreAuthorize('hasRole(ADMIN)') that is not enforced |
| ai-9 | LOW | ai | BAD_BEHAVIOUR | FIX | AnthropicClient retry loop ignores the configured backoff/jitter and can be a single attempt |
| auth-6 | LOW | auth | DESIGN_DIVERGENCE | FIX | Soft-deleted-user session is not revoked by the authentication filter |
| auth-7 | LOW | auth | STALE_DOC | DOC-ONLY | lld/auth.md is stale vs shipped reality on config prefix, service interfaces, DTO shapes, and LoginContext type |
| auth-8 | LOW | auth | BAD_BEHAVIOUR | FIX | @Transactional(REQUIRES_NEW) on bumpLastUsedAt is bypassed by self-invocation through a CompletableFuture lambda |
| core-7 | LOW | core | BAD_BEHAVIOUR | FIX-OR-ACCEPT | Origin rate-limit bucket leak: per-(origin,user) buckets are created on every distinct user and never evicted |
| core-8 | LOW | core | STALE_DOC | DOC-ONLY | lld/core.md does not mention origin-tracking, though it is now a core-package responsibility |
| discovery-7 | LOW | discovery | STALE_DOC | DOC-ONLY | CandidateAiFilter interface javadoc references the deleted NoopCandidateAiFilterConfiguration / pass-through |
| discovery-8 | LOW | discovery | BAD_BEHAVIOUR | FIX | Dead code: SourceRegistry.resolveEnabled() (no-arg) and DiscoveryJobRepository.findByStatus are unused |
| feedback-3 | LOW | feedback | MISSING_CAPABILITY | DESCOPE? | Typed destinationResult shell (with Map fallback) described in LLD never implemented; mapper returns raw JsonNode |
| feedback-5 | LOW | feedback | STALE_DOC | DOC-ONLY | LLD §Consumed specifies an AiCallSucceededEvent listener that stamps lastClassifiedAt; no such listener exists |
| feedback-7 | LOW | feedback | BAD_BEHAVIOUR | FIX | getByIds issues N+1 queries (per-id getById, each with a separate pending-clarification lookup) |
| feedback-8 | LOW | feedback | BAD_BEHAVIOUR | FIX | Superseded async-sweep repository method left in place alongside its replacement |
| grocery-7 | LOW | grocery | BAD_BEHAVIOUR | FIX | RefreshPricesRequest carries a body userId that the controller ignores (resolves server-side) |
| grocery-8 | LOW | grocery | DESIGN_DIVERGENCE | FIX | Substitution decisions emit a single SubstitutionResolvedEvent instead of the LLD's SubstitutionAcceptedEvent / SubstitutionRejectedEvent pair |
| grocery-9 | LOW | grocery | STALE_DOC | DOC-ONLY | RecalculateShoppingListRequest field renamed planRevision → planGeneration without LLD update |
| household-8 | LOW | household | BAD_BEHAVIOUR | FIX | HouseholdSettingsDiffer stamps audit rows with Instant.now() instead of the injected Clock |
| notification-3 | LOW | notification | BAD_BEHAVIOUR | FIX | Debouncer bundle-target lookup is LIMIT 1 by (user,kind); a per-key match hidden behind a newer different-key row is missed |
| notification-4 | LOW | notification | STALE_DOC | DOC-ONLY | LLD NotificationUpdateService.markAllRead is typed void but ships (correctly) returning int |
| nutrition-10 | LOW | nutrition | CONTRACT_GAP | FIX | OpenAPI /lookup advertises live USDA/OFF fallthrough but query semantics are inconsistent across the module |
| nutrition-7 | LOW | nutrition | DESIGN_DIVERGENCE | FIX | initialiseTargets (DRI-seeded bootstrap) replaced by a bare updateTargets upsert that invents nothing |
| nutrition-8 | LOW | nutrition | MISSING_CAPABILITY | DESCOPE? | R__nutrition_seed_quantity_conversions.sql repeatable seed migration is missing |
| nutrition-9 | LOW | nutrition | DESIGN_DIVERGENCE | FIX-OR-ACCEPT | Ingredient mapping pipeline skips AI parse and AI match steps (takes first USDA/OFF hit) |
| planner-12 | LOW | planner | DESIGN_DIVERGENCE | FIX | Mid-week reoptimise has no dedicated POST /reoptimise endpoint / PlannerService.reoptimisePlan; replaced by suggestion accept/reject |
| planner-8 | LOW | planner | STALE_DOC | DOC-ONLY | Plan entity javadoc claims OPTIMISTIC_FORCE_INCREMENT version bump that the code does not perform |
| preference-6 | LOW | preference | STALE_DOC | DOC-ONLY | PreferenceQueryService/PreferenceUpdateService javadocs still say 'partial in 01a, the rest lands in subsequent tickets' |
| preference-7 | LOW | preference | STALE_DOC | DOC-ONLY | Comments claim @ValidDietaryIdentity 'will be added in 01c' and the rollback endpoint 'is deferred' — both no longer true |
| preference-8 | LOW | preference | DESIGN_DIVERGENCE | FIX-OR-ACCEPT | Medical-diet enforcement uses a hardcoded rejected-key table the design never specifies |
| provisions-3 | LOW | provisions | MISSING_CAPABILITY | DESCOPE? | PATCH /inventory/{itemId}/quantity (adjustQuantity) endpoint and service method never built |
| provisions-5 | LOW | provisions | STALE_DOC | DOC-ONLY | Service-impl and interface javadocs still describe the module as '01a / inventory only / none in 01a' |
| provisions-6 | LOW | provisions | DESIGN_DIVERGENCE | FIX | Cook-event, meal/standalone-consumption and grocery-import exposed via REST against LLD 'in-process only' rule |
| provisions-7 | LOW | provisions | DESIGN_DIVERGENCE | FIX-OR-ACCEPT | GroceryOrderConfirmedEvent listener remains a dormant no-op even though the grocery event now exists |
| recipe-5 | LOW | recipe | CONTRACT_GAP | FIX | LLD-listed version-history list/get endpoints not implemented or in the OpenAPI spec |
| recipe-6 | LOW | recipe | DESIGN_DIVERGENCE | FIX | Substitution state machine renamed (active|inactive|promoted -> PROPOSED|ACCEPTED|REJECTED|SUPERSEDED) and deactivate dropped |
| xcut-5 | LOW | xcut-architecture | STALE_DOC | DOC-ONLY | DiscoveryJobRunner class javadoc still describes 'skeleton mode' stub that no longer exists |
| xcut-6 | LOW | xcut-architecture | DESIGN_DIVERGENCE | FIX-OR-ACCEPT | Origin metadata is opt-in on events (OriginAwareEvent) rather than carried by the base event interface as the design specifies |
| xcut-7 | LOW | xcut-architecture | STALE_DOC | DOC-ONLY | Stale TODO(core-03) comments in grocery entities — normalisation has landed and write boundaries already normalise |
| xcut-8 | LOW | xcut-architecture | CONTRACT_GAP | FIX | No ArchUnit rule forbids cross-module access to another module's .internal packages |
| xcut-4 | LOW | xcut-completeness | MISSING_CAPABILITY | DESCOPE? | Custom /api/v1/admin/status operational endpoint (C-G-032) is not implemented |
| xcut-5 | LOW | xcut-completeness | MISSING_CAPABILITY | DESCOPE? | No retention/archival/aging of user-facing notification rows (GAP-39) |

## Suggested v1 gate — allergy/food-safety cluster

- **adaptation/adaptation-2** [CRITICAL/FIX] Hard-constraint (allergy/dietary) safety-net filter is never invoked inside the worker pipeline
- **discovery/discovery-2** [HIGH/DOC-ONLY] DiscoveryJobRunner class javadoc still describes the removed 'skeleton mode' saveImportedRecipe stub
- **planner/planner-5** [HIGH/FIX-OR-ACCEPT] Revert is copy-forward of the current ACTIVE plan, not revert-to a chosen historical plan; missing ownership check, hard-constraint re-filter, and Phase-2 refill
- **xcut-completeness/xcut-1** [HIGH/DESCOPE?] Allergy / Tier-1 hard-constraint removal has no confirmation/safety interstitial (GAP-04 ruled BUILD, not implemented)
- **household/household-1** [MEDIUM/FIX] Merge service skips LLD-mandated eater-membership validation for supplied eaterUserIds
- **preference/preference-2** [MEDIUM/FIX] Hard-constraint filter never produces AMBIGUOUS results; the LLD makes ambiguity-flagging the default
- **preference/preference-3** [MEDIUM/FIX] Dietary-identity exceptions widen the base diet regardless of context; LLD requires context-matching
- **preference/preference-4** [MEDIUM/DESCOPE?] @ValidDietaryIdentity custom validator specified by the LLD is never implemented or applied
- **nutrition/nutrition-7** [LOW/FIX] initialiseTargets (DRI-seeded bootstrap) replaced by a bare updateTargets upsert that invents nothing
- **preference/preference-6** [LOW/DOC-ONLY] PreferenceQueryService/PreferenceUpdateService javadocs still say 'partial in 01a, the rest lands in subsequent tickets'
- **preference/preference-7** [LOW/DOC-ONLY] Comments claim @ValidDietaryIdentity 'will be added in 01c' and the rollback endpoint 'is deferred' — both no longer true
- **preference/preference-8** [LOW/FIX-OR-ACCEPT] Medical-diet enforcement uses a hardcoded rejected-key table the design never specifies
