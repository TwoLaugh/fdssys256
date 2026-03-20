# Module LLDs

Each module is documented as if it were an independent service. This means:
- **Own data model** — tables it owns, no direct access to other modules' tables
- **Own API** — REST endpoints it exposes
- **Dependencies** — which other modules it calls and what it expects from them
- **Events** — what it emits that other modules might care about

## Modules (in build order)

0. [shared-reference](00-shared-reference.md) — Lookup tables (nutrient, allergen, cuisine, food category, meal type)
1. [profile](01-profile.md) — User identity, constraints, goals
2. [ai](02-ai.md) — Centralised LLM interaction layer
3. [recipe](03-recipe.md) — Recipe store, versioning, import
4. [nutrition-engine](04-nutrition-engine.md) — Ingredient mapping, macro calculation
5. [pantry](05-pantry.md) — Inventory tracking, freezer, waste
6. [planner](06-planner.md) — Meal plan generation, adjustments
7. [shopping](07-shopping.md) — Shopping list generation
8. [feedback](08-feedback.md) — Meal feedback, preference model
9. [nutrition-tracker](09-nutrition-tracker.md) — Planned vs actual intake
10. [discovery](10-discovery.md) — Online recipe search and suggestion
11. [grocery](11-grocery.md) — Tesco ordering automation
12. [health](12-health.md) — Mood, symptoms, weight, reviews
13. [notification](13-notification.md) — Alerts and reminders

## Conventions
- Timestamps: UTC
- Money: pence (integer)
- Weight: grams (integer)
- JSON responses: camelCase
- DB columns: snake_case
- Inter-module calls shown as `→ ModuleName.method()`
