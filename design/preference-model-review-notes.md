# Preference Model — Review Notes

*Suggestions from multi-perspective analysis (nutritionist, meal prepper, data engineer, household user). Organised by where changes should land.*

---

## A. Changes to make in preference-model.md

### A1. Add `rotation_cycle_weeks` to novelty tolerance
**Source:** Meal prepper
**Problem:** The novelty tolerance section captures per-slot variety (breakfast rotation of 3, dinner high-variety) but has no concept of how long before a *specific recipe* can repeat. Some people are fine seeing the same dinner weekly (taco Tuesday). Others need at least 2-3 weeks before a repeat.
**Suggestion:** Add to `novelty_tolerance`:
```json
"recipe_repeat_cooldown_weeks": {"default": 2, "overrides": {"breakfast": 0}}
```
This is the global minimum gap before the planner re-selects the same recipe. `0` means "no cooldown" (fine for breakfast staples). The planner already tracks what was served when, so this is just a scoring rule.
**Verdict: Should add.** Small, high-value. Directly affects plan quality and is easy to capture at onboarding.

### A2. Add brief note on `notes` field design intent
**Source:** Data engineer
**Problem:** Several notes fields (flavour_preferences, cuisine_preferences, meal_timing) are free-text. The data engineer flagged these as un-queryable and competing for token budget.
**Suggestion:** Don't restructure these — flavour notes like "Responds well to brightness" are genuinely hard to structure and are primarily consumed by the AI as prompt context, not queried programmatically. But add a design rationale note explaining this: notes fields exist for AI context enrichment, not for programmatic querying, and should be kept short. Every notes field should be supplementary to structured data, never the primary storage.
**Verdict: Should add as a short rationale paragraph.** Prevents future contributors from over-relying on notes or trying to query them.

### A3. Add `source` field to ingredient preferences
**Source:** Data engineer
**Problem:** The model says "likes garlic" but doesn't distinguish between explicitly stated preferences (user said "I love garlic"), behaviourally inferred ones (AI noticed garlic in 20 top-rated recipes), and onboarding-seeded ones (user swiped right on garlic bread). This matters because inferred preferences should be easier to override than explicit ones.
**Suggestion:** Add `"source": "feedback" | "inferred" | "onboarding"` to ingredient preference items.
**Verdict: Should add.** Low cost (one field per item), helps the update logic and the user-facing "here's what I think you like" view. Users can see which preferences are their own words vs AI guesses.

### A4. Mention per-section feedback counts as a future consideration
**Source:** Data engineer
**Problem:** `based_on_feedback_count: 87` is top-level, but cuisine preferences might be based on 87 feedbacks while ingredient trending is based on only 3. Per-section counts would help the planner weight sections differently.
**Verdict: Note as future consideration, don't add now.** Adds token cost across every section. The per-item `evidence_count` on ingredients already handles the highest-value case. Revisit if confidence weighting becomes a real problem.

---

## B. Changes to make in system-overview.md

### B1. Fix dead link to preference-model-shape.md
**Line 118** still links to `preference-model-shape.md` which was deleted and merged into `preference-model.md`. Update the link.

### B2. Update Feedback System processing description
**Line 259** says the Preference Model is "AI-generated structured summary, ~2000 tokens, regenerated every 5 feedbacks." The preference model design now uses delta-based updates, not full regeneration. Should update this line to match.

### B3. Update the Preference Model diagram box
**Lines 45-53** show the Preference Model box in the ASCII diagram with a slim set of fields (allergies, likes, dislikes, cuisine, cooking, meal struct). The preference model now covers significantly more ground (batch cooking, reheating, seasonal, eating context, etc.). The diagram doesn't need to list every field, but adding a few key additions like "batch cooking" and "logistics" would signal that the scope has grown. Optional — the diagram is meant to be a summary, not exhaustive.

---

## C. Notes for Household Model design

The household agent's suggestions were the most structurally significant, and almost all belong in a Household Model design doc rather than the preference model itself.

### C1. Preference merge strategy for shared meals
The planner needs to reconcile multiple preference models for shared meals. Proposed approach:
- **Hard constraints:** Union (if anyone is allergic, nobody eats it)
- **Soft preferences:** Weighted scoring where dislikes carry more weight than likes. A meal one person actively dislikes is worse than a meal that's merely not another person's top pick.
- **Configurable weighting:** Some households let the cook's preferences dominate. Others rotate whose turn it is to pick. The household model should have a `preference_priority` setting.

### C2. Per-person, per-day meal schedule
The household model needs a `meal_slot_eaters` structure: who eats which meals on which days, with per-slot metadata (home, packed, eating out, school lunch). This is household logistics, not individual preference. The individual preference model's `meal_structure` and `eating_context` describe what the person *wants*; the household schedule describes what actually *happens*.

### C3. Guest and occasion overrides
Temporary events that affect planning: guest dinners (extra headcount, temporary dietary constraints), birthdays (recipe requests), visiting relatives (temporary hard constraints for the duration). Proposed: a `meal_overrides` list in the household model with date, meal slot, override type, temporary eater constraints, and style overrides.

### C4. School lunchbox constraints
Per-child, per-school rules: nut bans, no heating available, container limits, age-appropriate food formats (finger food for young children), social acceptability concerns. These are environmental constraints on a meal slot, not personal preferences. Belongs in the household model attached to the child's school meal slots.

### C5. Irreconcilable constraint detection
When shared-meal constraint unions are impossible (vegan + keto = almost nothing), the planner should detect this and suggest splitting into individual meals rather than silently failing. The household model should define what happens: flag it, suggest a split, or ask the user.

---

## D. Notes for Recipe Engine design

### D1. Expand `packable` from boolean to structured
**Source:** Household agent, meal prepper
**Current:** `packable: true/false` on recipes.
**Proposed:** Richer packability metadata:
```json
"packability": {
  "works_cold": true,
  "leak_risk": "low",
  "temperature_safe_hours": 5,
  "finger_food": true,
  "reheats_well_in": ["microwave", "oven"]
}
```
This lets the planner match recipes against the preference model's reheating preferences and the household model's school lunchbox constraints. A recipe marked `"reheats_well_in": ["oven"]` won't be scheduled for an office lunch where only a microwave is available.

### D2. Recipe quality/trust scoring for discovered recipes
**Source:** System overview TODO
Web-scraped recipes may have inaccurate nutrition data or unreliable ingredients. The recipe engine should carry a `trust_level` or `data_quality` flag on recipes, distinguishing:
- User-entered (trusted — user verified)
- Imported from URL (medium — structured extraction, may have errors)
- AI-generated (medium — internally consistent but unverified)
- Web-discovered (low — scraped, may be garbage)

The optimiser and planner can then weight or flag low-trust recipes differently.

---

## E. Notes for Provisions design

### E1. Container capacity
**Source:** Meal prepper
The preference model captures batch cooking preferences (prep days, leftover days, freezer tolerance) but the *physical* constraint of "how many containers do you own" lives in Provisions as equipment. If the user has 8 containers and the planner schedules 12 portions of batch cooking, that's a logistics failure. Provisions should track container count, and the planner should check against it.

### E2. Grocery shopping cadence
**Source:** Meal prepper
The system assumes a weekly grocery order, but some people shop twice a week (fresh produce mid-week) or buy in bulk monthly with weekly fresh top-ups. This affects recipe scheduling: fresh-ingredient recipes should go early in the week, hardier ingredients later. Shopping cadence is a Provisions concern (when does new stock arrive?) rather than a preference.

### E3. Defrost lead time
**Source:** Meal prepper, nutritionist
The notification system already handles defrost reminders, but Provisions should track whether the user is willing to defrost overnight in the fridge or only uses quick methods (microwave, cold water). This affects which freezer meals the planner can schedule with short notice.

---

## F. Notes for Nutrition Model design

### F1. Interaction rule with Preference Model's meal timing
**Source:** Nutritionist
The Nutrition Model owns intermittent fasting / eating windows. The Preference Model now has `meal_timing` with preferred meal times. If the Nutrition Model says "eating window 12pm-8pm" but the Preference Model says "breakfast at 7am", there's a contradiction. Define the interaction: the Nutrition Model's eating window is the hard boundary; the Preference Model's timing is the preferred placement *within* that window. The planner should raise a conflict if they're incompatible.

### F2. Portion sizing boundary
**Source:** Nutritionist
The Preference Model has `portion_style` (prefers large-volume, lower-calorie-density plates). The Nutrition Model owns actual calorie/gram targets per meal. These interact: if the Nutrition Model says "400 cal lunch" and the preference says "large volume", the planner should lean toward voluminous low-calorie-density foods (big salads with protein) rather than calorie-dense compact meals. Make this interaction explicit in the Nutrition Model design.

---

## G. Notes for Feedback System design

### G1. Update to reflect delta-based preference updates
**Source:** Data engineer
The feedback system description in the system overview says it "maintains the Preference Model (AI-generated structured summary, ~2000 tokens, regenerated every 5 feedbacks)." This should be updated to match the delta-based approach: the feedback system routes preference-relevant feedback to the preference update pipeline, which proposes deltas (not full regeneration).

### G2. Feedback → preference model update is not the feedback system's job
Clarification: the feedback system *routes* feedback. The preference model update pipeline *consumes* routed feedback and proposes deltas. The feedback system doesn't "maintain" the preference model — it feeds it. This distinction matters for module boundaries.

---

## H. Notes for Planner design

### H1. Failed meal fallback preferences
**Source:** Meal prepper
When a meal fails (burnt, ran out of time, ingredient went off), what does the user default to? A `fallback_meals` concept — "eggs on toast", "freezer stash", "specific takeaway" — would let the planner suggest recovery options and adjust remaining nutritional targets. This could live in the preference model as a small field, or in the planner's re-optimisation logic. Defer to planner design.

### H2. How the planner uses the new preference fields
The preference model now has significantly more information than before (batch cooking logistics, reheating constraints, eating contexts, seasonal preferences, ingredient frequency caps, novelty tolerance per slot). The planner design should explicitly describe how each of these feeds into Phase 1 scoring and Phase 2 augmentation. Key interactions:
- `batch_cooking.prep_days` → schedule batch cooks on these days
- `batch_cooking.max_leftover_days` → limit how far a batch-cooked meal stretches
- `reheating_preferences.exclusions` → don't schedule reheated fish for office lunch
- `eating_context` → match recipe format to location (packed, sit-down)
- `seasonal_preferences` → weight recipe scoring by current season
- `ingredient_preferences.frequency_caps` → hard cap on how often an ingredient appears
- `novelty_tolerance` per slot → control repetition per meal type
- `cooking_preferences.contexts` → match recipe complexity to the cooking context for that meal slot

---

## I. Suggestions considered and rejected

### I1. Weather-reactive preferences (nutritionist, meal prepper)
A weather API integration to nudge the planner toward soups on cold days. Interesting but adds significant complexity (external API dependency, location services) for marginal value. Seasonal preferences are a coarser but much simpler approximation. **Deferred indefinitely.**

### I2. Mindful eating / eating speed (nutritionist)
Clinically relevant but extremely niche for a meal planning system. The system plans meals, it doesn't coach eating behaviour. **Rejected.**

### I3. Remove `meal_structure` from individual preference model entirely (household agent)
The agent argued this belongs only in the household model. Disagree — a single person without a household still has meal structure preferences. The household model needs its *own* per-person schedule that may override or extend the individual preference, but the preference model field should stay. **Rejected, but noted the household model interaction.**

### I4. Per-section feedback counts (data engineer)
Adding `based_on_feedback_count` to every section of the preference JSON. Useful in theory, but adds token cost across every section. The per-item `evidence_count` on ingredients handles the highest-value case. **Deferred — revisit if confidence weighting becomes a real problem.**

### I5. Supplement timing (nutritionist)
When the user takes supplements affects meal slot design. Low priority — supplements don't change recipe selection in most cases. Can be added later without breaking anything. **Deferred.**

### I6. Full temporal stability classification per field (data engineer)
Classifying every preference field as permanent/slow-moving/drifting/seasonal/transient. The schema already implies this (hard constraints are immutable, experiments are volatile, ingredients have evidence counts). Formalising it adds metadata complexity for little practical gain. **Rejected — the structure already communicates stability implicitly.**
