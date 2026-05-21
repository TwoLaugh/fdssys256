# Frontend-Readiness Roadmap — 2026-05-21

The follow-on to [2026-05-21-backend-state-audit.md](2026-05-21-backend-state-audit.md). Turns the gap analysis into an ordered execution plan answering: **what backend work must happen for a frontend project to begin in earnest, exercise the full design intent, and ship to real users.**

The roadmap is structured as three tiers (A, B, C) because "frontend-ready" means different things at different stages of frontend development. Tier A is "frontend can start". Tier B is "frontend can demo end-to-end". Tier C is "frontend can ship to users."

## Tier A — Frontend can start building (≈4-5 days)

This is the minimum so a frontend developer can stand up a UI against the existing API and discover missing pieces empirically while the rest of the roadmap progresses in parallel.

### A1. CORS config in dev profile (~0.25 day)

`design/technical-architecture.md:940-942` specifies CORS for `http://localhost:5173` (Vite). Backend currently has no CORS config — frontend dev will be blocked the moment it tries to call.

- Add `WebMvcConfigurer` bean in dev profile with the allowed origin
- Or use `@CrossOrigin` on the relevant controllers (less preferred)
- Verify with a manual curl from a different origin

### A2. Recipe image storage backend (~1-2 days)

Per gap audit C-A-053. Recipe has no `image_url` field; no upload endpoint. The frontend will need both — recipe lists, recipe detail pages, and recipe-edit forms all need images.

- Add `image_url` column to `recipe_recipes` (Flyway migration)
- Decide storage strategy (local FS in v1 — simplest)
- `POST /api/v1/recipes/{id}/image` accepting multipart; writes to a config-driven directory; stores the path
- `GET /api/v1/recipes/{id}/image` serves the file (or just return URL and let the frontend hit it directly)
- Max-size validation (5MB default), MIME-type whitelist

### A3. Repo hygiene (~0.5 day)

Per gap audit cluster on ops:
- Add `.env.example` documenting required env vars (DB_PASSWORD, ANTHROPIC_API_KEY, USDA_API_KEY, OPENAI_API_KEY)
- Add `spring-boot-starter-actuator` dependency + minimal exposure (`/actuator/health`, `/actuator/info`) — frontend's health-check page will want this
- Update README from its stale state (currently says "no user-facing endpoints yet")

### A4. Verify list-endpoint pagination + filtering (~1 day)

Frontend lists need pagination/filtering. We have endpoints but not all are paginated consistently.

- Audit collection endpoints (`GET /api/v1/recipes`, `GET /api/v1/nutrition/intake`, etc.) for: `?page=N&size=M&sort=...` support, total-count in response, sensible defaults.
- Where missing, add Spring Data Pageable.
- Specifically: recipe browse (C-E-011 said this exists; verify it's properly paginated and filtered), intake history search (C-B-048 — add filters: recipeId, mealType, search text).

### A5. Confirm OpenAPI spec is current (~0.5 day)

Frontend will generate TypeScript types from `src/main/resources/openapi/openapi.yaml`. The spec has been auto-updated through PRs but worth one sanity check pass — generate types, see if anything's missing.

**Tier A exit criteria:** frontend dev can `vite dev`, log in via the auth endpoints, list recipes (with pagination), view recipe detail with image, edit a recipe, see error responses in the standard envelope. Most user flows are stub-able past Tier A.

## Tier B — Frontend can demo end-to-end (≈4-5 weeks)

This is where the system's actual design promise gets exercised: AI learns from feedback, notifications surface things, the planner reacts to changes.

### B1. Foundation hole — preference Tier 2/3 + feedback bridges (≈2-3 weeks)

The single highest-leverage backend work in the roadmap. Until this lands, the system is a static-rules planner; after, it's an AI meal-prep that actually learns.

**B1.1 Taste Profile entity** (~5-7 days)
- Per `design/preference-model.md:75-163`: JSONB document with structured fields (flavour preferences, texture, ingredient preferences with evidence_count/last_signal/source, cuisine, cooking method, portion style, household context, recipes to repeat/avoid, active experiments, learned insights)
- Flyway: `preference_taste_profile` table + JSONB column + indexes
- Entity, repository, service (query + update split per style guide)
- MapStruct mapper
- REST endpoints: GET (view), PUT (override), POST `/preferences/taste-profile/refresh-now` (user-triggered update)
- Audit log of changes (mirrors `HardConstraintsAuditLog` pattern)

**B1.2 Lifestyle Config entity** (~3-4 days)
- Per `design/preference-model.md:189-292`: full settings shape (meal_structure, meal_timing, novelty_tolerance, cooking_contexts, batch_cooking, reheating_preferences, eating_context, seasonal_preferences, meal_type_preferences, accompaniments, grocery_quality_preferences)
- Standard CRUD endpoints
- Audit log

**B1.3 Preference archive** (~2 days)
- `preference_archive` table for pruned items (unbounded retention)
- Add to AI delta handler so items move here on prune
- Read API for "re-emerging preferences" detection (depends on archive)

**B1.4 Real feedback destination bridges** (~5-7 days)

Replace four Noop configurations:
- `PreferenceFeedbackBridge` — apply `taste_profile_delta` to Taste Profile (uses B1.1)
- `NutritionFeedbackBridge` — apply target adjustments to NutritionTargets
- `ProvisionsFeedbackBridge` — apply "I don't have a food processor" → Equipment removal; "this recipe needs less salt" → not applicable (out of provisions scope, route to preference instead)
- `RecipeFeedbackBridge` — route to existing adaptation pipeline (which exists)

**This is where the [origin-tracking pattern](../origin-tracking-pattern.md) gets applied for the first time.** Feedback bridges call the same REST controllers users call, with `X-Origin: ai-feedback` and `X-Origin-Trace: <feedback_id>` headers. The pattern doc has the full spec.

### B2. Notifications subsystem (~5-7 days)

Per gap audit cluster + the existing `lld/notification.md` (which is detailed but unimplemented).

- Bring `lld/notification.md` from `lld/` to `lld/` (it's already there — confirm it's still accurate; if not, update before building)
- Build `NotificationService` + `notification_log` + `notification_preferences` per the LLD
- Event listeners: ProvisionEventListener, NutritionEventListener, PlannerEventListener
- Scheduled scanners: expiry (default 2 days fridge / 14 days freezer), defrost reminders, staple replenishment
- REST: `GET /api/v1/notifications`, `PUT /.../read`, `PUT /.../dismissed`, `PUT /.../actioned`
- In-app only per HLD (no email/SMS/push for v1)

### B3. Recipe ratings (~2 days)

Per gap audit C-A-026. Multi-dimensional rating (taste / effort_worth_it / portion_fit / repeat_value, each 0-100). Per-version, attached to the scheduled recipe's pinned version (not the recipe).

- `recipe_ratings` table + entity
- Service for capture (one-tap default = `taste` only; detailed mode for all four)
- REST: `POST /api/v1/recipes/{id}/ratings`
- **Ratings count as feedback** — they fire `FeedbackProcessedEvent` and get classified into a taste-profile signal. This is where the loop closes visibly.

### B4. Discovery skeleton → wired (~2-3 days)

Per gap audit C-C-030. `RecipeWriteApi.saveImportedRecipe` SPI doesn't exist; `DiscoveryJobRunner` writes EXTRACTION_FAILED rows instead of persisting.

- Add `saveImportedRecipe(ImportedRecipeData)` to `RecipeWriteApi` interface
- Implement in `RecipeServiceImpl` — creates a Recipe with `catalogue=SYSTEM`, `data_quality=WEB_DISCOVERED`
- Wire `CandidateAiFilter` real impl (Noop currently)
- Discovery jobs now actually grow the system catalogue

**Tier B exit criteria:** the full AI-learning loop works. A user can: log a meal → get feedback prompted (or volunteer it) → feedback gets classified → applied to taste profile / nutrition target / inventory → next week's plan reflects the change → notifications surface relevant events. The system has the behaviour the HLD promises.

## Tier C — Frontend can ship to users (≈1-2 weeks)

Beyond demoability. Production hardening.

### C1. Household RBAC (~3-5 days)

Per gap audit C-G-063. Current state: `PlansController:55-57` exposes cross-tenant reads (any authenticated caller can read any household's plans). HouseholdRole enum exists; HouseholdRoleChangedEvent exists; no policy enforces it.

- Define role enumeration (PRIMARY / MEMBER / VIEWER? — match HouseholdRole.java)
- Per-action permission matrix as a Spring Security `@PreAuthorize`-able policy
- Apply at every controller that touches household-scoped data
- Cross-tenant audit test (call as user A, try to read household B's data → expect 403)

### C2. GDPR endpoints (~3-5 days)

UK consumer app. Required regardless of HLD silence (C-G-062 added during user review).

- `DELETE /api/v1/auth/account` — scrubs PII; soft-delete or hard-delete decision per legal advice
- `GET /api/v1/auth/export` — emits a JSON bundle of all user-owned data (preferences, intake, household membership, recipes-they-created)
- 30-day grace period for delete (optional but common)
- Test that cascading deletes don't break shared-household data (other members' provisions, etc.)

### C3. Ops hygiene completion (~1-2 days)

- `scripts/backup.sh` running `pg_dump` via Windows Task Scheduler / cron, gzipped, OneDrive sync per `design/technical-architecture.md:959-968`
- Restore drill — test the backup works
- Custom `/api/v1/admin/status` endpoint per `:780-781` (DB connectivity, last AI call, last USDA call, current month AI cost)
- Postgres slow-query logging in prod profile

### C4. LLD-drift cheap fixes (~1-2 days)

From the LLD drift audit. Pick one of:
- ArchUnit rule enforcing repository package-private, OR
- Update `lld/style-guide.md` to acknowledge `public` repositories as the accepted reality

Plus:
- Decide global vs per-module exception handlers (consolidate OR update style guide)
- Update `lld/planner.md` to reflect the actual two-impl shape (`PlannerServiceImpl` for read, `PlanWriteServiceImpl` for write)
- Move `lld/notification.md` to `lld/archive/` IF notifications won't ship — otherwise leave it now that B2 will build against it

**Tier C exit criteria:** legal-clean (GDPR), tenant-clean (RBAC), ops-clean (backup + monitoring), design-clean (no silent style drift). Safe to deploy.

## Tier D — Stretch / post-launch (timing TBD)

Not blocking ship-to-users, but valuable.

- **AI cost controls** — Anthropic prompt caching, circuit breaker, pre-call token counting, Batches API. Per gap audit C-C-006/010/015/016. ~5-7 days. Only matters once you have real prompt traffic.
- **Grocery vertical** — shopping lists, grocery orders, price history, Tesco computer-use. Per gap audit cluster. ~3-4 weeks minimum, weeks more for Tesco. Significant scope; defer until post-launch product feedback says it's important.
- **Stage D refine-directive loop** — currently dormant per design. Activate when you want plan-time recipe optimisation to actually run.

## Dependency graph

```
A (CORS, images, env, pagination, OpenAPI) — independent, can run in parallel
    |
    +— B1 (preference Tier 2/3 + bridges)
    |       |
    |       +— B3 (ratings — depends on feedback bridges to close the loop)
    |
    +— B2 (notifications — depends on nothing in B1, but pairs naturally)
    |
    +— B4 (discovery wiring — independent)
    |
    +— C (RBAC, GDPR, ops, LLD-drift — independent, but blocks ship-to-users)
    |
    +— D (stretch — post-launch)
```

Notable independence:
- **Tier A items can run in parallel** with each other (different files)
- **B2 and B4 don't depend on B1** — could run alongside if you have multiple agents
- **C runs entirely independent** — start it as soon as Tier A unblocks frontend work
- **A → B → C is the user-experience sequence; not strict execution sequence**

## Recommended execution pattern

The same per-module-agent pattern that worked for the mutation campaign and the audit work applies here:

- One agent per work item, isolated worktree, scoped brief
- Each agent runs the relevant LLD as input
- Verify against spotless + scoped tests + CI gate (all gates active post-#113)
- PR per work item; merge after CI green

Estimate: with 2-3 agents in parallel, Tier A in 2-3 days. B1.1 + B1.2 + B2 in parallel = 1.5-2 weeks. Then sequential through B1.3, B1.4, B3, B4, then Tier C. **Realistic 5-8 weeks for fully-ready including ship-to-users.**

## Validation: workflow tests (Phase 3 — after Tier B)

Once Tier B lands, run end-to-end workflow tests as the user listed. The premise: these tests should exercise REAL behaviour, not mocked stubs. So they need the foundation hole closed first.

Suggested test cases (one per workflow):

1. **USDA lookup → recipe nutrient persistence**
   - Search USDA for "chicken breast"
   - Save the result to a recipe ingredient
   - Override the calorie value
   - Confirm: ingredient_mapping cached, recipe nutrition reflects override, override flagged so future recalc doesn't overwrite

2. **Recipe URL import**
   - POST URL to import endpoint
   - Confirm: AI extraction completed, USDA mapping applied per ingredient, tags inferred, hard-filter passed
   - Check `data_quality=IMPORTED`, `nutrition_status=COMPLETE` (or `PARTIAL` with `needs_review` flags)

3. **Recipe edit (manual)**
   - PUT update to a recipe
   - Confirm: new RecipeVersion created with `trigger=MANUAL_EDIT`, advisory lock held during edit, `parent_version_id` correct, old version preserved

4. **Week plan generation with mock household**
   - Seed mock data: 2 household members with overlapping but distinct hard constraints, ~30 recipes in catalogue, plausible inventory + budget
   - POST `/api/v1/plans/generate`
   - Confirm: 7 days × N slots filled, no hard-constraint violations, scoring breakdown logged, decision_log row written, Stage C LLM pick recorded

5. **Feedback closure (the big one — requires Tier B)**
   - User submits "I don't like spicy food"
   - Confirm: classification routes to preference (HIGH confidence), PreferenceFeedbackBridge applies delta to Taste Profile, `taste_profile_versions` row appended, archive untouched
   - User submits "I don't have a microwave"
   - Confirm: routes to provisions, equipment record removed, `EquipmentChangedEvent` fires
   - User submits "I had too much sodium today"
   - Confirm: routes to nutrition, micro target adjusted (or warning surfaced if confidence below threshold)
   - User corrects a misclassification
   - Confirm: routing log marked corrected, ground-truth saved, re-routed

6. **Mid-week re-optimisation**
   - Active plan, item spoils in inventory
   - Confirm: ProvisionMaterialityFilter passes, ReoptSuggested event fires, user prompt notification, on accept → scoped re-opt only touches unconsumed slots

These should all be **integration tests** (Spring `@SpringBootTest` + Testcontainers Postgres), not unit tests. Tests live in `src/test/java/com/example/mealprep/workflow/` or similar. Naming convention: `<Workflow>EndToEndIT`.

## What this roadmap doesn't decide

- **Tesco automation timing** — depends on product decision about grocery-in-MVP
- **Multi-week / monthly horizon plans** — per HLD out-of-scope; reconsider post-launch if users ask
- **PWA / mobile** — per HLD deferred; depends on frontend architecture choices not yet made
- **Prometheus / structured metrics** — per HLD out-of-scope; SLF4J + Actuator is the chosen stack

These are noted to make explicit what's deliberately NOT being scheduled.

## Bottom line

A frontend project can start within a week (Tier A: 4-5 days). It can demo the full AI-learning experience in 4-5 weeks (Tier B). It can ship to real users in 6-8 weeks total (Tier C). Stretch items extend timeline post-launch but don't block.

The single most consequential piece of work is **B1.1 Taste Profile entity + B1.4 feedback bridges**. Until that lands, the system is a static-rules planner that can't actually learn — which is the whole point of the project. Everything else is supporting infrastructure for that core loop.
