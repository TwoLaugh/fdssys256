# Technical Architecture

*How the modules connect, how data flows between them, and what patterns the remaining subsystem HLDs should follow.*

## What This Document Is

This sits between the system overview ("what the system does") and the individual subsystem HLDs ("how each module works internally"). It defines the wiring: how modules communicate, how the database is structured, what events exist, how AI is integrated, and what the frontend-backend contract looks like.

The audience is an implementer who needs to know "when I build the Planner module, how exactly does it call the Preference Model?"

---

## Module Communication

### Service interfaces via Spring DI

Modules communicate through **service interfaces** — Java interfaces defined in each module's package, injected via Spring constructor injection. This is the single most important architectural rule:

**No module ever accesses another module's JPA repositories directly. The service interface is the boundary.**

Each module exposes two types of interface:

- **Query services** (read-only, widely injected): `PreferenceQueryService`, `NutritionQueryService`, `ProvisionQueryService`, `RecipeQueryService`
- **Update services** (write, narrowly injected): `PreferenceUpdateService`, `NutritionUpdateService`, `ProvisionUpdateService`, `RecipeUpdateService`

This split makes dependency direction explicit. The Planner injects query services for all three data models + Recipe Engine. Only the Feedback System and a few specific flows inject update services.

### Who injects what

```
                        Query Services                      Update Services
                  Pref  Nutr  Prov  Recipe            Pref  Nutr  Prov  Recipe
                  ────  ────  ────  ──────            ────  ────  ────  ──────
Planner            ✓     ✓     ✓      ✓
Optimiser          ✓     ✓     ✓      ✓                                   ✓
Feedback System    ✓     ✓     ✓      ✓                ✓     ✓     ✓      ✓
Grocery Module                 ✓                                   ✓
Notification       ✓     ✓     ✓
Household          ✓           ✓
```

The Feedback System is the only component that writes to all four destinations. The Grocery Module only reads from and writes to Provisions. The Planner is read-only — it produces a plan as output, but never mutates the data models directly.

### When to use events instead

Service calls are for "I need a result." Events are for "something happened that others might care about." Rule of thumb:

- Caller needs the response → service call
- Caller doesn't care who listens → publish an event

Events use Spring `ApplicationEvent`, published synchronously within the same process. No external message broker needed for a monolith. If async processing is needed later, Spring's `@Async` event listeners handle it without changing the event model.

---

## Event System

### Event catalogue

| Event | Published by | Listened by | Downstream action |
|---|---|---|---|
| `MealCookedEvent` | UI/Planner (user marks meal cooked) | Provisions (deduct ingredients), Nutrition Logger (auto-confirm intake) | Inventory deduction, nutrition tracking |
| `MealConsumedEvent` | UI (user confirms eating pre-made meal) | Provisions (deduct one portion) | Portion deduction from fridge/freezer |
| `ProvisionChangedEvent` | Provisions (after any inventory update, grocery delivery, waste log) | Planner | Offers mid-week re-optimisation for remaining days |
| `NutritionIntakeDivergedEvent` | Nutrition Logger (actual intake diverges significantly from planned) | Planner | Shifts remaining targets, may trigger re-optimisation |
| `PreferenceChangedEvent` | Preference module (after taste profile delta or lifestyle config change) | Planner | Offers re-optimisation if mid-plan |
| `FeedbackProcessedEvent` | Feedback System (after AI classifies and routes) | Notification System | Confirms to user what was updated |
| `RecipeImportedEvent` | Recipe Engine (new recipe added to either catalogue) | Optimiser (Trigger 1) | Run adaptation against data models |
| `RecipeEvolvedEvent` | Optimiser or Recipe Engine (version/branch created) | Nutrition Engine | Recalculate nutrition for new version |
| `GroceryOrderConfirmedEvent` | Grocery Module (order confirmed by user) | Provisions | Add items to inventory, update supplier cache |
| `DataModelChangedEvent` | Any data model (significant change to constraints/targets) | Optimiser (Trigger 3) | Batch re-optimise affected system catalogue recipes |
| `ExpiryApproachingEvent` | Scheduled check on Provisions | Notification System | Alert user about expiring items |
| `HealthDirectiveReceivedEvent` | Health Platform integration | Notification System, Nutrition/Preference | Alert user to review proposed directive |

### Event payload design

Events carry IDs and a change type, not full data objects. The listener fetches current state via the relevant query service.

```java
public class ProvisionChangedEvent extends ApplicationEvent {
    private final Long userId;
    private final ChangeType changeType;  // INVENTORY_DEDUCTION, GROCERY_DELIVERY, WASTE_LOGGED, EXPIRY_UPDATE
    private final List<Long> affectedItemIds;
}

public class MealCookedEvent extends ApplicationEvent {
    private final Long userId;
    private final Long recipeId;
    private final Long planId;
    private final Long mealSlotId;
    private final int servingsCooked;
    private final boolean isBatchCook;
}
```

The Planner listener receives `ProvisionChangedEvent`, calls `ProvisionQueryService.getInventory(userId)` to get current state, and decides whether re-optimisation is warranted. It never acts on stale data embedded in the event.

---

## Database Strategy

### Module ownership

Each module owns its tables. Table names are prefixed by module:

```
preference_hard_constraints
preference_taste_profile          ← jsonb column for the AI-maintained document
preference_taste_profile_archive  ← unbounded preference archive
preference_lifestyle_config
nutrition_targets
nutrition_intake_log
nutrition_ingredient_mapping      ← the USDA/Open Food Facts cache
nutrition_food_mood_journal
provision_inventory
provision_equipment
provision_budget
provision_supplier_products       ← product cache
provision_waste_log
provision_staples
recipe_recipes
recipe_versions
recipe_branches
planner_plans
planner_meal_slots
feedback_entries
feedback_routing_log
ai_call_log                       ← cross-cutting, owned by AI Service
notification_log
auth_users
household_members
household_environments
```

### Cross-module references

**No `@ManyToOne` or `@JoinColumn` across module boundaries.** If the Planner references a recipe, it stores `recipe_id` as a plain `Long` column. Resolution happens through `RecipeQueryService.getById(recipeId)`.

Shared identifiers and where they're owned:

| Identifier | Owned by | Used by | Type |
|---|---|---|---|
| `ingredient_mapping_key` | Nutrition Model (`nutrition_ingredient_mapping.search_term`) | Provisions (inventory items), Supplier data, Recipe ingredients | `text` |
| `recipe_id` | Recipe Engine (`recipe_recipes.id`) | Planner (meal slots), Provisions (batch cook source), Feedback (routing) | `bigint` |
| `product_id` | Provisions (`provision_supplier_products.product_id`) | Grocery Module | `text` |
| `user_id` | Auth (`auth_users.id`) | Every module | `bigint` |

### JSONB columns

AI-maintained documents and cached structured data use PostgreSQL `jsonb`:

- `preference_taste_profile.document` — the ~2500-token taste profile JSON
- `nutrition_ingredient_mapping.nutrition_per_100g` — full macro/micro data per ingredient
- `provision_supplier_products.substitution_history` — array of substitution records

Everything else uses normal relational columns. The rule: if the AI reads/writes it as a document, use `jsonb`. If it's queried by specific fields, use columns.

### Migrations

Flyway, with per-module subfolders:

```
src/main/resources/db/migration/
├── preference/
│   ├── V1__create_hard_constraints.sql
│   ├── V2__create_taste_profile.sql
│   └── V3__create_lifestyle_config.sql
├── nutrition/
│   ├── V1__create_targets.sql
│   └── V2__create_ingredient_mapping.sql
├── provision/
│   ├── V1__create_inventory.sql
│   └── V2__create_supplier_products.sql
├── recipe/
│   └── V1__create_recipes_and_versions.sql
└── ...
```

All modules share a single PostgreSQL database. Schema separation is by naming convention, not by physical schema — simpler to manage for a personal project.

---

## AI Service Architecture

### Central abstraction

Every AI interaction goes through `AiService`. Calling modules don't touch the Anthropic API directly.

```java
public interface AiService {
    <T> T execute(AiTask<T> task);
}

public interface AiTask<T> {
    TaskType getTaskType();           // determines model tier + timeout
    Map<String, String> getContext(); // fills template placeholders
    Class<T> getResponseType();       // for JSON deserialization
}
```

Each module defines its own task implementations. The Planner defines `PlanCompositionTask`, `PlanAugmentationTask`. The Feedback System defines `FeedbackClassificationTask`. The AI Service doesn't know anything about meal planning — it routes, calls, parses, and logs.

### Model routing

```java
public enum ModelTier {
    FRONTIER,  // claude-sonnet / claude-opus (latest)
    MID,       // claude-haiku / claude-sonnet (latest)
    CHEAP      // claude-haiku
}
```

| Task | Model Tier | Typical context size |
|---|---|---|
| Plan composition (Phase 1: recipe selection) | TBD (may be deterministic/hybrid) | ~2500 tokens |
| Plan augmentation (Phase 2: creative gap-filling) | Frontier | ~5000 tokens |
| Mid-week re-optimisation | Same as Phase 1 | ~2500 tokens |
| Recipe adaptation (Optimiser) | Mid | ~2000 tokens |
| Recipe generation | Mid | ~1500 tokens |
| Recipe import from URL | Mid | Variable (page content) |
| Feedback classification + routing | Cheap | ~1000 tokens |
| Preference model delta update | Mid | ~3000 tokens |
| Nutrition ingredient mapping (AI parsing + USDA match) | Cheap | ~500 tokens |
| Free-text input parsing | Cheap | ~300 tokens |
| Health directive parsing | Cheap | ~500 tokens |
| Tesco product matching | Mid/Frontier | Variable |

### Prompt management

Templates stored as files, version-controlled with the code:

```
src/main/resources/prompts/
├── planner/
│   ├── recipe-selection.txt
│   ├── plan-assembly.txt
│   └── plan-adjustment.txt
├── recipe/
│   ├── import-from-url.txt
│   ├── generate-recipe.txt
│   └── evolve-recipe.txt
├── feedback/
│   ├── classify-feedback.txt
│   └── update-preference-model.txt
├── nutrition/
│   └── map-ingredients-to-usda.txt
├── discovery/
│   └── search-and-filter.txt
└── grocery/
    └── tesco-product-match.txt
```

Each template has:
- System message (role, constraints, output format)
- `{{placeholder}}` markers for dynamic context
- JSON schema for the expected response

Why files, not database: version-controlled, reviewable in diffs, no runtime DB dependency for prompts. If dynamic prompts are needed later (A/B testing, per-user tuning), move to DB then.

### Context assembly

The caller (not the AI Service) assembles the context. Each module knows what context its AI tasks need. The AI Service just fills templates and calls the API.

**The two-pass planning approach** controls context size for the most expensive operation:

```
Pass 1 — Recipe Selection (cheap/mid model)
  Context: profile + preferences + provisions + recipe INDEX
           (names, tags, ratings, macros — not full recipes)
  Output:  15-20 candidate recipe IDs
  Size:    ~2500 tokens input

Pass 2 — Plan Assembly (frontier model)
  Context: profile + preferences + provisions + FULL selected recipes
  Output:  7-day structured meal plan
  Size:    ~5000 tokens input
```

This pattern — index first, full details only for selected items — applies to any AI task that needs to reason over a large set. Recipe discovery uses the same approach.

### Response parsing and retry

```
AI returns response
    │
    ▼
1. Parse as JSON
    ├── Success + correct schema → return parsed result
    ├── Malformed JSON → attempt simple repair (trailing commas, unescaped quotes)
    │   ├── Repair succeeds → return parsed result
    │   └── Repair fails → go to step 2
    └── Valid JSON, wrong schema → go to step 2
    │
    ▼
2. Retry with correction prompt
    "Your response was missing field X / had invalid format. Please fix."
    ├── Success → return parsed result
    └── Still failing → go to step 3
    │
    ▼
3. Fail gracefully
    Log the failure. Return a user-friendly error.
    Never show raw AI output to the user.
    Never retry more than twice for a single request.
```

### Cost tracking

Every API call is logged to `ai_call_log`:

| Column | Type | Purpose |
|---|---|---|
| `timestamp` | timestamp | When the call was made |
| `user_id` | bigint | Who triggered it |
| `task_type` | text | Which AI task (e.g., `PLAN_ASSEMBLY`, `FEEDBACK_CLASSIFICATION`) |
| `model_used` | text | Which model was actually called |
| `input_tokens` | integer | Tokens sent |
| `output_tokens` | integer | Tokens received |
| `cost_estimate` | decimal | Calculated from token counts × model pricing |
| `latency_ms` | integer | Round-trip time |
| `success` | boolean | Whether parsing succeeded |

Monthly summary available in settings: "This month: £X.XX across Y API calls."

**Cost safety:** Hard cap per request (configurable, default: 100k input tokens). If context assembly produces a context exceeding this, abort before calling the API and log a warning. This catches context assembly bugs. Timeout per task type (default: 30s, planner Phase 2: 60s).

---

## External API Integration

### USDA FoodData Central

- **Client:** `UsdaApiClient` in the nutrition module
- **Purpose:** Ingredient identification, nutrition lookup (~370k food entries)
- **API:** `https://fdc.nal.usda.gov/api-guide`
- **Caching:** The `nutrition_ingredient_mapping` table IS the cache. Once an ingredient is mapped to a USDA FDC ID, it's never looked up again unless the user corrects it. After a few months, API calls become rare.
- **Rate limiting:** 1000 requests/hour with API key. Wrap in a rate limiter for safety.
- **Failure:** Retry on 5xx, up to 2 attempts. On persistent failure, flag the ingredient as "unmapped" — the recipe still works, nutrition just has a gap. A background job retries unmapped ingredients periodically.

### Open Food Facts

- **Client:** `OpenFoodFactsClient` in the nutrition module
- **Purpose:** Branded/packaged products, barcode lookup
- **API:** `https://world.openfoodfacts.org/data`
- **Caching:** Same as USDA — results cached in `nutrition_ingredient_mapping` with `source = "open_food_facts"`
- **Fallback hierarchy:** Search USDA first, fall back to Open Food Facts for branded items

### Tesco (via GroceryProvider)

- **Interface:** `GroceryProvider` abstraction in the grocery module
- **Implementation:** `TescoGroceryProvider` — uses browser automation (Claude computer use or Chrome connector) to interact with the Tesco website
- **This is fundamentally different from a REST API.** It's the most fragile integration and needs the most defensive error handling.
- **Product data cached** in `provision_supplier_products`. Price staleness rules defined in the Provision Model HLD.
- **Failure:** The shopping list still exists. The user can shop manually. Log the failure point for debugging.

```java
public interface GroceryProvider {
    List<ProductMatch> searchProduct(String query);
    void addToBasket(String productId, int quantity);
    Basket getBasket();
    BigDecimal getProductPrice(String productId);
    OrderConfirmation confirmOrder();
}
```

Future suppliers implement the same interface. The grocery module doesn't know which supermarket it's talking to.

---

## Data Flow Diagrams

### Flow 1: Weekly Plan Generation

```
User triggers "Generate Plan"
         │
         ▼
Planner reads via query services:
    PreferenceQueryService ── hard constraints, taste profile, lifestyle config
    NutritionQueryService ─── targets, eating window, activity adjustments
    ProvisionQueryService ─── inventory, equipment, budget, supplier prices
    RecipeQueryService ─────── recipe index (names, tags, ratings, macros)
         │
         ▼
[Optional] Planner invokes Recipe Optimiser (Trigger 4: plan-time)
    Optimiser reads same 3 data models + adapts system catalogue recipes
    Publishes RecipeEvolvedEvent for any adapted recipes
         │
         ▼
Phase 1: Recipe Selection (AI — cheap/mid model, or deterministic/hybrid)
    Input:  constraints + recipe index (~2500 tokens)
    Output: 15-20 candidate recipe IDs
         │
         ▼
Planner fetches full recipe details for candidates via RecipeQueryService
         │
         ▼
Phase 2: Creative Augmentation (AI — frontier model)
    Input:  constraints + full recipes + gaps (~5000 tokens)
    Output: 7-day plan with meal slots filled
         │
         ▼
Hard constraint filter (deterministic code — allergy/dietary check)
         │
         ▼
Shopping list calculation (deterministic code):
    plan ingredients − inventory + staples at low/out → mapped to supplier pack sizes
         │
         ▼
Plan + shopping list presented to user
```

### Flow 2: Feedback Loop

```
User gives feedback ("too salty and too expensive")
         │
         ▼
Feedback System: AI classifier (cheap model)
    Input:  feedback text + UI context (which screen, which meal/recipe)
    Output: classification with routing destinations
              "too salty" ──────► Recipe Engine (recipe-specific change)
              "too expensive" ──► Provisions (cost concern)
         │
         ▼
For each destination, Feedback System calls the relevant update service:
    RecipeUpdateService.proposeEvolution(recipeId, feedback)
    ProvisionUpdateService.recordCostFeedback(userId, feedback)
         │
         ▼
Publishes FeedbackProcessedEvent (payload: which destinations were updated)
         │
         ▼
Side effects (via event listeners):
    RecipeEvolvedEvent ──────► Nutrition Engine recalculates
    ProvisionChangedEvent ───► Planner offers mid-week re-optimisation
         │
         ▼
User sees: "Updated: proposed recipe change for Chicken Stir Fry, noted cost concern"
```

### Flow 3: Grocery Order Cycle

```
Planner produces shopping list
         │
         ▼
Grocery Module reads:
    Shopping list items
    ProvisionQueryService ── supplier cache (known products, prices)
         │
         ▼
For uncached items: GroceryProvider.searchProduct(query)
    Cache new product data in Provisions supplier table
         │
         ▼
GroceryProvider.addToBasket() for each item
         │
         ▼
User reviews basket (mandatory — no auto-ordering)
         │
         ▼
GroceryProvider.confirmOrder()
    Publishes GroceryOrderConfirmedEvent
         │
         ▼
Provisions listener:
    Adds purchased items to inventory (with category-based expiry defaults)
    Updates supplier cache prices
    Records substitutions (if any)
         │
         ▼
User can correct: expiry dates, reject substitutions, adjust quantities
```

### Flow 4: Cook Event

```
User marks meal as cooked
         │
         ▼
Publishes MealCookedEvent(userId, recipeId, servingsCooked, isBatchCook)
         │
         ▼
┌─────────────────────────────────┐     ┌──────────────────────────────┐
│ Provisions listener             │     │ Nutrition Logger listener    │
│                                 │     │                              │
│ Deducts recipe ingredients      │     │ Auto-confirms planned        │
│ from inventory                  │     │ nutrition for that meal slot  │
│                                 │     │                              │
│ Shows confirmation:             │     │ User can override if they    │
│ "Removed 400g chicken,          │     │ deviated from the recipe     │
│  1 pepper, 200g rice"           │     │                              │
│                                 │     └──────────────────────────────┘
│ If batch cook:                  │
│   Prompts for fridge/freezer    │
│   split → creates prepared      │
│   portion inventory entries     │
└─────────────────────────────────┘
```

---

## Error Handling

### AI errors

- **Garbage response:** Retry twice with correction prompts, then fail with a user-friendly message. Never show raw AI output to the user.
- **Cost safety:** Hard cap per request. Abort before calling the API if context exceeds the threshold.
- **Timeout:** Configurable per task type. Default 30s, planner Phase 2 gets 60s.
- **Rate limit (429):** Exponential backoff, retry up to 3 times.

**Key principle:** AI features being down should never prevent the user from viewing their current plan, recipes, or inventory. Those are all local data. Only generation and adaptation features require the API.

### External API errors

- **USDA / Open Food Facts down:** Flag ingredient as "unmapped," recipe saved with "nutrition pending" status. Background job retries periodically.
- **Tesco automation fails:** Shopping list still exists — user can shop manually. Log the failure point for debugging.

### Data integrity

- **Inventory negative after deduction:** Floor at zero, alert user (already in Provision Model guardrails)
- **Stale data consolidation:** Each data model defines its own staleness thresholds:
  - Supplier prices: flagged after 2 weeks, excluded after 4 weeks (relaxed during 8-week ramp-up)
  - Inventory: prompted after 3 weeks with no updates when planner depends on the item
  - Lifestyle config: review prompt every 2-3 months
  - Taste profile: continuously updated from feedback, staleness detection via behavioural drift

---

## Frontend-Backend Contract

### REST API with JSON

Standard URL structure:

```
/api/v1/preferences/{userId}/hard-constraints          GET, PUT
/api/v1/preferences/{userId}/taste-profile              GET
/api/v1/preferences/{userId}/lifestyle-config           GET, PUT

/api/v1/nutrition/{userId}/targets                      GET, PUT
/api/v1/nutrition/{userId}/intake/{date}                GET, PUT
/api/v1/nutrition/{userId}/journal/{date}               GET, POST

/api/v1/provisions/{userId}/inventory                   GET
/api/v1/provisions/{userId}/inventory/{itemId}          GET, PUT, DELETE
/api/v1/provisions/{userId}/staples                     GET, PUT
/api/v1/provisions/{userId}/equipment                   GET, PUT
/api/v1/provisions/{userId}/budget                      GET, PUT

/api/v1/recipes                                         GET (search/filter), POST
/api/v1/recipes/{recipeId}                              GET, PUT
/api/v1/recipes/{recipeId}/versions                     GET
/api/v1/recipes/{recipeId}/branches                     GET

/api/v1/planner/{userId}/plans                          GET (list), POST (generate)
/api/v1/planner/{userId}/plans/{planId}                 GET
/api/v1/planner/{userId}/plans/{planId}/meals/{mealId}/cook      POST
/api/v1/planner/{userId}/plans/{planId}/meals/{mealId}/consume   POST

/api/v1/feedback                                        POST

/api/v1/grocery/{userId}/shopping-list                  GET
/api/v1/grocery/{userId}/orders                         POST
```

### Async operations

Plan generation and grocery ordering are long-running (10-60+ seconds). These use a poll-based pattern:

1. Client sends `POST /api/v1/planner/{userId}/plans` → receives `202 Accepted` with a `jobId`
2. Client polls `GET /api/v1/planner/{userId}/jobs/{jobId}` → returns status (`pending`, `running`, `completed`, `failed`)
3. On `completed`, the response includes the plan data

Start with polling. Upgrade to Server-Sent Events (SSE) for real-time progress updates if the UX demands it.

### Response shapes

**Success:**
```json
{
  "data": { ... },
  "meta": { "timestamp": "2026-04-15T10:00:00Z" }
}
```

**Error:**
```json
{
  "error": {
    "code": "PLAN_GENERATION_FAILED",
    "message": "Couldn't generate a plan right now. Please try again."
  }
}
```

**Paginated lists:**
```json
{
  "data": [ ... ],
  "meta": {
    "page": 0,
    "size": 20,
    "totalElements": 42
  }
}
```

### Frontend state management

- **TanStack Query** manages all server state. Every API endpoint maps to a query key. Mutations invalidate related queries automatically. This is the cache — no separate state store for server data.
- **Zustand** stores only local UI state: which modal is open, draft feedback text, filter selections, active tab. Never server data.
- **Never duplicate server state in Zustand.** If it comes from the API, TanStack Query owns it.

---

## Contracts for Remaining Subsystems

Each remaining HLD must define the following. This ensures the docs are implementation-ready and consistent with this architecture.

### Recipe Engine HLD must define:

- **Query service:** `RecipeQueryService` — search/filter, get by ID, get recipe index (for planner context assembly), get nutrition, get versions/branches
- **Update service:** `RecipeUpdateService` — create, import from URL, version, branch, update nutrition data
- **Events published:** `RecipeImportedEvent`, `RecipeEvolvedEvent`
- **AI tasks:** Recipe import (URL → structured recipe), recipe generation (gap-filling), recipe discovery (web search + filter)
- **API endpoints:** CRUD, search, version history, branching
- **Key flow:** Import pipeline (URL fetch → AI extraction → nutrition engine pass → store)

### Meal Planner HLD must define:

- **Query services injected:** `PreferenceQueryService`, `NutritionQueryService`, `ProvisionQueryService`, `RecipeQueryService`
- **Events listened to:** `ProvisionChangedEvent`, `NutritionIntakeDivergedEvent`, `PreferenceChangedEvent`
- **AI tasks:** Plan composition (Phase 1), plan augmentation (Phase 2) — context assembly details, model tier, JSON schema for the plan output
- **Deterministic code:** Shopping list calculation, hard constraint filter (allergy/dietary check post-AI)
- **Key decision:** What "offers re-optimisation" means concretely (notification with one-click re-run? auto-run? user prompt?)
- **Async flow:** Plan generation is long-running — define the job/polling pattern
- **Failure mode:** What happens when AI fails mid-plan? Show previous plan as template? Show error + retry?

### Recipe Optimiser HLD must define:

- **Query services injected:** `PreferenceQueryService`, `NutritionQueryService`, `ProvisionQueryService`, `RecipeQueryService`
- **Update service injected:** `RecipeUpdateService`
- **Events listened to:** `RecipeImportedEvent` (Trigger 1), `DataModelChangedEvent` (Trigger 3)
- **Events published:** `RecipeEvolvedEvent`
- **AI task:** Recipe adaptation — context (recipe + relevant constraints), model tier, JSON schema for proposed changes
- **Key design:** How proposed changes are stored pending user approval (user catalogue) vs applied immediately (system catalogue)
- **The four triggers** with detailed flow for each

### Feedback System HLD must define:

- **Update services injected:** `PreferenceUpdateService`, `NutritionUpdateService`, `ProvisionUpdateService`, `RecipeUpdateService`
- **Events published:** `FeedbackProcessedEvent` (with payload of which destinations were updated)
- **AI task:** Feedback classification — context (feedback text + UI screen context + meal/recipe data), model tier, JSON schema for routing decisions
- **Multi-destination routing:** How classification that splits across destinations works (e.g., "too salty and too expensive" → Recipe Engine + Provisions)
- **Misclassification correction:** How the user discovers and corrects misrouted feedback
- **API endpoint:** Single `POST /api/v1/feedback` with free-text + UI context metadata
