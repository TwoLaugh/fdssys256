# MealPrep AI — End-to-End System Overview

*Last updated after design review. This is the single source of truth for the system architecture.*

## What This Is

An AI-powered meal planning and health optimisation system for personal/family use. The AI handles planning, recipe management, and learning from feedback. The user views, gives feedback, and makes adjustments.

## High-Level Flow

```
┌──────────────────────────────────────────────────────────┐
│                     USER PROFILE                         │
│  constraints, goals, household, cooking prefs, budget    │
└──────────────┬───────────────────────────────────────────┘
               │
               ▼
┌──────────────────────┐    ┌─────────────┐    ┌──────────────────┐
│    RECIPE LIBRARY    │◄───│   RECIPE    │    │     PANTRY       │
│  (store, versions,   │    │  DISCOVERY  │    │ (fridge, freezer,│
│   import, create)    │    │  (online    │    │  cupboard, waste │
└──────────┬───────────┘    │   search)   │    │  tracking)       │
           │                └─────────────┘    └────────┬─────────┘
           │                                            │
           ▼                                            ▼
┌──────────────────────────────────────────────────────────┐
│                    MEAL PLANNER                          │
│  arranges recipes across the week, optimises ingredient  │
│  utilisation, handles mid-week adjustments               │
└──────────────┬───────────────────────────────────────────┘
               │
    ┌──────────┼──────────┬────────────────┐
    ▼          ▼          ▼                ▼
┌────────┐ ┌────────┐ ┌──────────┐ ┌────────────┐
│SHOPPING│ │NUTRITN │ │ FEEDBACK │ │  COOKING   │
│  LIST  │ │TRACKER │ │  SYSTEM  │ │   MODE     │
└───┬────┘ └───┬────┘ └────┬─────┘ │(recipe UI) │
    │          │           │       └────────────┘
    ▼          ▼           │
┌────────┐ ┌────────────┐  │
│ TESCO  │ │  HEALTH    │  │
│ ORDER  │ │  TRACKER   │  │
└───┬────┘ │(mood,wt,   │  │
    │      │ symptoms,  │  │
    ▼      │ wearables, │  ▼
┌────────┐ │ labs,      │ ┌──────────────┐
│ PANTRY │ │ genomics)  │ │  PREFERENCE  │
│(update)│ └─────┬──────┘ │    MODEL     │
└────────┘       │        │(AI-maintained│
                 │        │  summary)    │
                 ▼        └──────┬───────┘
          ┌────────────┐         │
          │  WEEKLY /  │         │
          │  MONTHLY   │◄────────┘
          │  REVIEW    │
          └────────────┘
                 │
                 ▼
          updates User Profile, Meal Planner context
```

---

## Core Modules (12)

### 1. User Profile
Static-ish identity + constraints + goals + household members.
- Hard constraints: allergies, dietary identity, medical diets
- Soft constraints: intolerances, dislikes
- Goals: calories, macros, body composition, health targets
- Cooking: skill level, time limits, equipment, batch cooking preference
- Budget, meal structure, variety settings, shopping preferences
- Household members with per-person constraint overrides
- Phased onboarding: essential first, rest over first week

### 2. Recipe Store
CRUD + versioning for all recipes regardless of source.
- Three sources: AI-generated, user-submitted, imported from URL (all first-class)
- Versioning with changelogs and per-version feedback
- AI-calculated nutrition (via USDA FoodData Central + Open Food Facts)
- Recipe-specific notes (distinct from global preferences)
- Tags, difficulty, prep/cook time, servings, equipment needed

### 3. Recipe Discovery
Finds new recipes online, filters against profile, presents candidates.
- Search → hard-filter (constraints) → score (preferences) → present
- User controls new-vs-familiar ratio per week
- Learns from accept/reject patterns over time
- Imports accepted recipes into Recipe Store

### 4. Pantry Manager
Inventory tracking across fridge, freezer, and cupboard.
- Items: name, quantity, unit, category, storage location, expiry date
- In: auto-add from shopping list, manual additions
- Out: auto-deduct when meal is cooked, manual removal
- Freezer management: frozen portions, defrost reminders
- Food waste tracking: what was thrown away, why, cost
- Expiry alerts for items approaching use-by date

### 5. Meal Planner (+ Plan Adjustments)
The central orchestrator. Arranges recipes across the week.
- Inputs: profile, recipe library, pantry, preference model, overrides, budget
- Optimises ingredient utilisation across pack sizes
- Handles mid-week disruptions (skipped meals, substitutions, schedule changes)
- Event + intent UX: detect disruption → ask what user wants → AI proposes adjustment
- Outputs: 7-day plan, ingredient utilisation map, daily nutrition totals, cost estimate

### 6. Shopping List Generator
Deterministic: plan ingredients minus pantry stock.
- Grouped by store section
- Accounts for pack sizes where known
- Budget estimate
- No AI needed — pure arithmetic

### 7. Grocery Ordering (Tesco)
Browser automation via Claude computer use / Chrome connector.
- AI navigates Tesco site, searches items, picks best matches, adds to basket
- User always reviews basket before checkout
- Handles substitutions by flagging to Plan Adjustment system
- Purchased items auto-added to Pantry

### 8. Nutrition Tracker
Tracks planned vs actual intake.
- Pre-populated from meal plan each day
- User confirms (default = planned), skips, or manually adjusts
- Daily/weekly macro dashboard (calories, protein, carbs, fat)
- Feeds into Health Tracker for trend analysis

### 9. Health Tracker
Closes the loop between food and health outcomes.
- Tier 1 (v1): mood/energy logs, symptom tracker, weight, progress photos
- Tier 2: wearable sync (Apple Health, Garmin, etc.)
- Tier 3: blood panel uploads with AI analysis
- Tier 4: genomics (SNP extraction for food-related variants)
- Generates weekly/monthly AI reviews
- Feeds insights back into User Profile and Meal Planner

### 10. Feedback System
Collects and structures user feedback on meals.
- Conversational input (natural language, not forms)
- AI scores against rubric: taste, ease, nutrition fit, portion, repeat desire
- Maintains the Preference Model (AI-generated structured summary)
- Drives recipe evolution (versioning, changelogs)

### 11. AI Service
Centralised layer for all LLM interactions.
- Routes to appropriate model tier (frontier / mid / cheap)
- Manages prompt templates
- Assembles context per request (profile + relevant data)
- Parses structured responses
- Cost tracking per call
- Handles API failures gracefully

### 12. Notification System
Alerts and reminders delivered in-app.
- Expiry warnings from Pantry
- Defrost reminders from Pantry (freezer)
- Prep reminders from Meal Planner ("start marinating at 6pm")
- Nutrition alerts from Tracker ("way under protein today")
- Weekly review available

*Frontend is the 13th component but it's the UI layer, not a domain module.*

---

## Preference Model (concrete design)

An AI-maintained structured document, regenerated periodically from accumulated feedback. Bounded in size, human-readable, sent as context to the planner.

```json
{
  "strong_likes": ["tangy flavours", "crispy textures", "one-pot meals"],
  "strong_dislikes": ["overly sweet savoury dishes", "complex multi-step recipes"],
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
    "weeknight": "under 30 mins, minimal washing up",
    "weekend": "willing to spend 1-2 hours, enjoys the process"
  },
  "learned_insights": [
    "Prefers brown rice over white",
    "Likes spicy but not extreme heat",
    "Responds well to 5-8 ingredient recipes"
  ]
}
```

---

## Household Model

Even in single-user mode, cooking for family means:
- Primary user (the planner) + household members list
- Each member: name, constraint overrides (allergies, dislikes)
- Meal slots specify who's eating (e.g., "dinner for 2" vs "lunch for 1")
- Shared meals respect the union of all eaters' hard constraints
- Portions scale per meal based on headcount

---

## AI Model Tiers

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
| Health review generation | Mid | Weekly/monthly |
| Tesco product matching + navigation | Mid/Frontier | 1x/week |
| Shopping list calculation | Deterministic code | 1x/week |
| Nutrition aggregation | Deterministic code | Daily |

---

## Tech Stack

- **Backend**: Spring Boot (Java 17) — modular monolith
- **Database**: PostgreSQL (Docker)
- **Frontend**: React (responsive web app, PWA later)
- **AI**: Anthropic API (Claude models, tiered)
- **Nutrition data**: USDA FoodData Central + Open Food Facts
- **Grocery automation**: Claude computer use / Chrome connector
- **Hosting**: Local / self-hosted

---

## Architecture: Modular Monolith

Single deployable application with clean internal module boundaries.

```
src/main/java/com/example/mealprep/
├── profile/        ← User Profile + Household
├── recipe/         ← Recipe Store + Versioning
├── discovery/      ← Recipe Discovery
├── pantry/         ← Pantry + Freezer + Waste Tracking
├── planner/        ← Meal Planner + Adjustments
├── shopping/       ← Shopping List Generator
├── grocery/        ← Tesco Ordering
├── nutrition/      ← Nutrition Tracker
├── health/         ← Health Tracker (mood, weight, labs, genomics)
├── feedback/       ← Feedback System + Preference Model
├── ai/             ← AI Service (centralised LLM layer)
├── notification/   ← Notifications
└── MealPrepApplication.java
```

Modules communicate through service interfaces. Each owns its own DB tables. Extractable to microservices later if ever needed.

---

## Phased Delivery

### Phase 1: Core Loop
- User profile + constraints + household setup
- Pantry management (fridge, freezer, cupboard — manual)
- Recipe store (CRUD, import from URL, AI-generated)
- AI meal plan generation with ingredient utilisation
- Shopping list generation
- Basic nutrition dashboard
- React frontend with core views

### Phase 2: Intelligence
- Feedback system (conversational, rubric scoring)
- Preference model (AI-maintained, evolving)
- Recipe versioning and evolution
- Recipe discovery (online search + filter)
- Plan adjustments (event + intent UX)
- Cooking mode (step-by-step recipe view)
- Nutrition tracking (planned vs actual)

### Phase 3: Automation & Health
- Tesco grocery ordering (Claude browser control)
- Auto-pantry updates from purchases
- Health tracker tier 1 (mood, symptoms, weight, progress photos)
- Weekly/monthly AI reviews
- Notifications (expiry, prep reminders, defrost)
- Food waste tracking and reporting

### Phase 4: Advanced Health & Polish
- Wearable integration (Apple Health, Garmin, etc.)
- Blood panel upload and AI analysis
- Genomics integration
- PWA (installable, offline recipe viewing)
- Data backup and export
- Natural language search across the system
