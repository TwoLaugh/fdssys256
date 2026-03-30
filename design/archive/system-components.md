# System Components — Three-Loop Architecture

*This doc maps the conceptual three-loop architecture to implementation modules. The loops are the "why," the modules are the "how."*

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    THREE CONSTRAINT LOOPS                        │
│                                                                  │
│  Loop 1 (Preference)    Loop 2 (Nutrition)    Loop 3 (Provisions)│
│  ├─ Preference Model    ├─ Nutrition Model    ├─ Pantry          │
│  ├─ Recipe Engine        ├─ Nutrition Tracker  ├─ Equipment       │
│  └─ Feedback System     ├─ Health Tracker     ├─ Budget          │
│                          └─ Feedback System    ├─ Supplier Data   │
│                                                └─ Feedback System │
│                                                                  │
│  ┌──────────────────────────────────────────────────────┐       │
│  │              MEAL PLANNER (orchestrator)              │       │
│  │  Optimises across all three loops simultaneously      │       │
│  └──────────────────────────────────────────────────────┘       │
│                                                                  │
│  Cross-cutting: AI Service, Notifications, Auth, Frontend       │
└─────────────────────────────────────────────────────────────────┘
```

## Implementation Modules

### Auth
Thin infrastructure layer. Not a domain module.
- **Depends on**: Nothing
- **Used by**: Everything (provides user identity)
- Username/password, household membership

### Preference Model (Loop 1 state)
AI-maintained structured document summarising user preferences.
- **Depends on**: Feedback System (feedback triggers regeneration)
- **Used by**: Meal Planner (context for recipe selection), Recipe Engine (guides discovery and generation)
- Hard/soft constraints, taste preferences, cuisine preferences, cooking patterns, meal structure

### Nutrition Model (Loop 2 state)
Calorie/macro/micro targets and dietary identity.
- **Depends on**: Health Tracker (refines targets based on outcomes)
- **Used by**: Meal Planner (constrains nutritional balance), Nutrition Tracker (targets to compare against)
- Initially set during onboarding, refined over time by health data

### Provisions Manager (Loop 3 state)
Everything about what you have to work with.
- **Depends on**: Shopping List (purchased items added), Grocery Ordering (price/availability data)
- **Used by**: Meal Planner (constrains feasibility), Shopping List (what's already in stock), Notifications (expiry alerts, defrost reminders)
- Pantry inventory, equipment, environment, budget, supplier data, food waste tracking

### Recipe Engine
Unified system for all recipe operations — store, discovery, AI generation, versioning.
- **Depends on**: Preference Model (filtering and scoring), AI Service (generation, import, evolution)
- **Used by**: Meal Planner (the pool of recipes to arrange), Feedback System (what was eaten)
- Three sources: existing library, online discovery, AI generation
- All share the same versioning and constraint-aware mechanism

### Meal Planner (the orchestrator)
Generates weekly plans by simultaneously optimising across all three loops.
- **Depends on**: Preference Model, Nutrition Model, Provisions, Recipe Engine, AI Service
- **Used by**: Shopping List, Nutrition Tracker, Feedback System (which meal was eaten when)
- Handles mid-week disruptions via event + intent UX pattern
- The most coupled component — by design, since it orchestrates everything

### Shopping List Generator
Deterministic: plan ingredients minus provisions stock.
- **Depends on**: Meal Planner (what's needed), Provisions (what's in stock)
- **Used by**: Grocery Ordering, Provisions (purchased items update pantry)
- Grouped by store section, accounts for pack sizes, budget estimate
- No AI needed — pure arithmetic

### Grocery Ordering (Tesco)
Browser automation via Claude computer use / Chrome connector.
- **Depends on**: Shopping List, AI Service (browser control)
- **Used by**: Provisions (purchased items → pantry, price data → supplier cache), Meal Planner (substitution flags)
- User always reviews basket before checkout
- Purchased items auto-added to provisions

### Nutrition Tracker (Loop 2 logging)
Tracks planned vs actual nutrition intake.
- **Depends on**: Meal Planner (planned meals), Recipe Engine (nutrition data per recipe), Nutrition Model (targets)
- **Used by**: Health Tracker (trend data)
- Pre-populated from meal plan, user confirms/skips/adjusts

### Health Tracker (Loop 2 feedback)
Closes the loop between food and health outcomes.
- **Depends on**: Nutrition Tracker (what was eaten), AI Service (pattern analysis, review generation)
- **Used by**: Nutrition Model (refines targets based on outcomes)
- Tier 1: mood/energy, symptoms, weight, progress photos
- Tier 2+: wearables, blood panels, genomics
- Generates weekly/monthly AI reviews

### Feedback System (multi-loop routing)
Collects user feedback on meals and routes it to the appropriate loop(s).
- **Depends on**: Recipe Engine (what was eaten), Meal Planner (which slot)
- **Used by**: Preference Model (taste/ease feedback), Nutrition Model (portion/macro feedback), Provisions (cost/availability feedback), Recipe Engine (drives versioning)
- Conversational input, AI interprets and scores against rubric
- Maintains the Preference Model (~2000 token AI-generated summary, regenerated every 5 feedbacks)

### AI Service (cross-cutting)
Centralised layer for all LLM interactions.
- **Depends on**: Nothing (external API wrapper)
- **Used by**: Everything that needs AI
- Model tier routing (frontier/mid/cheap), prompt templates, context assembly, response parsing, cost tracking, failure handling

### Notification System (cross-cutting)
Alerts and reminders delivered in-app.
- **Depends on**: Provisions (expiry, defrost), Meal Planner (prep reminders), Nutrition Tracker (shortfalls), Health Tracker (review ready)
- **Used by**: Frontend

### Frontend
React web app — all views.
- **Depends on**: All backend services via API
- **Used by**: User

---

## Dependency Flow (Three Loops Visible)

```
                    LOOP 1                LOOP 2              LOOP 3
                 (Preference)           (Nutrition)         (Provisions)

              ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
              │  Preference  │    │  Nutrition    │    │  Provisions  │
              │    Model     │    │    Model      │    │   Manager    │
              └──────┬───────┘    └──────┬────────┘    └──────┬───────┘
                     │                   │                     │
         ┌───────────┘                   │                     │
         ▼                               ▼                     ▼
  ┌─────────────┐              ┌──────────────────────────────────────┐
  │   Recipe    │─────────────►│            MEAL PLANNER              │
  │   Engine    │              │      (the central orchestrator)      │
  └─────────────┘              └─────┬────────┬────────┬─────────────┘
                                     │        │        │
                              ┌──────┘    ┌───┘    ┌───┘
                              ▼           ▼        ▼
                        ┌──────────┐ ┌────────┐ ┌──────────┐
                        │ Feedback │ │Nutritn │ │ Shopping  │
                        │  System  │ │Tracker │ │   List    │
                        └─────┬────┘ └───┬────┘ └─────┬────┘
                              │          │            │
                    ┌─────────┤          ▼            ▼
                    │         │    ┌──────────┐ ┌──────────┐
                    ▼         │    │  Health  │ │  Tesco   │
              Preference      │    │ Tracker  │ │  Order   │
              Model ◄─────────┘    └────┬─────┘ └────┬─────┘
              (Loop 1                   │             │
               update)                  ▼             ▼
                                  Nutrition      Provisions
                                  Model          (update)
                                  (Loop 2        (Loop 3
                                   refine)        update)
```

---

## Standalone vs Coupled

### Can work independently
- **Provisions Manager** — inventory tracking, useful on its own
- **Recipe Engine** — CRUD + versioning, useful even without planning
- **AI Service** — stateless API wrapper
- **Nutrition Tracker** — could work with manual meal logging

### Need other components to function
- **Meal Planner** — needs all three loop states + recipes + AI (most coupled, by design)
- **Shopping List** — needs plan + provisions
- **Grocery Ordering** — needs shopping list + AI
- **Feedback System** — needs recipes + planner (what was eaten when)
- **Health Tracker** — needs nutrition tracker + AI
- **Notifications** — needs provisions + planner + nutrition data
- **Preference Model** — needs feedback system (to evolve)

---

## Architecture: Modular Monolith

One deployable application with clean internal module boundaries.

```
src/main/java/com/example/mealprep/
├── auth/             ← User accounts (thin auth layer)
├── preference/       ← Preference Model (Loop 1 state)
├── nutrition/        ← Nutrition Model + Tracker (Loop 2 state + logging)
├── provisions/       ← Pantry + Equipment + Environment (Loop 3 state)
├── recipe/           ← Recipe Engine (store, discovery, generation, versioning)
├── planner/          ← Meal Planner + Adjustments (orchestrator)
├── shopping/         ← Shopping List Generator
├── grocery/          ← Tesco Ordering (provisions output)
├── health/           ← Health Tracker (feeds back to nutrition model)
├── feedback/         ← Feedback System (routes to all three loops)
├── ai/               ← AI Service (cross-cutting LLM layer)
├── notification/     ← Notifications (cross-cutting alerts)
└── MealPrepApplication.java
```

**Key rules**:
- Modules communicate through well-defined service interfaces, not by reaching into each other's internals
- Each module owns its own database tables
- If you ever need to extract a microservice later, the module boundary is already clean — it's a refactor, not a rewrite

**This gives you 90% of the architectural benefits of microservices with 10% of the operational cost.**
