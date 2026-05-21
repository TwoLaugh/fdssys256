# MealPrep AI — Capability Inventory

Extracted 2026-05-21 from `design/` HLD layer (11 docs, ~6,000 lines).
289 capabilities catalogued — 285 from HLD extraction + 4 user-confirmed additions.

## Section counts

| Section | Capabilities | Notes |
|---|---|---|
| A. Foundational data models | 52 (+1 added) | Entities, attributes, relationships |
| B. User input surfaces | 47 (+1 added) | Every action a user can perform |
| C. AI capabilities | 43 | LLM tasks, prompt management, cost, retry |
| D. Optimization / planning | 39 | Constraint loops + scoring + adaptation |
| E. Recipe management | 22 | Catalogue, branches, versions, substitutions, discovery |
| F. External integrations | 30 | USDA, OFF, Tesco, health platform |
| G. Cross-cutting | 61 (+2 added) | Auth, multi-user, observability, events |
| H. Deferred / out-of-scope | 41 | Explicitly future or rejected |
| IMP. Implicit add-ons | 22 | High-stakes implicit capabilities |
| **Total** | **289** | (~5% overlap between IMP and earlier sections) |

## Tag types

- **EXPLICIT** — HLD literally states the capability
- **IMPLICIT** — HLD describes something that requires this capability to work
- **DEFERRED** — HLD marks as future / post-MVP / v2
- **OUT-OF-SCOPE** — HLD explicitly rules out
- **MISSING-FROM-HLD** — added by user spot-check; not in HLD but required regardless

## A. Foundational data models

C-A-001: Preference Model — three-tier structure
- Three tiers: hard constraints (DB-locked), taste profile (AI JSON ~2500 tokens), lifestyle config (settings)
- Source: system-overview.md:121; preference-model.md:7-18
- Type: EXPLICIT

C-A-002: Hard constraints storage (DB-locked, user-only)
- Allergies, dietary identity (base + structured exceptions + display label), severe intolerances, age restrictions stored in hard-locked DB table editable only by user
- Source: preference-model.md:27-53; system-overview.md:119
- Type: EXPLICIT

C-A-003: Taste profile JSON document (AI-maintained)
- Structured JSON: flavour preferences, texture, ingredient preferences (favourites/disliked/trending with evidence_count/last_signal/source), cuisine, cooking method, portion style, household_context, recipes_to_repeat/avoid, active_experiments, learned_insights
- Source: preference-model.md:75-163
- Type: EXPLICIT

C-A-004: Preference archive (unbounded)
- Items pruned from taste profile move to unbounded archive preserving evidence count, last signal date, pruning reason
- Source: preference-model.md:71; technical-architecture.md:199
- Type: EXPLICIT

C-A-005: Lifestyle config — full settings shape
- DB-stored settings: meal_structure (per weekday/weekend), meal_timing, novelty_tolerance, cooking_contexts, batch_cooking, reheating_preferences, eating_context, seasonal_preferences, meal_type_preferences, accompaniments, grocery_quality_preferences
- Source: preference-model.md:189-292
- Type: EXPLICIT

C-A-006: Profile metadata
- age_group, portion_scale, preference_volatility, update_confirmation_threshold
- Source: preference-model.md:316-332
- Type: EXPLICIT

C-A-007: Nutrition targets (macros)
- Calorie + macro (protein, carbs, fat, fibre) targets in grams, per-target enforcement (daily_floor vs weekly_average), per-meal distribution
- Source: nutrition-model.md:30-75
- Type: EXPLICIT

C-A-008: Micronutrient targets
- Iron, zinc, B12, vitamin D, omega-3, magnesium, calcium, sodium upper limit, potassium — tracked internally from day one; v1 UI shows macros only
- Source: nutrition-model.md:95-112
- Type: EXPLICIT

C-A-009: TDEE / activity adjustments
- Per-activity-level modifiers for calories and carbs; manual activity input in v1
- Source: nutrition-model.md:80-91
- Type: EXPLICIT

C-A-010: Eating windows (intermittent fasting)
- Nutrition-Model-owned eating_window with start/end times. Takes precedence over Preference Model meal_timing
- Source: nutrition-model.md:118-131
- Type: EXPLICIT

C-A-011: Planned vs actual intake log
- Per-day, per-meal record with planned (recipe_id, calories, macros) and actual (status: confirmed/overridden/pending, free_text, calories, macros) plus snacks and daily totals
- Source: nutrition-model.md:151-178
- Type: EXPLICIT

C-A-012: Food/mood journal
- Free-text journal entries tied to meal slots with timestamps; used as context by feedback classifier
- Source: nutrition-model.md:194-221
- Type: EXPLICIT

C-A-013: Provisions inventory
- item_id, name, category, storage_location, quantity, unit, expiry_date, added_at, source, source_ref, cost_paid, ingredient_mapping_key, notes
- Source: provision-model.md:53-84
- Type: EXPLICIT

C-A-014: Spice rack / staples (status-tracked)
- Status-tracked items (stocked/low/out) with is_staple flag; gram-level tracking deliberately not used
- Source: provision-model.md:97-124
- Type: EXPLICIT

C-A-015: Freezer-specific item fields
- frozen_at, max_freeze_weeks, defrost_method, defrost_lead_time_hours, source_recipe_id
- Source: provision-model.md:128-149
- Type: EXPLICIT

C-A-016: Equipment list (hard filter)
- List of equipment with name, available flag, details. Used as hard filter on recipe feasibility
- Source: provision-model.md:200-220
- Type: EXPLICIT

C-A-017: Budget (weekly target, tolerance, price_sensitivity)
- Weekly grocery spend target with currency, tolerance_over, price_sensitivity (low/moderate/high)
- Source: provision-model.md:231-249
- Type: EXPLICIT

C-A-018: Budget spend tracking
- Actual spend per week, orders that contributed, rolling 4-week average. Prompts adjustment if consistently over
- Source: provision-model.md:253-270
- Type: EXPLICIT

C-A-019: Supplier product cache
- Per (supplier, product): product_id, name, price, price_per_unit, unit, pack_size_g, last_checked, clubcard_price, substitution_history, ingredient_mapping_key
- Source: provision-model.md:280-296
- Type: EXPLICIT

C-A-020: Substitution history on supplier products
- Array of substitution records (date, substituted_with, accepted) per product
- Source: provision-model.md:292-294
- Type: EXPLICIT

C-A-021: Food waste log
- Per-entry: item, quantity, reason, cost_estimate, date, notes. Immutable; rolled to summaries after 3 months
- Source: provision-model.md:331-362
- Type: EXPLICIT

C-A-022: Recipe entity with full data model
- catalogue flag, name, description, current_version, current_branch, ingredients, method, metadata, character_fingerprint, nutrition_per_serving, rating, data_quality, source
- Source: recipe-system.md:62-131
- Type: EXPLICIT

C-A-023: Structured metadata tags
- Fixed dimensions (protein, cooking_method, complexity, flavour_profile, dietary_flags), free values. AI-inferred on import
- Source: recipe-system.md:97-103
- Type: EXPLICIT

C-A-024: Recipe data_quality tier
- Four levels: user_verified / imported / ai_generated / web_discovered
- Source: recipe-system.md:125-132
- Type: EXPLICIT

C-A-025: Character fingerprint
- defining_ingredients, defining_techniques, texture_essentials, flavour_anchors, complexity_tier, cuisine_anchor. Extracted on import, refreshed only on branch creation
- Source: recipe-system.md:137-155
- Type: EXPLICIT

C-A-026: Multi-dimensional recipe rating
- Four dimensions (taste, effort_worth_it, portion_fit, repeat_value) each 0-100 with count; per-version ratings
- Source: recipe-system.md:158-181
- Type: EXPLICIT

C-A-027: Recipe versions (linear)
- Every change creates new version with parent_version_id, structured changes diff, change_reason, trigger, created_by, trace_id
- Source: recipe-system.md:188-211
- Type: EXPLICIT

C-A-028: Recipe branches (creative forks)
- branch_id, parent_branch, branch_point_version, label, reason, current_version, own character_fingerprint
- Source: recipe-system.md:217-238
- Type: EXPLICIT

C-A-029: Branch divergence detection + standalone promotion
- Track divergence per branch (shared ingredients %, method similarity); above threshold prompts to promote
- Source: recipe-system.md:240-243
- Type: EXPLICIT

C-A-030: Substitutions (plan-level overlays)
- Overlays with original/substitute, reason, constraint_ref, temporary flag, applied_in_plans, notes, method_overlay
- Source: recipe-system.md:248-270
- Type: EXPLICIT

C-A-031: Substitution → version promotion
- After N applications (default 3) without revert, prompt to promote overlay to version
- Source: recipe-system.md:272-277
- Type: EXPLICIT

C-A-032: Two catalogues (user vs system)
- User catalogue (curated, approval required); system catalogue (AI-managed, direct write). Same schema
- Source: recipe-system.md:38-54
- Type: EXPLICIT

C-A-033: Plan entity
- plan_id, household_id, week_start_date, generation, replaces_plan_id, trigger, status (draft/generated/active/superseded/completed/rejected/abandoned), days array
- Source: meal-planner.md:118-134
- Type: EXPLICIT

C-A-034: Day + MealSlot + ScheduledRecipe model
- Day with date/slots/notes; MealSlot with kind, label, time_budget_min, shared flag, eaters array, scheduled_recipe, state; ScheduledRecipe with recipe_id, version, branch, servings, batch_cook_session_id, augmentation_notes
- Source: meal-planner.md:140-181
- Type: EXPLICIT

C-A-035: Batch cook session linkage
- batch_cook_session_id links slots that share one cook
- Source: meal-planner.md:177-180
- Type: EXPLICIT

C-A-036: Custom and per-person meal slots
- Slot kind supports breakfast/lunch/dinner/snack/custom; shared vs per-person with eaters list
- Source: meal-planner.md:155-160
- Type: EXPLICIT

C-A-037: Ingredient mapping cache
- Per-ingredient: search_term (key), usda_fdc_id, nutrition_per_100g (jsonb full macros+micros), default_piece_grams, confidence, last_verified, source
- Source: nutrition-model.md:454-465
- Type: EXPLICIT

C-A-038: Decision log
- decision_id, trace_id, parent_decision_id, scale, triggered_by, inputs, candidates with rollup, chosen, reasoning, emitted_directive, iteration, timestamps, duration_ms
- Source: optimisation-loop.md:256-286
- Type: EXPLICIT

C-A-039: AI call log
- Per-call: timestamp, user_id, task_type, model_used, prompt_version, input/output tokens, cost_estimate, latency_ms, success, retry_count, cache_hit
- Source: technical-architecture.md:446-460
- Type: EXPLICIT

C-A-040: Adaptation trace log
- Per-job trace keyed by trace_id: job_id, recipe_id, source, prompt_template_version, ai_model, inputs_snapshot, raw_ai_response, classification_decision, final_diff, confidence, validation_result, outcome, duration_ms. 6-month retention
- Source: recipe-system.md:531-563
- Type: EXPLICIT

C-A-041: Feedback entries + routing log tables
- feedback_entries + feedback_routing_log (per-route destination, confidence, extracted_feedback, action_taken, status, correction record)
- Source: feedback-system.md:305-322
- Type: EXPLICIT

C-A-042: Pending change entity
- pending_change_id, recipe_id, job_id, trace_id, change_dimension, proposed_diff, reasoning, status, created_at, expires_at (14 days)
- Source: recipe-system.md:420-434
- Type: EXPLICIT

C-A-043: AdaptationJob entity
- jobId, recipeId, source (IMPORT/FEEDBACK/DATA_MODEL_CHANGE/PLAN_TIME), priority (SYNC/ASYNC/BATCH), approvalPolicy, inputs, promptTemplateVersion, status, traceId
- Source: recipe-system.md:319-330
- Type: EXPLICIT

C-A-044: Notification log table
- notification_log table
- Source: technical-architecture.md:216
- Type: EXPLICIT

C-A-045: Planner jobs table
- planner_jobs for tracking async plan generation jobs
- Source: technical-architecture.md:211
- Type: EXPLICIT

C-A-046: Shopping list persisted as derived state
- shopping_lists rows for history; never edited directly — re-derives from plan/provisions changes
- Source: grocery.md:99-101
- Type: EXPLICIT

C-A-047: Grocery orders + provider state + substitution proposals + price history tables
- grocery_orders, grocery_provider_state, grocery_substitution_proposals, grocery_price_history
- Source: grocery.md:23, 161, 365
- Type: EXPLICIT

C-A-048: Price history observations
- ingredient_mapping_key, store, paid_unit_price, quantity, total_price, source (paid/quote/manual/manual_estimated/inflation_indexed), observed_at, confidence_weight, order_id
- Source: grocery.md:255-270
- Type: EXPLICIT

C-A-049: Household entity
- household_members, household_environments tables; HouseholdRoleChangedEvent
- Source: technical-architecture.md:217-218
- Type: EXPLICIT

C-A-050: Auth user entity
- auth_users; username + hashed password (no OAuth in v1); links to data models and household
- Source: system-overview.md:320-324
- Type: EXPLICIT

C-A-051: Recipe embedding column reserved
- pgvector column reserved; pipeline planner-owned
- Source: recipe-system.md:121, 723
- Type: EXPLICIT

C-A-052: Cost confidence aggregates per ingredient
- Per (ingredient_mapping_key, store) and cross-store: point estimate, confidence (Bayesian), range, last-seen recency
- Source: grocery.md:272-280
- Type: EXPLICIT

C-A-053: Image storage backend
- Recipe upload images need defined storage backend (local FS / S3 / base64-in-db / cloud blob). HLD specifies UX but not persistence
- Source: recipe-system.md:521-523 (storage backend unspecified)
- Type: MISSING-FROM-HLD (functional requirement)

## B. User input surfaces

C-B-001: Conversational free-text feedback submission
- Single conversational interface, accepts free-text with implicit UI context (screen, recipe_id, meal_slot_id, plan_id)
- Source: feedback-system.md:50-65
- Type: EXPLICIT

C-B-002: Misclassification correction by user
- User flags route as "this isn't right," sees alternatives, selects correct one; re-routes and logs ground truth
- Source: feedback-system.md:249-260
- Type: EXPLICIT

C-B-003: Manual hard constraint edits
- Hard constraints editable only by user directly through dedicated UI; never by AI/feedback/optimiser
- Source: preference-model.md:29-33
- Type: EXPLICIT

C-B-004: User taste profile viewing and override
- View "here's what I think you like," manually override any preference, overrides flagged
- Source: preference-model.md:421, 498-501
- Type: EXPLICIT

C-B-005: Lifestyle config edits via settings UI
- Standard settings UI; not touched by AI; periodic 2-3 month review prompts
- Source: preference-model.md:185, 309-310
- Type: EXPLICIT

C-B-006: Nutrition target manual editing
- User can override any macro/micro target, eating window, activity adjustments
- Source: nutrition-model.md:567-577
- Type: EXPLICIT

C-B-007: Standalone food logging (search USDA/OFF for snacks/drinks)
- User can search and log standalone food items directly from USDA/OFF, MyFitnessPal-style
- Source: nutrition-model.md:146
- Type: EXPLICIT

C-B-008: Free-text intake correction with AI parsing
- "I had a cheese sandwich instead" → AI parses, maps through nutrition engine, logs actual nutrition
- Source: nutrition-model.md:143-146
- Type: EXPLICIT

C-B-009: Single-tap intake confirmation
- Pre-filled planned meal confirmed with one tap
- Source: nutrition-model.md:141
- Type: EXPLICIT

C-B-010: Mark meal skipped
- Mark planned meal as skipped (logged as zero intake)
- Source: nutrition-model.md:144
- Type: EXPLICIT

C-B-011: User-correction of nutrition values
- Override calculated nutrition or specific ingredient mapping; overrides flagged
- Source: nutrition-model.md:489-496
- Type: EXPLICIT

C-B-012: Mark meal cooked (cook event)
- UI mark-cooked publishes MealCookedEvent, triggers inventory deduction with confirmation prompt
- Source: technical-architecture.md:90, 184
- Type: EXPLICIT

C-B-013: Cook-event ingredient deduction confirmation/correction
- "Removed from pantry: 400g chicken thighs..." — user can correct
- Source: provision-model.md:184
- Type: EXPLICIT

C-B-014: Batch cook fridge/freezer portion split
- On batch cook, user specifies storage split; creates split inventory entries
- Source: provision-model.md:186
- Type: EXPLICIT

C-B-015: Mark meal consumed (pre-made meal)
- Distinct from cook event — confirming user ate pre-made triggers one-portion deduction
- Source: provision-model.md:179
- Type: EXPLICIT

C-B-016: Manual add inventory item
- Add items bought elsewhere
- Source: provision-model.md:181
- Type: EXPLICIT

C-B-017: Manual remove inventory item
- Remove items (snacked, gone off, gave away)
- Source: provision-model.md:182
- Type: EXPLICIT

C-B-018: Inventory correction (quantity, expiry, location)
- Adjust quantity, change expiry, change location
- Source: provision-model.md:476-477
- Type: EXPLICIT

C-B-019: Waste log entry
- User logs wasted items with reason, quantity, cost estimate, notes
- Source: provision-model.md:183
- Type: EXPLICIT

C-B-020: Manual supplier price correction
- Override cached price; takes precedence until next grocery order refreshes
- Source: provision-model.md:478-479
- Type: EXPLICIT

C-B-021: Staple status toggle
- Single-tap status update for staple items (stocked/low/out)
- Source: provision-model.md:480-481
- Type: EXPLICIT

C-B-022: Equipment list edit
- Equipment feedback updates equipment list
- Source: provision-model.md:430
- Type: EXPLICIT

C-B-023: Budget adjustments anywhere
- Change target, tolerance, sensitivity at any time
- Source: provision-model.md:478-479
- Type: EXPLICIT

C-B-024: Manual recipe entry / form
- User fills form to create a recipe
- Source: recipe-system.md:464
- Type: EXPLICIT

C-B-025: Recipe edit (manual)
- Recipe Engine directly editable by user with no AI in the loop
- Source: system-overview.md:301-302
- Type: EXPLICIT

C-B-026: User-image upload for recipe
- Manual entries can upload image files
- Source: recipe-system.md:521-523
- Type: EXPLICIT

C-B-027: Promote system catalogue recipe to user catalogue
- One-tap promotion
- Source: system-overview.md:163
- Type: EXPLICIT

C-B-028: Demote user catalogue recipe back to system
- Soft delete; data preserved
- Source: recipe-system.md:41-44
- Type: EXPLICIT

C-B-029: Accept/reject/modify pending recipe change
- Side-by-side diff UI, per-change accept/reject, with conversational AI suggestion box
- Source: system-overview.md:228-231
- Type: EXPLICIT

C-B-030: Revert recipe to prior version
- RecipeUpdateService.revertToVersion
- Source: recipe-system.md:186, 722
- Type: EXPLICIT

C-B-031: User accepts/rejects plan
- generated → active via accept; generated → rejected via reject
- Source: meal-planner.md:212-215
- Type: EXPLICIT

C-B-032: User abandons active plan mid-week
- active → abandoned via service call with reason
- Source: meal-planner.md:226-228
- Type: EXPLICIT

C-B-033: User reverts to historical plan
- Revert creates new plan with generation = current+1; content copied
- Source: meal-planner.md:248-253
- Type: EXPLICIT

C-B-034: Override LLM plan pick
- UI presents all 5 candidates with LLM recommendation; user can override
- Source: meal-planner.md:106-107
- Type: EXPLICIT

C-B-035: Mark slot state transitions
- markSlotState supports planned → cooking → cooked → eaten or skipped
- Source: meal-planner.md:560
- Type: EXPLICIT

C-B-036: User-initiated mid-week re-optimisation
- Manual button directly invokes reoptimisePlan
- Source: meal-planner.md:406
- Type: EXPLICIT

C-B-037: Accept/reject health platform directive
- User reviews directive with evidence summary; accepts/rejects/modifies
- Source: nutrition-model.md:283-294
- Type: EXPLICIT

C-B-038: User-initiated taste profile update
- Manually triggered (in addition to every 5 feedbacks or weekly)
- Source: preference-model.md:346
- Type: EXPLICIT

C-B-039: Mark items bought (Tier 2 manual fulfilment)
- Per-line tap, quantity adjust, price entry, store. Bulk "mark all bought"
- Source: grocery.md:113-122
- Type: EXPLICIT

C-B-040: Refresh prices on demand
- "Refresh prices" affordance triggers quote pass
- Source: grocery.md:284
- Type: EXPLICIT

C-B-041: User accepts/rejects substitution proposal
- Per substitution: accept (substitute enters inventory) or reject (wasted-on-arrival)
- Source: grocery.md:211-219
- Type: EXPLICIT

C-B-042: User confirms grocery order in provider UI
- placed → awaiting_user_confirmation → confirmed; never auto-confirm
- Source: grocery.md:160-161
- Type: EXPLICIT

C-B-043: User cancels grocery order
- Cancel from any state until reconciled
- Source: grocery.md:184
- Type: EXPLICIT

C-B-044: Onboarding flows for each tier
- Hard constraints, preference quiz (10-15 dishes), staples checklist, equipment checklist, budget question, nutrition targets with Mifflin-St Jeor defaults
- Source: preference-model.md:469-491; provision-model.md:516-528
- Type: EXPLICIT

C-B-045: Quick-start recipe import on onboarding
- "Paste your 5 favourite recipe URLs" seeds user catalogue
- Source: recipe-system.md:683-685
- Type: EXPLICIT

C-B-046: Behavioural drift prompts
- Periodic prompts when logged behaviour contradicts config
- Source: preference-model.md:308-310
- Type: EXPLICIT

C-B-047: Day-level notes
- Per-day notes string user can set on Day entity
- Source: meal-planner.md:147
- Type: EXPLICIT

C-B-048: Intake history search and filter
- User can query past intake by date range, recipe, meal type, free-text content. Needed once users have months of accumulated data
- Source: NOT IN LIVE HLD. Generic gap noted in archive/design-review.md:133
- Type: MISSING-FROM-HLD (functional requirement)

## C. AI capabilities

C-C-001: AI Service abstraction layer
- Central AiService with AiTask interface; modules define task types; service handles routing, calls, parsing, logging
- Source: system-overview.md:330-337; technical-architecture.md:284-304
- Type: EXPLICIT

C-C-002: Three model tiers with config-driven mapping
- Tier-to-model mapping in YAML config with per-task overrides; no code change to swap
- Source: technical-architecture.md:340-361
- Type: EXPLICIT

C-C-003: Anthropic tool use for structured output
- All structured output uses tool definitions; guaranteed valid JSON
- Source: technical-architecture.md:308-336
- Type: EXPLICIT

C-C-004: Prompt template file storage (per module)
- Templates as files under src/main/resources/prompts/, version-controlled
- Source: technical-architecture.md:382-407
- Type: EXPLICIT

C-C-005: Prompt template versioning (pinned per job)
- prompt_template_version pinned at enqueue; staged rollouts; per-version metrics
- Source: technical-architecture.md:447; recipe-system.md:565-575
- Type: EXPLICIT

C-C-006: Prompt caching (Anthropic cache_control)
- Shared prefix between Pass 1 / Pass 2 cached; billed at 10%
- Source: technical-architecture.md:440-441
- Type: EXPLICIT

C-C-007: Per-task token caps
- Per-task input token caps; abort before calling if context exceeds
- Source: technical-architecture.md:364-378
- Type: EXPLICIT

C-C-008: Per-user daily spend cap
- Configurable default £5/day cap; degrade gracefully
- Source: technical-architecture.md:463
- Type: EXPLICIT

C-C-009: Per-task-type rate limits
- Plan generation max few/day, feedback classification max ~20/day
- Source: technical-architecture.md:464
- Type: EXPLICIT

C-C-010: Circuit breaker (Resilience4j)
- 5 consecutive failures → stop trying for 5 minutes
- Source: technical-architecture.md:465
- Type: EXPLICIT

C-C-011: AI retry on semantic errors (max 1)
- Only retry on semantic errors with correction prompt
- Source: technical-architecture.md:318-336
- Type: EXPLICIT

C-C-012: Idempotency for expensive AI operations
- Plan generation double-click dedup'd at job level
- Source: technical-architecture.md:467
- Type: EXPLICIT

C-C-013: AI cost tracking per call
- ai_call_log captures cost_estimate per call; monthly summary in settings
- Source: technical-architecture.md:443-460
- Type: EXPLICIT

C-C-014: Cost dashboard / monthly summary endpoint
- GET /api/v1/settings/ai-usage returns monthly cost summary
- Source: technical-architecture.md:780
- Type: EXPLICIT

C-C-015: Token counting before call
- Anthropic SDK count_tokens to verify context size when variable
- Source: technical-architecture.md:481
- Type: EXPLICIT

C-C-016: Anthropic Batches API for non-urgent
- DATA_MODEL_CHANGE may adapt 50+ system recipes via Batches API at 50% cost, within 24h
- Source: technical-architecture.md:480
- Type: EXPLICIT

C-C-017: AI extension: streaming
- Streaming for Phase 2 plan assembly UX
- Source: technical-architecture.md:479
- Type: DEFERRED

C-C-018: Two-pass planning context strategy
- Pass 1: cheap/mid model returns 15-20 candidate IDs from recipe index (~2500 tokens). Pass 2: frontier picks from full recipes (~5000 tokens)
- Source: technical-architecture.md:415-433
- Type: EXPLICIT

C-C-019: Plan composition AI task (Phase 1)
- Deterministic in v1 with hybrid LLM option
- Source: technical-architecture.md:366
- Type: EXPLICIT

C-C-020: Plan augmentation AI task (Phase 2 creative)
- Frontier model picks from N=5 plans, adds snacks/sides, swap ingredients. Max 5 augmentations, max 2 swap directives
- Source: meal-planner.md:99-110
- Type: EXPLICIT

C-C-021: Feedback classification AI task
- Cheap-model task classifying free-text into 0+ destinations with confidence, extracted_feedback, recipe_id
- Source: feedback-system.md:172-197
- Type: EXPLICIT

C-C-022: Confidence-based feedback handling
- ≥0.8 auto-route; 0.5-0.8 route+flag; <0.5 ask user to clarify
- Source: feedback-system.md:200-209
- Type: EXPLICIT

C-C-023: Recipe adaptation AI task (3 intelligence layers)
- Culinary (flavour, texture), nutritional (absorption, pairing), constraint satisfaction. All three active
- Source: recipe-system.md:295-301
- Type: EXPLICIT

C-C-024: Character preservation self-check by AI
- AI confirms preservation against character_fingerprint; broken = flagged not applied
- Source: recipe-system.md:359-365
- Type: EXPLICIT

C-C-025: Adaptation classification (VERSION/BRANCH/SUBSTITUTION/NO_CHANGE)
- AI returns one of four based on fingerprint check and temporal nature
- Source: recipe-system.md:380-396
- Type: EXPLICIT

C-C-026: AI confidence floor for auto-application
- Below 0.5, flag for user review even for system catalogue
- Source: recipe-system.md:361-365
- Type: EXPLICIT

C-C-027: Recipe URL import via AI extraction
- Mid-tier model, tool-use structured extraction from web pages with per-ingredient confidence
- Source: recipe-system.md:464, 500-504
- Type: EXPLICIT

C-C-028: AI tag inference on import
- Cheap model infers cuisine, protein, cooking_method, complexity, flavour_profile
- Source: recipe-system.md:480-483
- Type: EXPLICIT

C-C-029: Recipe AI generation (gap-filling)
- Pipeline-triggered when planner identifies gap; generates against constraint brief
- Source: recipe-system.md:466
- Type: EXPLICIT

C-C-030: Online recipe discovery (search + AI filter)
- Weekly or gap-triggered web search, hard-filter, score against preferences. Goes to system catalogue
- Source: recipe-system.md:467
- Type: EXPLICIT

C-C-031: Ingredient AI parsing
- Cheap model parses ingredients into structured (ingredient, quantity, unit, grams_estimate, usda_search_term, is_cooked) with confidence per ingredient
- Source: nutrition-model.md:414-433
- Type: EXPLICIT

C-C-032: USDA match selection by AI
- After API search returns top-N matches, cheap model picks correct USDA FDC ID + confidence
- Source: nutrition-model.md:436-450
- Type: EXPLICIT

C-C-033: Per-ingredient mapping confidence + needs_review flag
- Below 0.7 threshold flagged needs_review
- Source: recipe-system.md:500-504
- Type: EXPLICIT

C-C-034: AI delta updates to taste profile
- Mid-tier model proposes ADD/REMOVE/UPDATE/PROMOTE/DISCARD/ARCHIVE/RE-PROMOTE deltas; schema-validated
- Source: preference-model.md:336-396
- Type: EXPLICIT

C-C-035: Health platform directive parsing (AI)
- Cheap model parses directives
- Source: system-overview.md:357
- Type: EXPLICIT

C-C-036: Tesco product matching AI
- Mid/frontier model matches shopping list to Tesco product catalogue during navigation
- Source: technical-architecture.md:376
- Type: EXPLICIT

C-C-037: Quality evaluation deferred for v1
- Use mechanical metrics + feedback + 5-10 golden test cases
- Source: technical-architecture.md:471
- Type: DEFERRED

C-C-038: 5-10 golden test cases for prompt regression
- Fixed inputs with manually-judged good outputs
- Source: technical-architecture.md:471
- Type: EXPLICIT

C-C-039: Anomaly detection on taste profile updates
- After each update, compute structural diff; alert if >3 items removed
- Source: preference-model.md:417-419
- Type: EXPLICIT

C-C-040: Rollback of taste profile version
- Revert + replay feedback from rolled-back version's feedback_cursor forward
- Source: preference-model.md:421
- Type: EXPLICIT

C-C-041: Adaptive prompt enrichment via tool calls (future)
- v1 prompt-based; future could use real-time tool use
- Source: recipe-system.md:303-313
- Type: DEFERRED

C-C-042: Plausibility checking for recipe nutrition
- Flag recipes with suspicious numbers
- Source: nutrition-model.md:595
- Type: DEFERRED

C-C-043: Mock AiService in test profile
- Mock + £0 cap catches accidental real API calls
- Source: technical-architecture.md:955-958
- Type: EXPLICIT

## D. Optimization / planning

C-D-001: Optimization loop pattern
- A: candidate gen, B: rollup, C: choice, D: refine. Four-stage applied at week and recipe scales. A+B deterministic, C intelligence, D recursion
- Source: optimisation-loop.md:18-63
- Type: EXPLICIT

C-D-002: Hard-filter (pure code, before scoring)
- Hard constraints (allergies, dietary, equipment, household union) filter candidates before scoring
- Source: optimisation-loop.md:73-83
- Type: EXPLICIT

C-D-003: HardConstraintFilterService (shared)
- Service in preference module; methods check, checkRecipe, filterRecipes, checkForHousehold; injected by planner, optimiser, discovery, grocery
- Source: technical-architecture.md:145-181
- Type: EXPLICIT

C-D-004: Beam search (default Stage A algorithm)
- Width 20, depth = scope size; produces top-N=5; sub-second on realistic catalogues
- Source: optimisation-loop.md:97-104
- Type: EXPLICIT

C-D-005: Weighted-sum scoring with normalised sub-scores
- Each normalised to [0,1]; weighted sum gives composite
- Source: optimisation-loop.md:86-94
- Type: EXPLICIT

C-D-006: Multiplicative gates in scoring
- Reserved for "should be zero" — nutrition_floor_gate, variety_gate
- Source: optimisation-loop.md:92
- Type: EXPLICIT

C-D-007: Seven sub-scores at week scale
- preference, nutrition, cost, variety, time, batch, provisions
- Source: meal-planner.md:272-285
- Type: EXPLICIT

C-D-008: Initial uniform weights (1/7) for v1
- All weights ~0.143; calibration after ~10 plans
- Source: meal-planner.md:318-327
- Type: EXPLICIT

C-D-009: Rollup aggregator (fixed flat shape per scale)
- Week-level: daily_nutrition[7], weekly_macro_totals, cost_total + confidence, stale_ingredient_count, variety_index, time_total_per_day, batch_cook_sessions, score_breakdown
- Source: optimisation-loop.md:114-120
- Type: EXPLICIT

C-D-010: Top-N=5 default
- Default N=5; each scale may override; skip C-stage if top scores 2x runner-up
- Source: optimisation-loop.md:123-129
- Type: EXPLICIT

C-D-011: Cost sub-score with confidence regression
- Low-confidence projections regressed toward 0.5 neutral
- Source: meal-planner.md:307-315
- Type: EXPLICIT

C-D-012: Stage C creative augmentation (Phase 2)
- LLM picks from N=5, may add snacks/sides, swap via refine-directive, re-pair sides. Max 5 augmentations, max 2 swap directives
- Source: meal-planner.md:99-110
- Type: EXPLICIT

C-D-013: Augmentation hard-filter post-hoc
- Every augmentation passes through hard-filter; silent discard + log
- Source: meal-planner.md:351-354
- Type: EXPLICIT

C-D-014: Stage D refine-directives (to Recipe Optimiser)
- Planner emits refine-directive ("Wed's stir-fry needs to drop £2"); waits sync; re-runs Stage A
- Source: meal-planner.md:108-110
- Type: EXPLICIT

C-D-015: Iteration budget (3 refine cycles default)
- After 3 cycles, accept current best; unsatisfied directive logged
- Source: optimisation-loop.md:239-242
- Type: EXPLICIT

C-D-016: Fixed-point detection
- Same top-N twice in a row → stop
- Source: optimisation-loop.md:244-246
- Type: EXPLICIT

C-D-017: User abort
- Loop cancellable; cancelled persists nothing
- Source: optimisation-loop.md:248-251
- Type: EXPLICIT

C-D-018: Single-flight per scope
- One week-plan per household at a time, one adaptation per recipe
- Source: optimisation-loop.md:301
- Type: EXPLICIT

C-D-019: Mid-week re-optimization with pinning
- Same loop scoped to remaining slots. eaten/cooked/cooking/skipped immutable; planned-past pinned-or-skip; planned-future regenerable
- Source: meal-planner.md:395-449
- Type: EXPLICIT

C-D-020: Re-opt trigger filtering
- Provisions: spoiled/substituted affecting unconsumed slot. Nutrition: ≥15% macro variance. Preference: any hard-constraint change
- Source: meal-planner.md:408-413
- Type: EXPLICIT

C-D-021: Re-opt user confirmation (no auto-replace)
- Notification ("ingredient ran out — regenerate?"); user always confirms
- Source: meal-planner.md:441-444
- Type: EXPLICIT

C-D-022: Constraint feasibility check (before Stage A)
- Post-hard-filter pool size per slot; identifies slots below min_pool_per_slot (default 3); classifies conflict; surfaces resolutions
- Source: meal-planner.md:362-389
- Type: EXPLICIT

C-D-023: Four conflict types with proposed resolutions
- Household hard collision → split slot; Nutrition vs budget → ranked options; Provisions bottleneck → workarounds; Over-specified preferences → widen most restrictive
- Source: meal-planner.md:376-385
- Type: EXPLICIT

C-D-024: User-decline path: quality-flagged partial plan
- Best possible with quality_warning: true
- Source: meal-planner.md:387-389
- Type: EXPLICIT

C-D-025: Catalogue pre-filtering before index assembly
- Pre-filter excludes hard-filter failures, archived/unused system catalogue, coarse lifestyle filters
- Source: technical-architecture.md:432-434
- Type: EXPLICIT

C-D-026: Cold-start planner path
- If catalogue size < heuristic minimum (≥3× slot count), triggers discovery + generation pre-step. Bounded — up to 50 recipes. Flagged cold_start: true
- Source: meal-planner.md:255-263
- Type: EXPLICIT

C-D-027: Plan storage immutable + copy-forward revert
- Old plans never mutated; revert creates new plan
- Source: meal-planner.md:137-138
- Type: EXPLICIT

C-D-028: Plan lifecycle events
- PlanGenerated/Accepted/Superseded/Completed/Rejected/Abandoned, ReoptTriggered, ReoptSuggested
- Source: meal-planner.md:515-524
- Type: EXPLICIT

C-D-029: Recipe-scale adaptation loop
- Same loop with smaller candidate space — exhaustive scoring instead of beam search
- Source: optimisation-loop.md:108-110
- Type: EXPLICIT

C-D-030: Pantry-first planning for mid-week re-opt
- Inverts approach: start from "what needs using up" and build meals around those
- Source: provision-model.md:401-403
- Type: EXPLICIT

C-D-031: Provisions utilisation scoring (not hard pin)
- "Existing food should take precedence" via scoring sub-score; wasting purchased = worse score
- Source: meal-planner.md:428-438
- Type: EXPLICIT

C-D-032: Shopping list calculation (deterministic, planner-owned)
- Six steps: aggregate planned demand, subtract inventory, apply pack-size heuristics, add low/out staples, apply quality prefs, cost projection. No AI
- Source: grocery.md:81-90
- Type: EXPLICIT

C-D-033: Plan-time recipe optimisation (Trigger 4)
- Planner invokes Recipe Optimiser as pre-step to expand pool; substitution overlay attached
- Source: recipe-system.md:336-339
- Type: EXPLICIT

C-D-034: Greedy fallback when Stage A timeout
- Reduce beam width, retry once, then degrade to greedy. Plan flagged
- Source: meal-planner.md:485
- Type: EXPLICIT

C-D-035: Snapshot-based plan composition
- Plan composed against snapshot taken at Stage A start; stale recipes caught at slot rendering
- Source: meal-planner.md:486
- Type: EXPLICIT

C-D-036: Defrost lead-time scheduling
- Account for defrost_lead_time_hours when scheduling frozen
- Source: provision-model.md:151-153
- Type: EXPLICIT

C-D-037: Expiry-driven recipe prioritisation
- Planner prioritises recipes that use items approaching expiry
- Source: provision-model.md:159-160
- Type: EXPLICIT

C-D-038: Per-meal calorie distribution from meal structure default
- Per-meal targets defaulted from total calorie target and user's meal structure
- Source: nutrition-model.md:573-574
- Type: EXPLICIT

C-D-039: Stage skip when top score >2x runner-up
- Skip Stage C; logged as decision-log event
- Source: optimisation-loop.md:128-129
- Type: EXPLICIT

## E. Recipe management

C-E-001: Recipe creation (manual, URL import, AI gen, discovery)
- Four entry points; one common pipeline → nutrition mapping → tag inference → hard-constraint check → store
- Source: recipe-system.md:462-498
- Type: EXPLICIT

C-E-002: Recipe deduplication on import
- Normalised ingredient-set hash; collisions above 80% overlap + method ±20% surface "merge / variant branch / import anyway"
- Source: recipe-system.md:506-512
- Type: EXPLICIT

C-E-003: Recipe import failure handling
- URL unreachable: fail fast. AI garbage: store with needs_review + imported quality. Foreign-language: extract what possible. USDA fails: nutrition_status: pending
- Source: recipe-system.md:514-519
- Type: EXPLICIT

C-E-004: Recipe nutrition recalculation events
- RecipeEvolvedEvent triggers Nutrition Engine recalculation on every version/branch
- Source: recipe-system.md:376
- Type: EXPLICIT

C-E-005: Adaptation pipeline four triggers
- IMPORT (async), FEEDBACK (sync, user waiting), DATA_MODEL_CHANGE (batch), PLAN_TIME (sync, planner waiting)
- Source: recipe-system.md:332-340
- Type: EXPLICIT

C-E-006: Pending change supersession (per recipe, per dimension)
- Keyed by (recipe_id, change_dimension); new supersedes unreviewed prior
- Source: recipe-system.md:437-446
- Type: EXPLICIT

C-E-007: Optimisation budget per user per week (3 pending changes)
- Max 3/week default; additional ranked by confidence × impact for next week. System catalogue bypasses
- Source: recipe-system.md:448-455
- Type: EXPLICIT

C-E-008: Pending change expiry (14 days)
- Expired moves to history
- Source: recipe-system.md:456-458
- Type: EXPLICIT

C-E-009: Recipe archival policy (system catalogue, 3 months unused)
- Archived but retained in storage
- Source: system-overview.md:175-177
- Type: EXPLICIT

C-E-010: PlannerHint emission from optimiser
- PREP_LEAD_TIME / ABSORPTION_CONFLICT / NUTRITION_TRADEOFF when plan-level concerns discovered
- Source: recipe-system.md:398-407
- Type: EXPLICIT

C-E-011: Recipe search/filter API
- RecipeQueryService search, getRecipeIndex with RecipeFilter; GET /api/v1/recipes?cuisine=...&maxTime=...&catalogue=user
- Source: recipe-system.md:694-700; technical-architecture.md:748
- Type: EXPLICIT

C-E-012: Recipe version history retrieval
- GET /api/v1/recipes/{recipeId}/versions
- Source: recipe-system.md:701
- Type: EXPLICIT

C-E-013: Recipe branches retrieval
- GET /api/v1/recipes/{recipeId}/branches
- Source: recipe-system.md:702
- Type: EXPLICIT

C-E-014: Recipe nutrition retrieval (cached calculation)
- GET /api/v1/recipes/{recipeId}/nutrition
- Source: recipe-system.md:707
- Type: EXPLICIT

C-E-015: Recipe optimistic concurrency (parent_version_id race-check)
- Each write includes parent_version_id; catalogue rejects writes whose parent isn't current; pipeline rebases up to 3 retries
- Source: recipe-system.md:610-611
- Type: EXPLICIT

C-E-016: Manual-edit advisory lock
- Manual edit takes short 30s advisory lock; pipeline writes wait
- Source: recipe-system.md:614
- Type: EXPLICIT

C-E-017: DATA_MODEL_CHANGE batch deferral on FEEDBACK arrival
- If FEEDBACK arrives for recipe in batch, batch defers
- Source: recipe-system.md:617
- Type: EXPLICIT

C-E-018: Quality dashboard
- Per-recipe / per-source / per-prompt-version accept/reject/revert rates
- Source: recipe-system.md:577-584
- Type: EXPLICIT

C-E-019: System catalogue pruning
- Recipes never used + no user interaction after 3 months archived
- Source: system-overview.md:175-177
- Type: EXPLICIT

C-E-020: Branch + version embedding planner-owned
- Embedding pipeline planner-owned; Recipe System stores vectors
- Source: recipe-system.md:120-121
- Type: DEFERRED

C-E-021: Recipe deletion not supported (soft-archive only)
- No hard delete; archived recipes retained
- Source: recipe-system.md:627-634
- Type: EXPLICIT

C-E-022: Recipe image storage (URL or upload)
- URL for imported, file upload for manual. Optional
- Source: recipe-system.md:521-523
- Type: EXPLICIT

## F. External integrations

C-F-001: USDA FoodData Central API client
- UsdaApiClient; ingredient ID + nutrition; ~370k entries; API key with 1000 req/hr; Resilience4j retry on 5xx (2 attempts); WireMock testing
- Source: technical-architecture.md:486-496
- Type: EXPLICIT

C-F-002: USDA cache via nutrition_ingredient_mapping table
- Table IS the cache; once mapped never re-looked up
- Source: technical-architecture.md:491
- Type: EXPLICIT

C-F-003: Open Food Facts API client (branded fallback)
- OpenFoodFactsClient; better for branded/packaged + barcode; same cache; fallback after USDA
- Source: technical-architecture.md:498-504
- Type: EXPLICIT

C-F-004: Background USDA retry job
- @Scheduled retries unmapped ingredients
- Source: technical-architecture.md:493
- Type: EXPLICIT

C-F-005: GroceryProvider abstraction
- Provider-agnostic interface: searchProduct, addToBasket, getBasket, getProductPrice, confirmOrder, quote, placeOrder, checkStatus, cancel
- Source: technical-architecture.md:510-519
- Type: EXPLICIT

C-F-006: Tesco implementation via Claude computer use
- TescoGroceryProvider — browser automation; separate Docker container; hard timeouts; memory 1GB
- Source: technical-architecture.md:510-528
- Type: EXPLICIT

C-F-007: Long-lived browser session with cookie persistence
- Per-user cookies in grocery_provider_state
- Source: grocery.md:166-167
- Type: EXPLICIT

C-F-008: Quote pass independent of order placement
- Quotes without intent to place; refreshes price cache; tracked against AI cost cap
- Source: grocery.md:204-208
- Type: EXPLICIT

C-F-009: Browser partial-failure handling
- 3 of 5 items added → placed_partial; user gets checkout link
- Source: grocery.md:225-228
- Type: EXPLICIT

C-F-010: Provider login expiry handling
- Order stays draft; user re-authenticates and re-runs
- Source: grocery.md:225
- Type: EXPLICIT

C-F-011: Delivery slot selection failure handling
- Order pauses at placed; user picks slot manually
- Source: grocery.md:228
- Type: EXPLICIT

C-F-012: Substitution unparseable handling
- Proposal captured as unparsed; user resolves manually
- Source: grocery.md:227
- Type: EXPLICIT

C-F-013: Provider down + scheduled retry
- ProviderUnavailableException; order marked provider_unavailable; retry scheduled
- Source: grocery.md:229
- Type: EXPLICIT

C-F-014: AiUnavailable fallback to manual basket
- When cost cap exceeded, falls back to printable shopping list
- Source: grocery.md:230
- Type: EXPLICIT

C-F-015: Health Platform integration
- Optional separate platform; communicates via dietary directives (propose-not-apply); exports intake/composition/journal/adherence
- Source: nutrition-model.md:223-313
- Type: EXPLICIT

C-F-016: Directive types
- Ingredient restriction, target adjustment, macro rebalance, elimination trial, reintroduction protocol, sensitivity downgrade
- Source: nutrition-model.md:275-283
- Type: EXPLICIT

C-F-017: Staged elimination protocol
- Elimination → reintroduction → resolution phases with per-phase rules and durations
- Source: nutrition-model.md:255-262
- Type: EXPLICIT

C-F-018: HealthDirectiveReceivedEvent → Notification
- Event published when directive arrives; user reviews
- Source: technical-architecture.md:104
- Type: EXPLICIT

C-F-019: Future-supplier GroceryProvider plugins
- Sainsbury's, Ocado etc. plug in via same interface
- Source: grocery.md:172-176
- Type: DEFERRED

C-F-020: MyFitnessPal history import as alternative cold-start
- Alternative to preference quiz
- Source: preference-model.md:476-477
- Type: DEFERRED

C-F-021: Wearable-driven activity input
- v1 manual; wearables later
- Source: nutrition-model.md:88-90
- Type: DEFERRED

C-F-022: Scheduled background price refresh
- Weekly @Scheduled quote of curated list (top-50 ingredients)
- Source: grocery.md:289-290
- Type: EXPLICIT

C-F-023: Inflation-indexed price fallback
- Stale/no observations apply ~0.5%/month factor (configurable)
- Source: grocery.md:285-286
- Type: EXPLICIT

C-F-024: No multi-supplier basket splitting (v1)
- One provider per order
- Source: grocery.md:378-380
- Type: OUT-OF-SCOPE

C-F-025: No recurring/subscription orders (v1)
- One-shot only
- Source: grocery.md:380
- Type: OUT-OF-SCOPE

C-F-026: No loyalty schemes auto-apply
- Clubcard pricing captured; auto-apply future
- Source: grocery.md:381
- Type: DEFERRED

C-F-027: No click-and-collect (v1)
- Delivery-only
- Source: grocery.md:382
- Type: OUT-OF-SCOPE

C-F-028: No receipt scanning (v1)
- Bulk mark-bought is manual entry
- Source: grocery.md:383
- Type: OUT-OF-SCOPE

C-F-029: No barcode lookup via camera (v1)
- Future
- Source: grocery.md:384
- Type: DEFERRED

C-F-030: No real-time deal scraping
- Surface "below your usual price" from user's own history only
- Source: grocery.md:386-388
- Type: OUT-OF-SCOPE

## G. Cross-cutting

C-G-001: Authentication
- Spring Security with session or JWT in httpOnly cookie. userId resolved server-side
- Source: technical-architecture.md:784-792
- Type: EXPLICIT

C-G-002: Household membership switching (?actingAs=memberId)
- Acting-as member via header/query param
- Source: technical-architecture.md:712
- Type: DEFERRED

C-G-003: Service-interface boundary (Query + Update services per module)
- No cross-module repository access; query + update interfaces; DTOs (Java records) cross boundaries
- Source: technical-architecture.md:14-36
- Type: EXPLICIT

C-G-004: Batch query methods from day one
- Every query service exposes getByIds(List); budget 15-20 DTOs
- Source: technical-architecture.md:32-36
- Type: EXPLICIT

C-G-005: Spring Modulith or ArchUnit boundary enforcement
- Compile/test-time enforcement of module boundaries
- Source: technical-architecture.md:222-228
- Type: EXPLICIT

C-G-006: Flyway timestamp-based migrations (single sequence)
- All modules share single PG DB and migration sequence; timestamp versioning
- Source: technical-architecture.md:261-279
- Type: EXPLICIT

C-G-007: No cross-module @ManyToOne / @JoinColumn
- Cross-module references as plain UUID columns; resolution via query services
- Source: technical-architecture.md:230-232
- Type: EXPLICIT

C-G-008: Cross-module read-only SQL views permitted for UI aggregation
- Native SQL views joining across module tables for dashboards
- Source: technical-architecture.md:253-258
- Type: EXPLICIT

C-G-009: Shared ingredient_mapping_key normalisation
- Always lowercase, trimmed; normaliseKey() utility
- Source: technical-architecture.md:238
- Type: EXPLICIT

C-G-010: @TransactionalEventListener(AFTER_COMMIT) default
- Critical default; only sync when listener must be in same transaction
- Source: technical-architecture.md:68-78
- Type: EXPLICIT

C-G-011: Sealed event hierarchies
- ProvisionChangedEvent sealed: ItemSpoiled / ItemRanOut / ItemAddedFromGrocery / SubstitutionAccepted
- Source: technical-architecture.md:107-131
- Type: EXPLICIT

C-G-012: Event debouncing / batching
- Provisions batches 15 inventory changes → 1 event with affectedItemIds. Feedback publishes 1 FeedbackProcessedEvent after all routing
- Source: technical-architecture.md:80-85
- Type: EXPLICIT

C-G-013: Full event catalogue
- MealCookedEvent, MealConsumedEvent, ProvisionChangedEvent (sealed), EquipmentChangedEvent, BudgetChangedEvent, HouseholdMember*, NutritionIntakeDivergedEvent, PreferenceChangedEvent, FeedbackProcessedEvent, RecipeImportedEvent, RecipeEvolvedEvent, GroceryOrderConfirmedEvent, DataModelChangedEvent, ItemNearingExpiryEvent, HealthDirectiveReceivedEvent
- Source: technical-architecture.md:88-104
- Type: EXPLICIT

C-G-014: Trace IDs propagated via MDC and service args
- trace_id / decision_id app-generated; propagated through service args and SLF4J MDC
- Source: technical-architecture.md:242
- Type: EXPLICIT

C-G-015: Async job/polling pattern
- Plan gen + grocery ordering: client POSTs → 202 + jobId → polls GET /jobs/{jobId} every 2s
- Source: technical-architecture.md:795-803
- Type: EXPLICIT

C-G-016: REST API versioning under /api/v1/
- All endpoints under /api/v1/
- Source: technical-architecture.md:715-781
- Type: EXPLICIT

C-G-017: Full REST endpoint surface
- preferences/nutrition/provisions/recipes/planner/feedback/grocery/notifications/settings/admin
- Source: technical-architecture.md:716-781
- Type: EXPLICIT

C-G-018: Standardised JSON envelope (data + meta)
- Success: { data, meta {timestamp} }. Error: { error {code, message, retryable} }. Paginated lists include page/size/totalElements/sort
- Source: technical-architecture.md:810-852
- Type: EXPLICIT

C-G-019: HTTP status code conventions
- 200/201/202/400/404/409/422/500
- Source: technical-architecture.md:830-839
- Type: EXPLICIT

C-G-020: OpenAPI spec + springdoc-openapi → TypeScript types
- Spec is the contract; frontend types generated
- Source: technical-architecture.md:861-864
- Type: EXPLICIT

C-G-021: TanStack Query + Zustand split
- TanStack for server state, Zustand for UI/drafts
- Source: technical-architecture.md:857-860
- Type: EXPLICIT (frontend, listed for completeness)

C-G-022: Resilience4j for retry/rate-limit/circuit-breaker
- Use Resilience4j for all resilience patterns
- Source: technical-architecture.md:466
- Type: EXPLICIT

C-G-023: Notifications system listening across modules
- NotificationService listens to events: expiry, defrost, prep, nutrition alerts, health directives, feedback confirmations
- Source: system-overview.md:362-373
- Type: EXPLICIT

C-G-024: Notification list + mark-read endpoints
- GET /api/v1/notifications + PUT /api/v1/notifications/{id}/read
- Source: technical-architecture.md:776-777
- Type: EXPLICIT

C-G-025: Notification: expiry warnings from Provisions
- Default 2 days fridge, 14 days freezer
- Source: provision-model.md:421-422
- Type: EXPLICIT

C-G-026: Notification: defrost reminders
- At meal_time - defrost_lead_time_hours
- Source: provision-model.md:422
- Type: EXPLICIT

C-G-027: Notification: prep reminders
- "Start marinating at 6pm" from Meal Planner
- Source: system-overview.md:369
- Type: EXPLICIT

C-G-028: Notification: nutrition alerts
- "Under protein today"
- Source: system-overview.md:370
- Type: EXPLICIT

C-G-029: Notification: staple replenishment
- "Running low on paprika — added to next shop"
- Source: provision-model.md:423
- Type: EXPLICIT

C-G-030: Structured JSON logging via SLF4J + Logback
- Every external API call, event pub/recv, Flyway migrations, every hard-filter logged
- Source: technical-architecture.md:870-876
- Type: EXPLICIT

C-G-031: Spring Boot Actuator health/metrics
- /actuator/health, /actuator/metrics for JVM
- Source: technical-architecture.md:879-882
- Type: EXPLICIT

C-G-032: Custom /api/v1/admin/status endpoint
- DB connectivity, last AI call, last USDA call, current month AI cost
- Source: technical-architecture.md:780-781
- Type: EXPLICIT

C-G-033: Household model (shared Provisions, multi-user)
- Per-user account + Preference + Nutrition Model. Shared Provisions per household
- Source: system-overview.md:306-316
- Type: EXPLICIT

C-G-034: Shared-meal union of hard constraints
- For shared meals, union of all eaters' hard constraints
- Source: system-overview.md:311
- Type: EXPLICIT

C-G-035: Per-meal portion scaling by headcount
- Portions scale per meal based on headcount
- Source: system-overview.md:312
- Type: EXPLICIT

C-G-036: Household member feedback on own meals
- Members give feedback on own meals; primary user manages provisions and shared plan
- Source: system-overview.md:314-315
- Type: EXPLICIT

C-G-037: Household preference merge for soft prefs (weighted)
- Soft preferences aggregated (mean of taste-profile vectors, weighted)
- Source: meal-planner.md:457-460
- Type: EXPLICIT

C-G-038: Per-person plan output for split slots
- Per-person slots produce per-person scheduled recipes; nutrition aggregation per-user
- Source: meal-planner.md:468-471
- Type: EXPLICIT

C-G-039: Daily pg_dump backup script
- Docker exec pg_dump via Windows Task Scheduler / cron; gzipped; 30-day retention; OneDrive sync; tested restore
- Source: technical-architecture.md:959-968
- Type: EXPLICIT

C-G-040: Database reset profile (dev only)
- Spring dev profile drops/recreates schema
- Source: technical-architecture.md:949-951
- Type: EXPLICIT

C-G-041: WireMock for USDA/OFF tests; Testcontainers for integration
- WireMock with recorded responses; Testcontainers for real JSONB/Flyway tests
- Source: technical-architecture.md:955-957
- Type: EXPLICIT

C-G-042: CORS config in dev profile
- Allows http://localhost:5173
- Source: technical-architecture.md:940-942
- Type: EXPLICIT

C-G-043: Spring Boot devtools + Vite HMR
- Hot reload for backend and frontend
- Source: technical-architecture.md:943-945
- Type: EXPLICIT

C-G-044: Three application config files
- application.yml, application-dev.yml, application-test.yml
- Source: technical-architecture.md:937-938
- Type: EXPLICIT

C-G-045: .env.example with gitignored .env
- DB_PASSWORD, ANTHROPIC_API_KEY, USDA_API_KEY in .env
- Source: technical-architecture.md:931-936
- Type: EXPLICIT

C-G-046: Postgres slow query logging
- log_min_duration_statement = 1000
- Source: technical-architecture.md:887
- Type: DEFERRED

C-G-047: Staleness thresholds per model
- Supplier prices: flagged 2wk, excluded 4wk. Inventory: prompted 3wk. Lifestyle config: review 2-3 months. Taste profile: continuous
- Source: technical-architecture.md:699-704
- Type: EXPLICIT

C-G-048: Inventory negative-quantity floor
- Floor at zero if cook deduction would go negative; alert user
- Source: provision-model.md:454-455
- Type: EXPLICIT

C-G-049: 2+ year retention of taste profile versions
- Keep at least last 10; first year keep all
- Source: preference-model.md:414-416
- Type: EXPLICIT

C-G-050: 6-month retention of raw adaptation traces
- Then aggregate per-prompt-version metrics
- Source: recipe-system.md:560-562
- Type: EXPLICIT

C-G-051: 3-month retention of raw waste entries
- Then rolled to weekly summaries
- Source: provision-model.md:361-362
- Type: EXPLICIT

C-G-052: Plan retention indefinite
- ~500 KB/year/household
- Source: meal-planner.md:506
- Type: EXPLICIT

C-G-053: Price history retention indefinite (compact after 12 months)
- Old raw rows can compact after 12 months
- Source: grocery.md:359-360
- Type: EXPLICIT

C-G-054: Grocery orders archived 12 months post-reconciled
- Excluded from default queries
- Source: grocery.md:200
- Type: EXPLICIT

C-G-055: Allergy safety audit trail
- Every HardConstraintFilterService invocation logged for safety audit
- Source: technical-architecture.md:875
- Type: EXPLICIT

C-G-056: Ambiguous-filter-item user confirmation
- Ambiguous cases flag for user confirmation rather than silent reject
- Source: technical-architecture.md:177-181
- Type: EXPLICIT

C-G-057: Multi-user from v1
- Family members from v1
- Source: system-overview.md:324
- Type: EXPLICIT

C-G-058: Hard constraint changes logged with timestamps
- For audit
- Source: preference-model.md:511-512
- Type: EXPLICIT

C-G-059: Lifestyle config audit log
- Standard audit log of changes
- Source: preference-model.md:423-424
- Type: EXPLICIT

C-G-060: Concurrent provider/manual edit guidance
- Deferred to LLD
- Source: grocery.md:370
- Type: DEFERRED

C-G-061: Shared household inventory concurrent edits
- Last-write-wins with notification. Deferred to Household Model design
- Source: provision-model.md:541-542
- Type: DEFERRED

C-G-062: User account deletion / data export (GDPR)
- "Delete my account" flow scrubbing PII; "download my data" flow exporting user-owned data
- Source: NOT IN LIVE HLD
- Type: MISSING-FROM-HLD (UK consumer requirement)

C-G-063: Household role / permission model
- Role enumeration + per-action permission matrix. Events imply roles exist (HouseholdRoleChangedEvent) but enumeration is never defined
- Source: technical-architecture.md:95 (event); system-overview.md:315 (one-line policy)
- Type: MISSING-FROM-HLD (latent — events suggest design started)

## H. Deferred / out-of-scope

C-H-001: Adaptation aggressiveness on import setting (DEFERRED)
C-H-002: Cross-recipe adaptation context (DEFERRED)
C-H-003: Periodic character_fingerprint re-extraction (DEFERRED)
C-H-004: Cross-household collaborative system catalogue (OUT-OF-SCOPE)
C-H-005: Proactive feedback prompts (DEFERRED)
C-H-006: Implicit feedback-on-feedback signal (DEFERRED)
C-H-007: Activity-adjusted Preference Model fields (DEFERRED)
C-H-008: Supplement timing in preference model (DEFERRED)
C-H-009: Weather-reactive preferences (DEFERRED)
C-H-010: Defrost lead time tolerance in lifestyle config (DEFERRED)
C-H-011: Bioavailability modelling for cooking methods (DEFERRED)
C-H-012: Alcohol tracking (DEFERRED)
C-H-013: Supplement integration with intake tracking (DEFERRED)
C-H-014: Plausibility checking for recipe nutrition (DEFERRED)
C-H-015: Cross-recipe pack-size waste optimisation (DEFERRED)
C-H-016: Container capacity tracking in equipment (DEFERRED)
C-H-017: Grocery shopping cadence tracking (DEFERRED)
C-H-018: Failed-meal fallback preferences (DEFERRED)
C-H-019: Per-section feedback counts in taste profile (DEFERRED)
C-H-020: Full temporal stability classification per field (OUT-OF-SCOPE)
C-H-021: Mindful eating coaching (OUT-OF-SCOPE)
C-H-022: Removing meal_structure from individual preference model (OUT-OF-SCOPE)
C-H-023: Multi-week / monthly horizon plans (OUT-OF-SCOPE)
C-H-024: Trend / multi-week optimisation scale (OUT-OF-SCOPE)
C-H-025: Day-scale optimisation (OUT-OF-SCOPE)
C-H-026: Ingredient parameter tuning (OUT-OF-SCOPE)
C-H-027: Generic OptimisationLoopService / Java abstraction (OUT-OF-SCOPE)
C-H-028: Per-household scoring weight tuning (DEFERRED)
C-H-029: Multi-location / transferring items (DEFERRED)
C-H-030: Extended thinking (Anthropic API feature) (DEFERRED)
C-H-031: Per-prompt A/B testing (DEFERRED)
C-H-032: Server-sent events (SSE) for plan generation (DEFERRED)
C-H-033: Prometheus / Grafana / ELK / distributed tracing (OUT-OF-SCOPE)
C-H-034: OAuth (DEFERRED)
C-H-035: Microservices extraction (DEFERRED)
C-H-036: PWA / mobile (DEFERRED)
C-H-037: Multi-turn clarification in feedback classification (DEFERRED)
C-H-038: Classifier learning from correction history (DEFERRED)
C-H-039: Fine-tuning classifier on correction dataset (DEFERRED)
C-H-040: Cross-domain food-health correlations (OUT-OF-SCOPE - belongs to Personal Biology Platform)
C-H-041: Structured health tracking (mood scales, weight, labs, wearables, genomics) (OUT-OF-SCOPE - belongs to Personal Biology Platform)

## IMP. Implicit add-ons (highest-stakes)

C-IMP-001: Audit trail for hard constraint changes
- Requires audit-log infrastructure separate from version history
- Source: preference-model.md:511-512
- Type: IMPLICIT

C-IMP-002: Ingredient-mapping cache write path
- Cache populated on-demand from USDA/OFF responses; write at recipe-import + user-correction
- Source: nutrition-model.md:357
- Type: IMPLICIT

C-IMP-003: Recipe ingredient → inventory matching
- Cook-event deduction matches recipe ingredient quantities to inventory. FIFO by expiry? User-prompt? UNDERSPECIFIED
- Source: provision-model.md:184
- Type: IMPLICIT

C-IMP-004: Inventory mapping_key resolution for manual adds
- AI inference OR search-and-confirm UX. Underspecified
- Source: provision-model.md:73
- Type: IMPLICIT

C-IMP-005: Per-recipe servings scaling
- Recipes have servings; planner allocates portions; nutrition is per-serving. Scaling math must happen
- Source: recipe-system.md:86-87
- Type: IMPLICIT

C-IMP-006: Shopping list → ingredient_mapping_key resolution → Tesco
- Aggregates by mapping_key; Tesco product matching AI; depends on cached supplier_products.ingredient_mapping_key index
- Source: grocery.md:81-90
- Type: IMPLICIT

C-IMP-007: Re-emerging preference detection from archive
- Similarity / match routine across archive during delta calls
- Source: preference-model.md:71
- Type: IMPLICIT

C-IMP-008: Soft-intolerance signal in taste profile
- Soft intolerances in taste profile; planner/optimiser reads + scoring penalty
- Source: preference-model.md:59-61
- Type: IMPLICIT

C-IMP-009: Per-version rating data flow
- Rating UI must attach to slot's pinned recipe_version, not current
- Source: recipe-system.md:209
- Type: IMPLICIT

C-IMP-010: Recipe rating UI default-tap + detailed mode
- Default asks only taste (one tap); detailed mode for others
- Source: recipe-system.md:179
- Type: EXPLICIT (UI surface)

C-IMP-011: NutritionIntakeDivergedEvent (15% per macro/day default)
- Terminology in meal-planner.md:402 says "NutritionLoggerEvent"; event catalogue lists NutritionIntakeDivergedEvent. Implementation must publish on threshold breach
- Source: meal-planner.md:402; technical-architecture.md:96
- Type: IMPLICIT

C-IMP-012: Category-based expiry defaults table
- Reference table of category → default expiry days. Fresh chicken +3d, dairy +7d
- Source: provision-model.md:164-166
- Type: IMPLICIT

C-IMP-013: Pack-size heuristic table
- Static reference table mapping ingredient categories to typical pack sizes
- Source: grocery.md:84
- Type: IMPLICIT (deferred to LLD)

C-IMP-014: Allergy derivative mapping table
- "peanuts" also catches "peanut oil", "peanut butter". Maintained lookup table, not AI
- Source: technical-architecture.md:173
- Type: EXPLICIT

C-IMP-015: Free-text → ingredient_mapping_key normalisation routine
- Used in 4+ places (nutrition mapping, manual inventory add, free-text intake correction, grocery search)
- Source: technical-architecture.md:238
- Type: IMPLICIT

C-IMP-016: Daily/weekly nutrition aggregation (deterministic)
- Already cross-referenced
- Source: system-overview.md:360
- Type: EXPLICIT

C-IMP-017: Cold-start partial-cost-projection labelling
- "Partial estimate" if supplier cache coverage < 80%
- Source: technical-architecture.md:577
- Type: EXPLICIT

C-IMP-018: Aggregation across batch cook for grocery
- batch_cook_session_id; grocery aggregates ingredient quantities by session
- Source: meal-planner.md:177-180
- Type: EXPLICIT

C-IMP-019: Recipe → ingredient quantity aggregator (deterministic)
- Step 1 of shopping list calculation
- Source: grocery.md:82
- Type: EXPLICIT

C-IMP-020: Feedback partial-success surface
- Each destination write is own transaction; partial success acceptable, logged, surfaced
- Source: feedback-system.md:163-164
- Type: EXPLICIT

C-IMP-021: Feedback correction undo logic per destination
- Recipe adaptations pending: cancellable. Preference deltas: partial rollback. Provisions: undo-able. Nutrition: harder if cascaded
- Source: feedback-system.md:262-264
- Type: EXPLICIT

C-IMP-022: Inflation factor configurable property
- mealprep.grocery.inflation_factor_monthly
- Source: grocery.md:286
- Type: EXPLICIT
