# AUDIT — LOW findings (41)

Grouped by module. Source: design/audits/2026-05-29-v1-backend-conformance-audit.md

## adaptation

- **adaptation-8** [DOC-ONLY] — package-info and AdaptationServiceImpl class javadoc still describe trigger methods / service bodies as UOE skeletons
  - _where:_ src/main/java/com/example/mealprep/adaptation/domain/service/package-info.java:1-10
  - _fix:_ Update package-info and the class javadoc to reflect that the public surface is implemented; if keeping ticket lineage, phrase it historically rather than as current state.

## ai

- **ai-10** [FIX] — Admin AI endpoints carry @PreAuthorize('hasRole(ADMIN)') that is not enforced
  - _where:_ src/main/java/com/example/mealprep/ai/api/controller/AdminAiController.java:25-33,50,60,72,82
  - _fix:_ Track the ROLE_ADMIN follow-up so the admin AI observability surface is actually gated before exposing it to real (non-admin) authenticated users; the contract test (AdminAiControllerIT) excludes SecurityAutoConfiguration so it does not catch this.
- **ai-9** [FIX] — AnthropicClient retry loop ignores the configured backoff/jitter and can be a single attempt
  - _where:_ src/main/java/com/example/mealprep/ai/domain/service/internal/AnthropicClient.java:69-92
  - _fix:_ Clarify maxRetries-as-attempts vs maxRetries-as-retries and align the field name + loop bound; add jitter if the LLD's backoff contract matters for rate-limit recovery.

## auth

- **auth-6** [FIX] — Soft-deleted-user session is not revoked by the authentication filter
  - _where:_ src/main/java/com/example/mealprep/auth/config/SessionAuthenticationFilter.java:89-92
  - _fix:_ Either revoke the session when a soft-deleted user is detected (a small write in a separate tx, since the filter must not throw), or update lld/auth.md Flow 3 to drop the 'also revokes the session' clause and rely on the reaper/soft-delete tx to clean up.
- **auth-7** [DOC-ONLY] — lld/auth.md is stale vs shipped reality on config prefix, service interfaces, DTO shapes, and LoginContext type
  - _where:_ lld/auth.md (§Configuration line 592, §CurrentUserResolver lines 307-316, §Service Interfaces lines 264-299, §DTOs lines 163-197) vs implementation
  - _fix:_ Refresh lld/auth.md to reflect the shipped contract (mealprep.auth prefix, the actual resolver/service/DTO signatures, auto-login register), and cross-link the OpenAPI schemas as the authoritative DTO contract to prevent future drift.
- **auth-8** [FIX] — @Transactional(REQUIRES_NEW) on bumpLastUsedAt is bypassed by self-invocation through a CompletableFuture lambda
  - _where:_ src/main/java/com/example/mealprep/auth/security/ServiceTokenAuthenticationProvider.java:113-126
  - _fix:_ Remove the ineffective @Transactional from bumpLastUsedAt (and correct the javadoc), or move the bump to a separate injected bean so REQUIRES_NEW actually applies if a real surrounding transaction is ever introduced on this path.

## core

- **core-7** [FIX-OR-ACCEPT] — Origin rate-limit bucket leak: per-(origin,user) buckets are created on every distinct user and never evicted
  - _where:_ src/main/java/com/example/mealprep/core/origin/internal/InMemoryTokenBucketRateLimiter.java:40,66-82
  - _fix:_ Add time-based eviction of buckets whose windowEnd is well in the past (e.g. opportunistic prune on compute, or a periodic sweep), or document the growth bound explicitly. Low priority given single-instance v1 and modest user counts.
- **core-8** [DOC-ONLY] — lld/core.md does not mention origin-tracking, though it is now a core-package responsibility
  - _where:_ lld/core.md (no §Origin) vs design/origin-tracking-pattern.md l.156 ('lld/core.md — OriginContext belongs in the core module') and shipped src/main/java/com/example/mealprep/core/origin/* (15 classes)
  - _fix:_ Add an §Origin-Tracking subsection to lld/core.md (or a short addendum) cataloguing the core.origin surface and pointing at design/origin-tracking-pattern.md + tickets/core/02b, so the core LLD reflects the module's real scope.

## discovery

- **discovery-7** [DOC-ONLY] — CandidateAiFilter interface javadoc references the deleted NoopCandidateAiFilterConfiguration / pass-through
  - _where:_ src/main/java/com/example/mealprep/discovery/domain/service/internal/CandidateAiFilter.java:8-15
  - _fix:_ Update CandidateAiFilter's javadoc to point at AiCandidateAiFilter / AiCandidateAiFilterConfiguration and drop the Noop / pass-through reference.
- **discovery-8** [FIX] — Dead code: SourceRegistry.resolveEnabled() (no-arg) and DiscoveryJobRepository.findByStatus are unused
  - _where:_ src/main/java/com/example/mealprep/discovery/domain/service/internal/SourceRegistry.java:68-73
  - _fix:_ Either remove the unused methods or add a brief // retained-for-SPI-completeness note. Cosmetic; no functional impact.

## feedback

- **feedback-3** [DESCOPE?] — Typed destinationResult shell (with Map fallback) described in LLD never implemented; mapper returns raw JsonNode
  - _where:_ src/main/java/com/example/mealprep/feedback/api/mapper/RoutingLogMapper.java:55-58
  - _fix:_ Either implement the destination-keyed typed-shell deserialisation per the LLD, or update LLD §Mappers and the RoutingLogMapper javadoc to record that v1 deliberately passes the raw JsonNode through (the Object surface makes it acceptable).
- **feedback-5** [DOC-ONLY] — LLD §Consumed specifies an AiCallSucceededEvent listener that stamps lastClassifiedAt; no such listener exists
  - _where:_ lld/feedback.md:696-704 vs src/main/java/com/example/mealprep/feedback (no AiCallSucceededEvent listener)
  - _fix:_ Update LLD §Consumed to record that lastClassifiedAt is stamped inline in Flow 2's DB writes rather than via an AiCallSucceededEvent telemetry listener, or remove the §Consumed listener spec entirely.
- **feedback-7** [FIX] — getByIds issues N+1 queries (per-id getById, each with a separate pending-clarification lookup)
  - _where:_ src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java:481-492
  - _fix:_ Implement getByIds as a single findAllById-style query scoped to userId plus one batched pending-clarification lookup, or accept the current behaviour and note in the LLD that getByIds fans out to per-id reads.
- **feedback-8** [FIX] — Superseded async-sweep repository method left in place alongside its replacement
  - _where:_ src/main/java/com/example/mealprep/feedback/domain/repository/FeedbackEntryRepository.java:34-36
  - _fix:_ Remove findBySubmissionStatusInAndCreatedAtBefore if no caller remains, or correct its javadoc to point at findStuckForRetry / feedback-01i.

## grocery

- **grocery-7** [FIX] — RefreshPricesRequest carries a body userId that the controller ignores (resolves server-side)
  - _where:_ src/main/java/com/example/mealprep/grocery/api/dto/RefreshPricesRequest.java (line 11) + PriceHistoryController.refresh (lines 118-121)
  - _fix:_ Drop userId from RefreshPricesRequest (and the OpenAPI schema) to match the server-side-resolved convention used by every other endpoint; keep only ingredientMappingKeys + useProviderQuote.
- **grocery-8** [FIX] — Substitution decisions emit a single SubstitutionResolvedEvent instead of the LLD's SubstitutionAcceptedEvent / SubstitutionRejectedEvent pair
  - _where:_ src/main/java/com/example/mealprep/grocery/event/SubstitutionResolvedEvent.java
  - _fix:_ Update the LLD Events section to record the single SubstitutionResolvedEvent (with decision) as the shipped contract and explain the cross-module naming rationale, so the event catalogue matches reality.
- **grocery-9** [DOC-ONLY] — RecalculateShoppingListRequest field renamed planRevision → planGeneration without LLD update
  - _where:_ src/main/java/com/example/mealprep/grocery/api/dto/RecalculateShoppingListRequest.java (line 13)
  - _fix:_ Update lld/grocery.md to use planGeneration consistently (request field, unique constraint, idempotency prose) to match the shipped DTO and the planner's generation counter.

## household

- **household-8** [FIX] — HouseholdSettingsDiffer stamps audit rows with Instant.now() instead of the injected Clock
  - _where:_ src/main/java/com/example/mealprep/household/domain/service/internal/HouseholdSettingsDiffer.java:65
  - _fix:_ Inject Clock into HouseholdSettingsDiffer and use Instant.now(clock) so audit timestamps are deterministic in tests and consistent with the event timestamp.

## notification

- **notification-3** [FIX] — Debouncer bundle-target lookup is LIMIT 1 by (user,kind); a per-key match hidden behind a newer different-key row is missed
  - _where:_ src/main/java/com/example/mealprep/notification/domain/service/internal/NotificationDebouncer.java:39-57
  - _fix:_ For per-key kinds, either query by bundlingKey directly (add a containment predicate to findOpenForBundling) or fetch a small page and scan for the key-matching row rather than only inspecting the single newest one.
- **notification-4** [DOC-ONLY] — LLD NotificationUpdateService.markAllRead is typed void but ships (correctly) returning int
  - _where:_ lld/notification.md:349 vs src/main/java/com/example/mealprep/notification/domain/service/NotificationUpdateService.java:28
  - _fix:_ Fix the LLD interface signature to `int markAllRead(...)` to match the endpoint table and the implementation.

## nutrition

- **nutrition-10** [FIX] — OpenAPI /lookup advertises live USDA/OFF fallthrough but query semantics are inconsistent across the module
  - _where:_ openapi/paths/nutrition.yaml:616 + IngredientLookupController.java:71-84 vs NutritionQueryService.java:117-122
  - _fix:_ Tighten the OpenAPI /lookup description to reflect that external resolution depends on USDA/OFF client availability (and is first-hit, capped confidence) until the AI match step ships, so frontend expectations are calibrated.
- **nutrition-7** [FIX] — initialiseTargets (DRI-seeded bootstrap) replaced by a bare updateTargets upsert that invents nothing
  - _where:_ NutritionUpdateService.java (no initialiseTargets)
  - _fix:_ Either implement initialiseTargets to seed DRI micro defaults from the seed table at onboarding (per HLD bootstrapping), or update the LLD/HLD to record that DRI seeding is performed by the onboarding wizard (computing the full request) rather than the nutrition module, and remove the now-misleading 'loaded by initialiseTargets' note.
- **nutrition-8** [DESCOPE?] — R__nutrition_seed_quantity_conversions.sql repeatable seed migration is missing
  - _where:_ src/main/resources/db/migration (only R__nutrition_seed_dri_defaults.sql present)
  - _fix:_ Add the quantity-conversions seed when the AI parse step lands, or drop it from the LLD migration list if conversions will be supplied by the AI Service LLD instead.
- **nutrition-9** [FIX-OR-ACCEPT] — Ingredient mapping pipeline skips AI parse and AI match steps (takes first USDA/OFF hit)
  - _where:_ src/main/java/com/example/mealprep/nutrition/domain/service/internal/IngredientMappingPipeline.java:81-107
  - _fix:_ Wire IngredientParseTask/IngredientMatchTask once the AI task catalogue exists, or document in the LLD that v1 ships a deterministic first-hit fallback with a 0.85 confidence cap pending the AI match step.

## planner

- **planner-12** [FIX] — Mid-week reoptimise has no dedicated POST /reoptimise endpoint / PlannerService.reoptimisePlan; replaced by suggestion accept/reject
  - _where:_ src/main/java/com/example/mealprep/planner/api/controller/PlansController.java:181-202 (reopt-suggestion accept/reject)
  - _fix:_ Confirm with the user that the suggestion-accept flow fully replaces the designed reoptimisePlan/POST /reoptimise surface, and update the LLD §Service Interfaces + §API Surface so the contract matches shipped reality (or add the missing endpoint).
- **planner-8** [DOC-ONLY] — Plan entity javadoc claims OPTIMISTIC_FORCE_INCREMENT version bump that the code does not perform
  - _where:_ src/main/java/com/example/mealprep/planner/domain/entity/Plan.java:31-41
  - _fix:_ Either implement the version bump (planner-7) or update this javadoc to state that child writes do not currently bump the parent version.

## preference

- **preference-6** [DOC-ONLY] — PreferenceQueryService/PreferenceUpdateService javadocs still say 'partial in 01a, the rest lands in subsequent tickets'
  - _where:_ src/main/java/com/example/mealprep/preference/domain/service/PreferenceQueryService.java:13-15
  - _fix:_ Update the two interface javadocs to reflect the shipped split-interface design (point to the dedicated TasteProfile/LifestyleConfig/Archive services) and remove the 'lands in subsequent tickets' promises (or note metadata is descoped per preference-1).
- **preference-7** [DOC-ONLY] — Comments claim @ValidDietaryIdentity 'will be added in 01c' and the rollback endpoint 'is deferred' — both no longer true
  - _where:_ src/main/java/com/example/mealprep/preference/api/dto/UpdateHardConstraintsRequest.java:14-16
  - _fix:_ Remove/correct the '01c will add @ValidDietaryIdentity' comments (tie to preference-4's resolution) and drop the stale 'rollback is deferred/absent' notes in preference.yaml and TasteProfileController now that rollback ships.
- **preference-8** [FIX-OR-ACCEPT] — Medical-diet enforcement uses a hardcoded rejected-key table the design never specifies
  - _where:_ src/main/java/com/example/mealprep/preference/domain/service/internal/MedicalDietRules.java:17-25
  - _fix:_ Confirm the intended enforcement model for medical_diets with the user. If they are meant to be hard-enforced, document the rejected-key taxonomy in the LLD (ideally seed it as reference data like allergen_derivatives rather than a hardcoded class). If they are advisory, move them out of the deterministic hard-filter.

## provisions

- **provisions-3** [DESCOPE?] — PATCH /inventory/{itemId}/quantity (adjustQuantity) endpoint and service method never built
  - _where:_ lld/provisions.md:418,500,321 vs ProvisionUpdateService.java (no adjustQuantity)
  - _fix:_ Add the PATCH quantity endpoint per the LLD, or drop it from the LLD endpoint table / DTO list and note that quantity edits go through PUT.
- **provisions-5** [DOC-ONLY] — Service-impl and interface javadocs still describe the module as '01a / inventory only / none in 01a'
  - _where:_ ProvisionServiceImpl.java:118-129
  - _fix:_ Refresh these class/interface javadocs to describe the fully-built surface rather than the 01a slice; drop the 'lands in 01b/01c/...' forward references that have since shipped.
- **provisions-6** [FIX] — Cook-event, meal/standalone-consumption and grocery-import exposed via REST against LLD 'in-process only' rule
  - _where:_ api/controller/CookEventController.java
  - _fix:_ Reconcile the LLD: update line 516 to record that these flows now have an operator/test REST surface, or gate the endpoints behind admin auth and note the rationale in the design.
- **provisions-7** [FIX-OR-ACCEPT] — GroceryOrderConfirmedEvent listener remains a dormant no-op even though the grocery event now exists
  - _where:_ domain/service/internal/GroceryOrderConfirmedListener.java
  - _fix:_ Wire GroceryOrderConfirmedListener's @TransactionalEventListener(AFTER_COMMIT)+@Transactional(REQUIRES_NEW) method to fetch the order and call applyGroceryOrder (the listener javadoc already prescribes this), or document that auto-add is intentionally manual-fulfilment-only for v1. Separately, reconcile the MealCookedEvent contract: the planner ships PlanCompletedEvent etc., not MealCookedEvent, so the cook-event listener trigger needs realignment with what the planner actually publishes.

## recipe

- **recipe-5** [FIX] — LLD-listed version-history list/get endpoints not implemented or in the OpenAPI spec
  - _where:_ lld/recipe.md §REST Controllers lines 645-646 (RecipeVersionsController GET list + GET by versionNumber)
  - _fix:_ Add the version-history listing and single-version-by-number read endpoints (and corresponding OpenAPI paths) if the frontend needs version browsing, or strike them from the LLD REST table if the diff+revert surface is considered sufficient for v1.
- **recipe-6** [FIX] — Substitution state machine renamed (active|inactive|promoted -> PROPOSED|ACCEPTED|REJECTED|SUPERSEDED) and deactivate dropped
  - _where:_ design/recipe-system.md §Substitution lifecycle lines 648-654 & lld/recipe.md line 176/573 vs src/main/java/com/example/mealprep/recipe/api/dto/SubstitutionState.java
  - _fix:_ Reconcile the HLD/LLD substitution-lifecycle prose with the shipped four-state machine, and confirm the loss of a distinct INACTIVE (constraint-lifted) state is acceptable for v1 — if the planner needs to distinguish 'rejected by user' from 'constraint no longer applies', add that state.

## xcut-architecture

- **xcut-5** [DOC-ONLY] — DiscoveryJobRunner class javadoc still describes 'skeleton mode' stub that no longer exists
  - _where:_ src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryJobRunner.java:64-70
  - _fix:_ Remove the 'skeleton mode' paragraph from the class javadoc; describe the real persist-via-RecipeWriteApi.saveImportedRecipe behaviour including the newlyCreated() dedup branch.
- **xcut-6** [FIX-OR-ACCEPT] — Origin metadata is opt-in on events (OriginAwareEvent) rather than carried by the base event interface as the design specifies
  - _where:_ design/origin-tracking-pattern.md:73 ("Domain events fired downstream carry the original X-Origin and X-Origin-Trace"), :92 ("Add origin and originTrace to base event interface")
  - _fix:_ Acceptable as a pragmatic deviation, but it means a notification listener cannot uniformly distinguish 'you changed this' vs 'AI changed this' across all event types (the pattern doc's stated use case in §4 Event metadata). If that UX is in v1 scope, either widen origin to the base interface or document which events must carry it. Otherwise update the pattern doc to reflect the opt-in OriginAwareEvent design actually chosen.
- **xcut-7** [DOC-ONLY] — Stale TODO(core-03) comments in grocery entities — normalisation has landed and write boundaries already normalise
  - _where:_ src/main/java/com/example/mealprep/grocery/domain/entity/ShoppingListLine.java:30-31
  - _fix:_ Delete or update the three TODO(core-03) comments to state that normalisation is enforced at the respective write boundary, so a future reader doesn't think these entities accept un-normalised keys.
- **xcut-8** [FIX] — No ArchUnit rule forbids cross-module access to another module's .internal packages
  - _where:_ src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java (whole file)
  - _fix:_ Add a cross-cutting ArchUnit rule: no class residing outside module X may depend on classes in com.example.mealprep.X..internal.. (with the sanctioned same-module and SPI carve-outs). This makes the internal-package boundary as enforced as the repository boundary already is.

## xcut-completeness

- **xcut-4** [DESCOPE?] — Custom /api/v1/admin/status operational endpoint (C-G-032) is not implemented
  - _where:_ design/technical-architecture.md:780-781 / capability C-G-032
  - _fix:_ Add the GET /api/v1/admin/status endpoint aggregating DB connectivity, last AI/USDA call timestamps, and month-to-date AI cost, or note it as deferred. This was scheduled as Tier C (ship-to-users) ops hygiene, hence LOW for a v1 test pass.
- **xcut-5** [DESCOPE?] — No retention/archival/aging of user-facing notification rows (GAP-39)
  - _where:_ src/main/java/com/example/mealprep/notification/ — only DispatchLogCleanupScheduler.java sweeps scanner dispatch-log tables
  - _fix:_ Add an aging/archival sweep for the notification rows (or an isArchived flag + age cutoff) per GAP-39, or document it as deferred. LOW for v1 since it is a slow-growing operational concern, not a correctness/safety issue.
