# Design Review — Full Audit

## Inconsistencies Found & Fixed

### 1. system-overview.md is stale
The phased delivery and data model haven't been updated since the early ideation. It's missing:
- Health tracking / biometric data
- Freezer management and food waste tracking in pantry
- Recipe discovery as a distinct concept
- Plan adjustment handling
- The modular monolith decision
- Cooking mode
- Health-driven feedback loops

**Action**: Rewrite system-overview.md as the single source of truth.

### 2. additional-ideas.md has items that are now settled
- Freezer management → now part of Pantry (settled)
- Food waste tracking → now part of Pantry (settled)
- Cooking mode → settled as a recipe UI view
- Weekly review → settled and expanded into health tracking
- Health integration → expanded into full health-tracking.md

**Action**: Clean up additional-ideas.md to only contain genuinely unsettled future ideas.

### 3. Phasing is inconsistent across docs
- system-overview.md says Phase 1/2/3/4
- health-tracking.md has its own Tier 1/2/3/4
- No single doc defines what actually ships when

**Action**: Create a single phasing doc.

### 4. "Plan Adjustment Handler" vs "Meal Planner"
system-components.md lists these as separate components, but plan-disruptions.md treats adjustments as part of the planning system. These are tightly coupled — adjusting a plan IS planning.

**Decision**: Merge Plan Adjustment Handler into Meal Planner module. It's the same domain.

### 5. Notification System is underspecified
Listed as a component but no design doc covers what notifications exist, how they're delivered (push? email? in-app only?), or frequency.

---

## Things That Jump Out

### A. The AI Service needs more thought
It's described as a "centralised LLM layer" but this is actually one of the most important architectural decisions. Questions:

- **Prompt management**: Where do prompts live? Hardcoded? Template files? Database? Version-controlled?
  - Meal plan generation prompts will be complex and evolve. They need to be iterable without redeploying.
- **Context assembly**: The meal planner needs to send: user profile + pantry state + recipe library (or a subset) + feedback history + current overrides. That's potentially a LOT of tokens. How do we:
  - Summarise the recipe library instead of sending every recipe?
  - Compress feedback history into a preference summary?
  - Decide what's relevant context vs noise?
- **Response parsing**: AI returns meal plans, recipe modifications, feedback interpretations. All need to be parsed into structured data. What happens when the AI returns something unparseable?
- **Caching / deduplication**: If the user asks for a plan twice with the same inputs, do we re-call the AI or cache?
- **Cost tracking**: Even for a single user, useful to know "this month cost £X in API calls." Logging per-call cost.
- **Fallback strategy**: What if the API is down? Queue the request? Show an error? Use a cached plan?

### B. Recipe nutrition calculation has a gap
We said "auto-calculate nutrition even if the source provides it." But the pipeline isn't defined:
- Where does the nutrition database live? Local import of USDA data? API calls per ingredient?
- If local: that's a significant data import (USDA has 370k foods). Need a search/match layer.
- If API: adds latency and external dependency to recipe creation.
- Ingredient name matching is non-trivial: "chicken breast" vs "chicken breast, skinless, boneless, raw" vs "chicken, broiler, breast meat only, raw" — which USDA entry?
- **Suggestion**: Use AI to map recipe ingredients to USDA entries. A cheap model call that says "match these ingredients to their closest USDA FoodData Central entries" would work well and sidesteps the fuzzy matching problem.

### C. The preference model is vague
Feedback-and-recipe-evolution.md describes collecting feedback and the AI "consulting" it. But:
- What IS the preference model, concretely? A JSON document? A set of database records? Embeddings?
- How is it updated? AI regenerates a summary after each feedback? Appended incrementally?
- How does it avoid growing unboundedly? After a year of feedback, you can't send it all as context.
- **Suggestion**: The preference model should be an AI-maintained structured document (JSON or markdown) that gets regenerated periodically. Something like:

```json
{
  "strong_likes": ["tangy flavours", "crispy textures", "one-pot meals"],
  "strong_dislikes": ["overly sweet savoury dishes", "complicated multi-step recipes"],
  "ingredient_preferences": {
    "positive": ["lemon", "garlic", "chickpeas"],
    "negative": ["coriander", "blue cheese"]
  },
  "cuisine_preferences": {
    "positive": ["Mediterranean", "East Asian"],
    "neutral": ["Indian", "Mexican"],
    "negative": ["Traditional British"]
  },
  "cooking_patterns": {
    "weeknight_preference": "under 30 mins, minimal washing up",
    "weekend_preference": "willing to spend 1-2 hours, enjoys the process"
  },
  "learned_insights": [
    "Prefers brown rice over white rice",
    "Likes spicy food but not 'blow your head off' spicy",
    "Responds well to recipes with 5-8 ingredients, not more"
  ]
}
```

This stays bounded in size, is human-readable, and can be sent as context to the planner.

### D. Multi-person household is more complex than "single user for now"
Even in v1, you said this is for you AND family. That means:
- Some meals are for 1 person, some for 2-4
- Different people may have different constraints (partner lactose intolerant, kid won't eat mushrooms)
- Portion scaling per meal
- The user profile doc mentions "per-person overrides" but this isn't reflected in the data model or planning logic anywhere else

**Suggestion**: Model this as: one primary user (the planner), with a "household members" list. Each member has a name and a set of constraint overrides. Meal slots specify which members are eating. The planner respects the union of all constraints for shared meals.

### E. No data backup / export strategy
This is a personal system with potentially years of data — recipes, health metrics, progress photos, preference history. If the database dies, that's all gone.
- Automated backups of PostgreSQL (pg_dump on a schedule)
- Export functionality: download your data as JSON/CSV
- Recipe export in a standard format (could use schema.org/Recipe JSON-LD)

### F. Onboarding flow isn't designed
user-profile.md mentions phased onboarding (essential → important → nice-to-have) but there's no UX design for it. This is actually the user's FIRST experience with the app and will determine if they stick with it.
- Should it feel like a form? A conversation with AI? A wizard?
- How long should it take? (Target: under 5 minutes for essential, spread the rest over the first week)
- Could the AI generate a sample meal plan during onboarding so the user immediately sees value?

### G. Offline / resilience
What happens when:
- Internet is down? (Can't reach Claude API, can't reach nutrition databases)
- You're in the kitchen cooking with no signal?
- The Docker container crashes?

Cooking mode and recipe viewing should work offline (PWA cache). Planning and AI features obviously need connectivity. Worth thinking about what's cached locally.

### H. Search and filtering across the system
Multiple views need search: recipe library, pantry, shopping list history, meal history. This should probably be a shared capability, not reimplemented per module. Could be:
- PostgreSQL full-text search (built-in, good enough for this scale)
- Or AI-powered natural language search: "show me high protein meals I haven't made in a month"

The natural language search is actually a killer feature — much more useful than filter dropdowns for a single-user app.

---

## Missing Design Docs

1. **AI service architecture** — prompt management, context assembly, model routing, cost tracking
2. **Notifications** — what triggers them, delivery method, frequency, do-not-disturb
3. **Onboarding UX** — first-time user experience flow
4. **Data model** — concrete schema (deferred intentionally, but will be needed before building)
5. **Phasing / roadmap** — single source of truth for what ships when

---

## Things I Think Are Strong

- **Recipe versioning with changelogs** — this is genuinely novel for a meal planning app
- **Two-layer preference system** (global vs recipe-specific) — clean and practical
- **Ingredient utilisation as the core planning challenge** — correctly identified
- **Tiered AI model usage** — pragmatic cost management
- **Event + intent pattern for disruptions** — simple UX for a complex problem
- **Health tracking tiers** — realistic phasing from self-reported to genomics
- **Modular monolith** — right architecture for the project scope
- **Recipe discovery funnel** — search broadly, filter hard, present few
- **Conversational feedback over forms** — plays to LLM strengths
