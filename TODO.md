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

## Phase 2: UI/UX Design (Figma)
- [ ] Map out all screens and navigation flow
- [ ] Design key views (plan view, recipe library, pantry, shopping list, nutrition dashboard, health tracker, profile/settings)
- [ ] Mobile vs desktop responsive layouts
- [ ] Cooking mode view
- [ ] Feedback/conversation UI

## Phase 3: Prototype Key Components
- [ ] Recipe development — test with Claude conversations (no code, just prompting for specific recipes and seeing quality)
- [ ] Recipe import — test Claude extracting structured recipe from real recipe URLs
- [ ] Tesco ordering — test Claude browser control adding items to a Tesco basket from a shopping list
- [ ] Nutrition lookup — test USDA FoodData Central API with real ingredients, check accuracy
- [ ] Preference model — test Claude maintaining a bounded preference JSON from sample feedback
- [ ] Meal plan generation — test two-pass approach with Claude (recipe selection → plan assembly)
- [ ] Weekly review — test Claude generating a health/nutrition review from sample data

## Phase 4: Build
- [ ] With confirmed designs, Figma screens, and validated prototypes — implement the system following the build order in build-order.md
