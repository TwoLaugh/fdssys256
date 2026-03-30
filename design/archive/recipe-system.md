# Recipe System

## Recipe Sources

Recipes enter the system in three ways:

### 1. AI-Generated
- Created by the AI during meal plan generation
- May be inspired by known dishes or novel combinations
- Fully editable by user after creation
- Tagged as `source: ai`

### 2. User-Submitted
- User manually inputs a recipe they already know/like
- Structured input: name, ingredients, steps, rough nutrition (AI can estimate if not provided)
- Tagged as `source: user`

### 3. Imported from Web
- User pastes a URL from a recipe website (BBC Good Food, Allrecipes, etc.)
- AI parses the page: extracts ingredients, steps, nutrition, servings
- Stored locally — no dependency on the source site staying up
- Tagged as `source: imported`, keeps original URL as reference

**All three types are first-class citizens once in the system.** They all get versioning, feedback, rubric scoring, and can be included in meal plans equally.

---

## Recipe Page (UI)

### Recipe Library View
A browsable/searchable list of all recipes in the system.

- **Filters**: source (ai/user/imported), tags (cuisine, meal type, difficulty), rating, dietary compatibility
- **Sort**: by rating, times cooked, last cooked, date added
- **Search**: by name, ingredient, tag
- **Quick stats per card**: rating, times cooked, prep time, calories/serving

### Individual Recipe View
Full recipe detail page with:

- **Header**: name, source badge, version indicator (e.g., "v3"), overall rating
- **Ingredients**: with quantities, scaled to serving size
- **Steps**: numbered instructions
- **Nutrition**: per serving — calories, protein, carbs, fat (+ any other tracked macros)
- **Tags**: cuisine, meal type, difficulty, dietary labels (auto-tagged by AI)
- **Recipe-specific notes**: things the AI should consider for THIS recipe specifically (see below)
- **History tab**: all versions with changelogs, feedback per version, rubric scores over time
- **Actions**:
  - "Suggest changes" → opens chat with AI about this specific recipe
  - "Add to next plan" → requests it be included in upcoming meal plan
  - "Cook now" → marks as cooked, triggers pantry deduction, prompts for feedback
  - "Archive" → removes from active rotation but keeps history

### Add Recipe
- **Manual**: form with fields for name, ingredients, steps, servings, notes
- **Import**: paste URL, AI extracts and structures it, user reviews before saving
- **AI suggest**: describe what you want ("a quick high-protein lunch with chicken") and AI generates one

---

## Preference Layers: Global vs Recipe-Specific

This is important to get right. There are two layers of context:

### Global Preferences (User Profile)
Things that apply to ALL recipes and planning decisions:
- Dietary identity: vegan, vegetarian, pescatarian, etc.
- Allergies: nuts, shellfish, gluten — hard constraints, never include
- Intolerances: lactose, histamine — strong avoidance, but user might accept occasionally
- Dislikes: coriander, olives — avoid by default across all recipes
- Goals: high protein, low carb, calorie target, etc.
- Practical: max cooking time on weeknights, equipment available, skill level

**These are non-negotiable (allergies) or strong defaults (dislikes). The AI should never suggest a peanut dish to someone with a nut allergy, even if the recipe is highly rated.**

### Recipe-Specific Notes
Things that apply to ONE recipe and wouldn't make sense as global rules:
- "Use less garlic in this one — the flavour is too strong with the other spices"
- "This works better with fresh pasta, not dried"
- "Double the sauce if making for more than 2 people"
- "I like this spicier than the recipe suggests"
- "Don't use the slow cooker method, stovetop was better"

**These are context the AI consults when evolving or re-planning this specific recipe. They don't affect other recipes.**

### How the AI Uses Both Layers

When generating or evolving a recipe, the AI sees:

```
GLOBAL CONTEXT:
- Dietary: vegan
- Allergies: tree nuts
- Dislikes: coriander, raw tomato
- Goals: 2200 cal/day, 150g protein
- Practical: max 30min weeknight cooking

RECIPE CONTEXT (Tofu Stir Fry v2):
- Notes: "Needs more crunch — add water chestnuts or bamboo shoots"
- Notes: "Marinade the tofu for at least 1hr, don't skip this"
- Last feedback: "Good but a bit bland, could use more chilli"
- Rubric: {taste: 3, ease: 5, nutrition: 4, repeat: maybe}
```

The AI knows:
- No nuts, no coriander, no animal products (global)
- This specific recipe needs more crunch and heat (recipe-specific)
- These are different kinds of knowledge and shouldn't be confused

---

## Open Questions
- Should the AI proactively suggest recipe imports? ("I found a recipe online that matches your preferences, want to add it?")
- How to handle recipe scaling? (cooking for 1 vs 4 — just multiply, or does the AI know that some things don't scale linearly?)
- Should there be a "favourites" or "hall of fame" for recipes that consistently score 5/5?
- Recipe sharing — if this ever goes multi-user, can users share recipes with each other?
