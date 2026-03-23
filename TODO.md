# Development Plan

## Phase 1a: Review High-Level Design (do first — shapes the LLD work)
Review order: core concepts first, then features that depend on them, then support/polish.
- [ ] 1. system-overview.md — the single source of truth, review first
- [ ] 2. user-profile.md — everything depends on knowing the user
- [ ] 3. ai-service.md — the engine behind most features
- [ ] 4. preference-model.md — how AI learns the user, feeds into recipes/planner
- [ ] 5. recipe-system.md — core content unit
- [ ] 6. nutrition-engine.md — how recipes get nutrition data
- [ ] 7. feedback-and-recipe-evolution.md — how recipes improve
- [ ] 8. meal-planning.md — the main product value
- [ ] 9. pantry-tracking.md — supports planner and shopping
- [ ] 10. plan-disruptions.md — edge cases for the planner
- [ ] 11. frontend-ux.md — how users interact with all of the above
- [ ] 12. health-tracking.md — later-phase feature, but review the vision
- [ ] 13. risk-and-complexity.md — sanity check after reviewing everything
- [ ] 14. build-order.md — confirm sequencing still makes sense
- [ ] 15. additional-ideas.md — anything worth promoting to a real feature?

## Phase 1b: Review Module LLDs (after high-level is confirmed)
- [ ] 00 - Shared Reference
- [ ] 01 - Profile
- [ ] 02 - AI Service
- [ ] 03 - Recipe
- [ ] 04 - Nutrition Engine
- [ ] 05 - Pantry
- [ ] 06 - Planner
- [ ] 07 - Shopping
- [ ] 08 - Feedback
- [ ] 09 - Nutrition Tracker
- [ ] 10 - Discovery
- [ ] 11 - Grocery (Tesco)
- [ ] 12 - Health
- [ ] 13 - Notification

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
