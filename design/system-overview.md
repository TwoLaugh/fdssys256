# MealPrep AI — End-to-End System Overview

*Single source of truth for the system architecture.*

## What This Is

An AI-powered meal planning and health optimisation system for personal/family use. The AI handles planning, recipe management, and learning from feedback. The user views, gives feedback, and makes adjustments.

## Architecture: Three Constraint Loops

The Meal Planner is the central orchestrator. It simultaneously satisfies three parallel constraint-optimisation loops, each with its own input state, internal optimisation, and output that feeds back into itself.

```
                 ┌───────────────────────────────────────┐
                 │            USER FEEDBACK               │
                 │                                        │
                 │    natural language, ratings,           │
                 │    manual overrides on any part         │
                 └─────┬─────────────┬─────────────┬──────┘
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
                   │                 │                  │
                   ▼                 ▼                  ▼
          ┌────────────────────────────────────────────────────┐
          │                   MEAL PLANNER                      │
          │                                                     │
          │  optimises across all three constraint systems      │
          │  to produce a weekly plan                           │
          │                                                     │
          │         ▲                              │            │
          │         │    ┌───────────────────┐     │            │
          │         └────│  RECIPE ENGINE    │◄────┘            │
          │              │                   │                  │
          │              │  store, discover, │                  │
          │              │  generate, evolve │                  │
          │              └───────────────────┘                  │
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
                │                │                   │
                └────────┬───────┴───────────────────┘
                         ▼
                 ┌───────────────┐
                 │ USER FEEDBACK │
                 │  (loops back) │
                 └───────────────┘
```

### Loop 1: Preference Loop
**Input:** Preference Model (likes, dislikes, allergies, intolerances, cooking style, cuisine preferences, meal structure, time constraints)
**Optimisation:** Find/create recipes that match taste, ease, and lifestyle. The Recipe Engine searches existing recipes, discovers online, and generates new ones — all filtered and scored against preferences.
**Output:** Selected recipes in the weekly plan. User feedback after eating refines the preference model.

### Loop 2: Nutrition Loop
**Input:** Nutrition Model (calorie/macro/micro targets, dietary identity, health goals). The model is refined over time by health tracking data — mood, symptoms, weight, labs, wearable data, genomics — which lives within this loop, not as a separate module. Health tracking is how the nutrition model learns from outcomes.
**Optimisation:** Balance nutritional targets across the week. Individual meals may miss targets but the weekly total should converge.
**Output:** Nutrition logger tracks planned vs actual. User feedback on portions and nutrition fit refines the model. Health data (mood, weight trends, lab results) triggers deeper target adjustments via AI-generated weekly/monthly reviews.

### Loop 3: Provisions Loop
**Input:** Provisions (pantry inventory, freezer, cupboard, equipment, kitchen environment, budget, supplier availability/pricing). Budget constraint requires checking Tesco prices, so Tesco is already involved at the input stage.
**Optimisation:** Work within what's available, maximise ingredient utilisation across pack sizes, minimise waste and cost.
**Output:** Tesco order (price-aware shopping + ordering). This replaces a separate "shopping list" — the shopping list is just the internal calculation that feeds the order. Purchased items update pantry. User feedback like "too expensive", "couldn't find", "needs equipment I don't have" refines provisions.

### The Hard Problem
The planner's real challenge is satisfying all three loops simultaneously. A recipe might be perfect for preferences but blow the budget. Another might nail nutrition targets but require equipment you don't own. The AI must find the best overall solution, not optimise each loop independently.

---

## Recipe Engine

Unified system for all recipe operations. Combines what was previously separate (recipe store, discovery, AI generation) because they share the same mechanisms: constraint awareness, versioning, and preference/nutrition context.

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
| Tesco product matching + navigation | Mid/Frontier | 1x/week |
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

The primary way users interact with and improve the system.
- Conversational input (natural language, not forms)
- AI interprets and scores against rubric: taste, ease, nutrition fit, portion, cost, repeat desire
- Routes feedback to the appropriate loop:
  - Taste/ease/cuisine → Preference Model
  - Portion size/nutrition fit/health signals (mood, symptoms, weight) → Nutrition Model
  - Cost/availability/equipment/shelf life → Provisions
- Health tracking (mood, energy, symptoms, weight, labs, wearables, genomics) feeds through here into the Nutrition Model — it's part of the feedback loop, not a separate system
- Drives recipe evolution (versioning, changelogs)
- Maintains the Preference Model (AI-generated structured summary, ~2000 tokens, regenerated every 5 feedbacks)
- Generates weekly/monthly AI reviews correlating food with health outcomes

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
- **Grocery automation**: Claude computer use / Chrome connector
- **Hosting**: Local / self-hosted

---

## Architecture: Modular Monolith

Single deployable application with clean internal module boundaries. The three-loop conceptual architecture maps to implementation modules like this:

```
src/main/java/com/example/mealprep/
├── auth/             ← User accounts (thin auth layer)
├── preference/       ← Preference Model (Loop 1 state)
├── nutrition/        ← Nutrition Model + Tracker + Health Tracking (Loop 2)
├── provisions/       ← Pantry + Equipment + Environment (Loop 3 state)
├── recipe/           ← Recipe Engine (store, discovery, generation, versioning)
├── planner/          ← Meal Planner + Adjustments (the orchestrator)
├── grocery/          ← Tesco: price checking, shopping list, ordering (Loop 3 output)
├── feedback/         ← Feedback System (routes to all three loops)
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
- Tesco integration (price checking + shopping list + ordering)
- Basic nutrition dashboard
- React frontend with core views

### Phase 2: Intelligence
- Feedback system (conversational, rubric scoring, routes to loops)
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
