# User Profile

*In the three-loop architecture, "User Profile" is no longer a single monolithic concept. It's split across three domain models plus a thin auth layer. This doc describes what data lives where and how onboarding works.*

## Where Profile Data Lives

### Auth (thin infrastructure layer)
- Username, hashed password
- Account creation date
- Household membership (which household you belong to)
- This is NOT a domain module — just infrastructure

### Preference Model (Loop 1 — see preference-model.md)
Evolves from feedback. AI-maintained structured document.
- Hard constraints: allergies, dietary identity, medical diets
- Soft constraints: intolerances, ingredient dislikes, cuisine dislikes
- Taste preferences: flavour likes/dislikes, texture preferences
- Cooking preferences: skill level, max cooking time (weeknight vs weekend), batch cooking appetite, preferred techniques
- Meal structure: which meals per day, fixed slots, eating out schedule, new-vs-familiar ratio, portion size
- Cuisine preferences: liked/neutral/disliked cuisines
- Variety & adventurousness settings

### Nutrition Model (Loop 2)
Evolves from health tracking and goal adjustments.
- Daily calorie target (or range)
- Macro targets: protein, carbs, fat (grams or ratios or priorities like "high protein")
- Micro targets: iron, vitamin D, fibre, etc. (row-per-nutrient, expandable)
- Goal context: bulking, cutting, maintenance, general health
- Dietary identity (also in preference model as a hard constraint — source of truth for safety is here)

### Provisions (Loop 3 — see pantry-tracking.md)
Evolves from shopping, usage, and feedback.
- Equipment available: oven, hob, microwave, slow cooker, air fryer, etc.
- Kitchen environment: "small kitchen, one hob" — affects recipe complexity
- Budget: weekly grocery target, price sensitivity, organic/free-range preference
- Shopping preferences: primary store, shopping frequency, delivery vs click-and-collect
- Pantry inventory (the dynamic part)

## Household

- Each user has their own account, preference model, and nutrition model
- Households share provisions (pantry, equipment, environment, budget)
- Household settings define which meals are shared vs individual
- Per-person constraint overrides for household members (allergies, dislikes)
- For shared meals, planner respects the union of all eaters' hard constraints
- Portions scale per meal based on headcount

## Onboarding

All of this is a lot to fill in at once. Phased approach:

### Essential (before first plan)
- Account creation (username/password)
- Dietary identity (omnivore/vegetarian/vegan/etc.)
- Allergies (safety-critical, must be set immediately)
- Calorie target (or "let AI suggest based on my goals")
- Household: cooking for just yourself or others?

### Important (first week)
- Intolerances and dislikes
- Cooking time limits (weeknight vs weekend)
- Budget and shopping preferences
- Meal structure (which meals, any fixed slots)
- Equipment list

### Nice to have (learn over time)
- Cuisine preferences — the AI can infer these from feedback
- Variety settings — defaults are fine, adjust as you go
- Micro nutrient targets — start with just macros
- Skill level — AI infers from which recipes you rate as "easy" vs "hard"

### Cold start
The quick preference quiz (see preference-model.md) seeds the preference model during onboarding: show 10-15 dishes, user swipes like/dislike, AI generates an initial model. This means even the first meal plan has some personalisation.
