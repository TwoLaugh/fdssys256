# Meal Planning System

## Key Insight: Planning ≠ Recipe Creation

Meal planning is mostly an **arrangement problem**, not a creative one. The AI isn't inventing 21 meals from scratch each week. It's:

1. Drawing from the recipe library (existing recipes, AI-generated, imported, discovered)
2. Arranging them across the week
3. Optimizing for ingredient utilisation across the plan

## The Real Complexity: Ingredient Utilisation

This is the core optimisation challenge. Supermarkets sell in fixed pack sizes. Recipes use arbitrary amounts.

**Example problem**:
- Recipe A needs 200g spinach
- Recipe B needs 100g spinach
- Tesco sells spinach in 250g bags
- Buy 1 bag → 50g waste OR buy 2 bags → 200g waste

**Good planning means**:
- Clustering recipes that share ingredients in the same week
- Matching recipe quantities to pack sizes where possible
- If there's inevitable leftover (e.g. 150g of a 500g chicken pack), scheduling a recipe later in the week that uses ~150g chicken
- Prioritising nearly-expired pantry items by placing recipes that use them early in the week
- Suggesting simple uses for small remainders ("use the leftover coriander in Wednesday's salad")

**This is where the AI adds real value** — a human would just buy stuff and waste the rest. The AI can reason about the full week as a system.

## Planning Inputs

```
Recipe Library (rated, versioned, tagged)
  + User Profile (constraints, goals, preferences)
  + Pantry State (what's already available, expiry dates)
  + User Overrides ("I want pizza Friday", "eating out Wednesday")
  + Variety Preferences (X new recipes per week, Y repeats)
  + Budget Target
  + Pack Size Data (from Tesco or manual — "chicken comes in 500g packs")
```

## Planning Outputs

```
Weekly Meal Plan
  ├── 7 days × meal slots (breakfast, lunch, dinner, snacks)
  ├── Each slot: recipe, servings, which pantry items it uses
  ├── Ingredient utilisation map (what's bought, where each portion goes)
  ├── Predicted leftover ingredients + suggested uses
  ├── Daily nutrition totals vs targets
  ├── Estimated weekly cost
  └── Shopping list (derived — what's needed beyond pantry)
```

## Variety Control

User configures their preference for novelty vs. familiarity:
- **New recipes per week**: e.g., "3 new, rest from my library"
- **Sources for new**: AI-generated, discovered online, or both
- **Repeat frequency**: "don't repeat the same dinner within 2 weeks"
- **Staple slots**: "I always want overnight oats for weekday breakfasts" (locks those slots)

---

# Recipe Discovery

## The Idea
A background process (or on-demand) that finds recipes online, filters them against the user's profile, and offers them for inclusion in the library.

## How It Works

1. **Search phase**: AI searches recipe sites/databases for recipes matching broad criteria
   - Cuisine types the user likes
   - Ingredient categories in season or on sale
   - Meal types needed (quick weeknight dinners, batch cook lunches, etc.)

2. **Filter phase**: Automated cut based on hard constraints
   - Remove anything with allergens
   - Remove anything violating dietary identity (meat dishes for vegans, etc.)
   - Remove anything exceeding max cooking time for the intended slot
   - Remove anything with ingredients the user has rated as disliked

3. **Score & rank**: AI scores remaining recipes on fit
   - How well do macros align with goals?
   - Does it use ingredients the user already likes?
   - Does it complement the existing library (fills gaps in cuisine variety)?
   - Ease of making vs. user skill level

4. **Present to user**: "I found 5 recipes you might like this week"
   - User swipes through: add to library / skip / save for later
   - Added recipes get imported (parsed, nutrition calculated, stored)

5. **Over time**: Discovery gets smarter
   - Learns which suggested recipes the user actually accepts
   - Learns which cuisines/styles they consistently skip
   - Narrows search to higher-hit-rate territory

## Discovery Frequency
- Could run weekly alongside plan generation
- Or on-demand: "Find me something new for dinner"
- User sets how many new recipes per week they want to try

## Sources
- Recipe websites (BBC Good Food, Allrecipes, Serious Eats, etc.)
- Reddit recipe communities
- YouTube cooking channels (extract recipe from description/comments)
- AI-generated originals inspired by patterns in liked recipes

---

## Open Questions
- Pack size data: where does this come from? Manual input? Scraped from Tesco? Crowd-sourced defaults?
- How to handle recipes that share prep (e.g., "make a big batch of tomato sauce Sunday, use in 3 recipes across the week")?
- Seasonal ingredient awareness — should the AI prefer in-season produce?
- Should discovery run automatically or only when the user asks?
