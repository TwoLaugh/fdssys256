# AI Service — Detailed Design

## What This Module Does

Every AI interaction in the system goes through this layer. It's responsible for:
- Assembling the right context for each type of request
- Picking the right model
- Managing prompt templates
- Parsing responses into structured data
- Tracking costs
- Handling failures

## The Core Problem: Context Assembly

The meal planner needs to send a LOT of context to the AI:
- User profile (constraints, goals, preferences)
- Preference model (learned likes/dislikes)
- Pantry state (what's in the house, expiry dates)
- Recipe library (what recipes exist to choose from)
- Feedback history (recent meal ratings)
- Current overrides ("I want tacos Tuesday", "eating out Friday")
- Pack size data (if available)

A single user after 6 months might have: 200+ recipes, 50+ pantry items, hundreds of feedback entries. That's too much raw data for a single prompt.

### Solution: Layered Context with Summaries

```
ALWAYS INCLUDED (small, static-ish):
├── User profile constraints + goals          (~500 tokens)
├── Preference model (the JSON summary)       (~300 tokens)
└── Current overrides for this plan            (~100 tokens)

INCLUDED AS SUMMARIES (medium, changes weekly):
├── Pantry state (item list with expiry)      (~500 tokens)
├── Recipe library INDEX                       (~1000 tokens)
│   (name, tags, rating, last used, macros — not full recipes)
└── Recent feedback summary (last 2-4 weeks)  (~300 tokens)

INCLUDED ON DEMAND (large, only when needed):
└── Full recipe details                        (only for recipes the AI selects)
```

### The Two-Pass Planning Approach

Rather than stuffing everything into one massive prompt:

**Pass 1 — Recipe Selection** (cheap/mid model)
- Send: profile + preferences + pantry + recipe INDEX (names, tags, ratings, macros only)
- Ask: "Pick 15-20 candidate recipes for this week from this library, considering what's in the pantry and the user's preferences. Also suggest 3-5 new recipes to discover."
- Returns: list of recipe IDs + brief reasoning

**Pass 2 — Plan Assembly** (frontier model)
- Send: profile + preferences + pantry + FULL details of the 15-20 selected recipes + overrides
- Ask: "Arrange these into a 7-day meal plan. Optimise ingredient utilisation. Ensure nutrition targets are met. Explain ingredient flow across the week."
- Returns: structured meal plan

This keeps each call focused and within reasonable context sizes. Pass 1 is cheap (just scanning an index). Pass 2 is expensive but has only the relevant recipes, not the entire library.

### Context for Other Tasks

| Task | Context Needed | Size |
|------|---------------|------|
| Plan generation | Full (two-pass above) | Large |
| Plan adjustment | Current plan + pantry + the affected recipes | Medium |
| Recipe evolution | Single recipe (all versions) + feedback + profile | Small |
| Recipe import | Raw HTML from URL | Medium |
| Feedback → preference model | All feedback since last update + current model | Medium |
| Nutrition mapping | Single recipe's ingredient list | Small |
| Health review | Nutrition logs + health logs + profile (period summary) | Medium |
| Recipe discovery | Profile + preferences + recipe index (to avoid duplicates) | Medium |

---

## Prompt Management

### Where Prompts Live
Prompts are **template files stored in the resources directory**, not hardcoded in Java.

```
src/main/resources/prompts/
├── planner/
│   ├── recipe-selection.txt       ← Pass 1
│   ├── plan-assembly.txt          ← Pass 2
│   └── plan-adjustment.txt
├── recipe/
│   ├── import-from-url.txt
│   ├── generate-recipe.txt
│   └── evolve-recipe.txt
├── feedback/
│   ├── interpret-feedback.txt
│   └── update-preference-model.txt
├── nutrition/
│   └── map-ingredients-to-usda.txt
├── health/
│   └── generate-review.txt
├── discovery/
│   └── search-and-filter.txt
└── grocery/
    └── tesco-product-match.txt
```

### Template Format
Each prompt template has:
- A system message (role, constraints, output format)
- Placeholders for dynamic context (e.g., `{{user_profile}}`, `{{pantry_state}}`)
- Output format specification (JSON schema the response must conform to)

```
# Example: plan-assembly.txt

SYSTEM:
You are a meal planning assistant. Generate a 7-day meal plan.

CONSTRAINTS (never violate):
{{user_hard_constraints}}

GOALS:
{{user_nutrition_goals}}

PREFERENCES:
{{preference_model}}

CURRENT PANTRY:
{{pantry_state}}

AVAILABLE RECIPES (use these):
{{selected_recipes_full}}

USER OVERRIDES:
{{overrides}}

OUTPUT FORMAT:
Return a JSON object matching this schema:
{
  "days": [
    {
      "date": "2026-03-23",
      "meals": [
        {
          "slot": "breakfast|lunch|dinner|snack",
          "recipe_id": 123,
          "servings": 1,
          "notes": "uses pantry chicken expiring Tuesday"
        }
      ]
    }
  ],
  "ingredient_flow": {
    "chicken_breast_500g": ["Monday dinner (200g)", "Wednesday lunch (300g)"]
  },
  "shopping_needed": [...],
  "daily_nutrition": [...],
  "reasoning": "Brief explanation of key planning decisions"
}
```

### Why Files, Not Database
- Version controlled with the code
- Easy to review changes in PRs
- No runtime dependency on DB for prompts
- Can be iterated rapidly during development
- If we ever want dynamic prompts (A/B testing, per-user customisation), move to DB later

---

## Response Parsing

Every AI call expects a structured JSON response. The service must handle:

1. **Happy path**: Response is valid JSON matching the expected schema → parse and return
2. **Malformed JSON**: AI returns JSON with syntax errors → attempt repair (common fixes: trailing commas, unescaped quotes), retry once if repair fails
3. **Wrong schema**: Valid JSON but missing required fields → retry with a more explicit prompt ("Your response was missing the 'daily_nutrition' field. Please include it.")
4. **Refusal or off-topic**: AI doesn't follow instructions → log, retry once, then fail with a user-friendly error
5. **Timeout**: API call takes too long → fail gracefully, show cached/previous plan if available

### Implementation

```java
public interface AiResponseParser<T> {
    T parse(String rawResponse) throws ParseException;
}

// Each task type has its own parser
public class MealPlanParser implements AiResponseParser<MealPlan> { ... }
public class RecipeParser implements AiResponseParser<Recipe> { ... }
public class PreferenceModelParser implements AiResponseParser<PreferenceModel> { ... }
```

---

## Model Routing

```java
public enum ModelTier {
    FRONTIER,  // claude-opus / claude-sonnet (latest)
    MID,       // claude-sonnet / claude-haiku (latest)
    CHEAP      // claude-haiku
}

// Each task type maps to a tier
Map<TaskType, ModelTier> routing = Map.of(
    PLAN_ASSEMBLY, FRONTIER,
    PLAN_ADJUSTMENT, FRONTIER,
    RECIPE_SELECTION, MID,
    RECIPE_GENERATION, MID,
    RECIPE_EVOLUTION, MID,
    RECIPE_IMPORT, MID,
    FEEDBACK_INTERPRETATION, MID,
    PREFERENCE_UPDATE, MID,
    HEALTH_REVIEW, MID,
    NUTRITION_MAPPING, CHEAP,
    TEXT_PARSING, CHEAP
);
```

---

## Cost Tracking

Log every API call:
- Timestamp
- Task type
- Model used
- Input tokens
- Output tokens
- Cost (calculated from token counts and model pricing)
- Latency
- Success/failure

Monthly summary available in settings: "This month: £X.XX across Y API calls."

Useful for:
- Spotting unexpectedly expensive operations
- Deciding if a task should be moved to a cheaper model
- Budgeting

---

## Failure Handling

| Failure | Strategy |
|---------|----------|
| API timeout | Retry once, then show error + use cached data if available |
| Rate limit (429) | Back off and retry with exponential delay |
| API down (500/503) | Queue request, notify user "AI features temporarily unavailable" |
| Bad response (unparseable) | Retry once with stricter prompt, then fail gracefully |
| Context too large | Reduce context (fewer recipes, shorter history) and retry |

**Key principle**: AI features being down should never prevent the user from viewing their current plan, recipes, or pantry. Those are all local data. Only generation/evolution features require the API.

---

## Open Questions
- Should we cache AI responses? E.g., if the user generates a plan, doesn't like it, and immediately regenerates — should it be a fresh call or can we cache the first result?
- Tool use: should the planner AI have access to tools (search recipes, check pantry) rather than us pre-assembling all context? This is more agentic but harder to control cost/latency.
- Streaming: should plan generation stream results to the UI, or wait for the complete response? Streaming feels more responsive for long operations.
