# MealPrep AI — Ideation

## Raw Idea
A meal prep app where:
- Users set constraints (calories, intolerances, preferences, budget)
- AI designs a weekly meal plan + shopping list + recipes/instructions
- Users give feedback → system continuously improves
- Users can make adjustments ("I want lasagna this week, fit it in")
- App can order groceries from Tesco online (or similar)
- Nice frontend, AI does the logic, users mostly view + give feedback

---

## Settled Decisions
- **Single user, single eater** for now — no household/multi-person complexity. Design extensibly so it can be added later, but don't build for it.
- **Design for extensibility** — architecture should support multi-user later but don't build for it yet
- **Pantry/ingredient tracking** — system knows what's in the house
- **Nutrition tracking** — track what's actually eaten, not just what's planned
- **Tiered AI usage** — use cheaper/smaller models for routine tasks (parsing, categorization), frontier models only where reasoning quality matters (plan generation, feedback incorporation)
- **Semi-production grade** — solid architecture, not throwaway, but pragmatic
- **Backup/export, onboarding UX, offline resilience** — later concerns. Don't design anything that makes them impossible, but don't prioritise them now.

---

## Key Questions to Explore

### 1. User Profile & Constraints
- What constraints do we support? (calories, macros, allergies, intolerances, budget, cooking time, skill level, equipment, household size?)
- How do users set these up? (onboarding wizard? settings page?)
- Do constraints change week-to-week or are they relatively stable?

### 2. Meal Plan Generation
- What does a "meal plan" look like? (breakfast/lunch/dinner/snacks x 7 days?)
- Does it account for leftovers and batch cooking?
- How much variety vs. repetition? (some people like eating the same lunch all week)
- Does it factor in what's already in the pantry?

### 3. Feedback Loop
- What kinds of feedback? ("loved this", "hated this", "too much effort", "portion too small"?)
- How granular? (per-meal? per-ingredient? per-recipe?)
- How does the AI incorporate feedback over time? (preference model per user?)

### 4. Shopping & Ordering
- Shopping list generation: group by aisle? by recipe? by store section?
- Tesco integration: API? Browser automation? OAuth?
- Handle substitutions when items are out of stock?
- Budget tracking and optimization?

### 5. Pantry / Ingredient Tracking
- How do ingredients enter the system? (from shopping list auto-add? manual? receipt scan?)
- How do they leave? (auto-deduct when a recipe is cooked? manual?)
- Expiry tracking? ("Use the chicken by Thursday")
- Fuzzy matching? ("500g mince" bought vs "400g beef mince" in recipe)

### 6. Nutrition Tracking
- Auto-calculated from meal plan, or user confirms what they actually ate?
- Daily/weekly macro dashboard?
- How to handle deviations? ("I skipped lunch", "I had a snack that wasn't on the plan")
- Integration with anything? (MyFitnessPal? Apple Health?) — probably not for v1

### 7. Recipe & Instructions
- Source recipes from where? (AI-generated? curated database? user-submitted?)
- How detailed are instructions? (beginner-friendly? assume competence?)
- Step-by-step mode with timers?

### 8. User Adjustments
- "I want pizza on Friday" — how does the system rebalance the week?
- "I'm eating out Wednesday" — skip that meal
- "I bought extra chicken" — use it up
- Swap suggestions: "don't like this, give me alternatives"

### 9. Frontend / UX
- What's the primary view? (weekly calendar? daily view? dashboard?)
- Mobile-first or desktop-first?
- Notifications/reminders? ("Start marinating chicken at 6pm")

### 10. AI Architecture
- Where does the AI sit? (backend service calling an LLM? embedded agent?)
- What context does it need per request? (full history? last 4 weeks? just constraints?)
- Which tasks need frontier models vs cheap models?

---

## Open Design Decisions
<!-- Add decisions as we discuss them -->
