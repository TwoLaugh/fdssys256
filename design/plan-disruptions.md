# Plan Disruptions & Mid-Week Adjustments

## The Problem

The meal plan is generated weekly, but reality doesn't follow the plan. Things that cause disruptions:

1. **Skipped meals** — didn't eat the planned dinner, went out instead
2. **Substitutions from Tesco** — ordered chicken thighs, got chicken breast
3. **Use-by dates** — the mince expires Thursday, but the recipe using it is on Saturday
4. **Impulse purchases** — bought something not on the list
5. **Leftovers** — made too much, have enough for another meal
6. **Changed plans** — friend coming for dinner Friday, need to cook for 3 not 1
7. **Ingredient didn't arrive** — out of stock, no substitution
8. **User just doesn't fancy it** — "I don't want soup today"

## The Key Design Question

When something changes, the user needs to tell the system **what they want to happen**, not just what happened. The same event can have very different desired outcomes:

**"I skipped Tuesday's dinner"**
- Option A: "Just skip it, don't adjust anything" → mark as skipped, log 0 nutrition
- Option B: "Redistribute the ingredients into the rest of the week" → AI rebalances Wed-Sun
- Option C: "I'll eat it tomorrow instead" → swap Tuesday and Wednesday meals

**"Tesco substituted X for Y"**
- Option A: "That's fine, use it the same way" → update pantry, keep plan
- Option B: "That doesn't work for the recipe, adjust" → AI suggests alternative recipe or modification
- Option C: "I'm returning it" → remove from pantry, regenerate shopping need

## Proposed UX: Event + Intent

Rather than trying to auto-handle every disruption, the system should:

1. **Detect or be told about the disruption** (some can be auto-detected, most are user-reported)
2. **Ask a simple question about intent** — not a complex form, just "what do you want to do?"
3. **AI proposes a specific adjustment** — user approves or tweaks

### Example Flow

```
User taps "Skip" on Tuesday dinner (Chicken Stir Fry)

System: "Skipping Chicken Stir Fry. What about the ingredients?"

  [ Don't change anything ]    ← just logs the skip
  [ Move to another day ]      ← AI suggests best day based on schedule + expiry
  [ Adjust the week ]          ← AI rebalances remaining days
  [ Tell me more ]             ← opens chat for nuanced situation

Most of the time the user picks one of the quick options.
"Tell me more" is the escape hatch for complex situations.
```

### Another Example

```
Tesco notification: "Substituted: Chicken Thighs 500g → Chicken Breast 500g"

System: "Your Thursday stir fry used chicken thighs. Chicken breast works but cooks differently."

  [ Use it anyway ]            ← update pantry, AI adds a note to the recipe ("used breast, adjust cook time")
  [ Adjust the recipe ]        ← AI modifies cooking method for breast
  [ Swap the meal ]            ← AI suggests alternative recipe for the substituted ingredient
```

## Auto-Detection Opportunities

Some disruptions can be detected without user input:
- **Tesco substitutions** → from order confirmation/delivery email or scrape
- **Use-by dates approaching** → pantry expiry tracking triggers a prompt ("Mince expires tomorrow, you're using it Saturday — want to move the recipe forward?")
- **Nutrition shortfall** → if user logs skipped meals, system notices daily targets are way off and can suggest a higher-protein snack etc.

Things that always need user input:
- Skipped meals (system can't know if you just haven't logged it yet or actually skipped)
- Changed social plans
- Impulse purchases
- "I don't fancy this"

## Design Principles

1. **Never auto-adjust without asking** — the plan is the user's, not the AI's
2. **Quick options for common scenarios** — 80% of disruptions are simple, don't make them fill out forms
3. **Chat escape hatch** — for weird situations the quick options don't cover
4. **Ingredient awareness always** — every adjustment should consider knock-on effects on other meals that week
5. **Nutrition recalculation** — after any adjustment, dashboard updates immediately
6. **Don't nag** — if the user skips a meal and says "don't change anything", respect that. Don't follow up asking if they're sure.
