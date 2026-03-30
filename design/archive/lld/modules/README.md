# Module LLDs

Each module is documented as if it were an independent service. This means:
- **Own data model** — tables it owns, no direct access to other modules' tables
- **Own API** — REST endpoints it exposes
- **Dependencies** — which other modules it calls and what it expects from them
- **Events** — what it emits that other modules might care about

## Architecture Note

The high-level design now uses a **three-loop architecture** (see system-overview.md). The LLD modules below were written before this rewrite and need updating during Phase 1b review. Key boundary changes:

- **Module 01 (profile)** → splits into: **auth** (thin account layer), **preference** (Loop 1 state), and **nutrition-model** (Loop 2 state). Budget/equipment/shopping prefs move to provisions.
- **Module 05 (pantry)** → becomes **provisions** (pantry + equipment + environment + budget + supplier data)
- **Module 10 (discovery)** → merges into **recipe** (Recipe Engine = store + discovery + AI generation)
- **Module 08 (feedback)** → now routes to all three loops, not just preferences

These changes will be applied as each module LLD is reviewed.

## Modules (in build order)

0. [shared-reference](00-shared-reference.md) — Lookup tables (nutrient, allergen, cuisine, food category, meal type)
1. [profile](01-profile.md) — User identity, constraints, goals *(pending split into auth/preference/nutrition-model)*
2. [ai](02-ai.md) — Centralised LLM interaction layer
3. [recipe](03-recipe.md) — Recipe Engine: store, versioning, import, discovery, AI generation
4. [nutrition-engine](04-nutrition-engine.md) — Ingredient mapping, macro calculation (Loop 2 data layer)
5. [pantry](05-pantry.md) — Inventory tracking, freezer, waste *(pending rename to provisions + scope expansion)*
6. [planner](06-planner.md) — Meal plan generation, adjustments (the three-loop orchestrator)
7. [shopping](07-shopping.md) — Shopping list generation
8. [feedback](08-feedback.md) — Meal feedback, multi-loop routing, preference model maintenance
9. [nutrition-tracker](09-nutrition-tracker.md) — Planned vs actual intake (Loop 2 logging)
10. [discovery](10-discovery.md) — Online recipe search *(pending merge into recipe module)*
11. [grocery](11-grocery.md) — Tesco ordering automation (Loop 3 output)
12. [health](12-health.md) — Mood, symptoms, weight, reviews (Loop 2 feedback)
13. [notification](13-notification.md) — Alerts and reminders

## Conventions
- Timestamps: UTC
- Money: pence (integer)
- Weight: grams (integer)
- JSON responses: camelCase
- DB columns: snake_case
- Inter-module calls shown as `→ ModuleName.method()`
