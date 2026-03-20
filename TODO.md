# Development Plan

## Phase 1: Review & Finalise Design
- [ ] Review each module LLD — discuss and confirm data models, APIs, and flows
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
- [ ] Review high-level design docs — confirm ideas and direction
  - [ ] system-overview.md
  - [ ] ai-service.md
  - [ ] preference-model.md
  - [ ] nutrition-engine.md
  - [ ] recipe-system.md
  - [ ] feedback-and-recipe-evolution.md
  - [ ] meal-planning.md
  - [ ] pantry-tracking.md
  - [ ] plan-disruptions.md
  - [ ] health-tracking.md
  - [ ] frontend-ux.md
  - [ ] user-profile.md
  - [ ] risk-and-complexity.md
  - [ ] build-order.md
  - [ ] additional-ideas.md

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
