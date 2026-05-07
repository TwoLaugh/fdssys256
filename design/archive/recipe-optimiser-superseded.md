# Recipe Optimiser — Design

*The system's culinary and nutritional intelligence. Adapts individual recipes against constraints while preserving what makes them good.*

## What It Is

The Recipe Optimiser is the only component in the system that reasons about food *as food*. It takes a recipe and the three constraint models (Preference, Nutrition, Provisions) and proposes concrete adaptations — ingredient swaps, portion adjustments, method changes — while preserving the recipe's culinary integrity.

It has three layers of intelligence:

| Layer | What it knows | Example |
|---|---|---|
| **Culinary intelligence** | What makes food taste good. Flavour balance, texture contrast, cooking chemistry, ingredient interactions. | "Swapping cream for coconut cream changes the sweetness — rebalance the spice." |
| **Nutritional intelligence** | Food science at the ingredient level. Nutrient absorption, preparation impact, ingredient pairing for bioavailability. | "Pair this spinach with lemon to boost iron absorption. Soak these lentils overnight." |
| **Constraint satisfaction** | Mechanical swaps for budget, availability, equipment, dietary restrictions. | "Fillet steak → rump steak for budget. Needs longer cooking — adjust method step 3." |

All three layers work together on every adaptation. A constraint swap (layer 3) that violates culinary integrity (layer 1) or misses a nutritional opportunity (layer 2) is a bad adaptation even if it satisfies the constraint.

This is **not**:
- The Recipe Engine (which stores recipes — the Optimiser reads and writes to it but doesn't own the data)
- The Meal Planner (which selects and arranges recipes into weekly plans — the Optimiser works on individual recipes, not plan composition)
- The Nutrition Model (which stores targets and tracks intake — the Optimiser reads targets and reasons about food science, but nutrition calculation lives in the Nutrition Engine)
- The Feedback System (which classifies and routes feedback — the Optimiser receives recipe feedback from the Feedback System and does the actual creative work)

---

## Core Design Principle: Interface Everything for Upgrade

This system assumes AI capabilities will increase and costs will decrease significantly over time. Every intelligence source and reasoning step in the Optimiser is behind an interface, so the implementation can be swapped without changing how consumers interact with the Optimiser.

| Capability | v1 Implementation | Future upgrade path |
|---|---|---|
| **Culinary reasoning** | Baked into prompt templates — the AI model's existing knowledge, guided by explicit rules in the system prompt | Specialised food AI model, or structured culinary knowledge graph loaded into context |
| **Nutritional intelligence** | Baked into prompt templates — rules about nutrient pairing, absorption, preparation impact | Structured food science knowledge base (vector store of nutrient interactions, preparation best practices). Queryable by the Optimiser before generating adaptations. |
| **Constraint checking** | Read from the three data models via query services, passed as context to the AI | Could become real-time tool use — the AI calls tools to check budget, inventory, equipment during reasoning rather than receiving a static snapshot |
| **Recipe understanding** | Full recipe passed as text context to the AI | Recipe embeddings for semantic understanding. The AI reasons about the recipe's "character" via its position in embedding space, not just its ingredient list. |
| **Adaptation quality evaluation** | User feedback (rating the adapted version vs the original) | Automated evaluation via a second AI call that scores the proposed adaptation before presenting it to the user |

The pattern: define the interface by what it *does* (e.g., "assess whether this substitution preserves the recipe's character"), not by *how* it does it. The v1 implementation is a prompt. The v2 implementation might be a knowledge base query + AI reasoning. The consumers don't change.

This principle applies to the entire system but is most critical here — the Optimiser is where the densest AI reasoning lives, and where capability improvements will have the most impact.

---

## Four Triggers

The Optimiser is invoked from four distinct trigger points. Each has different inputs, urgency, and scope.

### Trigger 1: Import / Discovery

A new recipe enters the system (imported from URL, discovered online, AI-generated, manually added). The Optimiser runs it against all three data models and proposes adaptations.

```
RecipeImportedEvent received
         │
         ▼
Load recipe from RecipeQueryService
Load constraints from all three query services
         │
         ▼
Assess fit against constraints:
    Allergies / dietary identity    → hard violations (must fix)
    Equipment gaps                  → hard violations (must fix or flag)
    Nutrition fit                   → soft gaps (could improve)
    Budget fit                      → soft gaps (could improve)
    Preference alignment            → soft (could adjust flavours, method)
         │
         ▼
If hard violations exist:
    Propose adaptations to fix them (ingredient swaps, method changes)
If soft gaps exist:
    Propose optional improvements

         │
         ▼
User catalogue recipe → create pending changes (user reviews diff)
System catalogue recipe → apply adaptations directly, create new version
         │
         ▼
Publish RecipeEvolvedEvent → Nutrition Engine recalculates
```

**Scope:** Single recipe. Runs once on entry. Not urgent — can be async.

### Trigger 2: Post-Feedback

The user has eaten the meal and given recipe-specific feedback. The Feedback System classifies and routes it to the Optimiser (not directly to the Recipe Engine).

```
Feedback System calls OptimiserService.handleRecipeFeedback(recipeId, feedback)
         │
         ▼
Load recipe + all versions + feedback history
Load constraints from all three query services
         │
         ▼
Reason about the feedback with culinary + nutritional intelligence:
    "Too salty" → reduce salt, but also check if the salt was balancing
                  acidity — may need to add acid instead
    "Too dry"  → could be a method issue (overcooking), ingredient issue
                  (not enough fat/sauce), or portion issue
    "Needed more protein" → add protein source that fits the dish's flavour
                            profile, not just any protein
    "Bland"    → could mean under-seasoned, under-salted, or missing umami.
                  Check the user's flavour preferences for guidance.
         │
         ▼
Propose a new version (or branch if the adaptation is a genuine fork)
    Include structured diff + reasoning
         │
         ▼
User catalogue → pending change with diff for user review
System catalogue → apply directly
         │
         ▼
Publish RecipeEvolvedEvent
```

**Scope:** Single recipe. Triggered by user action. Medium urgency — the user expects to see a response.

**Key intelligence:** The Optimiser doesn't just mechanically address the feedback. It reasons about *why* the issue exists and what the best culinary fix is. "Too salty" doesn't always mean "reduce salt" — it might mean "add acid to balance." This is where culinary intelligence earns its keep.

### Trigger 3: Data Model Change

The user updates preferences, changes nutrition targets, or budget shifts. Recipes that were previously well-fitted may now have gaps.

```
DataModelChangedEvent received
         │
         ▼
Identify affected recipes in the system catalogue:
    Which recipes no longer meet the changed constraints?
    (e.g., new allergy → all recipes with that ingredient,
     budget reduced → recipes above the new cost threshold)
         │
         ▼
For each affected system catalogue recipe:
    Run adaptation (same logic as Trigger 1)
    Apply directly, create new version
         │
         ▼
For user catalogue: do NOT auto-adapt.
    Flag affected recipes with a notification:
    "Your budget changed — 3 of your recipes may be over budget.
     Would you like me to suggest alternatives?"
    User can trigger adaptation per-recipe or batch.
         │
         ▼
Publish RecipeEvolvedEvent for each adapted recipe
```

**Scope:** Potentially many recipes. Background task. Low urgency — use the Anthropic Message Batches API (50% cost, 24-hour turnaround) for system catalogue batch adaptation.

### Trigger 4: Plan-Time

During weekly plan composition, the Planner may invoke the Optimiser as a pre-step to expand the pool of well-fitting recipes.

```
Planner calls OptimiserService.adaptForPlan(recipeId, planConstraints)
         │
         ▼
planConstraints includes:
    Budget remaining for the week
    Ingredients already purchased / in inventory
    Nutrition gaps to fill (e.g., "need 50g more protein today")
    Equipment available
    Time constraint for this meal slot
         │
         ▼
Reason about plan-specific adaptations:
    "You have leftover roast chicken — use that instead of buying raw"
    "Budget is tight — swap fillet for rump this week"
    "This recipe is 400 cal but the slot needs 650 — suggest a side"
         │
         ▼
Return adapted recipe as a substitution (plan-level overlay, not a permanent version)
    Unless the adaptation is genuinely better — then propose as a version
```

**Scope:** Single recipe, plan-specific context. Synchronous — the Planner is waiting. Must be fast (use mid-tier model, focused context).

**Key distinction:** Plan-time adaptations are usually **substitutions** (temporary, constraint-driven), not versions (permanent improvements). The Optimiser decides which based on whether the adaptation is objectively better or just situationally necessary.

---

## Culinary Intelligence

### What it preserves

When adapting a recipe, the Optimiser must identify and preserve the recipe's **character** — the thing that makes it that dish and not a different dish. This includes:

- **Flavour identity:** A Thai green curry's character is the coconut + lemongrass + Thai basil combination. Substituting the coconut cream is a bigger character change than substituting the protein.
- **Texture contrasts:** A dish built around crispy-on-soft contrast (fish tacos, crumble) loses its point if the crispy element is removed.
- **Cooking method essence:** A stir fry's character is high-heat, fast cooking. Converting it to a slow-cooker dish is a branch, not a version.
- **Simplicity/complexity balance:** A 5-ingredient weeknight meal shouldn't be "improved" into a 15-ingredient project.

### What it changes freely

- Seasoning levels (salt, spice, acid, sweetness) — these are tuning parameters
- Portion sizes — scaling up or down
- Garnishes and optional ingredients
- Specific brands or product varieties
- Minor method adjustments (timing, temperature)

### How this is implemented (v1)

The Optimiser's prompt template includes explicit rules:

```
When adapting a recipe, follow these principles:

PRESERVE:
- The dish's core flavour identity (the 2-3 ingredients/techniques that define it)
- Texture contrasts that are central to the dish
- The cooking method unless the adaptation specifically requires changing it
- The overall complexity level (don't turn a quick meal into a project)

CHANGE FREELY:
- Seasoning levels, spice quantities, acid/sweetness balance
- Portion sizes and serving counts
- Garnishes and optional ingredients
- Specific product varieties or brands

WHEN SUBSTITUTING AN INGREDIENT:
- Consider what role it plays: flavour, texture, structure, moisture, protein, fat
- Replace with something that fills the same role, not just the same category
- If the substitution changes the dish's character, flag it as a branch, not a version
- Adjust other ingredients to rebalance (e.g., swap cream → coconut cream, add lime to offset sweetness)

NEVER:
- Produce a recipe that no one would want to eat just to satisfy a constraint
- Make a change without explaining why it works culinarily
- Ignore the user's taste profile when choosing between equally valid substitutions
```

**Upgrade path:** These rules could be replaced by or augmented with a culinary knowledge graph — a structured database of ingredient roles, flavour pairings, and substitution maps. The Optimiser would query the knowledge base before reasoning, getting structured data about "cream's role in this recipe is: fat + moisture + mild sweetness" rather than relying on the AI to infer this from the recipe text.

---

## Nutritional Intelligence

### What it knows

The Optimiser understands food science at the ingredient interaction level:

**Nutrient absorption pairing:**
- Iron absorption is enhanced by vitamin C — pair spinach/lentils with lemon/peppers
- Iron absorption is inhibited by calcium — avoid pairing iron-rich meals with dairy-heavy sides
- Zinc absorption is enhanced by animal protein — relevant when planning vegetarian meals
- Fat-soluble vitamins (A, D, E, K) need dietary fat for absorption — don't serve a vitamin-A-rich salad with fat-free dressing

**Preparation impact:**
- Soaking legumes overnight reduces phytic acid (which blocks mineral absorption) and improves digestibility
- Cooking tomatoes significantly increases lycopene bioavailability
- Overcooking vegetables degrades vitamin C and B vitamins
- Fermentation (yoghurt, kimchi, sourdough) enhances mineral bioavailability

**Anti-nutrient awareness:**
- Oxalates in spinach/chard bind calcium — not a good calcium source despite high calcium content
- Phytates in whole grains/legumes bind iron and zinc — soaking/sprouting reduces this
- Tannins in tea/coffee inhibit iron absorption — the Optimiser can note "don't serve tea with this iron-rich meal"

### How it's used

When the Optimiser adapts a recipe, it considers nutritional interactions alongside culinary integrity:

- **Proactive enhancement:** "This recipe is rich in iron. Adding a squeeze of lemon at the end would boost absorption and fits the flavour profile." This is a version improvement, not a constraint response.
- **Preparation suggestions:** "This recipe uses unsoaked lentils. For better nutrition and digestibility, suggest soaking overnight. Adjusted method step 1 and added a note about lead time." This affects the Planner (needs to schedule a prep action the day before).
- **Interaction warnings:** "This meal is your primary iron source today. The planned yoghurt side would inhibit absorption — consider serving the yoghurt with a different meal." This is a plan-level suggestion routed back to the Planner.
- **Constraint-aware nutrition:** When doing a budget swap (expensive ingredient → cheaper alternative), check whether the swap has nutritional implications. "Swapping salmon for chicken saves £3 but loses omega-3. Consider a different omega-3 source this week."

### How this is implemented (v1)

Baked into prompt templates as explicit food science rules. The AI models already have strong food science knowledge — the prompts make key rules explicit and consistent.

```
NUTRITIONAL INTELLIGENCE RULES:

ABSORPTION PAIRING:
- When a recipe is rich in iron (spinach, lentils, red meat), look for
  opportunities to add vitamin C (lemon, peppers, tomatoes) if it fits
  the flavour profile. Note the pairing benefit in the adaptation reasoning.
- When a recipe is rich in iron, flag if a calcium-heavy side is planned
  alongside it (the Planner should avoid this combination).
- Fat-soluble vitamins (A, D, E, K) need dietary fat. If a recipe is rich
  in these but low in fat, suggest adding a fat source.

PREPARATION:
- Legume recipes: suggest overnight soaking if the method doesn't include it.
  Flag the lead time for the Planner.
- Tomato-heavy recipes: note that cooking enhances lycopene bioavailability
  (prefer cooked tomato dishes for lycopene benefits).

WHEN SUBSTITUTING:
- Check nutritional impact alongside culinary impact.
- If a substitution significantly changes the nutritional profile (salmon → chicken
  losing omega-3), note what's lost so the Planner can compensate elsewhere in the week.
```

**Upgrade path:** A structured food science knowledge base — a vector store or relational database of nutrient interactions, preparation effects, and absorption modifiers. The Optimiser queries this before generating adaptations:

```
// v1: knowledge is in the prompt
optimiserPrompt.include(NUTRITIONAL_RULES_TEMPLATE)

// v2: knowledge is queried from a database
interactions = nutritionalKnowledgeService.getInteractions(recipe.ingredients)
optimiserContext.put("nutrient_interactions", interactions)
```

The interface is `NutritionalKnowledgeService` — v1 returns static rules from a config file, v2 queries a vector database, v3 might call a specialised food science model. The Optimiser doesn't care which — it receives structured nutritional guidance and incorporates it into its reasoning.

---

## Adaptation Decision: Version vs Branch vs Substitution

The Optimiser must decide how to store each adaptation. The three categories from the Recipe Engine design:

| Type | When | Storage | Permanence |
|---|---|---|---|
| **Version** | The adaptation is strictly better — you'd never go back | New version on the current branch | Permanent |
| **Branch** | The adaptation produces a genuinely different variant with independent merit | New branch from the current version | Permanent |
| **Substitution** | The adaptation is constraint-driven — not ideal but necessary for this plan/week | Overlay on the existing version | Temporary (plan-scoped or constraint-scoped) |

### Decision logic

```
Is this adaptation objectively better regardless of constraints?
    YES → Version (e.g., "doubled soy sauce based on feedback — consistently better")
    NO  → Continue

Does this adaptation produce a dish with different character that's worth keeping?
    YES → Branch (e.g., "coconut cream version is a different dish — lighter, sweeter, Thai-leaning")
    NO  → Continue

Is this adaptation driven by a temporary or situational constraint?
    YES → Substitution (e.g., "rump steak because fillet is over budget this week")
    NO  → Version (default for non-temporary improvements)
```

The Optimiser includes this classification in its response so the Recipe Engine stores it correctly.

---

## Service Interface

### OptimiserService

```java
public interface OptimiserService {
    // Trigger 1: Import / Discovery
    AdaptationResult adaptNewRecipe(Long recipeId);

    // Trigger 2: Post-Feedback (called by Feedback System)
    AdaptationResult handleRecipeFeedback(Long recipeId, FeedbackDTO feedback);

    // Trigger 3: Data Model Change
    List<AdaptationResult> batchAdaptForDataModelChange(
        Long userId, DataModelChangeType changeType);

    // Trigger 4: Plan-Time (called by Planner)
    AdaptationResult adaptForPlan(Long recipeId, PlanConstraints planConstraints);
}

public class AdaptationResult {
    private final Long recipeId;
    private final AdaptationType type;         // VERSION, BRANCH, SUBSTITUTION, NO_CHANGE
    private final RecipeDiff proposedChanges;   // structured diff
    private final String reasoning;             // culinary + nutritional explanation
    private final String nutritionalNotes;      // absorption pairing, prep suggestions
    private final boolean requiresApproval;     // true for user catalogue
    private final List<PlannerHint> plannerHints; // e.g., "needs overnight soak", "avoid calcium side"
}

public class PlannerHint {
    private final HintType type;  // PREP_LEAD_TIME, ABSORPTION_CONFLICT, NUTRITION_TRADEOFF
    private final String description;
    private final Map<String, Object> data;  // e.g., {"lead_time_hours": 12, "reason": "lentil soaking"}
}
```

**`PlannerHint`** is how the Optimiser communicates plan-level concerns back to the Planner. The Optimiser works on individual recipes but sometimes discovers things the Planner needs to know: "this recipe needs overnight prep," "don't serve this alongside dairy," "this substitution loses omega-3 — compensate elsewhere." The Planner reads hints and adjusts the plan accordingly.

### Dependencies

```
OptimiserService injects:
    PreferenceQueryService     ← hard constraints, taste profile, lifestyle config
    NutritionQueryService      ← nutrition targets, micro targets
    ProvisionQueryService      ← inventory, equipment, budget, supplier prices
    RecipeQueryService         ← recipe data, versions, branches
    RecipeUpdateService        ← writes versions, branches, substitutions, pending changes
    HardConstraintFilterService ← validates adaptations against allergies/dietary identity
    AiService                  ← culinary + nutritional reasoning
    NutritionalKnowledgeService ← food science rules (v1: static config, v2: knowledge base)
```

### Events

**Listens to:**
- `RecipeImportedEvent` → Trigger 1
- `DataModelChangedEvent` → Trigger 3

**Publishes:**
- `RecipeEvolvedEvent` → Nutrition Engine recalculates nutrition for new version/branch

Does not listen to feedback events directly — the Feedback System calls `OptimiserService.handleRecipeFeedback()` as a service call, not an event, because the Feedback System needs the result to confirm what was updated.

---

## AI Task Definition

### Recipe Adaptation Task

```java
public class RecipeAdaptationTask implements AiTask<AdaptationResponse> {
    private final TaskType taskType = TaskType.RECIPE_ADAPTATION;
    // Model tier: MID (Trigger 2, 4) or CHEAP via batches (Trigger 3)
    // Per-task token cap: 8,000

    // Context assembled by the Optimiser:
    // - Full recipe (current version, relevant branch if applicable)
    // - User's hard constraints (allergies, dietary identity)
    // - Taste profile summary (~2500 tokens)
    // - Relevant nutrition targets (macros for the meal slot, micro gaps)
    // - Relevant provisions data (budget remaining, inventory)
    // - Feedback text (Trigger 2 only)
    // - Plan constraints (Trigger 4 only)
    // - Nutritional knowledge rules (from NutritionalKnowledgeService)

    // Tool use schema ensures structured output:
    // {
    //   "adaptation_type": "version | branch | substitution | no_change",
    //   "changes": [ { "field": "...", "from": "...", "to": "...", "reason": "..." } ],
    //   "culinary_reasoning": "...",
    //   "nutritional_notes": "...",
    //   "planner_hints": [ { "type": "...", "description": "...", "data": {...} } ],
    //   "preserves_character": true/false,
    //   "confidence": 0.0-1.0
    // }
}
```

### Failure handling

- AI returns low confidence (< 0.5) → flag for user review rather than auto-applying
- AI returns `preserves_character: false` → never auto-apply, even for system catalogue. Propose as a branch.
- AI task fails entirely → recipe remains unchanged. Log failure. For Trigger 4 (plan-time), the Planner uses the unadapted recipe.
- Trigger 3 batch fails partially → adapted recipes are saved individually. Failed ones retry in the next scheduled run.

---

## Guardrails

### What the Optimiser must never do

- **Violate hard constraints.** Every adaptation passes through `HardConstraintFilterService` before being stored. The Optimiser's AI reasoning is not trusted for allergy safety — the deterministic filter is the final gate.
- **Auto-apply to user catalogue recipes.** Always pending change with user approval. No exceptions.
- **Produce inedible results.** The `preserves_character` field in the AI response is a self-check. If the AI itself says the adaptation breaks the dish, it's flagged. This catches cases where constraint satisfaction overrides culinary sense.
- **Silently change nutrition.** Every adaptation that changes ingredients triggers a Nutrition Engine recalculation via `RecipeEvolvedEvent`. The user sees updated nutrition on the adapted version.

### Quality signal

Per-version ratings are the primary quality signal. If version 2 (post-optimiser) consistently rates lower than version 1 (pre-optimiser), the Optimiser's adaptations are making things worse. The system should track:

- Average rating before vs after optimiser adaptations
- Accept/reject rate on pending changes (user catalogue)
- How often users revert to a previous version after accepting an adaptation

This data doesn't drive automatic changes in v1 — it's a dashboard for the user (and developer) to assess whether the Optimiser is helping. In v2, it could feed back into prompt tuning or knowledge base refinement.

---

## Boundaries with Other Components

| Concern | Lives in | Not in Optimiser because |
|---|---|---|
| Recipe storage, versioning, branching | Recipe Engine | The Optimiser proposes changes. The Recipe Engine stores them. |
| Nutrition calculation (USDA mapping, macro/micro math) | Nutrition Model (engine) | The Optimiser reasons about food science and pairing. The Nutrition Engine does the arithmetic. |
| Nutrition targets and tracking | Nutrition Model | The Optimiser reads targets as constraints. It doesn't set or track them. |
| Plan composition and scheduling | Meal Planner | The Optimiser adapts individual recipes. The Planner arranges them across a week. |
| Feedback classification and routing | Feedback System | The Feedback System decides *where* feedback goes. The Optimiser decides *what to do* with recipe feedback. |
| Hard constraint enforcement | Hard Constraint Filter (Preference module) | The Optimiser proposes adaptations. The filter validates them deterministically. |
| User preferences and lifestyle | Preference Model | The Optimiser reads preferences as context for adaptations. It doesn't update preferences. |
| Inventory, budget, equipment | Provision Model | The Optimiser reads provisions as constraints. It doesn't update provisions. |

### Key interaction rules

**Optimiser → Planner (via PlannerHints).** The Optimiser sometimes discovers plan-level concerns while working on individual recipes: prep lead times, absorption conflicts, nutritional trade-offs from substitutions. These are communicated as `PlannerHint` objects in the `AdaptationResult`. The Planner reads them and adjusts. The Optimiser never modifies the plan directly.

**Feedback System → Optimiser (service call, not event).** The Feedback System calls `OptimiserService.handleRecipeFeedback()` synchronously because it needs the result to confirm to the user what was changed. This is a service call, not an event — the Feedback System is a consumer of the Optimiser, not a publisher that the Optimiser listens to.

**Optimiser → Nutrition Engine (via event).** After creating a version or branch, the Optimiser publishes `RecipeEvolvedEvent`. The Nutrition Engine listens and recalculates nutrition for the new version. The Optimiser does not calculate nutrition itself — it reasons about food science (qualitative), the Nutrition Engine calculates numbers (quantitative).

---

## Open Questions

- **Adaptation aggressiveness.** How much should the Optimiser change on import? A discovered recipe that's 80% aligned with preferences could be left mostly alone (conservative) or tweaked to 95% alignment (aggressive). The right default probably depends on the user's attitude — some people want recipes adapted to them, others want to try recipes as-is and give feedback. This could be a user setting.
- **Cross-recipe nutritional optimisation.** The Optimiser works on individual recipes but nutritional intelligence sometimes requires reasoning across the plan ("don't pair this iron-rich dinner with a calcium-heavy side"). Currently this is handled via `PlannerHint`, which the Planner must interpret. Should the Optimiser have access to the current plan context in Triggers 1-3, not just Trigger 4? This would make PlannerHints more specific but significantly increases context size.
