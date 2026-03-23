# Feedback & Recipe Evolution

## Inspiration
ML-optimized hummus recipe — treat ingredients/proportions/methods as dimensions, minimize a loss function based on ratings. Too complex for full meals (too many dimensions, too few data points per recipe), but the *spirit* is right: recipes should evolve based on structured feedback.

## Core Approach: Conversational Feedback + Versioned Recipes

Rather than a formal optimization loop, lean into what LLMs are actually good at: understanding nuanced natural language feedback and making creative adjustments.

### How It Works

1. **User cooks a meal and gives feedback** — conversational, not a form
   - "The chicken was great but the sauce was too sweet"
   - "Took way too long for a weeknight"
   - "Kids loved this, make it again"
   - "Good but needs more protein"

2. **AI scores against a rubric** (see below) — partly from user feedback, partly calculated

3. **AI routes feedback to the appropriate loop(s)**:
   - Taste, ease, cuisine, cooking style → **Preference Model** (Loop 1)
   - Portion size, nutrition fit, macro balance → **Nutrition Model** (Loop 2)
   - Cost, ingredient availability, equipment needs, shelf life → **Provisions** (Loop 3)
   - Most feedback touches Loop 1. Some touches multiple loops simultaneously.

4. **AI stores the feedback** linked to the recipe version

5. **Next time the recipe is considered for a plan**, the AI can see:
   - All previous versions of this recipe
   - All feedback per version
   - What changed between versions and whether it helped
   - The rubric scores over time

6. **AI decides whether to adjust** — it can:
   - Tweak the recipe itself (less sugar in the sauce, sub honey for maple syrup)
   - Change portions
   - Suggest a cooking method change
   - Look online for ideas / techniques to address the feedback
   - Or leave it alone if scores are good

7. **Creates a new version** with a changelog ("reduced sugar by 30%, swapped to honey based on feedback about sweetness")

### This is NOT the hummus approach because:
- Meals have too many interacting dimensions for blind optimization
- We get maybe 1-2 data points per recipe per month, not hundreds
- An LLM can reason about *why* something didn't work, not just *that* it didn't work
- "Too sweet" is more actionable as language than as a -1 on a sweetness axis

### But it borrows the best parts:
- Structured tracking of changes over time
- Clear feedback signal tied to specific versions
- Directional improvement, not random exploration

---

## Feedback Rubric

Each meal gets scored on these dimensions. Some are user-provided, some auto-calculated. Each dimension maps to a loop:

| Dimension | Source | Scale | Loop | Notes |
|-----------|--------|-------|------|-------|
| **Taste** | User feedback (conversational) | 1-5 | Preference | AI interprets from natural language |
| **Ease of making** | User feedback | 1-5 | Preference | Time, effort, complexity |
| **Nutrition fit** | Calculated | % match to goals | Nutrition | How well did this meal's macros fit targets? |
| **Portion satisfaction** | User feedback | too little / right / too much | Nutrition | Were you still hungry? Too full? |
| **Ingredient accessibility** | Calculated | easy / moderate / hard | Provisions | Were ingredients easy to source? Already in pantry? |
| **Cost efficiency** | Calculated | price/serving | Provisions | From shopping list prices |
| **Repeat desire** | User feedback | yes / maybe / no | Preference | "Would you want this again?" |
| **Household reception** | User feedback | free text | Preference | Did the family like it? |

The AI uses these to decide:
- Whether to suggest the recipe again
- What to change if it does
- Whether to retire a recipe that consistently scores poorly

---

## Recipe Versioning

```
Recipe: Chicken Stir Fry
├── v1 (2026-03-25)
│   ├── ingredients: [chicken 500g, soy sauce 3tbsp, honey 2tbsp, ...]
│   ├── method: [...]
│   ├── feedback: "Too sweet, good texture though"
│   ├── rubric: {taste: 3, ease: 4, nutrition: 4, repeat: maybe}
│   └── ai_notes: "User found it too sweet — likely the honey + soy combo"
│
├── v2 (2026-04-08)
│   ├── changelog: "Reduced honey to 1tbsp, added rice vinegar for balance"
│   ├── ingredients: [chicken 500g, soy sauce 3tbsp, honey 1tbsp, rice vinegar 1tsp, ...]
│   ├── feedback: "Much better! Really liked the tang"
│   ├── rubric: {taste: 5, ease: 4, nutrition: 4, repeat: yes}
│   └── ai_notes: "Sweetness issue resolved. User responded well to acidity."
```

---

## What the AI Sees When Planning

When considering a recipe for next week's plan, the AI gets:

```
Recipe: Chicken Stir Fry (latest: v2, avg taste: 4.0, used 2x)
- Last feedback: "Much better! Really liked the tang" (v2, 2026-04-08)
- Trend: improved from v1→v2 (sweetness fix worked)
- Nutrition fit: good for high-protein days
- User repeat desire: yes
- Notes: user likes tangy flavours — could explore lemon/lime variants
```

This gives the AI enough context to make intelligent decisions about:
- Whether to include it
- Whether to suggest another evolution
- What other recipes might appeal (user likes tang → suggest lemon chicken?)

---

## Where Ideas Come From

The AI doesn't just iterate blindly. When it wants to improve a recipe, it can:

1. **Reason from feedback** — "too dry" → add a sauce, baste more, reduce cook time
2. **Draw from its training data** — knows culinary techniques, flavour pairings
3. **Cross-reference other rated recipes** — "user liked the sauce on Recipe X, try a similar approach here"
4. **Search online** — if the feedback suggests a technique gap (e.g., "how to get crispy skin"), look up established methods
5. **Propose changes and explain why** — user can approve/reject before it goes into the plan

---

## Feedback Examples by Loop

### Primarily Loop 1 (Preference)
- "The sauce was too sweet" → updates taste preferences
- "This took too long for a Tuesday" → reinforces weeknight time constraints
- "Kids loved this" → notes household reception for shared meals
- "I'm bored of chicken" → updates ingredient trending patterns

### Primarily Loop 2 (Nutrition)
- "Portion was too small, I was hungry after" → adjusts portion sizing
- "Good but needs more protein" → flags nutrition gap for this meal type
- "I felt sluggish after this — too many carbs?" → symptom correlation

### Primarily Loop 3 (Provisions)
- "Too expensive for a weeknight dinner" → cost feedback adjusts budget scoring
- "Couldn't find lemongrass at Tesco" → availability feedback
- "I don't have a food processor" → equipment constraint (if not already known)
- "The spinach went off before I used it" → shelf life consideration for planning

---

## Open Questions
- How many versions before retiring a recipe that isn't improving? (3 bad scores?)
- Should the user see the versioning explicitly, or just "here's the updated recipe"?
- Do we track per-ingredient preferences across recipes? ("user dislikes coriander in everything" vs "user didn't like coriander in this specific dish")
- How to handle the cold start — no feedback data yet, first few weeks are generic plans?
