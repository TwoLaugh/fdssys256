# MealPrep AI — End-to-End System Overview

*Single source of truth for the system architecture.*

## What This Is

An AI-powered meal planning and health optimisation system for personal/family use. The AI handles planning, recipe management, and learning from feedback. The user views, gives feedback, and makes adjustments.

## Architecture: Three Data Models, One Planner

The system has four major components with distinct roles:

- **Three data models (state):** Preference Model, Nutrition Model, and Provisions. These are data objects that hold constraints, targets, and current state. They don't contain optimisation logic — they're what gets optimised against.
- **One recipe database:** The Recipe Engine. An independent catalogue of recipes (stored, discovered, AI-generated) with versioning. It doesn't optimise anything — it's the pool the planner draws from.
- **One orchestrator:** The Meal Planner. This is where the optimisation lives. It queries the Recipe Engine to find combinations of recipes that satisfy all three data models simultaneously. It effectively runs three constraint-checking passes (preference, nutrition, provisions) that must converge on a single plan.

The planning cadence defaults to weekly but is configurable.

```
                 ┌───────────────────────────────────────┐
                 │            USER FEEDBACK              │
                 │                                       │
                 │    natural language, ratings,         │
                 │    manual overrides on any part       │
                 └─────┬─────────────┬─────────────┬─────┘
                       │             │             │
                       ▼             ▼             ▼
          ┌─────────────────┐ ┌─────────────┐ ┌─────────────────┐
          │   PREFERENCE    │ │  NUTRITION  │ │   PROVISIONS    │
          │     MODEL       │ │    MODEL    │ │                 │
          │                 │ │             │ │  pantry,        │
          │  likes/dislikes │ │  cal/macro/ │ │  equipment,     │
          │  allergies      │ │  micro tgts │ │  environment,   │
          │  cooking style  │ │  dietary id │ │  budget,        │
          │  cuisine prefs  │ │             │ │  supplier avail │
          │  meal structure │ │  refined by │ │                 │
          │                 │ │  health:    │ │                 │
          │                 │ │  mood, wt,  │ │                 │
          │                 │ │  symptoms,  │ │                 │
          │                 │ │  labs, wear │ │                 │
          └────────┬────────┘ └──────┬──────┘ └────────┬────────┘
                   │                 │                 │
                   ▼                 ▼                 ▼
          ┌────────────────────────────────────────────────────┐
          │                   MEAL PLANNER                     │
          │                                                    │
          │  optimises across all three constraint systems     │
          │  to produce a weekly plan                          │
          │                                                    │
          │         ▲                              │           │
          │         │    ┌───────────────────┐     │           │
          │         └────│  RECIPE ENGINE    │◄────┘           │
          │              │                   │                 │
          │              │  store, discover, │                 │
          │              │  generate, evolve │                 │
          │              └───────────────────┘                 │
          └─────┬──────────────────┬──────────────────┬────────┘
                │                  │                  │
                ▼                  ▼                  ▼
          ┌───────────┐   ┌──────────────┐   ┌──────────────┐
          │  WEEKLY   │   │  NUTRITION   │   │    TESCO     │
          │   PLAN    │   │   LOGGER     │   │    ORDER     │
          │           │   │              │   │              │
          │  7-day    │   │  planned vs  │   │  price-aware │
          │  schedule │   │  actual      │   │  shopping +  │
          │           │   │  intake      │   │  ordering    │
          └─────┬─────┘   └──────┬───────┘   └──────┬───────┘
                │                │                  │
                └────────────────┬──────────────────┘
                                 ▼
                         ┌───────────────┐
                         │ USER FEEDBACK │
                         │  (loops back) │
                         └───────────────┘
```

### Data Model 1: Preference Model
Holds the user's taste profile, constraints, and cooking lifestyle. Likes, dislikes, allergies, intolerances, cooking style, cuisine preferences, meal structure, time constraints. The planner filters and scores recipes against this. Feedback after eating refines it over time.

### Data Model 2: Nutrition Model
Holds calorie/macro/micro targets, dietary identity, and health goals. Refined over time by health tracking data — mood, symptoms, weight, labs, wearable data, genomics — which lives within this model, not as a separate module. Health tracking is how the nutrition model learns from outcomes. The planner balances nutritional targets across the planning period — individual meals may miss targets but the total should converge.

The nutrition logger works like MyFitnessPal: planned meals are pre-filled from the meal plan and can be confirmed with a tap, or overridden via AI-assisted free-text entry ("actually I had X instead") or manual editing. This tracks planned vs actual intake.

### Data Model 3: Provisions
Holds pantry inventory, freezer, cupboard, equipment, kitchen environment, budget, and supplier availability/pricing. Budget constraint requires checking grocery prices, so the grocery provider is already involved at the input stage. The planner works within what's available, maximises ingredient utilisation across pack sizes, and minimises waste and cost.

The grocery order is the output — the shopping list is just the internal calculation that feeds it. Purchased items update the pantry. The grocery integration sits behind an abstraction (GroceryProvider interface) so different suppliers can be slotted in. Tesco is the first concrete implementation, needed from day one for real cost optimisation.

### The Hard Problem
The planner's real challenge is satisfying all three data models simultaneously. A recipe might be perfect for preferences but blow the budget. Another might nail nutrition targets but require equipment you don't own. The AI must find the best overall solution, not optimise each model independently.

---

## Recipe Engine

Independent database for all recipe operations. The Recipe Engine is a catalogue — it stores, discovers, generates, and versions recipes, but contains no optimisation logic. The Meal Planner queries it to find and rank recipes against the three data models. Combines what was previously separate (recipe store, discovery, AI generation) because they share the same mechanisms: constraint awareness, versioning, and preference/nutrition context.

**Three sources, one pipeline:**
- **Existing library** — recipes already saved, with version history and feedback
- **Online discovery** — search the web, hard-filter against constraints, score against preferences, import accepted ones
- **AI generation** — create new recipes or adapt existing ones based on specific gaps (e.g., "need a high-protein weeknight meal under 30 mins")

**Recipe evolution** (same mechanism for all sources):
- Feedback triggers versioning — AI proposes changes, user approves
- Changelogs track what changed and why
- Per-version feedback so you can compare iterations
- Constraint-aware: won't evolve a recipe into something that violates allergies or nutrition targets

---

## Household Model

Household members share the Provisions module and add constraints to shared meal slots.

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
| Generate weekly meal plan | Frontier (Sonnet/Opus) | 1x/week |
| Rebalance plan after disruption | Frontier | Ad-hoc |
| Generate/evolve recipe | Mid (Haiku/Sonnet) | As needed |
| Incorporate feedback → preference model | Mid | After meals |
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
- Nutrition alerts from Tracker ("way under protein today")
- Weekly nutrition/health review available

---

## Feedback System

The primary way users interact with and improve the system. Single conversational interface with context-aware routing.

### Entry points
Feedback can be given from anywhere in the UI. The screen context provides implicit routing — feedback entered on the Tesco order screen is assumed to be a provisions concern, feedback on a recipe page is assumed to be a taste/preference concern, feedback on the nutrition dashboard is assumed to be a nutrition concern. General feedback (e.g., from a home screen) requires the classifier to work harder, but the AI can ask clarifying questions rather than guess.

### Four destinations
The AI classifier routes each piece of feedback to the appropriate destination(s):
- **Preference Model** — taste, likes/dislikes, cooking style, cuisine preferences
- **Nutrition Model** — portions, macro fit, health signals (mood, symptoms, weight)
- **Provisions** — cost, availability, equipment, shelf life
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
Every data model (Preference, Nutrition, Provisions) and the Recipe Engine are directly editable by the user with no AI in the loop. This is the escape hatch when the AI gets something wrong or the user just knows what they want. Manual changes take effect immediately.

---

## Preference Model (concrete shape)

AI-maintained structured document. Regenerated periodically from accumulated feedback. Bounded in size, human-readable, sent as context to the planner.

```json
{
  "hard_constraints": {
    "allergies": ["peanuts", "tree nuts"],
    "dietary_identity": "omnivore",
    "medical_diets": []
  },
  "soft_constraints": {
    "intolerances": ["lactose — mild"],
    "dislikes": ["coriander", "blue cheese"]
  },
  "taste_preferences": {
    "strong_likes": ["tangy flavours", "crispy textures", "one-pot meals"],
    "strong_dislikes": ["overly sweet savoury dishes"]
  },
  "ingredient_preferences": {
    "positive": ["lemon", "garlic", "chickpeas"],
    "negative": ["coriander", "blue cheese"]
  },
  "cuisine_preferences": {
    "positive": ["Mediterranean", "East Asian"],
    "neutral": ["Indian", "Mexican"],
    "negative": []
  },
  "cooking_patterns": {
    "skill_level": "intermediate",
    "weeknight": "under 30 mins, minimal washing up",
    "weekend": "willing to spend 1-2 hours, enjoys the process",
    "batch_cooking": "open to it on weekends"
  },
  "meal_structure": {
    "meals_per_day": 3,
    "snacks": false,
    "new_vs_familiar_ratio": "2 new per week"
  },
  "learned_insights": [
    "Prefers brown rice over white",
    "Likes spicy but not extreme heat",
    "Responds well to 5-8 ingredient recipes"
  ]
}
```

The user can view and manually correct this at any time. Hard constraints (allergies, dietary identity) are stored both here and in the database as immutable records — the AI-maintained version is a convenience copy, the DB is the source of truth for safety-critical constraints.

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

Single deployable application with clean internal module boundaries. Most modules are independently buildable: auth, preference, nutrition, provisions, recipe engine, and grocery can each be developed in isolation. The Meal Planner and Feedback System are where integration complexity lives — these depend on the other modules and should come last. The household model is also cross-cutting (shared provisions, constraint unions across users).

The conceptual architecture maps to implementation modules like this:

```
src/main/java/com/example/mealprep/
├── auth/             ← User accounts (thin auth layer)
├── preference/       ← Preference Model (data model — constraints + taste profile)
├── nutrition/        ← Nutrition Model + Logger + Health Tracking (data model — targets + tracking)
├── provisions/       ← Pantry + Equipment + Environment + Budget (data model — physical constraints)
├── recipe/           ← Recipe Engine (independent database — store, discovery, generation, versioning)
├── planner/          ← Meal Planner (orchestrator — optimises across all three data models)
├── grocery/          ← GroceryProvider abstraction + Tesco implementation (Provisions output)
├── feedback/         ← Feedback System (context-aware routing to four destinations)
├── ai/               ← AI Service (cross-cutting LLM layer)
├── notification/     ← Notifications (cross-cutting alerts)
└── MealPrepApplication.java
```

Modules communicate through service interfaces. Each owns its own DB tables. Extractable to microservices later if ever needed.

---

## Phased Delivery

### Phase 1: Core Loop
- Auth (simple username/password, multi-user)
- Preference Model (initial setup, hard/soft constraints, cooking prefs)
- Nutrition Model (calorie/macro targets)
- Provisions (pantry — manual tracking, equipment list)
- Recipe Engine (CRUD, import from URL, AI generation)
- Meal Planner (weekly plan generation, three-loop optimisation)
- Grocery integration via GroceryProvider abstraction (Tesco as first implementation: price checking + shopping list + ordering)
- Basic nutrition dashboard
- React frontend with core views

### Phase 2: Intelligence
- Feedback system (conversational, context-aware routing to four destinations: preference, nutrition, provisions, recipe engine)
- Preference Model evolution (AI-maintained, regenerated from feedback)
- Recipe versioning and evolution
- Recipe discovery (online search + filter)
- Plan adjustments (event + intent UX for mid-week disruptions)
- Nutrition tracking (planned vs actual)
- Health tracking tier 1 (mood, symptoms, weight — feeds into nutrition model)

### Phase 3: Health & Polish
- Weekly/monthly AI reviews (health → nutrition model refinement)
- Progress photos
- Notifications (expiry, prep reminders, defrost)
- Food waste tracking and reporting

### Phase 4: Advanced Health & Expansion
- Wearable integration (Apple Health, Garmin, etc.)
- Blood panel upload and AI analysis
- Genomics integration
- Household multi-user (shared provisions, shared meal slots)
- PWA (installable, offline recipe viewing)
- Data backup and export
- Natural language search across the system
