# Recipe Engine — Design

*Independent catalogue for all recipe operations. One of the four data models the system reads from.*

## What It Is

The Recipe Engine stores, versions, imports, discovers, and generates recipes. It is a catalogue — it contains no optimisation logic, no scoring, no planning intelligence. The Meal Planner queries it for candidates, the Recipe Optimiser adapts recipes against constraints, and the Feedback System triggers recipe evolution. The Recipe Engine just holds the data and serves it.

The engine has five concerns:

| Concern | What it holds | Updated by |
|---|---|---|
| **Recipe storage** | Two catalogues (user + system) with full recipe data: ingredients, method, metadata, nutrition | Import, AI generation, manual entry, optimiser adaptations |
| **Versioning** | Linear improvement history per recipe — every change creates a new version | Optimiser, feedback-driven evolution, manual edits |
| **Branching** | Creative forks where both variants have independent merit | Optimiser, user decision |
| **Substitutions** | Constrained variations made for practical reasons (budget, availability, dietary) — not preferred, just necessary | Optimiser (Triggers 1-4), manual edits |
| **Import / Generation** | Pipeline for getting recipes into the system from URLs, AI generation, or manual entry | User action, planner gap-filling, discovery |

This is **not**:
- The Meal Planner (which selects and arranges recipes into plans — the Recipe Engine is the pool it draws from)
- The Recipe Optimiser (which adapts recipes against the three data models — the Recipe Engine stores the results)
- The Nutrition Model (which calculates nutrition — the Recipe Engine stores the calculated values but doesn't compute them)
- A recommendation engine (scoring, similarity, embeddings — all planner/optimiser concerns, not catalogue concerns)

---

## Two Catalogues

### User catalogue

Recipes deliberately entered, imported, or saved by the user. These are "owned" recipes — the user's curated library.

- The Recipe Optimiser **proposes** changes but never applies them without user approval.
- Manual edits by the user are always permitted.
- The user can demote a recipe back to the system catalogue (soft delete from their library, recipe data preserved).

### System catalogue

AI-managed pool of recipes the system has discovered, generated, or adapted on its own.

- The Recipe Optimiser can **modify freely** without approval, giving the planner a larger and more flexible set of options.
- The user can **promote** any system recipe to their user catalogue at any time (one-tap action).
- System recipes that have never been used in a plan and have no user interaction (no feedback, no promotion) are **archived after 3 months** to prevent unbounded growth. Archived recipes are retained in storage but excluded from the planner's recipe index.

Both catalogues share the same data structures, versioning, branching, and substitution mechanisms. The only difference is the approval model.

---

## Recipe Data Structure

### Core fields

```json
{
  "recipe_id": 42,
  "catalogue": "user",
  "name": "Chicken Stir Fry",
  "description": "Quick weeknight stir fry with crispy vegetables",
  "current_version": 3,
  "current_branch": "main",

  "ingredients": [
    {
      "name": "chicken thighs",
      "quantity": 400,
      "unit": "g",
      "preparation": "sliced",
      "ingredient_mapping_key": "chicken thigh skinless raw",
      "optional": false
    },
    {
      "name": "red pepper",
      "quantity": 1,
      "unit": "whole",
      "preparation": "sliced",
      "ingredient_mapping_key": "red pepper raw",
      "optional": false
    },
    {
      "name": "soy sauce",
      "quantity": 2,
      "unit": "tbsp",
      "preparation": null,
      "ingredient_mapping_key": "soy sauce",
      "optional": false
    },
    {
      "name": "sesame seeds",
      "quantity": 1,
      "unit": "tbsp",
      "preparation": "toasted",
      "ingredient_mapping_key": "sesame seeds",
      "optional": true
    }
  ],

  "method": [
    {"step": 1, "instruction": "Slice the chicken thighs into strips."},
    {"step": 2, "instruction": "Heat oil in a wok over high heat."},
    {"step": 3, "instruction": "Stir fry chicken for 4-5 minutes until golden."},
    {"step": 4, "instruction": "Add vegetables and stir fry for 2-3 minutes."},
    {"step": 5, "instruction": "Add soy sauce, toss to coat, serve immediately."}
  ],

  "metadata": {
    "servings": 2,
    "prep_time_mins": 10,
    "cook_time_mins": 15,
    "total_time_mins": 25,
    "equipment_required": ["wok", "hob"],
    "stores_well": {"fridge_days": 3, "freezer_weeks": 8},
    "packable": true,
    "cuisine": "East Asian",
    "meal_types": ["lunch", "dinner"],
    "tags": {
      "protein": "chicken",
      "cooking_method": "stir-fry",
      "complexity": "minimal",
      "flavour_profile": ["umami", "savoury"],
      "dietary_flags": []
    }
  },

  "nutrition_per_serving": {
    "calories": 420,
    "protein_g": 38,
    "carbs_g": 12,
    "fat_g": 24,
    "fibre_g": 3,
    "micros": {
      "iron_mg": 2.1,
      "zinc_mg": 3.4
    },
    "nutrition_status": "calculated",
    "last_calculated": "2026-04-15T10:00:00Z"
  },

  "data_quality": "user_verified",
  "source": {
    "type": "imported",
    "url": "https://example.com/chicken-stir-fry",
    "imported_at": "2026-04-01T12:00:00Z"
  },

  "rating": {
    "score": 78,
    "count": 5,
    "last_rated": "2026-04-14"
  },

  "created_at": "2026-04-01T12:00:00Z",
  "updated_at": "2026-04-15T10:00:00Z"
}
```

### Field notes

**`ingredients`** are stored as structured data, not free text. Every ingredient has an `ingredient_mapping_key` that links to the Nutrition Model's `nutrition_ingredient_mapping` cache. This key is set during the import/generation pipeline (the Nutrition Model's AI parsing step normalises ingredient text to a mapping key). The `optional` flag lets the planner and optimiser know which ingredients can be dropped without breaking the recipe (garnishes, toppings).

**`method`** is a structured step list. Each step is a discrete instruction. This structure enables AI reasoning about recipes ("which step uses the food processor?", "can step 3 be parallelised with step 4?") and future features like interactive cooking mode.

**`metadata.tags`** are AI-inferred structured tags, set on import/creation and refreshed when a recipe is versioned. The tag dimensions are fixed (protein, cooking_method, complexity, flavour_profile, dietary_flags) but the values within each dimension are free (any cuisine, any protein, etc.). These give the planner concrete fields to filter and score against. The Recipe Engine stores them; the planner decides how to weight them.

**`metadata.tags` is not the long-term plan.** The tag dimensions are a pragmatic starting point. The Planner HLD may introduce vector embeddings (`pgvector`) alongside or replacing tags for soft scoring — recipe similarity, taste profile matching, variety control. If so, the Recipe Engine adds a nullable `embedding vector(1536)` column. The embedding pipeline (which model generates them, when they refresh) is planner-owned. The Recipe Engine just stores the vector.

**`nutrition_per_serving`** is always calculated by the Nutrition Model's engine, never taken from external sources. On import, any external nutrition data is discarded and recalculated internally. `nutrition_status` tracks whether calculation is complete: `calculated`, `pending` (waiting for USDA mapping), or `partial` (some ingredients unmapped). The planner can use recipes with `partial` nutrition but flags them in the plan.

**`data_quality`** reflects trust in the ingredient list:
- `user_verified` — manually entered or confirmed. Highest trust.
- `imported` — extracted from URL. Medium trust — extraction may have errors.
- `ai_generated` — created by the system. Medium trust — consistent but unverified.
- `web_discovered` — scraped from search results. Lowest trust — may be incomplete.

Low-trust recipes get a visual indicator in the plan. The nutrition engine can flag implausible mappings for user review.

**`rating`** is a 0-100 score stored internally for fine-grained planner scoring. Displayed to the user as a 5-star half-star UI (each half star = 10 points). `count` tracks how many times the recipe has been rated, which affects confidence weighting — a recipe with one 90-point rating is less reliable than one with ten 75-point ratings.

**`source`** records provenance. `type` values: `manual`, `imported`, `ai_generated`, `web_discovered`. For imports, the original URL is preserved. For AI-generated recipes, the generation context (what gap it was filling) could be stored as metadata.

---

## Versioning

Every recipe change creates a new version. Versions are linear improvements — given a later version, you'd never go back to an earlier one. The current version is the best known version of the recipe.

### Version structure

```json
{
  "version_id": 3,
  "recipe_id": 42,
  "branch": "main",
  "parent_version_id": 2,
  "changes": {
    "ingredients": [
      {"action": "modified", "field": "soy sauce", "from": "1 tbsp", "to": "2 tbsp"}
    ],
    "method": [],
    "metadata": [
      {"action": "modified", "field": "total_time_mins", "from": 20, "to": 25}
    ]
  },
  "change_reason": "User feedback: needed more flavour. Doubled soy sauce.",
  "trigger": "feedback",
  "created_at": "2026-04-15T10:00:00Z",
  "created_by": "optimiser"
}
```

**Version metadata:**
- `parent_version_id` — which version this was derived from. Enables version history traversal.
- `changes` — structured diff showing exactly what changed. Enables side-by-side comparison of any two versions.
- `change_reason` — human-readable explanation of why the change was made. Set by the Optimiser (from feedback text), by the user (manual edit note), or by the system (import, generation).
- `trigger` — what caused this version: `feedback`, `optimiser`, `manual_edit`, `import`, `data_model_change`.
- `created_by` — `user`, `optimiser`, `feedback_system`, `import_pipeline`.

**Per-version feedback:** Ratings are tracked per version. If version 2 had a score of 60 and version 3 (with doubled soy sauce) gets 85, the system knows the change was an improvement. This is the quality signal that validates whether optimiser-proposed changes actually help.

**Retention:** Keep all versions. Recipe version history is small (a few KB of diffs per version) and the audit trail is valuable — seeing how a recipe evolved over months is useful for both the user and the AI's learning.

---

## Branching

Branches are creative forks where both variants have genuine, independent merit. Unlike versions (strictly improvements), branches represent a choice: "this recipe works as a rich/heavy dish AND as a lighter/tangier variant — both are worth keeping."

### When to branch

- **Protein swap that changes character:** Chicken stir fry → beef stir fry. Different cooking times, different flavour profile, different nutritional profile. Both are valid dishes.
- **Flavour direction fork:** A curry that works as a rich coconut version AND a lighter tomato-based version. Neither is "better" — they suit different moods/contexts.
- **Cooking method change:** Oven-roasted vs pan-fried version of the same dish, where the method fundamentally changes the result.

### When NOT to branch (use a version instead)

- Adjusting salt, spice level, or proportions — that's a refinement, not a fork.
- Adding a side dish or garnish — the core recipe is the same.
- Fixing an error in the method steps.

### Branch structure

```json
{
  "branch_id": "beef-variant",
  "recipe_id": 42,
  "parent_branch": "main",
  "branch_point_version": 2,
  "label": "Beef Stir Fry",
  "reason": "Protein swap: chicken → beef. Different cooking approach, richer flavour.",
  "current_version": 1,
  "created_at": "2026-04-10T14:00:00Z",
  "created_by": "optimiser"
}
```

Each branch has its own version history. The main branch continues independently. Branches share a common ancestor (`branch_point_version`) but diverge from there.

The planner can choose between branches based on which better fits the current plan — e.g., the beef branch is over budget this week, use the chicken branch. Branch selection is a planner concern, not a Recipe Engine concern.

---

## Substitutions

Substitutions are a third concept, distinct from both versions and branches. They are constrained variations made for practical reasons — not because the variant is better (version) or equally desirable (branch), but because a limitation forces a change.

### When substitutions happen

- **Budget:** Recipe uses fillet steak, budget requires a cheaper cut → substitute rump steak.
- **Availability:** Tesco doesn't stock fresh coriander this week → substitute parsley.
- **Dietary restriction:** Temporary elimination protocol (from a health directive) removes eggs → substitute flax eggs in baking.
- **Equipment:** Recipe uses a food processor the user doesn't have → substitute knife prep.

### Substitution structure

```json
{
  "substitution_id": "sub-007",
  "recipe_id": 42,
  "version_id": 3,
  "original": {
    "ingredient_mapping_key": "fillet steak raw",
    "quantity": 300,
    "unit": "g"
  },
  "substitute": {
    "ingredient_mapping_key": "rump steak raw",
    "quantity": 300,
    "unit": "g"
  },
  "reason": "budget",
  "constraint_ref": null,
  "temporary": true,
  "applied_in_plans": ["plan-2026-w15"],
  "notes": "Cheaper cut, slightly tougher — needs longer cooking. Optimiser adjusted method step 3."
}
```

**Key distinction from branches:** Substitutions are not ideal. If the constraint is lifted (budget increases, ingredient becomes available, elimination protocol ends), the original ingredient should return. Branches are both desirable; substitutions have a clear preferred version.

**Substitutions are stored as overlays on existing versions**, not as new versions or branches. The base recipe is unchanged. The planner applies substitutions at plan-time when constraints require them. This means the recipe library isn't polluted with budget-constrained variants — the substitution is a plan-level annotation that references the recipe.

**Temporary substitutions** (linked to a constraint that may change) are tracked with `temporary: true` and can auto-expire when the constraint is removed. Permanent substitutions (user genuinely prefers rump steak to fillet) should be promoted to a version change by the user.

---

## Import Pipeline

Recipes enter the system from three sources, all flowing through the same pipeline:

### Manual entry

User fills in a form with ingredients, method steps, and metadata. The simplest path — data is already structured.

```
User fills form
    │
    ▼
Validate required fields (name, at least 1 ingredient, at least 1 method step)
    │
    ▼
Nutrition Engine: map ingredients to USDA via ingredient_mapping_key
    (AI parsing normalises names, estimates grams for vague quantities)
    │
    ▼
Hard constraint filter: check ingredients against user's allergies/dietary identity
    Flag violations rather than reject — user may be entering for a household member
    │
    ▼
Store in user catalogue with data_quality = "user_verified"
    Publish RecipeImportedEvent
```

### Import from URL

AI extracts structured recipe data from a web page.

```
User provides URL
    │
    ▼
Fetch page content (HTTP GET, extract readable content)
    │
    ▼
AI extraction (mid-tier model):
    Input:  page content
    Output: structured recipe (via tool use — guaranteed valid JSON matching recipe schema)
        - name, description
        - ingredients (name, quantity, unit, preparation)
        - method steps
        - servings, prep time, cook time
        - equipment (inferred from method)
        - cuisine, tags (inferred from ingredients and method)
    │
    ▼
Nutrition Engine: map ingredients to USDA
    External nutrition data from the page is DISCARDED — recalculated internally
    │
    ▼
AI tag inference (cheap model):
    Input:  recipe name, ingredients, method
    Output: structured tags (cuisine, protein, cooking_method, complexity, flavour_profile)
    │
    ▼
Hard constraint filter: check against allergies/dietary identity
    │
    ▼
Store in user catalogue with data_quality = "imported"
    Publish RecipeImportedEvent → Optimiser (Trigger 1) runs adaptation
```

**Failure handling:** If AI extraction produces garbage (missing ingredients, nonsensical method), store the recipe with a "needs review" flag rather than discarding. The user can manually correct. If the URL is unreachable, fail immediately with a clear error.

### AI generation

The system creates a recipe to fill a specific gap identified by the planner or requested by the user.

```
Gap identified (e.g., "need a high-protein vegetarian lunch under 30 mins")
    │
    ▼
AI generation (mid-tier model):
    Input:  gap description + relevant constraints (dietary, equipment, budget)
           + taste profile summary (flavour/cuisine preferences)
    Output: structured recipe (via tool use)
    │
    ▼
Nutrition Engine: calculate nutrition from generated ingredients
    │
    ▼
AI tag inference
    │
    ▼
Hard constraint filter
    │
    ▼
Store in system catalogue with data_quality = "ai_generated"
    Publish RecipeImportedEvent → Optimiser (Trigger 1)
```

### Online discovery

Search the web for recipes matching specific criteria, filter against constraints, and import the best matches.

```
Discovery trigger (weekly, or planner identifies a gap)
    │
    ▼
Web search for recipes matching criteria
    (e.g., "Mediterranean vegetarian dinner under 45 minutes")
    │
    ▼
AI filters and ranks search results against user constraints
    │
    ▼
For top N results: run the URL import pipeline
    │
    ▼
Store in system catalogue with data_quality = "web_discovered"
```

Discovery results go into the system catalogue. The user can promote any to their library. Discovered recipes that are never used are pruned after 3 months.

---

## Recipe Images

Imported recipes often include images. Images are stored as URLs (for imported/discovered recipes) or as uploaded files (for manual entry). Image storage is optional — a recipe without an image is fully functional.

For AI-generated recipes, images are not generated in v1. This could be added later using image generation models, but it's a cosmetic enhancement with no planning impact.

---

## How It Gets Used

### By the Meal Planner

**Primary consumer.** Reads:
- Recipe index (names, tags, ratings, macros, equipment, prep time) — compact representation for Phase 1 selection
- Full recipe details for selected candidates — ingredients, method, nutrition, metadata
- Branch availability — which variants exist for a given recipe
- Substitution history — what's been substituted before and why

The Planner never writes to the Recipe Engine. It reads candidates, applies substitutions at plan-time, and outputs a plan that references recipe IDs.

### By the Recipe Optimiser

**Primary writer.** The Optimiser:
- Creates new versions (Trigger 2: post-feedback, Trigger 3: data model change)
- Creates branches (when adaptation produces a genuinely different variant)
- Creates substitutions (Trigger 4: plan-time budget/availability constraints)
- Proposes changes to user catalogue recipes (via pending change mechanism)
- Applies changes freely to system catalogue recipes

### By the Nutrition Engine

**Writes nutrition data.** On `RecipeImportedEvent` or `RecipeEvolvedEvent`:
- Maps all ingredients through the USDA pipeline
- Calculates `nutrition_per_serving`
- Sets `nutrition_status` (`calculated`, `pending`, `partial`)

### By the Feedback System

**Routes recipe-specific feedback.** When the user says "this recipe needs more garlic":
- Feedback System calls `RecipeUpdateService.proposeEvolution(recipeId, feedbackText)`
- The update service creates a pending evolution request
- The Optimiser picks it up (Trigger 2) and proposes a new version
- For user catalogue: proposed as a diff for user approval
- For system catalogue: applied automatically

### By the Hard Constraint Filter

**Reads ingredient lists.** Every recipe's ingredients are checked against the user's allergy/dietary identity database before being shown in a plan. The filter calls `RecipeQueryService.getIngredients(recipeId)` and checks each `ingredient_mapping_key` against the hard constraint database.

---

## Approval Model for User Catalogue Changes

When the Optimiser proposes a change to a user catalogue recipe, it doesn't create a new version directly. Instead:

1. The Optimiser creates a **pending change** — a diff showing the proposed modifications.
2. The user sees the proposed change as a notification: "Suggested improvement to Chicken Stir Fry: double soy sauce based on your feedback."
3. The user can **accept** (creates the new version), **reject** (discards the proposal), or **modify** (edit the proposal before accepting).
4. Accepted changes create a new version with `trigger: "optimiser"` and `created_by: "optimiser"`.

**Pending change storage:** Stored as a `recipe_pending_changes` table with the recipe_id, proposed diff, reason, trigger source, and status (`pending`, `accepted`, `rejected`, `modified`). Pending changes expire after 2 weeks if not reviewed.

**The approval UX:** Presented as a side-by-side diff — original ingredient/step in one column, proposed replacement in the other, with accept/reject per change. A conversational AI suggestion box is available alongside the diff so the user can discuss or refine the proposal before accepting.

System catalogue recipes have no pending change mechanism — the Optimiser writes directly.

---

## Search and Filtering

The Recipe Engine exposes search via `RecipeQueryService`. The planner's pre-filtering step and the user's recipe browser both use this.

### Query parameters

```
GET /api/v1/recipes?
    catalogue=user|system|both          ← which catalogue
    cuisine=mediterranean,east-asian    ← tag filter (OR within dimension)
    protein=chicken,tofu                ← tag filter
    maxTime=30                          ← total_time_mins ≤ 30
    mealType=lunch,dinner               ← suitable meal types
    complexity=minimal,moderate          ← complexity tag
    equipment=oven,hob                  ← only recipes using this equipment
    excludeEquipment=food_processor     ← exclude recipes needing this
    minRating=60                        ← minimum rating score
    dataQuality=user_verified,imported  ← trust tier filter
    nutritionStatus=calculated          ← only fully calculated recipes
    query=stir fry                      ← free text search on name/description
    sort=rating,desc                    ← sort field and direction
    page=0&size=20                      ← pagination
```

### Recipe index (for planner context assembly)

A compact representation returned by `RecipeQueryService.getRecipeIndex()`:

```json
[
  {
    "recipe_id": 42,
    "name": "Chicken Stir Fry",
    "catalogue": "user",
    "tags": {"cuisine": "East Asian", "protein": "chicken", "cooking_method": "stir-fry", "complexity": "minimal"},
    "rating": 78,
    "rating_count": 5,
    "total_time_mins": 25,
    "equipment": ["wok", "hob"],
    "calories_per_serving": 420,
    "protein_per_serving_g": 38,
    "servings": 2,
    "stores_well": true,
    "packable": true,
    "meal_types": ["lunch", "dinner"],
    "branch_count": 2,
    "last_used_in_plan": "2026-04-08"
  }
]
```

This is what the planner sends to AI in Phase 1 — enough to select candidates without loading full recipe details. The planner pre-filters (hard constraints, equipment, time) before building this index to keep it within the token budget.

---

## Boundaries with Other Models

| Concern | Lives in | Not in Recipe Engine because |
|---|---|---|
| Taste preferences (likes, dislikes) | Preference Model | Recipe Engine stores ratings, not preferences. The planner uses both. |
| Nutritional targets | Nutrition Model | Recipe Engine stores nutrition data per recipe, not what the user needs. |
| Nutrition calculation | Nutrition Model (engine) | Recipe Engine stores the results. Calculation is triggered by events, not by the Recipe Engine itself. |
| Ingredient availability / pricing | Provision Model | Recipe Engine stores what a recipe needs. Provisions stores what's available and what it costs. |
| Recipe scoring and selection | Meal Planner | Recipe Engine serves candidates. The planner scores and selects. |
| Recipe adaptation logic | Recipe Optimiser | Recipe Engine stores the results. The Optimiser decides what to change. |
| Embedding generation | Meal Planner (future) | If embeddings are added, the planner owns the pipeline. Recipe Engine stores the vectors. |
| Substitution decisions | Recipe Optimiser / Planner | Recipe Engine stores substitution records. The Optimiser decides when to substitute. |

---

## Bootstrapping (Cold Start)

Both catalogues start empty. The first plan generation has no recipes to select from in Phase 1 — it relies on Phase 2 (creative augmentation) to generate meals from scratch, or triggers AI generation/discovery as a pre-step.

**Week 1-2:** The system leans heavily on AI generation. As the user imports recipes, gives feedback, and the system discovers more, the catalogues grow.

**Quick-start option:** During onboarding, offer to import a batch of recipes from URLs the user provides ("Paste your 5 favourite recipe links"). This seeds the user catalogue immediately and gives the planner real recipes for the first plan.

**System catalogue:** Grows organically from discovery, generation, and optimiser adaptations. Pruned after 3 months of disuse (see [Two Catalogues](#two-catalogues)).

---

## Service Interfaces

### RecipeQueryService

```java
public interface RecipeQueryService {
    RecipeDTO getById(Long recipeId);
    List<RecipeDTO> getByIds(List<Long> recipeIds);
    RecipeIndexDTO getRecipeIndex(Long userId, RecipeFilter filter);
    List<RecipeDTO> search(RecipeSearchCriteria criteria);
    List<VersionDTO> getVersionHistory(Long recipeId, String branch);
    List<BranchDTO> getBranches(Long recipeId);
    List<SubstitutionDTO> getSubstitutions(Long recipeId);
    List<String> getIngredientMappingKeys(Long recipeId);
    NutritionDTO getNutrition(Long recipeId);
}
```

### RecipeUpdateService

```java
public interface RecipeUpdateService {
    RecipeDTO create(CreateRecipeRequest request);
    RecipeDTO importFromUrl(String url, Long userId);
    RecipeDTO generate(RecipeGenerationRequest request);
    VersionDTO createVersion(Long recipeId, CreateVersionRequest request);
    BranchDTO createBranch(Long recipeId, CreateBranchRequest request);
    SubstitutionDTO createSubstitution(Long recipeId, CreateSubstitutionRequest request);
    PendingChangeDTO proposeEvolution(Long recipeId, String feedbackText);
    void acceptPendingChange(Long pendingChangeId);
    void rejectPendingChange(Long pendingChangeId);
    void updateNutrition(Long recipeId, NutritionDTO nutrition);
    void promoteToUserCatalogue(Long recipeId, Long userId);
    void archiveSystemRecipe(Long recipeId);
    void storeEmbedding(Long recipeId, float[] embedding);  // future — planner-owned pipeline
}
```

### Events published

- `RecipeImportedEvent(recipeId, catalogue, source)` — new recipe added. Listened by Optimiser (Trigger 1).
- `RecipeEvolvedEvent(recipeId, versionId, branchId, trigger)` — version or branch created. Listened by Nutrition Engine (recalculate).

---

## Open Questions

- **Recipe deduplication.** If the user imports a recipe that's essentially the same as one already in the system catalogue (discovered earlier), should the system detect this and merge/link them? Fuzzy matching on ingredient lists + recipe names could surface duplicates, but the threshold for "same recipe" is ambiguous.
- **Collaborative recipe sharing.** If multiple households use the system independently, could there be a shared recipe pool? Out of scope for v1 but the `system_catalogue` + `data_quality` architecture could support it — shared recipes would enter as `web_discovered` quality and be promoted by individual users.
