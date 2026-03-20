# Preference Model — Detailed Design

## What It Is

A structured, bounded summary of everything the AI has learned about the user's food preferences. It's the distilled output of all feedback, ratings, and behavioural patterns.

It is NOT:
- The raw feedback data (that's stored separately in the Feedback System)
- The user profile (that's explicit constraints/goals set by the user)
- A machine learning model (no embeddings, no weights — just structured text)

## Why It Exists

Without it, every AI call would need to include the user's entire feedback history. After 6 months that could be hundreds of entries. The preference model is a **compression** of that history into something small enough to include in every relevant prompt.

Think of it as: the AI writes itself a cheat sheet about you, and updates it regularly.

## Structure

```json
{
  "last_updated": "2026-04-15",
  "based_on_feedback_count": 87,

  "flavour_preferences": {
    "likes": ["tangy/acidic", "umami-rich", "moderate spice", "fresh herbs (except coriander)"],
    "dislikes": ["overly sweet savoury dishes", "very bitter flavours"],
    "notes": "Responds well to brightness (lemon, vinegar). Prefers layered seasoning over single-note flavours."
  },

  "texture_preferences": {
    "likes": ["crispy elements", "contrast (soft + crunchy)"],
    "dislikes": ["mushy/overcooked vegetables"],
    "notes": "Consistently rates stir-fries higher when veg is still crunchy."
  },

  "ingredient_preferences": {
    "favourites": ["chicken thighs", "sweet potato", "lemon", "garlic", "chickpeas", "spinach"],
    "disliked": ["coriander", "blue cheese", "raw celery", "fennel"],
    "trending_positive": ["tahini", "miso"],
    "trending_negative": [],
    "notes": "Recently started enjoying tahini-based dressings. Has used miso in 3 well-rated recipes."
  },

  "cuisine_preferences": {
    "favourites": ["Mediterranean", "East Asian", "Middle Eastern"],
    "enjoys": ["Indian", "Mexican"],
    "less_preferred": ["Traditional British", "French (heavy sauces)"],
    "notes": "Strong preference for lighter, vegetable-forward cuisines. Open to trying new cuisines if they align with flavour preferences."
  },

  "cooking_preferences": {
    "preferred_complexity": "5-8 ingredients, one-pot or sheet-pan preferred on weeknights",
    "enjoyed_techniques": ["roasting", "stir-frying", "marinating"],
    "disliked_techniques": ["deep frying (mess)", "anything requiring constant stirring"],
    "notes": "Weeknight tolerance is genuinely 30 mins. Weekend cooking is more relaxed."
  },

  "portion_patterns": {
    "notes": "Tends to rate portions as 'right' for most recipes. Has said 'too small' for salad-based meals twice — may need to increase salad portions or add more protein."
  },

  "meal_type_preferences": {
    "breakfast": "Likes consistency — overnight oats, eggs, smoothies. Doesn't want complex breakfasts.",
    "lunch": "Prefers batch-cooked or leftover-based. Values convenience over novelty.",
    "dinner": "Most open to variety and new recipes here.",
    "snacks": "Minimal — fruit, nuts, yoghurt. Doesn't want elaborate snack recipes."
  },

  "recipes_to_repeat": [
    {"name": "Chicken Stir Fry v2", "reason": "Highly rated, easy, fits weeknight constraint"},
    {"name": "Roasted Sweet Potato Bowl", "reason": "Consistently rated 5/5"},
    {"name": "Miso Salmon", "reason": "New favourite, requested repeat"}
  ],

  "recipes_to_avoid": [
    {"name": "Mushroom Risotto", "reason": "Rated poorly twice, 'too much stirring, bland result'"},
    {"name": "Raw Kale Salad", "reason": "Portion complaints, 'not satisfying enough'"}
  ],

  "active_experiments": [
    "Trying more miso-based dishes to confirm emerging preference",
    "Testing whether user likes tofu (mixed signals so far — 1 good, 1 bad rating)"
  ]
}
```

## How It Gets Updated

### Trigger
The preference model is regenerated:
- After every 5 new feedback entries (batch update, not per-meal)
- Or weekly, whichever comes first
- Or manually if the user asks ("update my preferences")

### Process
1. Load current preference model
2. Load all feedback since last update
3. Send to AI (mid-tier model):
   ```
   Here is the current preference model:
   {{current_model}}

   Here are the new feedback entries since the last update:
   {{new_feedback}}

   Update the preference model to incorporate this new feedback.
   Rules:
   - Keep the same JSON structure
   - Add new patterns you've noticed
   - Update existing preferences if feedback contradicts them
   - Move confirmed experiments to likes/dislikes
   - Keep the model concise — summarise, don't list every data point
   - Note anything surprising or contradictory for the "active_experiments" section
   ```
4. Parse the response, validate structure, store as the new model
5. Keep previous version (for rollback if the update is bad)

### Why Not Real-Time?
- Updating after every single meal would be noisy (one bad rating doesn't mean a preference change)
- Batching lets the AI see patterns across multiple meals
- Cheaper (fewer API calls)
- Less risk of the model oscillating

## How It Gets Used

### By the Meal Planner
Included in the plan assembly prompt as context. The planner sees:
- What flavours/ingredients/cuisines to lean toward
- What to avoid
- Which recipes the user wants to see again
- Current experiments to continue

### By Recipe Discovery
When searching for new recipes, the preference model guides:
- Which cuisines to search in
- What ingredient profiles to look for
- What to filter out

### By Recipe Evolution
When evolving a recipe based on feedback, the AI can cross-reference:
- "User likes tangy — try adding lemon to this dish"
- "User dislikes complex recipes — simplify rather than add steps"

## Bootstrapping (Cold Start)

First 1-2 weeks: no feedback yet, preference model is nearly empty.

Options:
1. **Start blank**: AI generates generic plans based on profile constraints only. Preference model says "No feedback yet. Generate a varied plan to explore the user's preferences."
2. **Quick preference quiz**: During onboarding, show 10-15 dishes/ingredients and ask quick like/dislike. Seeds the model immediately.
3. **Import from existing data**: If user has MyFitnessPal history or a list of favourite recipes, import and infer preferences.

**Recommendation**: Option 2 is low effort and immediately useful. Show pictures of dishes, user swipes like/dislike, AI generates an initial preference model from that.

## Guardrails

- The preference model is viewable by the user ("Here's what I think you like — correct anything that's wrong")
- User can manually override any preference ("Actually I do like fennel, I just didn't like it in that one recipe")
- Overrides are flagged so the AI doesn't re-learn the wrong thing from old data
- Model never exceeds ~2000 tokens (roughly the size of the example above) — forces summarisation
