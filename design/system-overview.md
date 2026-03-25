# MealPrep AI — End-to-End System Overview

*Single source of truth for the system architecture.*

## What This Is

An AI-powered meal planning and health optimisation system for personal/family use. The AI handles planning, recipe management, and learning from feedback. The user views, gives feedback, and makes adjustments.

## Architecture: Three Data Models, One Planner

The system has seven major components with distinct roles:

- **Three data models (state):** Preference Model, Nutrition Model, and Provisions. Data objects that hold constraints, targets, and current state. They don't contain optimisation logic — they're what gets optimised against.
- **Recipe Engine:** An independent catalogue (split into user and system catalogues) of recipes with versioning and branching. It doesn't optimise anything — it's the pool the planner draws from.
- **Recipe Optimiser:** Adapts individual recipes against the three data models. Triggered on import, after feedback, on data model changes, or at plan-time. Proposes changes to user recipes (requires approval), freely modifies system recipes.
- **Meal Planner:** The orchestrator. Operates in two phases: plan-level composition (combinatorial search) and plan-level creative augmentation (AI-generated gap-filling). It may invoke the Recipe Optimiser as a pre-step before composition, but the optimiser is its own component — not a planner phase. The planner queries the Recipe Engine to find combinations of recipes that satisfy all three data models simultaneously.
- **Feedback System:** Single conversational interface accessible from anywhere in the UI. Context-aware classifier routes feedback to four destinations (Preference Model, Nutrition Model, Provisions, Recipe Engine). Can split a single piece of feedback across multiple destinations. The only component that writes back to the data models via AI interpretation — all other writes are manual direct edits or planner outputs. The feedback system does not route to the Meal Planner directly — plan re-runs are a downstream consequence of data model changes (e.g., updating Provisions after an ingredient spoils triggers the planner to re-optimise remaining days).

The planning cadence defaults to weekly but is configurable.

```
     ╔═════════════════════════════════════════════════════════════════════╗
     ║                       USER INTERACTION                              ║
     ║             view plan · cook · eat · give feedback                  ║
     ╚════════════════════════════════╤════════════════════════════════════╝
                                      │
                                      ▼
     ┌─────────────────────────────────────────────────────────────────────┐
     │                        FEEDBACK SYSTEM                              │
     │                                                                     │
     │  conversational input → AI classifies → routes to:                  │
     │    taste/ease/cuisine ────────────────────────► preference          │
     │    portion/nutrition/health ──────────────────► nutrition           │
     │    cost/availability/equipment ──────────────► provisions           │
     │    recipe-specific changes ──────────────────► recipe engine        │
     │                                                                     │
     │  single input can split across multiple destinations                │
     └───────┬─────────────────┬─────────────────┬───────────────────┬─────┘
             │                 │                 │                   │
             ▼                 ▼                 ▼                   ▼
     ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
     │  PREFERENCE  │   │  NUTRITION   │   │  PROVISIONS  │   │ RECIPE ENGINE│
     │    MODEL     │   │    MODEL     │   │              │   │              │
     │              │   │              │   │              │   │              │
     │ allergies    │   │ cal/macro/   │   │ pantry       │   │ user catalog │
     │ likes        │   │ micro tgts   │   │ freezer      │   │ sys catalog  │
     │ dislikes     │   │ dietary id   │   │ equipment    │   │              │
     │ cuisine      │   │              │   │ environment  │   │ versioning   │
     │ cooking      │   │ ◄─ health:   │   │ budget       │   │ branching    │
     │ meal struct  │   │ mood · wt    │   │ suppliers    │   │              │
     │              │   │ symptoms     │   │              │   │ store/import │
     │              │   │ labs · wear  │   │ ◄─ grocery   │   │ discover/gen │
     │              │   │              │   │    prices    │   │              │
     └───────┬──────┘   └───────┬──────┘   └───────┬──────┘   └───────┬──────┘
             │                  │                  │                  │
             ▼                  ▼                  ▼                  ▼
     ┌─────────────────────────────────────────────────────────────────────┐
     │                       RECIPE OPTIMISER                              │
     │                                                                     │
     │  adapts individual recipes against all three data models            │
     │  triggers: import · feedback · data model change · plan-time        │
     │  user recipes: propose (requires approval)                          │
     │  system recipes: apply freely                                       │
     └────────────────────────────────┬────────────────────────────────────┘
                                      │
                                      ▼
     ╔═════════════════════════════════════════════════════════════════════╗
     ║                         MEAL PLANNER                                ║
     ║                                                                     ║
     ║  queries Recipe Engine · checks against all three data models       ║
     ║  may invoke Recipe Optimiser as pre-step                            ║
     ║                                                                     ║
     ║  Phase 1: composition — select + arrange from both catalogues       ║
     ║  Phase 2: creative augmentation — AI fills remaining gaps           ║
     ╚══════════╤════════════════════╤════════════════════╤════════════════╝
                │                    │                    │
                ▼                    ▼                    ▼
     ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐
     │    WEEKLY PLAN      │  │  NUTRITION LOGGER   │  │   GROCERY ORDER     │
     │                     │  │                     │  │                     │
     │  schedule for       │  │  planned vs actual  │  │  GroceryProvider    │
     │  planning period    │  │  intake tracking    │  │  (Tesco first)      │
     │                     │  │                     │  │                     │
     │                     │  │                     │  │  purchased items    │
     │                     │  │                     │  │  ──► provisions     │
     └──────────┬──────────┘  └──────────┬──────────┘  └──────────┬──────────┘
                │                        │                        │
                └────────────────────────┼────────────────────────┘
                                         │
                                         ▼
     ╔═════════════════════════════════════════════════════════════════════╗
     ║                       USER INTERACTION                              ║
     ║                          ↺ loops back                               ║
     ╚═════════════════════════════════════════════════════════════════════╝

        ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄ cross-cutting services ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄

     ┌─────────────────────────────────┐  ┌────────────────────────────────┐
     │          AI SERVICE             │  │     NOTIFICATION SYSTEM        │
     │                                 │  │                                │
     │  frontier · mid · cheap         │  │  expiry · defrost warnings     │
     │  prompt mgmt · context          │  │  prep reminders                │
     │  structured parsing             │  │  nutrition alerts              │
     │  cost tracking · retry          │  │  health review available       │
     └─────────────────────────────────┘  └────────────────────────────────┘
```

---

## Data Model 1: Preference Model

Holds the user's taste profile, constraints, and cooking lifestyle. Likes, dislikes, allergies, intolerances, cooking style, cuisine preferences, meal structure, time constraints. The planner filters and scores recipes against this. Feedback after eating refines it over time.

Hard constraints (allergies, dietary identity) are stored in a separate, hard-locked database table that is only editable by the user directly — never by AI, never by the feedback system, never by the optimiser. **Allergy safety is enforced deterministically, not by prompts.** Every output that touches food is passed through a deterministic hard-filter that checks against the allergy database before being shown to the user. This filter is code, not an AI instruction. The system never trusts the LLM to remember or respect allergies.

For the concrete JSON shape and details, see [preference-model-shape.md](preference-model-shape.md).

---

## Data Model 2: Nutrition Model

Holds calorie/macro/micro targets, dietary identity, and health goals. Designed for maximum depth — the system should support everything a serious nutrition tracker would need.

**Macro targets:**
- Total daily calorie target and macro gram targets (not just ratios — 180g protein is a hard floor, not "30% of calories")
- Per-meal macro distribution (e.g., 40g protein at breakfast, 50g at lunch, 50g at dinner, 40g across snacks)
- Daily floors vs weekly averages — some targets (like protein) need to be consistent daily, not just converge over the week
- TDEE variations for training days vs rest days (manual activity input initially, wearable-driven later)

**Micro targets:**
- Full micronutrient tracking: iron, zinc, B12, vitamin D, omega-3, magnesium, etc.
- User-settable targets for any tracked micro
- Micronutrient data sourced from USDA FoodData Central and Open Food Facts (also usable for in-app standalone food search — logging "a banana" as a snack pulls from these databases)

**Dietary patterns:**
- Intermittent fasting support (eating windows, fasting periods)
- Dietary identity (vegan, keto, paleo, etc.) as a constraint layer on top of numerical targets

Refined over time by health tracking data — mood, symptoms, weight, labs, wearable data, genomics — which lives within this model, not as a separate module. Health tracking is how the nutrition model learns from outcomes. The planner balances nutritional targets across the planning period — individual meals may miss targets but the total should converge, with daily floors respected for key nutrients.

The nutrition logger works like MyFitnessPal: planned meals are pre-filled from the meal plan and can be confirmed with a tap, or overridden via AI-assisted free-text entry ("actually I had X instead") or manual editing. Standalone food items (snacks, drinks, etc.) can be searched and logged directly from the USDA/Open Food Facts databases. This tracks planned vs actual intake.

---

## Data Model 3: Provisions

Holds pantry inventory, freezer, cupboard, equipment, kitchen environment, budget, and supplier availability/pricing. Budget constraint requires checking grocery prices, so the grocery provider is already involved at the input stage. The planner works within what's available, maximises ingredient utilisation across pack sizes, and minimises waste and cost.

The grocery order is the output — the shopping list is just the internal calculation that feeds it. Purchased items update the pantry. The grocery integration sits behind an abstraction (GroceryProvider interface) so different suppliers can be slotted in. Tesco is the first concrete implementation, needed from day one for real cost optimisation.

---

## Recipe Engine

Independent catalogue for all recipe operations. It stores, discovers, generates, and versions recipes, but contains no optimisation logic. The Meal Planner queries it to find and rank recipes against the three data models.

### Two catalogues

- **User catalogue** — recipes deliberately entered, imported, or saved by the user. These are "owned" recipes. The Recipe Optimiser proposes changes to these but never applies them without user approval. This is the curated library.
- **System catalogue** — AI-managed pool of recipes the system has discovered, generated, or adapted on its own. The Recipe Optimiser can modify these freely without approval, giving the planner a much larger and more flexible set of options to draw from. The user can promote any system recipe to their user catalogue at any time.

Both catalogues share the same data structures, versioning, and branching mechanisms. The only difference is the approval model.

### Three sources, one pipeline
- **Manual entry / import** — user adds a recipe or imports from URL. Goes into the user catalogue.
- **Online discovery** — search the web, hard-filter against constraints, score against preferences. Goes into the system catalogue; user can promote to their catalogue.
- **AI generation** — create new recipes based on specific gaps (e.g., "need a high-protein weeknight meal under 30 mins"). Goes into the system catalogue unless the user explicitly saves it.

### Recipe properties

Every recipe carries metadata that the planner uses for scheduling and optimisation:
- **Servings** — how many portions the recipe makes. Enables batch cooking: a recipe that makes 5 servings can fill Monday–Friday lunches from a single cook.
- **Stores well** — whether the recipe keeps well in the fridge/freezer, and for how long. The planner uses this to schedule batch-cooked meals across multiple days safely.
- **Packable** — whether the meal works cold or reheated without a full kitchen (e.g., suitable for a lunchbox or packed lunch). Distinct from a sit-down cooked meal.
- **Prep time / cook time / total time** — used for scheduling against the user's time constraints per meal slot.
- **Equipment required** — checked against Provisions.

These properties let the user express preferences like "Tuesdays and Wednesdays we want to eat the same thing" or "I want to meal prep all lunches on Sunday." The planner selects recipes with the right storage/packability/servings properties and schedules a single cook session with consumption spread across the week.

### Versioning and branching

Every recipe change creates a new version. Versions are comparable — you can look at any two versions side by side and see what changed and why (ingredients, method steps, portions). Per-version feedback lets you track whether changes actually improved the recipe.

**Branching** handles the case where a recipe has meaningful variants that should coexist rather than replace each other. If you swap the protein in a stir fry from chicken to beef, that's not just a version — it changes several steps and produces a meaningfully different dish. That becomes a branch: same base recipe, different variant. The planner can pick between branches based on which better fits the current plan (e.g., beef branch is over budget this week, use the chicken branch). Branches share a common ancestor and can each have their own version history.

A version is a linear change (tweak salt, adjust portion size). A branch is a fork (different protein, different cooking method that changes the character of the dish).

---

## Recipe Optimiser

A single mechanism for adapting recipes against the three data models (Preference Model, Nutrition Model, Provisions). It doesn't live inside the Recipe Engine (which is just storage) or the Meal Planner (which is plan composition). It's a distinct component that can be triggered from multiple places.

### Trigger 1: Import / discovery
A recipe enters the system (imported from URL, discovered online, manually added). The optimiser runs it against all three data models and proposes adaptations. "This recipe uses cream, you're lactose intolerant — swap for coconut cream?" "This calls for fillet steak, that's over budget — suggest rump instead?" For user catalogue recipes, the user reviews and accepts or rejects. For system catalogue recipes, changes apply automatically.

### Trigger 2: Post-feedback
The user has eaten the meal and given feedback ("too salty", "needed more protein"). The feedback system updates the relevant data model(s), and the optimiser proposes a new version (or branch) of that specific recipe addressing the feedback.

### Trigger 3: Data model change
The user updates preferences, changes nutrition targets, or budget shifts. Recipes that were previously well-fitted may now have gaps. The user can hit a "re-optimise" button on any recipe (or batch across the catalogue) to get new suggestions against the updated constraints. For user catalogue recipes, suggestions are proposed. For system catalogue recipes, changes apply automatically.

### Trigger 4: Plan-time
During weekly plan composition, the Meal Planner may invoke the optimiser as a pre-step to expand the pool of well-fitting recipes. The optimiser handles the actual adaptation. Changes to user recipes are surfaced in the plan ("I swapped turkey for chicken in Wednesday's stir fry to stay in budget"). System catalogue recipes are adapted freely.

### Core principle: propose, not apply (for user recipes)
The user catalogue is the user's curated library. The optimiser never silently mutates it. Every change is a proposed new version or branch that the user can accept, reject, or modify. The system catalogue has no such restriction — the AI can iterate freely, giving the planner more options to work with.

---

## Meal Planner

The orchestrator. Operates in two distinct phases. Before either phase runs, the planner may invoke the Recipe Optimiser (Trigger 4: plan-time) as a pre-step to expand the pool of well-fitting recipes — but this is the optimiser doing its job, not a planner phase.

### Phase 1: Plan-level composition
The combinatorial problem — selecting and arranging recipes from both catalogues across the planning period to satisfy all three data models in aggregate. Which combination of meals across 7 days best satisfies preferences, hits nutrition targets in total, and fits within budget/pantry? This is search and ranking over a known set. **The exact algorithmic approach is TBD** — options include deterministic scoring with search algorithms, traditional ML, LLMs, or a hybrid. Multi-constraint optimisation may be better served by a scoring function + search than by pure LLM generation. To be determined during implementation.

### Phase 2: Plan-level creative augmentation
After composition, the planner identifies remaining gaps and makes intelligent additions or swaps that no existing recipe covers. Adding a yoghurt as a snack to hit a protein target. Swapping one cut of meat for another to drop cost while maintaining preferences. These are plan-level interventions, not recipe modifications — they don't go through recipe versioning. This is the hardest phase because the AI reasons creatively over the full space of possible food items rather than searching a known catalogue. Uses frontier AI, with hard constraint guardrails (allergies, dietary identity).

### Mid-week re-optimisation

The planner doesn't only run once at the start of the week. It can re-run on the remaining days of an active plan when the data models it depends on change:
- **Provisions change** — ingredient spoiled, Tesco delivered substitutions, something ran out. The feedback system or manual edit updates Provisions, and the planner detects the change and offers to regenerate the remaining days.
- **Nutrition model change** — actual intake has diverged from planned (logged via nutrition logger). The updated actuals shift the remaining targets, triggering re-optimisation of remaining meals to compensate.
- **Preference or schedule change** — user updates time constraints, marks a day as eating out, etc. The data model change propagates to the planner.

Re-optimisation runs the same two phases but scoped to the remaining days only, respecting ingredients already purchased and meals already eaten. The Recipe Optimiser may also run as a pre-step if needed. It's the same pipeline, just with a smaller window and more locked-in constraints.

### The Hard Problem
The planner's real challenge is satisfying all three data models simultaneously across both phases. A recipe might be perfect for preferences but blow the budget. Another might nail nutrition targets but require equipment you don't own. The AI must find the best overall solution, not optimise each model independently.

---

## Feedback System

The primary way users interact with and improve the system. Single conversational interface with context-aware routing.

### Entry points
Feedback can be given from anywhere in the UI. The screen context provides implicit routing — feedback entered on the Tesco order screen is assumed to be a provisions concern, feedback on a recipe page is assumed to be a taste/preference concern, feedback on the nutrition dashboard is assumed to be a nutrition concern. General feedback (e.g., from a home screen) requires the classifier to work harder, but the AI can ask clarifying questions rather than guess.

### Four destinations
The AI classifier routes each piece of feedback to the appropriate destination(s):
- **Preference Model** — taste, likes/dislikes, cooking style, cuisine preferences
- **Nutrition Model** — portions, macro fit, health signals (mood, symptoms, weight)
- **Provisions** — cost, availability, equipment, shelf life. This includes disruptions like "the chicken's gone off" (removes chicken from Provisions) or "Tesco substituted parsley for coriander" (updates what's actually available). These Provisions changes then trigger the planner to re-optimise remaining days as a downstream effect.
- **Recipe Engine** — the recipe itself needs changing (triggers versioning/evolution). Distinct from preference feedback: "I don't like coriander" is a preference; "this recipe needs more garlic" is a recipe change.

A single piece of feedback can route to multiple destinations. "That meal was too expensive and I didn't like the texture" splits to both provisions and preference. The classifier handles this.

### Misclassification
Misrouted feedback silently degrades the wrong model, so routed feedback should be surfaceable and correctable by the user rather than fire-and-forget.

### Processing
- Conversational input (natural language, not forms)
- AI interprets and scores against rubric: taste, ease, nutrition fit, portion, cost, repeat desire
- Health tracking (mood, energy, symptoms, weight, labs, wearables, genomics) feeds through here into the Nutrition Model — it's part of the feedback loop, not a separate system
- Maintains the Preference Model (AI-generated structured summary, ~2000 tokens, regenerated every 5 feedbacks)
- Generates weekly/monthly AI reviews correlating food with health outcomes

### Manual direct edits
Every data model (Preference Model, Nutrition Model, Provisions) and the Recipe Engine are directly editable by the user with no AI in the loop. This is the escape hatch when the AI gets something wrong or the user just knows what they want. Manual changes take effect immediately.

---

## Household Model

Household members share Provisions and add constraints to shared meal slots.

- Each user has their own account, Preference Model, and Nutrition Model
- Provisions (pantry, equipment, environment) are shared per household
- Household settings define which meals are shared vs individual
- For shared meals, the planner respects the union of all eaters' hard constraints (allergies, dietary identity)
- Portions scale per meal based on headcount
- Primary user manages provisions and the shared plan; household members can give feedback on their own meals

---

## User Accounts

Thin auth layer — not a domain module, just infrastructure.
- Username + hashed password (simple, no OAuth initially)
- Links to the user's Preference Model, Nutrition Model, and household membership
- Multi-user from v1 (family members)

---

## AI Service

Cross-cutting layer for all LLM interactions. Every module that needs AI goes through this.

- Routes to appropriate model tier (frontier / mid / cheap)
- Manages prompt templates (file-based, versioned)
- Assembles context per request
- Parses structured responses with retry on malformed output
- Cost tracking per call
- Handles API failures gracefully (retry, fallback, degrade)

### AI Model Tiers

| Task | Model Tier | Frequency |
|------|-----------|-----------|
| Meal Planner Phase 1: plan composition | TBD (may be deterministic/hybrid) | 1x/week |
| Meal Planner Phase 2: creative augmentation | Frontier (Sonnet/Opus) | 1x/week |
| Mid-week re-optimisation (remaining days) | TBD (same approach as Phase 1) | Ad-hoc |
| Recipe Optimiser: adapt recipes against data models | Mid (Haiku/Sonnet) | Per trigger |
| Generate new recipe | Mid (Haiku/Sonnet) | As needed |
| Incorporate feedback → Preference Model | Mid | After meals |
| Recipe discovery (search + filter) | Mid | Weekly |
| Import recipe from URL | Mid | Per import |
| Nutrition: map ingredients to USDA entries | Cheap (Haiku) | Per recipe |
| Parse user free-text input | Cheap (Haiku) | Per interaction |
| Nutrition/health review generation | Mid | Weekly/monthly |
| Grocery product matching + navigation (Tesco initially) | Mid/Frontier | 1x/week |
| Shopping list calculation | Deterministic code | 1x/week |
| Nutrition aggregation | Deterministic code | Daily |

---

## Notification System

Alerts and reminders delivered in-app. Listens to events across all modules.
- Expiry warnings from Provisions
- Defrost reminders from Provisions (freezer)
- Prep reminders from Meal Planner ("start marinating at 6pm")
- Nutrition alerts from Nutrition Model ("way under protein today")
- Weekly nutrition/health review available

---

## Tech Stack

- **Backend**: Spring Boot (Java 17) — modular monolith
- **Database**: PostgreSQL (Docker)
- **Frontend**: React + TypeScript + Vite (responsive web app, PWA later)
- **State/Styling**: TanStack Query + Zustand + Tailwind CSS
- **AI**: Anthropic API (Claude models, tiered)
- **Nutrition data**: USDA FoodData Central + Open Food Facts
- **Grocery automation**: GroceryProvider abstraction; Tesco via Claude computer use / Chrome connector
- **Hosting**: Local / self-hosted

---

## Architecture: Modular Monolith

Single deployable application with clean internal module boundaries. Most modules are independently buildable: auth, preference, nutrition, provisions, recipe engine, and grocery can each be developed in isolation. The Meal Planner, Recipe Optimiser, and Feedback System are where integration complexity lives — these depend on the data models and Recipe Engine and should come last. The Household Model is also cross-cutting (shared provisions, constraint unions across users).

The conceptual architecture maps to implementation modules like this:

```
src/main/java/com/example/mealprep/
├── auth/             ← User accounts (thin auth layer)
├── preference/       ← Preference Model (data model — constraints + taste profile)
├── nutrition/        ← Nutrition Model + Logger + Health Tracking (data model — targets + tracking)
├── provisions/       ← Pantry + Equipment + Environment + Budget (data model — physical constraints)
├── recipe/           ← Recipe Engine (independent catalogue — user + system catalogues, versioning, branching)
├── optimiser/        ← Recipe Optimiser (adapts recipes against data models — four trigger points)
├── planner/          ← Meal Planner (orchestrator — two-phase optimisation: composition + creative augmentation)
├── grocery/          ← GroceryProvider abstraction + Tesco implementation (Provisions output)
├── feedback/         ← Feedback System (context-aware routing to four data destinations)
├── household/        ← Household Model (shared provisions, multi-user constraint unions)
├── ai/               ← AI Service (cross-cutting LLM layer)
├── notification/     ← Notifications (cross-cutting alerts)
└── MealPrepApplication.java
```

Modules communicate through service interfaces. Each owns its own DB tables. Extractable to microservices later if ever needed.

---

## Phased Delivery

See [phased-delivery.md](phased-delivery.md) for the full phase breakdown.
