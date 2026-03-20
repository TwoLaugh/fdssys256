# Build Order

## Principles
- Each step should produce something usable/testable, not just plumbing
- Build dependencies before the things that need them
- Frontend and backend can be developed in parallel tracks once the API shape is clear
- Get to "generate a meal plan" as fast as possible — that's the moment it feels real

## The Order

### Step 1: Foundation + Project Restructure
**What**: Restructure the existing Spring Boot project from the journal app into the modular monolith structure. Set up the module packages, shared config, and React frontend scaffolding.

- Rename/restructure packages to `com.example.mealprep.*`
- Set up module packages (profile, recipe, pantry, planner, shopping, ai, etc.)
- Add Anthropic Java SDK dependency (or HTTP client for Claude API)
- Scaffold React frontend (create-react-app or Vite) alongside Spring Boot
- Set up API communication pattern (REST controllers + React fetch)
- Clean out the journal app code

**Testable outcome**: Empty app with the right structure, frontend talks to backend, builds and runs.

### Step 2: User Profile
**What**: The profile module — constraints, goals, preferences, cooking prefs.

- Data model: UserProfile entity + DB migration
- REST API: GET/PUT profile
- Frontend: Settings page with sections (constraints, goals, cooking, budget)
- Seed with your actual data so everything downstream has real context to work with

**Testable outcome**: You can fill in your dietary constraints, calorie goals, allergies, etc. and persist them.

### Step 3: AI Service (core)
**What**: The centralised AI layer — model routing, prompt templates, response parsing, cost tracking.

- Anthropic API client wrapper
- Model tier routing (frontier/mid/cheap)
- Prompt template loading from resource files
- JSON response parsing with retry logic
- Cost logging per call
- Write 2-3 initial prompt templates (will flesh out as other modules need them)

**Testable outcome**: Can send a prompt to Claude and get a parsed JSON response back. Cost is logged.

### Step 4: Recipe Store
**What**: Recipe CRUD, versioning, nutrition calculation, import from URL.

- Data model: Recipe, RecipeVersion, RecipeIngredient entities
- REST API: CRUD recipes, list/search/filter, get version history
- Nutrition engine: AI parsing → USDA API → ingredient mapping cache → calculate per-serving macros
- Recipe import: paste URL → AI extracts structured recipe → store
- Frontend: Recipe library page (card grid, filters), recipe detail page, add recipe (manual + URL import)

**Testable outcome**: You can add recipes manually, import from URLs, see calculated nutrition. This is already useful on its own.

### Step 5: Pantry Manager
**What**: Ingredient inventory across fridge/freezer/cupboard.

- Data model: PantryItem entity (name, qty, unit, category, storage location, expiry)
- REST API: CRUD pantry items, filter by location/category/expiry
- Frontend: Pantry page grouped by category, expiry highlighting, quick add
- No auto-deduction yet (that comes with the planner)

**Testable outcome**: You can track what's in your kitchen. Expiring items are visible.

### Step 6: Meal Planner (the big one)
**What**: AI-driven weekly plan generation using the two-pass approach.

- Prompt templates: recipe selection (pass 1) + plan assembly (pass 2)
- Context assembly: profile + pantry + recipe index → pass 1 → full recipes → pass 2
- Data model: MealPlan, MealSlot entities
- REST API: generate plan, get current plan, override a slot
- Pantry auto-deduction: when a meal is marked as cooked, subtract ingredients
- Frontend: Weekly plan view (calendar grid), daily dashboard, mark as cooked/skip

**Testable outcome**: Generate a real meal plan for your week. Mark meals as cooked and see pantry update. This is the core value moment.

### Step 7: Shopping List
**What**: Deterministic generation from plan minus pantry.

- Logic: aggregate plan ingredients → subtract pantry stock → group by category
- Data model: ShoppingList, ShoppingItem entities
- REST API: generate list, check off items, get cost estimate
- Frontend: Shopping list page with checkboxes, grouped by section

**Testable outcome**: Generate a shopping list from your meal plan. Check items off as you shop.

### Step 8: Feedback System + Preference Model
**What**: Collect meal feedback, maintain the AI-generated preference model.

- Data model: FeedbackEntry entity, PreferenceModel (stored as JSON)
- Feedback collection: conversational input after meals, AI interprets and scores against rubric
- Preference model: regeneration pipeline (batch feedback → AI update → store new version)
- Recipe evolution: feedback triggers recipe versioning, AI suggests changes
- Frontend: feedback prompt after marking meal as cooked, "suggest changes" chat sidebar on recipe page, preference model viewer in settings

**Testable outcome**: Give feedback on meals, see your preference model build up, see recipes evolve based on your input.

### Step 9: Nutrition Tracker
**What**: Planned vs actual nutrition tracking with daily/weekly dashboards.

- Pre-populated from meal plan each day
- User confirms/skips/adjusts
- Data model: NutritionLog entity
- Frontend: daily macro progress bars, weekly trend charts

**Testable outcome**: See your daily nutrition, confirm what you ate, view trends.

### Step 10: Recipe Discovery
**What**: AI finds recipes online, filters against profile, presents suggestions.

- Discovery pipeline: AI searches → filters against constraints → scores against preferences → presents top picks
- Frontend: discovery section in recipe library ("Suggested for you"), accept/skip
- Accepted recipes imported into Recipe Store

**Testable outcome**: AI suggests new recipes you might like. Accept ones that appeal.

### Step 11: Plan Adjustments
**What**: Mid-week disruption handling.

- Event + intent UX: skip, swap, move, rebalance
- AI rebalancing when requested (adjusts remaining days)
- Pantry/nutrition recalculation after adjustments
- Frontend: quick-action buttons on meal slots, chat fallback for complex adjustments

**Testable outcome**: Skip a meal, swap for something else, have the AI rebalance your week.

### Step 12: Grocery Ordering (Tesco)
**What**: Claude browser automation to add shopping list to Tesco basket.

- Integration with Claude computer use / Chrome connector
- Shopping list item → Tesco search → AI picks best product → add to basket
- User reviews basket before any checkout
- Purchased items auto-added to pantry
- Substitution handling → flags to plan adjustment system

**Testable outcome**: Your shopping list turns into a Tesco basket with one click.

### Step 13: Health Tracker + Reviews
**What**: Mood/energy/symptom logging, weight tracking, AI-generated reviews.

- Data model: HealthLog entity (mood, energy, symptoms, weight, notes)
- Weekly/monthly AI review generation
- Frontend: daily health check-in, weight chart, review pages
- Later tiers: wearable sync, blood panels, genomics

**Testable outcome**: Log how you feel, see patterns correlated with meals, get AI-generated weekly summaries.

### Step 14: Notifications + Cooking Mode + Polish
**What**: Everything that makes it feel finished.

- Notifications: expiry alerts, defrost reminders, prep prompts
- Cooking mode: step-by-step recipe view with large text and timers
- Natural language search across the system
- PWA setup (installable, offline recipe viewing)
- Data export

**Testable outcome**: A polished, daily-driver app.

---

## What This Looks Like As a Timeline

```
Step 1-2:  Foundation         ██░░░░░░░░░░░░░░░░░░  You can set up your profile
Step 3:    AI Service         ████░░░░░░░░░░░░░░░░  AI works
Step 4:    Recipes            ██████░░░░░░░░░░░░░░  You can manage recipes
Step 5:    Pantry             ████████░░░░░░░░░░░░  You can track ingredients
Step 6:    MEAL PLANNER       ██████████░░░░░░░░░░  ← Core value. It plans your meals.
Step 7:    Shopping List      ████████████░░░░░░░░  Plan → shopping list
Step 8:    Feedback           ██████████████░░░░░░  System learns from you
Step 9:    Nutrition          ████████████████░░░░  Track what you eat
Step 10:   Discovery          ██████████████████░░  AI finds new recipes
Step 11-14: Everything else   ████████████████████  Polish, Tesco, health
```

Steps 1-7 get you a working meal planning system.
Steps 8-9 make it smart.
Steps 10+ make it great.
