# Meal Planning System

## Key Insight: Planning ≠ Recipe Creation

Meal planning is mostly an **arrangement problem**, not a creative one. The AI isn't inventing 21 meals from scratch each week. It's:

1. Drawing from the Recipe Engine (existing library, discovered online, AI-generated)
2. Arranging them across the week
3. Optimising simultaneously across three constraint loops

## The Three-Loop Optimisation

The planner's real job is finding solutions that satisfy all three loops at once. Optimising any single loop independently is easy — the hard problem is the intersection.

### Loop 1 (Preferences): Does the plan match what the user likes?
- Recipes align with taste, cuisine, and cooking style preferences
- Variety meets the user's new-vs-familiar ratio
- Weeknight meals respect time constraints, weekends allow complexity
- Fixed slots honoured ("I always have overnight oats for weekday breakfasts")

### Loop 2 (Nutrition): Does the plan hit nutritional targets?
- Daily/weekly macro targets (calories, protein, carbs, fat)
- Individual meals may miss targets but the weekly total should converge
- Micro targets considered if set (iron, vitamin D, fibre)
- Goal context influences recipe selection (bulking → higher calorie density)

### Loop 3 (Provisions): Does the plan work with what's available?
- Uses pantry items before they expire
- Maximises ingredient utilisation across pack sizes (the core optimisation — see below)
- Stays within budget
- Only uses equipment that exists in the current environment
- Accounts for supplier availability (don't plan around items frequently out of stock)

## The Real Complexity: Ingredient Utilisation

This is the core optimisation challenge within Loop 3. Supermarkets sell in fixed pack sizes. Recipes use arbitrary amounts.

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
Recipe Engine (existing library + online discovery + AI generation)
  + Preference Model (Loop 1 state — taste, style, variety, constraints)
  + Nutrition Model (Loop 2 state — calorie/macro/micro targets)
  + Provisions (Loop 3 state — pantry, equipment, budget, pack sizes)
  + User Overrides ("I want pizza Friday", "eating out Wednesday")
```

## Planning Outputs

Each output feeds a different loop:

```
Weekly Meal Plan
  ├── 7 days × meal slots (breakfast, lunch, dinner, snacks)
  ├── Each slot: recipe, servings, which pantry items it uses
  │
  ├── → Loop 1: Selected recipes (feedback after eating refines preferences)
  ├── → Loop 2: Daily nutrition totals vs targets (logged, feeds health tracker)
  ├── → Loop 3: Shopping list (plan ingredients − pantry stock → Tesco order)
  │
  ├── Ingredient utilisation map (what's bought, where each portion goes)
  ├── Predicted leftover ingredients + suggested uses
  └── Estimated weekly cost
```

## Variety Control

User configures their preference for novelty vs. familiarity:
- **New recipes per week**: e.g., "3 new, rest from my library"
- **Sources for new**: AI-generated, discovered online, or both
- **Repeat frequency**: "don't repeat the same dinner within 2 weeks"
- **Staple slots**: "I always want overnight oats for weekday breakfasts" (locks those slots)

---

# Recipe Engine

*Unified system combining what was previously separate: Recipe Store, Recipe Discovery, and AI Recipe Generation. They share the same mechanisms: constraint awareness, versioning, and preference/nutrition context.*

See also: recipe-system.md (detailed recipe design), feedback-and-recipe-evolution.md (versioning and evolution)

## Three Sources, One Pipeline

### Existing Library
Recipes already saved, with version history and feedback. The primary pool the planner draws from.

### Online Discovery
Background or on-demand process that finds recipes online, filters against constraints, scores against preferences, imports accepted ones.
- Search → hard-filter (constraints) → score (preferences) → present
- User swipes: add to library / skip / save for later
- Learns from accept/reject patterns over time

### AI Generation
Creates new recipes or adapts existing ones based on specific gaps. This is the same mechanism as recipe evolution (feedback-driven versioning) — the difference is just the trigger:
- Evolution: "improve this existing recipe based on feedback"
- Generation: "create something new for this gap" (e.g., "need a high-protein weeknight meal under 30 mins using the chicken in the fridge")

Both need versioning, constraint awareness, and full preference/nutrition/provisions context.

## Discovery Sources
- Recipe websites (BBC Good Food, Allrecipes, Serious Eats, etc.)
- Reddit recipe communities
- YouTube cooking channels (extract recipe from description/comments)
- AI-generated originals inspired by patterns in liked recipes

## Discovery Frequency
- Could run weekly alongside plan generation
- Or on-demand: "Find me something new for dinner"
- User sets how many new recipes per week they want to try

---

## Open Questions
- Pack size data: where does this come from? Manual input? Scraped from Tesco? Crowd-sourced defaults?
- How to handle recipes that share prep (e.g., "make a big batch of tomato sauce Sunday, use in 3 recipes across the week")?
- Seasonal ingredient awareness — should the AI prefer in-season produce?
- Should discovery run automatically or only when the user asks?
- **How does three-loop optimisation work technically?** Several approaches to prototype:
  - *Single-pass AI prompt*: give all three models as context, AI reasons about trade-offs internally. Simple but implicit — hard to debug.
  - *Sequential priority*: hard constraints first → nutrition targets → provisions/budget. Clear but rigid — can't trade off nutrition for budget.
  - *Two-pass with constraint scoring*: Pass 1 (cheap model) scores every candidate recipe against all three loops. Pass 2 (frontier) assembles the week from scored pool, optimising combined score. Maps naturally to existing two-pass design.
  - *Iterative refinement*: generate initial plan, then run "critique" passes per loop and adjust. Multiple AI calls but each is simpler.
  - Architecture stays the same regardless — this is a prototyping question (Phase 4).
