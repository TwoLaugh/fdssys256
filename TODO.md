# Development Plan

## Phase 1: Design (current)

System overview is the source of truth. Design work = get each component's data model and behaviour nailed down in detail, working outward from the overview.

### 1a. Resolve system-overview.md TODOs
- [ ] Household constraint conflicts — conflict resolution strategy for irreconcilable shared-meal constraints
- [ ] Planner failure / fallback UX — what happens when no valid plan exists
- [ ] Nutrition Logger vs Feedback System boundary — clarify the one-liner distinction
- [ ] Recipe discovery quality/trust — how garbage-in on scraped recipes is handled

### 1b. Data model designs (Preference, Nutrition, Provisions)
The three data models are what everything else optimises against. Pin these down first.
- [ ] Preference Model — full schema, hard-constraint table design, soft preference structure, how feedback writes update it
- [ ] Nutrition Model — full schema, macro/micro targets, health tracking fields, how the logger interacts
- [ ] Provisions — full schema, pantry/freezer/equipment/budget, grocery price integration, expiry/depletion tracking

### 1c. Recipe data model
Depends on the three data models being clear (recipe properties need to map to what the optimiser checks against).
- [ ] Recipe Engine — schema for both catalogues, versioning/branching model, recipe metadata properties, source pipeline (manual/import/discovery/generation)

### 1d. Optimiser and Planner design
The hard parts. With data models and recipe structure defined, the constraint space is concrete.
- [ ] Recipe Optimiser — how it reads the three data models, adaptation logic per trigger, propose-vs-apply mechanics
- [ ] Meal Planner — Phase 1 composition approach (scoring/search/hybrid), Phase 2 creative augmentation, mid-week re-optimisation, how it invokes the optimiser

### 1e. Remaining component designs
- [ ] Feedback System — classifier design, routing logic, multi-destination splitting, misclassification correction UX
- [ ] Household Model — shared provisions, constraint unions, per-user vs shared meal slots
- [ ] AI Service — prompt management, tier routing, context assembly, cost tracking
- [ ] Notification System — event sources, delivery mechanism, alert types
- [ ] Grocery Provider — GroceryProvider interface, Tesco implementation approach

### 1f. Reconcile old HLD docs with system overview
The old design/ HLDs (preference-model.md, recipe-system.md, nutrition-engine.md, pantry-tracking.md, meal-planning.md, etc.) predate the current system overview and may conflict. Once the above designs are done:
- [ ] Review each old HLD against the system overview — retire, merge, or update as needed
- [ ] Update or retire old LLDs that no longer match

## Phase 2: Test Plan
Write comprehensive test specs per module so implementation = "make the tests pass."

### Backend
- [ ] Per-module integration test specs (the priority — test full flows through real DB)
  - [ ] API contract tests: every endpoint, every status code, every edge case
  - [ ] Cross-module flow tests (e.g., generate plan → mark cooked → pantry deducted → nutrition logged)
  - [ ] Auth/multi-user isolation tests (user A can't see user B's data)
  - [ ] Reference data seeding tests (all lookup tables populated correctly)
- [ ] Unit test specs (service logic, validation, edge cases — aim for near-100% coverage)
- [ ] AI integration test specs (mock AI responses, verify parsing and error handling)
- [ ] Database tests (migrations run cleanly, constraints enforced, indexes used)

### Frontend
- [ ] Component test specs (each UI component renders correctly with given props)
- [ ] User flow / E2E test specs (Playwright or Cypress)
  - [ ] Login → create profile → set nutrition targets
  - [ ] View plan → mark meal cooked → give feedback
  - [ ] Browse recipes → view detail → suggest changes
  - [ ] Shopping list → check items → complete
  - [ ] Nutrition dashboard → daily/weekly views
- [ ] Responsive layout tests (mobile vs desktop breakpoints)
- [ ] API mocking strategy (MSW or similar for frontend tests)
- [ ] Accessibility basics (keyboard nav, screen reader labels)

## Phase 3: UI/UX Design (Figma)
- [ ] Map out all screens and navigation flow
- [ ] Design key views (plan view, recipe library, pantry, shopping list, nutrition dashboard, health tracker, profile/settings)
- [ ] Mobile vs desktop responsive layouts
- [ ] Cooking mode view
- [ ] Feedback/conversation UI

## Phase 4: Prototype Key Components
- [ ] Recipe development — test with Claude conversations (no code, just prompting for specific recipes and seeing quality)
- [ ] Recipe import — test Claude extracting structured recipe from real recipe URLs
- [ ] Tesco ordering — test Claude browser control adding items to a Tesco basket from a shopping list
- [ ] Nutrition lookup — test USDA FoodData Central API with real ingredients, check accuracy
- [ ] Preference model — test Claude maintaining a bounded preference JSON from sample feedback
- [ ] Meal plan generation — test two-pass approach with Claude (recipe selection → plan assembly)
- [ ] Weekly review — test Claude generating a health/nutrition review from sample data

## Phase 5: Build
- [ ] With confirmed designs, Figma screens, and validated prototypes — implement the system following the build order in build-order.md
