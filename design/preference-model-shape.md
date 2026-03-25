# Preference Model — Concrete Shape

*Extracted from system-overview.md. See that doc for how this fits into the architecture.*

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

The user can view and manually correct this at any time. Hard constraints (allergies, dietary identity) are stored both here and in a separate, hard-locked database table that is only editable by the user directly — never by AI, never by the feedback system, never by the optimiser. The AI-maintained version in the preference model is a convenience copy; the DB is the source of truth for safety-critical constraints.

**Allergy safety is enforced deterministically, not by prompts.** Every output that touches food — plan composition, recipe optimisation, creative augmentation, grocery substitutions — is passed through a deterministic hard-filter that checks against the allergy database before being shown to the user. This filter is code, not an AI instruction. The system never trusts the LLM to remember or respect allergies.
