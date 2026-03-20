# System Components — Full Breakdown & Dependencies

## All Components

### 1. User Profile Service
Manages user identity, constraints, preferences, goals.
- **Depends on**: Nothing — foundational
- **Used by**: Everything else

### 2. Recipe Store
CRUD for recipes. Storage, versioning, tagging, search.
- **Depends on**: User Profile (for dietary filtering)
- **Used by**: Meal Planner, Recipe Discovery, Shopping List, Nutrition Tracker, Feedback System

### 3. Recipe Discovery
Finds new recipes online, filters against user profile, scores and presents them.
- **Depends on**: User Profile (constraints/preferences), Recipe Store (to check what's already in library), AI Service
- **Used by**: Recipe Store (imports discovered recipes), Meal Planner (new recipes feed into planning)

### 4. Pantry Manager
Tracks ingredient inventory — what's in the house (fridge, cupboard, freezer), quantities, expiry. Includes food waste tracking (what was thrown away and why) and freezer management (frozen portions, defrost reminders).
- **Depends on**: Nothing (standalone data store)
- **Used by**: Meal Planner, Shopping List Generator, Notifications (expiry alerts, defrost reminders)

### 5. Meal Planner
Generates weekly plans by arranging recipes across days, optimising ingredient usage.
- **Depends on**: User Profile, Recipe Store, Pantry Manager, Feedback/Preference Model, AI Service
- **Used by**: Shopping List Generator, Nutrition Tracker, Plan Adjustment Handler

### 6. Plan Adjustment Handler
Handles mid-week disruptions — skipped meals, substitutions, swaps, rebalancing.
- **Depends on**: Meal Planner (the current plan), Pantry Manager, Recipe Store, AI Service
- **Used by**: Meal Planner (updates the plan), Pantry Manager (adjusts stock), Shopping List Generator (may need top-up)

### 7. Shopping List Generator
Calculates what to buy: plan ingredients minus pantry stock.
- **Depends on**: Meal Planner (what's needed), Pantry Manager (what's in stock)
- **Used by**: Grocery Ordering, Pantry Manager (purchased items added back)

### 8. Grocery Ordering (Tesco)
Automates adding shopping list items to Tesco basket via browser control.
- **Depends on**: Shopping List Generator, User Profile (store preference, account), AI Service (browser control)
- **Used by**: Pantry Manager (purchased items → pantry), Plan Adjustment Handler (substitutions)

### 9. Nutrition Tracker
Tracks planned vs actual nutrition intake. Daily/weekly dashboards.
- **Depends on**: Meal Planner (planned meals), Recipe Store (nutrition data per recipe)
- **Used by**: Meal Planner (informs future plans if consistently over/under), Health Tracker, Dashboard

### 9b. Health Tracker (expansion of User Profile + Nutrition Tracker)
Tracks health signals over time: mood/energy, symptoms, weight, progress photos, wearable data, blood panels, genomics. Generates weekly/monthly reviews. Feeds insights back into meal planning.
- **Depends on**: User Profile (goals, constraints), Nutrition Tracker (what was eaten), AI Service (pattern analysis, review generation)
- **Used by**: Meal Planner (adjusted targets, discovered intolerances), User Profile (updates constraints based on findings)

### 10. Feedback System
Collects user feedback on meals, maintains preference model.
- **Depends on**: Recipe Store (what was eaten), Meal Planner (which plan slot)
- **Used by**: Recipe Store (recipe evolution/versioning), Meal Planner (preference model influences future plans), Recipe Discovery (learn what user accepts/rejects)

### 11. AI Service
Centralised layer for all LLM interactions. Routes to appropriate model tier.
- **Depends on**: Nothing (external API wrapper)
- **Used by**: Everything that needs AI — Meal Planner, Recipe Discovery, Plan Adjustments, Feedback interpretation, Grocery Ordering, Recipe import/parsing

### 12. Notification System
Alerts and reminders — expiry warnings, prep reminders, plan suggestions.
- **Depends on**: Pantry Manager (expiry), Meal Planner (schedule), Nutrition Tracker (shortfalls)
- **Used by**: Frontend (displays notifications)

### 13. Frontend
The web app UI — all views (dashboard, plan, recipes, pantry, nutrition, settings).
- **Depends on**: All backend services via API
- **Used by**: User

---

## Dependency Map

```
                    ┌──────────────┐
                    │ User Profile │
                    └──────┬───────┘
                           │ used by everything
          ┌────────────────┼────────────────────┐
          ▼                ▼                     ▼
   ┌─────────────┐  ┌───────────┐       ┌──────────────┐
   │Recipe Store  │  │  Pantry   │       │  AI Service  │
   └──────┬──────┘  │  Manager  │       │  (LLM layer) │
          │         └─────┬─────┘       └──────┬───────┘
          │               │                    │
          ▼               ▼                    │ used by many
   ┌──────────────┐      │              ┌──────┘
   │   Recipe     │      │              │
   │  Discovery   │      │              │
   └──────┬───────┘      │              │
          │              │              │
          ▼              ▼              ▼
        ┌─────────────────────────────────┐
        │         MEAL PLANNER            │
        │  (the central orchestrator)     │
        └───────────┬─────────────────────┘
                    │
         ┌──────────┼──────────┐
         ▼          ▼          ▼
  ┌────────────┐ ┌────────┐ ┌──────────────┐
  │  Shopping  │ │Nutrition│ │   Feedback   │
  │   List     │ │ Tracker │ │   System     │
  └─────┬──────┘ └────────┘ └──────────────┘
        │
        ▼
  ┌────────────┐
  │  Grocery   │
  │  Ordering  │
  └─────┬──────┘
        │
        ▼
  ┌────────────┐
  │   Pantry   │ ◄── cycle: purchases update pantry
  │  (update)  │
  └────────────┘

  ┌──────────────────┐
  │  Notifications   │ ◄── listens to pantry, planner, nutrition
  └──────────────────┘

  ┌──────────────────┐
  │    Frontend      │ ◄── consumes all services
  └──────────────────┘
```

---

## Standalone vs Coupled

### Can work independently (standalone data + logic)
- **User Profile** — just a settings store
- **Recipe Store** — CRUD + versioning, useful even without planning
- **Pantry Manager** — inventory tracking, useful on its own
- **AI Service** — stateless API wrapper
- **Nutrition Tracker** — could work with manual meal logging, no planner needed

### Need other components to function
- **Meal Planner** — needs profile + recipes + pantry + AI (the most coupled component)
- **Shopping List Generator** — needs plan + pantry
- **Grocery Ordering** — needs shopping list + AI
- **Recipe Discovery** — needs profile + recipe store + AI
- **Plan Adjustment Handler** — needs planner + pantry + recipes + AI
- **Feedback System** — needs recipes + planner (what was eaten when)
- **Notifications** — needs pantry + planner + nutrition data

---

## On Architecture: Microservices vs Modular Monolith

### Microservices would give you:
- Independent deployment of each component
- Different tech per service if needed
- Isolated scaling
- Clear API boundaries

### But for this project:
- **Single user** — no scaling pressure
- **13 services** each needing their own deployment, database, logging, error handling — massive operational overhead
- Inter-service communication (HTTP/gRPC/message queues) adds latency and failure modes
- Debugging distributed systems is genuinely hard
- You'd spend more time on infrastructure than features

### Recommended: Modular Monolith
One deployable application, but with clean internal module boundaries.

```
claudeTest/
├── src/main/java/com/example/mealprep/
│   ├── profile/          ← User Profile module
│   │   ├── UserProfile.java
│   │   ├── ProfileService.java
│   │   └── ProfileController.java
│   ├── recipe/           ← Recipe Store module
│   │   ├── Recipe.java
│   │   ├── RecipeVersion.java
│   │   ├── RecipeService.java
│   │   └── RecipeController.java
│   ├── pantry/           ← Pantry module
│   ├── planner/          ← Meal Planner module
│   ├── shopping/         ← Shopping List module
│   ├── grocery/          ← Grocery Ordering module
│   ├── nutrition/        ← Nutrition Tracker module
│   ├── feedback/         ← Feedback System module
│   ├── discovery/        ← Recipe Discovery module
│   ├── adjustment/       ← Plan Adjustment module
│   ├── notification/     ← Notifications module
│   ├── ai/               ← AI Service module (centralised LLM calls)
│   └── MealPrepApplication.java
```

**Key rules**:
- Modules communicate through well-defined service interfaces, not by reaching into each other's internals
- Each module owns its own database tables
- If you ever need to extract a microservice later, the module boundary is already clean — it's a refactor, not a rewrite

**This gives you 90% of the architectural benefits of microservices with 10% of the operational cost.** For a personal project, this is the right call. You can always split later if a specific module needs independent scaling (spoiler: it won't for a single user).
