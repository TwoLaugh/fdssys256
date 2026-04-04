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

**Implementation note:** A single `@Service` class per module can implement both interfaces. Splitting into separate impl classes creates coordination headaches when a write method needs to return updated state. The interfaces enforce the contract; the implementation can be pragmatic.

### Cross-module data transfer

**Entities never cross module boundaries.** Every query service method returns a DTO (a Java `record`), not a JPA entity. This means mapper classes or records for every cross-module data shape. Budget for 15-20 DTO/record classes across the inter-module contracts. Use MapStruct or manual mapping — the boilerplate is worth it for clean boundaries.

**Batch methods from day one.** When the Planner loads a plan, it needs to hydrate recipe details for every meal slot. Without batch methods, this is N service calls. Every query service should expose `getByIds(List<Long> ids)` alongside `getById(Long id)`.

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
Nutrition Logger               ✓                              ✓     ✓
```

The Feedback System is the only component that writes to all four destinations. The Grocery Module only reads from and writes to Provisions. The Planner is read-only — it produces a plan as output, but never mutates the data models directly.

**Note:** The Nutrition Logger needs `ProvisionUpdateService` for the snack consumption flow ("Remove 1 banana from pantry?" when a standalone food item is logged). This cross-model write is the canonical path for unplanned consumption.

**Important:** The Feedback System only classifies and routes — the actual update logic lives in each destination module's update service. The Feedback System calls `PreferenceUpdateService.applyFeedback(feedback)`, not `preferenceRepository.save(modifiedProfile)`. This prevents the Feedback System from becoming a god-object.

### When to use events instead

Service calls are for "I need a result." Events are for "something happened that others might care about." Rule of thumb:

- Caller needs the response → service call
- Caller doesn't care who listens → publish an event

---

## Event System

### Spring ApplicationEvent with transactional safety

Events use Spring `ApplicationEvent`. No external message broker needed for a monolith.

**Critical: use `@TransactionalEventListener(phase = AFTER_COMMIT)` as the default for all event listeners.** Not `@EventListener`.

Why: with synchronous `@EventListener`, the listener runs inside the publisher's transaction. If the Planner listener throws an exception while processing `ProvisionChangedEvent`, it **rolls back the Provisions inventory deduction** that published the event. A failed re-optimisation suggestion should never undo an inventory update. `@TransactionalEventListener(AFTER_COMMIT)` ensures the listener runs only after the publishing transaction commits, in its own transaction.

Use synchronous `@EventListener` only when the listener must participate in the publisher's transaction. There is no case for that in the current event catalogue.

For async processing, add `@Async` on top of `@TransactionalEventListener`. This gives thread-pool-based execution without changing the event model.

### Event debouncing

Some events can fire in rapid bursts. A grocery delivery updating 15 inventory items should not trigger 15 `ProvisionChangedEvent` publications and 15 re-optimisation offers. The Provisions module should batch changes from a single operation and publish one event after all writes complete, not one per item. The event payload includes `affectedItemIds` as a list for this reason.

Similarly, the Feedback System processing a single multi-destination feedback entry should publish one `FeedbackProcessedEvent` after all routing completes, not one per destination.

### Event catalogue

| Event | Published by | Listened by | Downstream action |
|---|---|---|---|
| `MealCookedEvent` | UI/Planner (user marks meal cooked) | Provisions (deduct ingredients), Nutrition Logger (auto-confirm intake) | Inventory deduction, nutrition tracking |
| `MealConsumedEvent` | UI (user confirms eating pre-made meal) | Provisions (deduct one portion) | Portion deduction from fridge/freezer |
| `ProvisionChangedEvent` | Provisions (after any inventory update, grocery delivery, waste log — **batched per operation**) | Planner | Offers mid-week re-optimisation for remaining days |
| `NutritionIntakeDivergedEvent` | Nutrition Logger (actual intake diverges significantly from planned) | Planner | Shifts remaining targets, may trigger re-optimisation |
| `PreferenceChangedEvent` | Preference module (after taste profile delta or lifestyle config change) | Planner | Offers re-optimisation if mid-plan |
| `FeedbackProcessedEvent` | Feedback System (after AI classifies and routes — **one event per feedback entry**) | Notification System | Confirms to user what was updated (payload includes which destinations) |
| `RecipeImportedEvent` | Recipe Engine (new recipe added to either catalogue) | Optimiser (Trigger 1) | Run adaptation against data models |
| `RecipeEvolvedEvent` | Optimiser or Recipe Engine (version/branch created) | Nutrition Engine | Recalculate nutrition for new version |
| `GroceryOrderConfirmedEvent` | Grocery Module (order confirmed by user) | Provisions | Add items to inventory, update supplier cache |
| `DataModelChangedEvent` | Any data model (significant change to constraints/targets) | Optimiser (Trigger 3) | Batch re-optimise affected system catalogue recipes |
| `ExpiryApproachingEvent` | Scheduled check on Provisions (`@Scheduled`) | Notification System | Alert user about expiring items |
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

Note: because listeners use `@TransactionalEventListener(AFTER_COMMIT)`, they run after the publishing transaction commits. The query service returns the already-updated state. If a listener needs "before and after," the event payload must carry the delta.

---

## Hard Constraint Filter

The most safety-critical component in the system. **Allergy and dietary identity enforcement is deterministic code, not AI.** Every output that touches food passes through this filter before reaching the user.

### Architectural home

The hard constraint filter is a **shared service** in the preference module: `HardConstraintFilterService`. It is injected by every component that produces food outputs:

```
                                    Injects HardConstraintFilterService
                                    ────────────────────────────────────
Meal Planner                        ✓  (after Phase 2 augmentation)
Recipe Optimiser                    ✓  (after proposing adaptations)
Recipe Discovery                    ✓  (after filtering search results)
Grocery Module                      ✓  (after handling substitutions)
```

### Interface

```java
public interface HardConstraintFilterService {
    FilterResult check(Long userId, List<String> ingredientMappingKeys);
    FilterResult checkRecipe(Long userId, Long recipeId);
    List<Long> filterRecipes(Long userId, List<Long> recipeIds);  // returns only safe recipes
}
```

The filter reads from `PreferenceQueryService.getHardConstraints(userId)` — the DB-locked allergy/dietary identity table. It never reads from the taste profile or any AI-maintained data.

### Enforcement rules

- Allergies: exact ingredient match + known derivative match (e.g., "peanuts" also catches "peanut oil", "peanut butter"). The derivative mapping is a maintained lookup table, not AI inference.
- Dietary identity: `base` filter applies universally, `exceptions` widen only when their conditions are met (frequency, context).
- Severe intolerances (coeliac, etc.): treated identically to allergies.
- Age restrictions: auto-populated for child profiles.

### The open question

How does the filter handle ambiguous cases? E.g., the planner suggests adding yoghurt as a side, and the user has a milk allergy with an exception for lactose-free dairy. The filter needs to know whether this specific yoghurt is lactose-free. This requires either: (a) the planner specifying "lactose-free yoghurt" explicitly, or (b) the filter flagging ambiguous items for user confirmation rather than silently rejecting. Approach (b) is safer — flag and ask, never silently pass something through.

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
planner_jobs                      ← async job tracking for plan generation
feedback_entries
feedback_routing_log
ai_call_log                       ← cross-cutting, owned by AI Service
notification_log
auth_users
household_members
household_environments
```

### Module boundary enforcement

Relying on developer discipline for boundary enforcement erodes quickly. Two options:

- **Spring Modulith** (recommended): provides compile-time module boundary detection, `@ApplicationModuleTest` for isolated testing, event publication logging with replay-on-failure, and auto-generated module dependency documentation. This system is a textbook Spring Modulith candidate — it provides 60% of what this doc describes, but built-in.
- **ArchUnit** (lightweight alternative): test-time enforcement via rules like "no class in `planner` package imports from `recipe.repository`." Trivial to write, catches violations in CI.

Either way, do not rely solely on naming conventions and good intentions.

### Cross-module references

**No `@ManyToOne` or `@JoinColumn` across module boundaries.** If the Planner references a recipe, it stores `recipe_id` as a plain `Long` column. Resolution happens through `RecipeQueryService.getById(recipeId)`.

Shared identifiers and where they're owned:

| Identifier | Owned by | Used by | Type | Normalisation |
|---|---|---|---|---|
| `ingredient_mapping_key` | Nutrition Model (`nutrition_ingredient_mapping.search_term`) | Provisions (inventory items), Supplier data, Recipe ingredients | `text` | Always lowercase, trimmed. The Nutrition Model provides a `normaliseKey()` utility. All modules must use it before storing or looking up keys — "Chicken Breast" and "chicken breast" must resolve to the same entry. |
| `recipe_id` | Recipe Engine (`recipe_recipes.id`) | Planner (meal slots), Provisions (batch cook source), Feedback (routing) | `bigint` | N/A |
| `product_id` | Provisions (`provision_supplier_products.product_id`) | Grocery Module | `text` | N/A |
| `user_id` | Auth (`auth_users.id`) | Every module | `bigint` | N/A |

### JSONB columns

AI-maintained documents and cached structured data use PostgreSQL `jsonb`:

- `preference_taste_profile.document` — the ~2500-token taste profile JSON
- `nutrition_ingredient_mapping.nutrition_per_100g` — full macro/micro data per ingredient
- `provision_supplier_products.substitution_history` — array of substitution records

Everything else uses normal relational columns. The rule: if the AI reads/writes it as a document, use `jsonb`. If it's queried by specific fields, use columns.

### Read models for cross-module views

The "no cross-module JPA" rule creates an N+1 problem for dashboard views that need data from multiple modules (e.g., "this week's plan with recipe names, nutrition totals, and ingredient costs"). The strict approach — resolving everything through service calls — works for individual lookups but is slow for list views.

**Exception: cross-module read-only SQL views or query classes are permitted for UI aggregation.** These are native SQL queries that join across module-prefixed tables, clearly marked as read models, never going through JPA entity mappings, and never writing data. Example: a `DashboardViewQuery` class in the planner module that joins `planner_meal_slots`, `recipe_recipes`, and `nutrition_targets` in a single query for the dashboard screen.

### Migrations

**Flyway with timestamp-based versioning.** All modules share a single PostgreSQL database and a single migration sequence.

```
src/main/resources/db/migration/
├── V20260401120000__preference_create_hard_constraints.sql
├── V20260401120100__preference_create_taste_profile.sql
├── V20260401120200__preference_create_lifestyle_config.sql
├── V20260402120000__nutrition_create_targets.sql
├── V20260402120100__nutrition_create_ingredient_mapping.sql
├── V20260403120000__provision_create_inventory.sql
├── V20260403120100__provision_create_supplier_products.sql
├── V20260404120000__recipe_create_recipes_and_versions.sql
└── ...
```

Why timestamps, not per-module `V1__` / `V2__`: Flyway requires globally unique, monotonically ordered version numbers. Per-module `V1__` in separate folders causes version collisions. Timestamps eliminate this problem entirely — no collision risk, natural ordering, and clear traceability to when each migration was written.

If using Spring Modulith, its `FlywayModuleInitializer` handles per-module migration ordering natively, which is another reason to adopt it.

---

## AI Service Architecture

### Central abstraction

Every AI interaction goes through `AiService`. Calling modules don't touch the Anthropic API directly.

```java
public interface AiService {
    <T> T execute(AiTask<T> task);
}

public interface AiTask<T> {
    TaskType getTaskType();           // determines model tier, timeout, token cap
    String getSystemPrompt();         // separate from user messages (Anthropic API requirement)
    Map<String, String> getContext(); // fills template placeholders for user message
    ToolDefinition getToolSchema();   // JSON schema for structured output via tool use
}
```

Each module defines its own task implementations. The Planner defines `PlanCompositionTask`, `PlanAugmentationTask`. The Feedback System defines `FeedbackClassificationTask`. The AI Service doesn't know anything about meal planning — it routes, calls, parses, and logs.

**Why `getSystemPrompt()` is separate:** The Anthropic API treats system instructions as a distinct parameter, not a message. Separating it at the interface level prevents accidentally putting system instructions in user messages, which degrades output quality.

### Structured output via Anthropic tool use

**Use Anthropic's tool use (function calling) for all structured output tasks.** Instead of asking the model to produce JSON in free text and then parsing/repairing it, define a tool schema and the model returns guaranteed-valid JSON matching that schema.

```java
// The AiService implementation:
// 1. Sends the tool schema as a tool definition
// 2. The model returns a tool_use content block with valid JSON
// 3. Deserialize directly — no JSON repair step needed
// 4. Only retry on semantic errors (wrong values), not structural ones
```

This eliminates the entire "malformed JSON → repair → retry" failure mode. The retry flow simplifies to:

```
Model returns tool_use response
    │
    ▼
1. Deserialize JSON from tool_use block
    ├── Success + passes validation → return result
    └── Semantic error (wrong values, missing logic) → go to step 2
    │
    ▼
2. Retry with correction prompt (max 1 retry)
    ├── Success → return result
    └── Still failing → fail gracefully
    │
    ▼
3. Log failure. Return user-friendly error. Never show raw AI output.
```

### Model routing

```java
public enum ModelTier {
    FRONTIER,  // highest capability
    MID,       // balance of capability and cost
    CHEAP      // fast, low cost
}
```

**Model-to-tier mapping lives in configuration, not code:**

```yaml
# application.yml
ai:
  tiers:
    frontier: claude-sonnet-4-20261001
    mid: claude-haiku-3.5-20250301
    cheap: claude-haiku-3.5-20250301
  overrides:
    TESCO_PRODUCT_MATCH: frontier  # per-task override
```

When Anthropic releases a new model, update config and test. No code changes. The `overrides` map lets you promote a single task to a higher tier without changing the global mapping.

**Resolve the tier ambiguity:** Frontier = best available (currently Sonnet 4 or Opus). Mid = Haiku 3.5 or Sonnet. Cheap = Haiku. No model appears in two tiers.

| Task | Model Tier | Expected context | Per-task token cap |
|---|---|---|---|
| Plan composition (Phase 1: recipe selection) | TBD (may be deterministic/hybrid) | ~2,500 tokens | 10,000 |
| Plan augmentation (Phase 2: creative gap-filling) | Frontier | ~5,000 tokens | 20,000 |
| Mid-week re-optimisation | Same as Phase 1 | ~2,500 tokens | 10,000 |
| Recipe adaptation (Optimiser) | Mid | ~2,000 tokens | 8,000 |
| Recipe generation | Mid | ~1,500 tokens | 6,000 |
| Recipe import from URL | Mid | Variable (page content) | 30,000 |
| Feedback classification + routing | Cheap | ~1,000 tokens | 5,000 |
| Preference model delta update | Mid | ~3,000 tokens | 12,000 |
| Nutrition ingredient mapping | Cheap | ~500 tokens | 3,000 |
| Free-text input parsing | Cheap | ~300 tokens | 2,000 |
| Tesco product matching | Mid/Frontier | Variable | 15,000 |

Per-task token caps replace the single global 100k cap. A 2x-4x multiplier over expected size catches real bugs while stopping runaway contexts early.

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

**Start with `{{placeholder}}` substitution.** When prompts need conditional sections (eating window yes/no, mid-week re-opt with locked meals, household members), switch to **Handlebars.java** (or Mustache) — lightweight template engines that support `{{#if}}`, `{{#each}}`, and nested context while remaining readable text files. Don't build this upfront — switch when you find yourself assembling multi-line conditional strings in Java.

Why files, not database: version-controlled, reviewable in diffs, no runtime DB dependency for prompts. Move to DB later if you need dynamic prompts (A/B testing, per-user tuning).

### Context assembly

The caller (not the AI Service) assembles the context. Each module knows what context its AI tasks need.

**The two-pass planning approach** controls context size for the most expensive operation:

```
Pass 1 — Recipe Selection (cheap/mid model)
  Context: profile + preferences + provisions + recipe INDEX
           (names, tags, ratings, macros, equipment, prep time — not full recipes)
  Output:  15-20 candidate recipe IDs + confidence flag
  Size:    ~2500 tokens input

Pass 2 — Plan Assembly (frontier model)
  Context: profile + preferences + provisions + FULL selected recipes
  Output:  7-day structured meal plan
  Size:    ~5000 tokens input (verify by counting actual tokens before calling)
```

This pattern — index first, full details only for selected items — applies to any AI task that needs to reason over a large set.

**Failure modes to design for:**
- Pass 1 selects poorly because the index is too lossy → include equipment requirements and prep time in the index, not just names/tags/macros
- Pass 1 returns too few candidates (tight constraints) → Pass 1 should return a confidence flag ("constrained — only 8 viable candidates") so Pass 2 knows to lean into creative augmentation
- Context size exceeds cap → count actual tokens before calling (Anthropic SDK supports `count_tokens`). If over cap, reduce candidate set or truncate recipe details
- Pass 1 and Pass 2 disagree (cheaper model selects candidates the frontier model rejects) → log this; if frequent, the index representation needs enriching

**Prompt caching:** The shared context between Pass 1 and Pass 2 (profile + preferences + provisions) is identical. Use Anthropic's prompt caching (`cache_control` breakpoints) to cache this prefix. Cached tokens are billed at 10% of the normal input rate — significant savings on the frontier call.

### Cost tracking

Every API call is logged to `ai_call_log`:

| Column | Type | Purpose |
|---|---|---|
| `timestamp` | timestamp | When the call was made |
| `user_id` | bigint | Who triggered it |
| `task_type` | text | Which AI task (e.g., `PLAN_ASSEMBLY`, `FEEDBACK_CLASSIFICATION`) |
| `model_used` | text | Which model was actually called |
| `prompt_version` | text | Hash or identifier of the prompt template used — correlates prompt changes with quality |
| `input_tokens` | integer | Tokens sent |
| `output_tokens` | integer | Tokens received |
| `cost_estimate` | decimal | Calculated from token counts × model pricing |
| `latency_ms` | integer | Round-trip time |
| `success` | boolean | Whether parsing succeeded |
| `retry_count` | integer | How many attempts (0 = first try succeeded) |
| `cache_hit` | boolean | Whether prompt caching was used |

Monthly summary available in settings: "This month: £X.XX across Y API calls."

**Cost safety mechanisms:**
- Per-task token caps (see table above) — abort before calling if context exceeds cap
- Per-user daily spend cap (configurable, default: £5/day) — AI features degrade gracefully when hit (show cached plans, disable generation)
- Per-task-type rate limits — plan generation max a few times per day, feedback classification max ~20/day. Catches runaway loops.
- Circuit breaker (Resilience4j or Spring Retry's `@CircuitBreaker`) — if 5 consecutive calls to the same task type fail, stop trying for 5 minutes. Prevents retry storms.
- Idempotency for expensive operations — if the user double-clicks "Generate Plan," the job/polling pattern deduplicates at the job level. Only one frontier API call.
- Log full prompt on failure only — avoids bloating the log table while preserving debuggability.

**Quality evaluation:** For v1, don't build automated evaluation. Track mechanical metrics (parse success, retry rate, latency, cost). Use the feedback system as the quality signal — negative feedback after plans correlates with prompt quality. Build 5-10 golden test cases (fixed inputs with manually-judged good outputs) and run them when changing prompts.

### Other Anthropic API features to consider

| Feature | Priority | Use case |
|---|---|---|
| **Tool use (function calling)** | High — use from day one | All structured output. Eliminates JSON repair. |
| **Prompt caching** | High — use from day one | Shared context between planning passes. 10% token cost on cached prefixes. |
| **Streaming** | Medium — add for Phase 2 plan assembly | Show progressive output ("Selecting recipes… Assembling Monday… Tuesday…") instead of a 30-60s spinner. |
| **Message Batches API** | Medium — for batch recipe re-optimisation | Trigger 3 (data model change) may adapt 50+ system catalogue recipes. Batches API processes at 50% cost, within 24 hours. Only for non-urgent background tasks. |
| **Token counting** | Low but useful | Verify context size before calling when content is variable (recipe import from URL). More accurate than estimation. |
| **Extended thinking** | Situational | Complex planning with hard constraint combinations. Higher token cost. Test on hardest cases. |

---

## External API Integration

### USDA FoodData Central

- **Client:** `UsdaApiClient` in the nutrition module
- **Purpose:** Ingredient identification, nutrition lookup (~370k food entries)
- **API:** `https://fdc.nal.usda.gov/api-guide`
- **Caching:** The `nutrition_ingredient_mapping` table IS the cache. Once mapped, never looked up again. After a few months, API calls become rare.
- **Rate limiting:** 1000 requests/hour with API key. Wrap in Spring Retry's `@Retryable` or Resilience4j `@RateLimiter`.
- **Failure:** Retry on 5xx (up to 2 attempts via `@Retryable`). On persistent failure, flag ingredient as "unmapped" with `nutrition_status = "pending"`. A `@Scheduled` background job retries unmapped ingredients periodically.
- **Testing:** WireMock with recorded responses. No need for Testcontainers.

### Open Food Facts

- **Client:** `OpenFoodFactsClient` in the nutrition module
- **Purpose:** Branded/packaged products, barcode lookup
- **API:** `https://world.openfoodfacts.org/data`
- **Caching:** Same as USDA — results cached in `nutrition_ingredient_mapping` with `source = "open_food_facts"`
- **Fallback hierarchy:** Search USDA first, fall back to Open Food Facts for branded items
- **Testing:** WireMock with recorded responses.

### Tesco (via GroceryProvider)

- **Interface:** `GroceryProvider` abstraction in the grocery module
- **Implementation:** `TescoGroceryProvider` — uses browser automation (Claude computer use or Chrome connector)

```java
public interface GroceryProvider {
    List<ProductMatch> searchProduct(String query);
    void addToBasket(String productId, int quantity);
    Basket getBasket();
    BigDecimal getProductPrice(String productId);
    OrderConfirmation confirmOrder();
}
```

**This is the most fragile integration in the system.** Browser automation against a retail website breaks when the site changes its DOM, adds CAPTCHAs, or updates anti-bot measures. This WILL break regularly.

**Recommendations:**
- **Defer implementation** until the core system works end-to-end. The shopping list has standalone value — the user copies it and shops manually. Automation adds convenience but massive fragility.
- When implementing, run the browser in a **separate Docker container** (e.g., `browserless/chrome`) with hard timeouts (page load: 30s, element wait: 10s, full order flow: 5 minutes, memory limit: 1GB).
- Design for **partial failure**: 3 of 5 items added to basket, automation breaks. The user sees what was added and can complete manually.
- Product data cached in `provision_supplier_products`. Price staleness rules defined in the Provision Model HLD.
- **Fallback is always available:** shopping list exists regardless of automation status.

Future suppliers implement the same `GroceryProvider` interface.

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
    RecipeQueryService ─────── recipe index (names, tags, ratings, macros,
                               equipment, prep time)
         │
         ▼
[Optional] Planner invokes Recipe Optimiser (Trigger 4: plan-time)
    Optimiser reads same 3 data models + adapts system catalogue recipes
    Publishes RecipeEvolvedEvent for any adapted recipes
         │
         ▼
Phase 1: Recipe Selection (AI — cheap/mid model, or deterministic/hybrid)
    Input:  constraints + recipe index (~2500 tokens)
    Output: 15-20 candidate recipe IDs + confidence flag
         │
         ▼
Planner fetches full recipe details for candidates via RecipeQueryService
         │
         ▼
Phase 2: Creative Augmentation (AI — frontier model, with prompt caching)
    Input:  constraints + full recipes + gaps (~5000 tokens)
    Output: 7-day plan with meal slots filled
         │
         ▼
Hard constraint filter (HardConstraintFilterService — deterministic code)
    Checks every ingredient in every meal against allergy/dietary DB
    Flags ambiguous items for user confirmation
         │
         ▼
Shopping list calculation (deterministic code):
    plan ingredients − inventory + staples at low/out → mapped to supplier pack sizes
         │
         ▼
Plan + shopping list + estimated cost presented to user
    (cost marked as "partial estimate" if supplier cache coverage < 80%)
```

### Flow 2: Feedback Loop

```
User gives feedback ("too salty and too expensive")
         │
         ▼
Feedback System: AI classifier (cheap model, via tool use for structured output)
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
Side effects (via @TransactionalEventListener AFTER_COMMIT):
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
Grocery module reads:
    Shopping list items
    ProvisionQueryService ── supplier cache (known products, prices)
         │
         ▼
For uncached items: GroceryProvider.searchProduct(query)
    Cache new product data in Provisions supplier table
         │
         ▼
GroceryProvider.addToBasket() for each item
    Handle partial failure: log which items succeeded, surface to user
         │
         ▼
User reviews basket (mandatory — no auto-ordering)
         │
         ▼
GroceryProvider.confirmOrder()
    Publishes GroceryOrderConfirmedEvent (single event, not per-item)
         │
         ▼
Provisions listener (@TransactionalEventListener AFTER_COMMIT):
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
│ (@TransactionalEventListener)   │     │ (@TransactionalEventListener)│
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
         │
         ▼
After both listeners complete:
    Publishes ProvisionChangedEvent (batched, single event)
```

---

## Error Handling

### AI errors

- **Structured output failure:** With tool use, JSON structure is guaranteed. Retry once on semantic errors (wrong values). Max 1 retry.
- **Timeout:** Configurable per task type. Default 30s, planner Phase 2 gets 60s.
- **Rate limit (429):** Exponential backoff via Resilience4j, retry up to 3 times.
- **Cost cap exceeded:** Abort before calling the API. Log a warning with the actual token count vs the cap.
- **Circuit breaker:** 5 consecutive failures → stop trying for 5 minutes.

**Key principle:** AI features being down should never prevent the user from viewing their current plan, recipes, or inventory. Those are all local data. Only generation and adaptation features require the API.

### External API errors

- **USDA / Open Food Facts down:** Flag ingredient as "unmapped," recipe saved with "nutrition pending" status. `@Scheduled` background job retries periodically.
- **Tesco automation fails:** Shopping list still exists — user shops manually. Log the failure point and which items succeeded for debugging. Surface partial success to user.

### Data integrity

- **Inventory negative after deduction:** Floor at zero, alert user (Provision Model guardrails)
- **Stale data consolidation:** Each model defines its own staleness thresholds:
  - Supplier prices: flagged after 2 weeks, excluded after 4 weeks (relaxed during 8-week ramp-up per Provision Model)
  - Inventory: prompted after 3 weeks with no updates when planner depends on the item
  - Lifestyle config: review prompt every 2-3 months
  - Taste profile: continuously updated from feedback, staleness detection via behavioural drift

---

## Frontend-Backend Contract

### REST API with JSON

**Resolve `userId` from auth context server-side** — don't put it in URL paths. The backend knows who the user is from the session/token. If household member switching is needed later, use a header or query param (`?actingAs=memberId`).

Standard URL structure:

```
# Preferences
GET    /api/v1/preferences/hard-constraints
PUT    /api/v1/preferences/hard-constraints
GET    /api/v1/preferences/taste-profile
GET    /api/v1/preferences/lifestyle-config
PUT    /api/v1/preferences/lifestyle-config

# Nutrition
GET    /api/v1/nutrition/targets
PUT    /api/v1/nutrition/targets
GET    /api/v1/nutrition/intake/{date}
PUT    /api/v1/nutrition/intake/{date}
GET    /api/v1/nutrition/journal/{date}
POST   /api/v1/nutrition/journal/{date}

# Provisions
GET    /api/v1/provisions/inventory
GET    /api/v1/provisions/inventory?expiring=true     ← convenience filter
POST   /api/v1/provisions/inventory                   ← create new item
GET    /api/v1/provisions/inventory/{itemId}
PUT    /api/v1/provisions/inventory/{itemId}
PATCH  /api/v1/provisions/inventory/{itemId}           ← partial update (quantity, expiry)
DELETE /api/v1/provisions/inventory/{itemId}
GET    /api/v1/provisions/staples
PUT    /api/v1/provisions/staples
GET    /api/v1/provisions/equipment
PUT    /api/v1/provisions/equipment
GET    /api/v1/provisions/budget
PUT    /api/v1/provisions/budget
POST   /api/v1/provisions/waste                        ← log waste entry

# Recipes
GET    /api/v1/recipes?cuisine=mediterranean&maxTime=30&catalogue=user
POST   /api/v1/recipes
GET    /api/v1/recipes/{recipeId}
PUT    /api/v1/recipes/{recipeId}
GET    /api/v1/recipes/{recipeId}/versions
GET    /api/v1/recipes/{recipeId}/branches
GET    /api/v1/recipes/{recipeId}/nutrition

# Planner
GET    /api/v1/planner/plans
GET    /api/v1/planner/plans/current                   ← active plan convenience endpoint
POST   /api/v1/planner/plans                           ← triggers async generation
GET    /api/v1/planner/plans/{planId}
GET    /api/v1/planner/plans/{planId}/shopping-list
POST   /api/v1/planner/plans/{planId}/meals/{mealId}/cook
POST   /api/v1/planner/plans/{planId}/meals/{mealId}/consume
GET    /api/v1/planner/jobs/{jobId}                    ← poll for async status

# Feedback
POST   /api/v1/feedback

# Grocery
GET    /api/v1/grocery/shopping-list                   ← current plan's shopping list
POST   /api/v1/grocery/orders                          ← triggers async ordering

# Notifications
GET    /api/v1/notifications                           ← list recent notifications
PUT    /api/v1/notifications/{id}/read                 ← mark as read

# Settings / Admin
GET    /api/v1/settings/ai-usage                       ← monthly cost summary from ai_call_log
GET    /api/v1/admin/status                            ← health: DB connectivity, last AI call, last USDA call, monthly cost
```

### Authentication

Simple username + hashed password (system overview: "no OAuth initially"). Use Spring Security with session-based auth or JWT.

- Session-based (simpler for a web app): Spring Security defaults. Session cookie sent with every request. TanStack Query doesn't need to manage tokens.
- JWT (if preferred): stored in httpOnly cookie (not localStorage). Frontend doesn't handle tokens directly.

Either way, the frontend attaches credentials automatically via cookies. No `Authorization` header management needed in TanStack Query's `queryFn`.

### Async operations

Plan generation and grocery ordering are long-running (10-60+ seconds). Poll-based pattern:

1. Client sends `POST /api/v1/planner/plans` → receives `202 Accepted` with `{ jobId: "abc123" }`
2. Client polls `GET /api/v1/planner/jobs/abc123` every 2 seconds (TanStack Query's `refetchInterval`)
3. Response includes progress: `{ status: "running", step: "Selecting recipes...", progress: 0.4 }` or `{ status: "completed", data: { planId: 42 } }`
4. On `completed`, stop polling, redirect to plan view

Start with polling. Upgrade to SSE if the UX demands real-time progress:

```
Future SSE shape:
  event: job-progress
  data: { "jobId": "abc123", "status": "running", "step": "Assembling Tuesday...", "progress": 0.6 }
```

### Response shapes

**Success:**
```json
{
  "data": { ... },
  "meta": { "timestamp": "2026-04-15T10:00:00Z" }
}
```

**Error (4xx/5xx — triggers TanStack Query error state):**
```json
{
  "error": {
    "code": "PLAN_GENERATION_FAILED",
    "message": "Couldn't generate a plan right now. Please try again.",
    "retryable": true
  }
}
```

**HTTP status code conventions:**
- `200` — success
- `201` — created (POST that creates a resource)
- `202` — accepted (async job started)
- `400` — validation error (bad input)
- `404` — resource not found
- `409` — conflict (e.g., concurrent edit)
- `422` — unprocessable entity (valid JSON but semantically wrong)
- `500` — internal server error

**Paginated lists:**
```json
{
  "data": [ ... ],
  "meta": {
    "page": 0,
    "size": 20,
    "totalElements": 42,
    "sort": "createdAt,desc"
  }
}
```

**Mutation responses:** Always return the created/updated resource in `data`. For feedback: return the routing result so the UI can confirm what was updated.

### Frontend state management

- **TanStack Query** manages all server state. Every API endpoint maps to a query key. Mutations invalidate related queries automatically. This is the authoritative cache — no separate state store for server data.
- **Zustand** stores local UI state (open modals, draft text, filter selections) and **working drafts** of server data that haven't been committed yet (e.g., editing the shopping list before confirming the order).
- **Rule:** TanStack Query owns the source of truth for anything the server knows about. Zustand owns ephemeral state and uncommitted drafts. The "never duplicate" principle applies to authoritative copies, not working drafts.

### OpenAPI / TypeScript types

Spring Boot generates an OpenAPI spec (via `springdoc-openapi`). Frontend generates TypeScript types from it using `openapi-typescript`. This prevents type drift between backend and frontend. The OpenAPI spec is the contract — define request/response shapes there, not in prose documentation.

---

## Observability

### Logging

Structured JSON logging via SLF4J + Logback (ships with Spring Boot). Log:
- Every external API call (USDA, Open Food Facts, Anthropic, Tesco) with endpoint, response time, status code, and correlation ID
- Every event publication and receipt with event type and key IDs
- Flyway migration results at startup
- Every hard constraint filter invocation and result (for safety audit)

### Health checks

Spring Boot Actuator (add `spring-boot-starter-actuator`):
- `/actuator/health` — auto-detects Postgres connectivity and disk space
- `/actuator/metrics` — JVM memory, thread counts, HTTP request timing

Custom `/api/v1/admin/status` endpoint: database connectivity, last successful AI call timestamp, last successful USDA call timestamp, current month's AI cost from `ai_call_log`.

### What you do NOT need

No Prometheus. No Grafana. No ELK stack. No distributed tracing. If slow queries become an issue, enable Postgres `log_min_duration_statement = 1000` (logs queries slower than 1s).

---

## Development Environment

### Startup sequence

1. Start Postgres: `docker-compose up -d`
2. Start Spring Boot: `./mvnw spring-boot:run` (or run from IDE)
3. Start React: `npm run dev` (Vite dev server on port 5173)

### Docker setup

```yaml
# docker-compose.yml
version: "3.8"
services:
  postgres:
    image: postgres:16-alpine
    container_name: mealprep-db
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: mealprep
      POSTGRES_USER: mealprep
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U mealprep"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  pgdata:
```

**Do NOT put Spring Boot in Docker during development.** Run it natively for faster iteration and easier debugging. Only containerise the app for "production-like" testing.

### Environment configuration

```
# .env (gitignored — never committed)
DB_PASSWORD=localdevpassword
ANTHROPIC_API_KEY=sk-ant-...
USDA_API_KEY=...
```

Ship a `.env.example` with placeholder values. Use `application.yml` for defaults, `application-dev.yml` for local overrides, `application-test.yml` for test profile. Three files, no more.

### CORS

Spring Boot backend runs on port 8080, Vite dev server on port 5173. Configure CORS in the `dev` profile to allow `http://localhost:5173`.

### Hot reload

- **Spring Boot:** `spring-boot-devtools` (auto-restart on class changes, ~2-3s warm restart)
- **React/Vite:** HMR works out of the box

### Database reset

Create a Spring profile that drops and recreates the schema: `spring.flyway.clean-disabled=false` in `application-dev.yml` only. Useful during early development when schema changes break migrations.

### Testing external APIs

- **USDA / Open Food Facts:** WireMock with recorded responses in `src/test/resources/wiremock/`
- **Anthropic API:** Mock `AiService` implementation injected in test profile. Set a cost cap of £0 in tests to catch accidental real API calls.
- **Postgres:** Testcontainers for integration tests that need real JSONB, Flyway, and native queries.

### Backups

Daily `pg_dump` via Windows Task Scheduler or cron:

```bash
docker exec mealprep-db pg_dump -U mealprep mealprep | gzip > /path/to/backups/mealprep-$(date +%Y%m%d).sql.gz
find /path/to/backups/ -name "mealprep-*.sql.gz" -mtime +30 -delete
```

Store on a different drive or sync to OneDrive. Test restore once — an untested backup is not a backup.

---

## Contracts for Remaining Subsystems

Each remaining HLD must define the following, ensuring implementation-readiness and consistency with this architecture.

### Recipe Engine HLD must define:

- **Query service:** `RecipeQueryService` — search/filter (with query params for cuisine, time, catalogue type, tags), get by ID, `getByIds(List<Long>)` for batch hydration, get recipe index (for planner context assembly — must include names, tags, ratings, macros, equipment, prep time), get nutrition, get versions/branches
- **Update service:** `RecipeUpdateService` — create, import from URL, version, branch, update nutrition data, `proposeEvolution(recipeId, feedback)` for feedback-driven changes
- **Events published:** `RecipeImportedEvent`, `RecipeEvolvedEvent`
- **AI tasks:** Recipe import (URL → structured recipe), recipe generation (gap-filling), recipe discovery (web search + filter). Each with context assembly, model tier, tool use schema, failure mode.
- **Two-catalogue approval model:** Every write path must check catalogue type. User catalogue → propose (requires approval). System catalogue → apply freely. This distinction must be explicit in the update service interface.
- **API endpoints:** CRUD, search with filters, version history, branching
- **Key flow:** Import pipeline (URL fetch → AI extraction → nutrition engine pass → hard constraint filter → store)

### Meal Planner HLD must define:

- **Query services injected:** `PreferenceQueryService`, `NutritionQueryService`, `ProvisionQueryService`, `RecipeQueryService`
- **Hard constraint filter:** Injects `HardConstraintFilterService`, runs after Phase 2
- **Events listened to:** `ProvisionChangedEvent`, `NutritionIntakeDivergedEvent`, `PreferenceChangedEvent`
- **AI tasks:** Plan composition (Phase 1), plan augmentation (Phase 2) — context assembly details, model tier, tool use schema for the plan output, failure mode
- **Deterministic code:** Shopping list calculation (5-step formula from Provision Model), hard constraint filter post-AI
- **Key decisions:** What "offers re-optimisation" means concretely (notification with one-click re-run? auto-run? user prompt?). What happens when AI fails mid-plan (show previous plan as template? error + retry?).
- **Async flow:** Plan generation uses the job/polling pattern. Define job states, progress reporting, cancellation.
- **Transactional boundaries:** Plan generation reads from all models but writes only to `planner_plans` / `planner_meal_slots`. No data model mutations.

### Recipe Optimiser HLD must define:

- **Query services injected:** `PreferenceQueryService`, `NutritionQueryService`, `ProvisionQueryService`, `RecipeQueryService`
- **Update service injected:** `RecipeUpdateService`
- **Hard constraint filter:** Injects `HardConstraintFilterService`, runs after proposing adaptations
- **Events listened to:** `RecipeImportedEvent` (Trigger 1), `DataModelChangedEvent` (Trigger 3)
- **Events published:** `RecipeEvolvedEvent`
- **AI task:** Recipe adaptation — context (recipe + relevant constraints from all 3 models), model tier, tool use schema for proposed changes, failure mode
- **The four triggers** with detailed flow for each
- **Propose vs apply:** How proposed changes are stored pending user approval (user catalogue) vs applied immediately (system catalogue). Proposed changes need a storage model (pending_recipe_changes table? version with status "proposed"?).

### Feedback System HLD must define:

- **Update services injected:** `PreferenceUpdateService`, `NutritionUpdateService`, `ProvisionUpdateService`, `RecipeUpdateService`
- **Events published:** `FeedbackProcessedEvent` (with payload of which destinations were updated)
- **AI task:** Feedback classification — context (feedback text + UI screen context + meal/recipe data), model tier, tool use schema for routing decisions, failure mode
- **Multi-destination routing:** How classification that splits across destinations works. Transactional boundary: if the Provisions write succeeds but the Recipe Engine write fails, what state is the system in? Each destination write should be independent — partial success is acceptable, logged, and surfaceable to the user.
- **Misclassification correction:** How the user discovers and corrects misrouted feedback. The UX for "here's what I think you meant — correct me?"
- **API endpoint:** Single `POST /api/v1/feedback` with `{ text: "too salty and expensive", context: { screen: "recipe_detail", recipeId: 123, mealSlotId: 456 } }`
- **Quality monitoring:** Track classification accuracy over time. When the user corrects a misroute, log it as ground truth for future evaluation.

### Grocery Module HLD must define:

- **`GroceryProvider` implementation strategy:** Browser automation approach, containerisation, timeout configuration
- **Partial failure handling:** What happens when 3 of 5 items are added to basket but automation breaks. User sees what succeeded, can complete manually.
- **Substitution acceptance/rejection flow:** How the user reviews and accepts/rejects Tesco substitutions through the UI
- **The deferred implementation strategy:** Shopping list works standalone from day one. Tesco automation is added later.
