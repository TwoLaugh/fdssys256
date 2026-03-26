# Preference Model — Design

*The user's taste profile, lifestyle constraints, and food logistics preferences. One of the three data models the system optimises against.*

## What It Is

A structured, bounded summary of everything the system knows about the user's food preferences. It's the distilled output of accumulated feedback, onboarding data, and behavioural patterns.

It is **not**:
- The raw feedback data (stored separately in the Feedback System)
- The Nutrition Model (calorie/macro/micro targets, health tracking)
- A machine learning model (no embeddings, no weights — structured data)

It also holds **hard constraints** (allergies, dietary identity) as a read-only copy from the hard-locked database. See [Hard Constraints and Safety](#hard-constraints-and-safety).

## Why It Exists

Without it, every AI call would need the user's entire feedback history. After 6 months that could be hundreds of entries. The preference model is a **compression** of that history into something small enough to include in every relevant prompt (~2500 tokens).

The AI writes itself a cheat sheet about you and updates it regularly.

---

## Hard Constraints and Safety

Hard constraints (allergies, dietary identity, age restrictions) are stored in a **hard-locked database table** that is only editable by the user directly — never by AI, never by the feedback system, never by the optimiser.

**Allergy safety is enforced deterministically, not by prompts.** Every output that touches food — plan composition, recipe optimisation, creative augmentation, grocery substitutions — is passed through a deterministic hard-filter that checks against the allergy database before being shown to the user. This filter is code, not an AI instruction. The system never trusts the LLM to remember or respect allergies.

**The preference model JSON does not contain hard constraints.** Hard constraints are injected from the database at prompt assembly time, clearly labeled and separate from the AI-maintained preference document. This prevents the AI from accidentally altering, dropping, or miscopying safety-critical data during preference model regeneration. The hard-filter always reads from the database, never from the preference JSON.

### Hard constraint fields (DB-only)

```json
{
  "allergies": ["peanuts", "tree nuts"],
  "dietary_identity": {
    "base": "vegetarian",
    "exceptions": [
      {"allows": "fish", "frequency": "2-3x/week", "context": "any"}
    ],
    "label_for_display": "pescatarian"
  },
  "medical_diets": [],
  "intolerances_hard": [
    {"substance": "gluten", "severity": "coeliac", "notes": "zero tolerance — treated as allergy-level"}
  ],
  "age_restrictions": []
}
```

**Dietary identity** is structured, not a single string. Real eating patterns don't fit labels — "mostly vegetarian but eats fish" or "keto on weekdays" require a `base` identity with `exceptions` that can be conditional on frequency or context. The deterministic hard-filter uses the `base` as the safe default and widens only when exception conditions are met.

**Intolerances** are split by severity. Mild intolerances (e.g., lactose — small amounts OK) are soft constraints in the preference model. Severe intolerances (e.g., coeliac) are promoted to hard constraints in the DB and run through the same deterministic filter as allergies. The distinction is medical, but the system behaviour for hard-enforcement intolerances is identical to allergies.

**Age restrictions** are auto-populated for child profiles (e.g., no whole nuts for under-5s, no raw shellfish) and enforced deterministically like allergies. See [Profile Metadata](#profile-metadata).

---

## Profile Metadata

Each preference model carries metadata about who it belongs to. This affects how the planner uses preferences and how aggressively the update logic responds to new signals.

```json
"profile_metadata": {
  "age": 32,
  "age_group": "adult",
  "portion_scale": 1.0,
  "preference_volatility": "normal",
  "update_confirmation_threshold": 3
}
```

- `age_group`: `young_child` | `child` | `teen` | `adult`. Drives age restriction auto-population and update logic thresholds.
- `portion_scale`: Relative to a standard adult portion. A 5-year-old might be 0.33, a 9-year-old 0.6. The Nutrition Model owns calorie targets; this is a portion presentation preference.
- `preference_volatility`: `high` | `normal` | `low`. Children are `high` — their preferences shift frequently, so the update logic requires more confirming data points before promoting an experiment to a stable preference.
- `update_confirmation_threshold`: How many consistent signals before a preference is considered stable. Defaults to 3 for adults, 5 for children.

---

## Preference Model Shape

The AI-maintained preference document. This is what gets included as context in planner, recipe optimiser, and discovery prompts.

```json
{
  "last_updated": "2026-04-15",
  "version": 14,
  "based_on_feedback_count": 87,
  "feedback_cursor": "feedback-87",

  "soft_constraints": {
    "intolerances": [
      {"substance": "lactose", "severity": "mild", "notes": "small amounts OK, avoid dairy-heavy dishes"}
    ]
  },

  "flavour_preferences": {
    "likes": ["tangy/acidic", "umami-rich", "moderate spice", "fresh herbs (except coriander)"],
    "dislikes": ["overly sweet savoury dishes", "very bitter flavours"],
    "notes": "Responds well to brightness (lemon, vinegar). Prefers layered seasoning."
  },

  "texture_preferences": {
    "likes": ["crispy elements", "contrast (soft + crunchy)"],
    "dislikes": ["mushy/overcooked vegetables"]
  },

  "ingredient_preferences": {
    "favourites": [
      {"item": "chicken thighs", "evidence_count": 23, "last_signal": "2026-04-10", "source": "feedback"},
      {"item": "sweet potato", "evidence_count": 15, "last_signal": "2026-04-08", "source": "feedback"},
      {"item": "lemon", "evidence_count": 18, "last_signal": "2026-04-12", "source": "feedback"},
      {"item": "garlic", "evidence_count": 20, "last_signal": "2026-04-11", "source": "inferred"},
      {"item": "chickpeas", "evidence_count": 12, "last_signal": "2026-04-05", "source": "feedback"}
    ],
    "disliked": [
      {"item": "coriander", "evidence_count": 8, "last_signal": "2026-04-01", "source": "feedback"},
      {"item": "blue cheese", "evidence_count": 5, "last_signal": "2026-03-20", "source": "onboarding"},
      {"item": "raw celery", "evidence_count": 3, "last_signal": "2026-03-15", "source": "feedback"}
    ],
    "trending_positive": [
      {"item": "tahini", "evidence_count": 3, "first_signal": "2026-03-20"},
      {"item": "miso", "evidence_count": 3, "first_signal": "2026-03-25"}
    ],
    "trending_negative": [],
    "frequency_caps": {
      "chicken": "3x/week",
      "sweet_potato": "3x/week"
    }
  },

  "cuisine_preferences": {
    "favourites": ["Mediterranean", "East Asian", "Middle Eastern"],
    "enjoys": ["Indian", "Mexican"],
    "less_preferred": ["Traditional British", "French (heavy sauces)"],
    "notes": "Preference for lighter, vegetable-forward cuisines."
  },

  "cooking_preferences": {
    "skill_level": "intermediate",
    "preferred_ingredient_count": {"min": 5, "max": 8},
    "preferred_methods": ["roasting", "stir-frying", "marinating"],
    "disliked_methods": ["deep frying", "constant-stirring dishes"],
    "parallel_cooking_tolerance": "moderate",
    "contexts": {
      "weeknight": {"max_time_mins": 30, "complexity": "minimal", "preferred_styles": ["one-pot", "sheet-pan"]},
      "weekend": {"max_time_mins": 120, "complexity": "moderate", "notes": "Enjoys the process"},
      "cooking_project": {"max_time_mins": 180, "complexity": "high", "frequency": "1-2x/month"},
      "lazy_night": {"max_time_mins": 10, "source": "freezer_or_leftover_only", "frequency": "1x/week"}
    }
  },

  "meal_structure": {
    "weekday": {
      "meals": ["breakfast", "lunch", "dinner"],
      "snacks": {"planned": false, "notes": "occasionally grabs fruit"}
    },
    "weekend": {
      "meals": ["brunch", "dinner"],
      "snacks": {"planned": true, "style": "grazing"}
    },
    "novelty_tolerance": {
      "breakfast": {"mode": "rotation", "rotation_size": 3},
      "lunch": {"mode": "batch_repeat", "max_consecutive_same": 5, "weekly_unique_minimum": 1},
      "dinner": {"mode": "high_variety", "max_consecutive_same": 1, "new_per_week": 2},
      "snacks": {"mode": "static"},
      "recipe_repeat_cooldown_weeks": {"default": 2, "overrides": {"breakfast": 0}}
    },
    "recurring_skips": [
      {"day": "friday", "meal": "dinner", "reason": "usually eats out"}
    ]
  },

  "meal_timing": {
    "preferred_schedule": {
      "breakfast": "07:00-08:00",
      "lunch": "12:30-13:00",
      "dinner": "18:30-19:30"
    },
    "flexibility": "moderate — lunch can shift but dinner is fixed",
    "notes": "Eats late on weekends, ~10am brunch replacing breakfast+lunch"
  },

  "batch_cooking": {
    "prep_days": [{"day": "sunday", "window": "morning", "max_session_hours": 3, "max_recipes": 4}],
    "max_leftover_days": {"default": 4, "overrides": {"dinner": 2, "lunch": 5}},
    "leftover_strategy": "transform",
    "freezer_tolerance": {"acceptable": true, "max_frozen_meals_per_week": 3, "exclusions": ["dairy-heavy dishes"]},
    "same_protein_same_day": false
  },

  "reheating_preferences": {
    "available_at_work": ["microwave"],
    "available_at_home": ["microwave", "stovetop", "oven", "air_fryer"],
    "preferred_method": "stovetop",
    "exclusions": [
      {"category": "fish", "rule": "never_reheat", "reason": "office microwave smell"},
      {"category": "fried_items", "rule": "never_reheat", "reason": "goes soggy"},
      {"category": "leafy_greens", "rule": "never_batch", "reason": "wilts after day 1"}
    ],
    "cold_meal_tolerance": ["grain bowls", "wraps", "salads"]
  },

  "eating_context": {
    "weekday_lunch": {"location": "office", "format": "packed", "constraints": ["portable", "fork-only or hands-free"]},
    "weekday_dinner": {"location": "home", "format": "sit-down"},
    "weekend": {"location": "home", "format": "relaxed"}
  },

  "portion_style": {
    "preference": "large-volume, lower-calorie-density plates preferred",
    "salad_meals": "needs substantial protein or grain to feel satisfying"
  },

  "beverage_preferences": {
    "with_meals": "water, occasionally sparkling",
    "morning": "black coffee before breakfast",
    "avoids": ["sugary drinks", "alcohol on weeknights"]
  },

  "seasonal_preferences": {
    "winter": {"lean_toward": ["stews", "soups", "roasted root veg", "warming spices"], "avoid": ["cold salads as mains"]},
    "summer": {"lean_toward": ["salads", "grilled", "cold noodles", "lighter portions"], "avoid": ["heavy stews"]}
  },

  "meal_type_preferences": {
    "breakfast": {"variety_tolerance": "low", "complexity_tolerance": "low", "staples": ["overnight oats", "eggs", "smoothies"]},
    "lunch": {"variety_tolerance": "low", "complexity_tolerance": "low", "notes": "Prefers batch-cooked or leftover-based. Values convenience over novelty."},
    "dinner": {"variety_tolerance": "high", "complexity_tolerance": "moderate", "notes": "Most open to variety and new recipes."},
    "snacks": {"variety_tolerance": "low", "complexity_tolerance": "minimal", "staples": ["fruit", "nuts", "yoghurt"]}
  },

  "household_context": {
    "individual_only_preferences": ["very spicy dishes", "blue cheese"],
    "household_suitable_notes": "For shared meals, keep spice mild and flavours broadly accessible"
  },

  "recipes_to_repeat": [
    {"name": "Chicken Stir Fry v2", "suitable_for": "household", "reason": "Highly rated, easy, fits weeknight constraint"},
    {"name": "Miso Salmon", "suitable_for": "individual", "reason": "New favourite, requested repeat"}
  ],

  "recipes_to_avoid": [
    {"name": "Mushroom Risotto", "reason": "Rated poorly twice — too much stirring, bland result"},
    {"name": "Raw Kale Salad", "reason": "Portion complaints, not satisfying enough"}
  ],

  "active_experiments": [
    {"hypothesis": "likes tahini-based dressings", "status": "testing", "evidence_for": 3, "evidence_against": 0, "created": "2026-03-20"},
    {"hypothesis": "likes tofu", "status": "testing", "evidence_for": 1, "evidence_against": 1, "created": "2026-03-25"}
  ],

  "learned_insights": [
    "Prefers brown rice over white",
    "Likes spicy but not extreme heat",
    "Responds well to 5-8 ingredient recipes"
  ]
}
```

### Field design rationale

**Notes fields are for AI context, not programmatic querying.** Several sections have free-text `notes` fields (flavour, cuisine, meal timing). These exist because some preference nuances — "Responds well to brightness (lemon, vinegar)" — are genuinely hard to structure and are consumed by the AI as prompt context. Notes should always be supplementary to structured data, never the primary storage for a preference. Keep them short; they compete with structured fields for the token budget.

**Source tracking on ingredient preferences.** The `source` field distinguishes `"feedback"` (user explicitly said something), `"inferred"` (AI noticed a pattern the user never mentioned), and `"onboarding"` (seeded from the initial quiz). This helps the user-facing "here's what I think you like" view — users can see which preferences are their own words vs AI guesses. Inferred preferences should be easier to override than explicit ones.

**Evidence tracking on ingredient preferences.** `evidence_count` and `last_signal` let the planner and optimiser weight preferences by confidence. A favourite with 23 data points steers harder than one from onboarding with 2. Stale preferences (no signal in 3+ months) get flagged for re-evaluation. This adds tokens per item but is worth it for the fields the planner weights most heavily.

**Ingredient frequency caps.** Prevents the planner from over-indexing on favourites. Even if chicken thighs are the top-rated protein, 5 chicken dinners in a week is monotonous.

**Structured cooking contexts.** Replaces flat strings like "under 30 mins on weeknights" with per-context objects the planner can query directly. `lazy_night` and `cooking_project` are real meal-prep patterns that the planner needs to schedule around.

**Novelty tolerance per meal slot.** Replaces the single `new_vs_familiar_ratio` value. Breakfast tolerance is fundamentally different from dinner tolerance — most people want identical breakfasts but varied dinners. `recipe_repeat_cooldown_weeks` controls how long before a specific recipe can reappear — some people are fine with weekly taco Tuesday, others need 2-3 weeks before a repeat.

**Batch cooking as a first-class section.** The system overview describes batch cooking support in the Recipe Engine (servings, stores_well, packable) but the preference model previously gave the planner nothing to match that metadata against. Now the planner knows prep day preferences, leftover tolerance, freezer tolerance, and whether to repeat or transform leftovers.

**Reheating preferences.** Critical for batch cooking to actually work. Without this, the planner schedules microwave-reheated fish for a Wednesday office lunch and the user skips it.

**Seasonal preferences.** Prevents tone-deaf suggestions (gazpacho on a freezing Tuesday, heavy stew in August). The planner can cross-reference season when scoring recipes.

**Household context tags.** `suitable_for` on recipes and `individual_only_preferences` let the planner distinguish "foods I enjoy alone" from "foods that work for the family" when filling shared vs individual meal slots. Full household merge logic belongs in the Household Model design.

---

## How It Gets Updated

### Delta-based updates, not full regeneration

The AI does **not** regenerate the entire preference document from scratch. Instead, it proposes **deltas** — specific additions, removals, and modifications — which the application applies to a schema it controls. This prevents structural drift, lossy overwrites, and accidental changes to fields the AI shouldn't touch.

### Trigger

Updates are triggered:
- After every 5 new feedback entries (batch update, not per-meal)
- Or weekly, whichever comes first
- Or manually if the user requests it

### Process

1. Load the current preference model (application-owned, schema-validated)
2. Load all feedback entries since the `feedback_cursor`
3. Send to AI (mid-tier model) with a delta prompt:
   ```
   Here is the current preference model:
   {{current_model}}

   Here are the new feedback entries since the last update:
   {{new_feedback}}

   Propose changes to the preference model as a list of deltas:
   - ADD item to field (e.g., "add 'tahini' to ingredient_preferences.favourites")
   - REMOVE item from field
   - UPDATE item (e.g., "increment evidence_count for 'garlic'")
   - UPDATE notes field
   - PROMOTE experiment to stable preference (move from active_experiments to the appropriate field)
   - DISCARD experiment (insufficient evidence or contradicted)

   Rules:
   - Only propose changes supported by the new feedback
   - Do not alter hard constraints (allergies, dietary identity) — those are managed separately
   - Remove any learned_insight already captured in structured fields
   - Discard any experiment older than 4 weeks with insufficient evidence
   - Keep learned_insights capped at 8 items and active_experiments at 5
   ```
4. Parse the delta response, validate each operation against the schema
5. Apply valid deltas to the preference model
6. Increment version, update `feedback_cursor` and `last_updated`
7. Store the new version alongside the previous version

### Why deltas, not full regeneration?

- **No structural drift.** The application owns the schema. The AI can't rename keys, change nesting, or alter the JSON structure.
- **No lossy overwrites.** The AI can't accidentally drop a preference by omitting it from a regeneration. It has to explicitly propose a REMOVE.
- **Auditable.** Every change has a clear delta that can be logged, reviewed, and rolled back individually.
- **Hard constraint safety.** Hard constraints are never in the document, so the AI can't touch them even accidentally.

### Why not real-time?

- Updating after every single meal would be noisy (one bad rating ≠ a preference change)
- Batching lets the AI see patterns across multiple meals
- Cheaper (fewer API calls)
- Less risk of the model oscillating

---

## Versioning

Every update creates a new version. Versions are stored with:

- `version` — monotonic integer
- `generated_at` — timestamp
- `feedback_range` — IDs of feedback entries incorporated (e.g., feedback 83–87)
- `trigger` — batch | weekly | manual
- `deltas_applied` — the list of changes made
- `model_tier_used` — which AI model generated the deltas

**Retention:** Keep at least the last 10 versions. In the first year, keep all versions while failure patterns are still being learned.

**Anomaly detection:** After each update, compute a structural diff. Alert if the diff is unusually large (e.g., more than 3 items removed in a single update). Large diffs likely indicate a bad update.

**Rollback:** Rolling back reverts to a previous version and replays feedback from the rolled-back version's `feedback_cursor` forward. The `feedback_cursor` makes this deterministic — you know exactly which feedback needs reprocessing.

**User-facing:** The "Here's what I think you like" view highlights recent changes so the user can catch errors quickly. The user can manually override any preference, and overrides are flagged so the AI doesn't re-learn the wrong thing from old data.

---

## How It Gets Used

### By the Meal Planner
Included in the plan assembly prompt as context. The planner sees flavour/ingredient/cuisine preferences, cooking contexts for each meal slot, batch cooking logistics, seasonal preferences, and novelty tolerance. Hard constraints are injected separately from the DB.

### By the Recipe Optimiser
When adapting a recipe, the optimiser cross-references: "User likes tangy — try adding lemon." "User dislikes complex recipes — simplify rather than add steps." Evidence counts let the optimiser weight adaptations by preference confidence.

### By Recipe Discovery
When searching for new recipes, the preference model guides cuisine selection, ingredient profiles, and filtering. Trending preferences drive exploration.

### By the Household Model
For shared meals, the planner reads multiple preference models and computes an intersection. `household_context` tags and `suitable_for` on recipe preferences help distinguish individual-only from family-suitable options. Full merge logic (weighting, conflict resolution) is defined in the Household Model design.

---

## Bootstrapping (Cold Start)

First 1–2 weeks: no feedback yet, preference model is nearly empty.

**Approach: Quick preference quiz during onboarding.**
Show 10–15 dishes/ingredients, user swipes like/dislike. AI generates an initial preference model from that. Low effort, immediately useful. Seeded preferences carry low `evidence_count` values so they're easily overridden by real feedback.

**Alternative sources:** Import from MyFitnessPal history or a list of favourite recipes to infer initial preferences.

The initial model is explicitly flagged as low-confidence. The planner generates varied plans in the first weeks to explore the preference space, rather than over-fitting to sparse onboarding data.

---

## Guardrails

- The preference model is **viewable and editable** by the user ("Here's what I think you like — correct anything that's wrong")
- User manual overrides are flagged so the AI doesn't re-learn the wrong thing from old data
- Active experiments have a lifecycle: after 4 weeks or N data points, they must resolve (promote to preference or discard)
- Learned insights are capped at 8 items; insights that duplicate structured fields are removed
- Active experiments are capped at 5 items
- The 2500-token budget forces summarisation — if the model grows too large, low-evidence or stale preferences are the first to be pruned

---

## Boundaries with Other Models

| Concern | Lives in | Not in Preference Model because |
|---|---|---|
| Calorie/macro/micro targets | Nutrition Model | Nutritional targets, not taste preferences |
| Intermittent fasting / eating windows | Nutrition Model | Nutritional constraint; meal_timing here is the preferred schedule *within* that window |
| Health tracking (mood, weight, symptoms) | Nutrition Model | Health outcomes, not food preferences |
| Pantry inventory, equipment, budget | Provisions | Physical constraints, not personal preferences |
| Grocery prices and availability | Provisions | Supply-side, not demand-side |
| Shared meal schedules, who eats what | Household Model | Per-household structure, not per-person preference |
| School lunchbox policies (nut bans etc.) | Household Model | Environmental constraints on a meal slot, not personal preference |
| Guest/occasion overrides | Household Model | Temporary scheduling events, not stable preferences |
| Per-person meal schedule (who eats which meals) | Household Model | Household logistics, not individual preference |

---

## Open Questions

- **Activity-adjusted preferences.** The Nutrition Model handles TDEE variations for training vs rest days, but should the Preference Model also capture *what kind of food* the user wants on training days vs rest days (e.g., carb-forward post-workout, lighter on rest days)? Currently deferred — revisit when the Nutrition Model design is complete.
- **Supplement timing.** If the user takes supplements, when they take them can affect meal slot design (e.g., iron on empty stomach before breakfast). Low priority but worth noting for future iterations.
- **Weather-reactive preferences.** Seasonal preferences are coarse. A weather API integration could nudge the planner toward soups on cold days, but this adds complexity. Deferred.
- **Defrost lead time tolerance.** Affects which freezer meals the planner can schedule and how much notice is needed. Currently captured in Provisions (freezer inventory) but the user's willingness to defrost overnight vs quick-defrost is a preference. Revisit during Provisions design.
