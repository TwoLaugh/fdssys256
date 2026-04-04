# Feedback System — Design

*Single conversational interface for all user feedback. Classifies, routes, and confirms.*

## What It Is

The Feedback System is the primary way users improve the system over time. It takes free-text input from anywhere in the UI, classifies it using AI, and routes it to the appropriate destination(s). It is a **classifier and router** — it decides *where* feedback goes, not *what to do with it*. The actual update logic lives in each destination.

The system has three concerns:

| Concern | What it does |
|---|---|
| **Classification** | AI interprets free-text feedback and determines which destination(s) it targets |
| **Routing** | Calls the appropriate update service(s) for each classified destination |
| **Confirmation** | Reports back to the user what was updated and where, enabling misclassification correction |

This is **not**:
- The Recipe Optimiser (which does the creative work of adapting recipes — the Feedback System routes recipe feedback to the Optimiser)
- The Nutrition Logger (which records what the user actually ate — the Feedback System records what the user *thought* about what they ate)
- Any of the data models (which own their update logic — the Feedback System just triggers it)

---

## Core Design Principle: Interface Everything for Upgrade

The same principle from the Recipe Optimiser applies here. The classifier is the AI-intensive component, and its implementation should be swappable.

| Capability | v1 Implementation | Future upgrade path |
|---|---|---|
| **Classification** | Single AI call (cheap model) with tool use for structured routing output | Multi-turn clarification when confidence is low. Richer context loading. |
| **Confidence handling** | Low-confidence classifications ask the user to confirm the routing | Classifier learns from correction history, improves over time |
| **Context awareness** | UI screen context passed as metadata (which page, which recipe/meal) | Full session context — what the user has been looking at, recent interactions |
| **Multi-destination splitting** | AI returns multiple destinations in one structured response | Could become an agent that reasons about each destination independently |

---

## Entry Points and Context

Feedback can be given from anywhere in the UI. The screen context provides implicit routing signals that significantly improve classification accuracy.

| UI Context | Implicit signal | Likely destinations |
|---|---|---|
| Recipe detail page | Feedback is about this specific recipe | Recipe (via Optimiser), Preference |
| Plan view (specific meal) | Feedback is about this meal in context | Recipe (via Optimiser), Nutrition, Preference |
| Plan view (general) | Feedback is about the whole plan/week | Preference, Nutrition, Provisions |
| Grocery/shopping screen | Feedback is about cost or availability | Provisions |
| Nutrition dashboard | Feedback is about portions or macro fit | Nutrition |
| Settings/general | No implicit signal — classifier works harder | Any destination |

The UI passes context metadata with every feedback submission:

```json
{
  "text": "This was too salty and really expensive",
  "context": {
    "screen": "plan_meal_detail",
    "recipe_id": 42,
    "recipe_version": 3,
    "meal_slot_id": 156,
    "plan_id": 12,
    "date": "2026-04-15"
  }
}
```

The context is an input to the classifier, not a hard routing rule. Feedback entered on the recipe page *could* be about provisions ("I can't afford these ingredients") — the classifier uses context as a signal, not a constraint.

---

## Four Destinations

### Destination 1: Recipe (via Optimiser)

**What routes here:** Feedback about a specific recipe's quality — taste, seasoning, method, ingredients, texture.

**Examples:**
- "This needed more garlic"
- "The sauce was too thick"
- "Really bland — needs more seasoning"
- "Loved this but the chicken was dry"
- "Too many steps for a weeknight"

**Action:** The Feedback System calls `OptimiserService.handleRecipeFeedback(recipeId, feedback)`. The Optimiser does the creative reasoning and proposes a recipe adaptation. The Feedback System receives the `AdaptationResult` and includes it in the confirmation message.

**Not routed here:** "I don't like chicken" — that's a preference, not a recipe-specific issue. "The portion was too small" — that could be nutrition (calorie target) or preference (portion style), not the recipe itself.

### Destination 2: Preference Model

**What routes here:** Feedback about taste, likes/dislikes, cuisine, cooking style, lifestyle.

**Examples:**
- "I don't like coriander"
- "This week's meals were boring — I want more variety"
- "I prefer lighter meals in summer"
- "I don't want to cook for more than 20 minutes on weeknights"
- "This cuisine isn't for me"

**Action:** The Feedback System calls `PreferenceUpdateService.applyFeedback(userId, feedback)`. The Preference module incorporates the feedback into its delta update pipeline — it may update the taste profile immediately for strong signals (explicit "I don't like X"), or batch it with other feedback for the next scheduled delta update.

**Key distinction from recipe feedback:** "This stir fry was too salty" is recipe feedback (fix the recipe). "I generally don't like very salty food" is preference feedback (update the taste profile so future recipes are less salty).

### Destination 3: Nutrition Model

**What routes here:** Feedback about portions, macro fit, hunger, energy levels, health signals.

**Examples:**
- "The portions have been too small this week"
- "I'm always hungry after lunch"
- "I need more protein"
- "I felt sluggish after dinner — might have been too carb-heavy"
- "I want to increase my calorie target"

**Action:** The Feedback System calls `NutritionUpdateService.applyFeedback(userId, feedback)`. The Nutrition module interprets and applies: portion complaints may adjust per-meal calorie distribution, protein complaints may adjust protein floors, energy/mood observations are logged to the food/mood journal for correlation.

### Destination 4: Provisions

**What routes here:** Feedback about cost, availability, equipment, waste, supplier issues.

**Examples:**
- "This week was too expensive"
- "I couldn't find this ingredient at Tesco"
- "I don't have a food processor"
- "I keep throwing away lettuce"
- "The chicken from Tesco had a really short shelf life"

**Action:** The Feedback System calls `ProvisionUpdateService.applyFeedback(userId, feedback)`. The Provisions module handles each type: cost complaints may prompt a budget review, availability issues update the supplier cache, equipment feedback updates the equipment list, waste observations log to waste tracking, shelf life feedback adjusts expiry estimates.

---

## Multi-Destination Routing

A single piece of feedback can route to multiple destinations. This is common — users don't think in system modules.

**Example:** "This stir fry was too salty and really expensive"
- "too salty" → Recipe (via Optimiser): propose a less salty version
- "really expensive" → Provisions: record cost concern

**Example:** "I'm bored of chicken every day and the portions are too small"
- "bored of chicken" → Preference: reduce chicken frequency preference / increase variety tolerance
- "portions too small" → Nutrition: adjust per-meal calorie distribution

### How multi-destination works

The AI classifier returns a structured response with all identified destinations:

```json
{
  "classifications": [
    {
      "destination": "recipe",
      "confidence": 0.92,
      "extracted_feedback": "Stir fry was too salty",
      "recipe_id": 42
    },
    {
      "destination": "provisions",
      "confidence": 0.85,
      "extracted_feedback": "Recipe was too expensive"
    }
  ]
}
```

The Feedback System processes each classification independently. Each destination write is its own transaction — if the Provisions write fails, the Recipe/Optimiser write still succeeds. Partial success is acceptable, logged, and surfaced to the user.

---

## Classification

### AI Task

```java
public class FeedbackClassificationTask implements AiTask<ClassificationResponse> {
    private final TaskType taskType = TaskType.FEEDBACK_CLASSIFICATION;
    // Model tier: CHEAP (Haiku)
    // Per-task token cap: 5,000

    // Context:
    // - Feedback text
    // - UI context metadata (screen, recipe_id, meal_slot_id, plan_id)
    // - Brief summary of available destinations and what routes where
    //   (so the model knows the routing taxonomy)

    // Tool use schema:
    // {
    //   "classifications": [
    //     {
    //       "destination": "recipe | preference | nutrition | provisions",
    //       "confidence": 0.0-1.0,
    //       "extracted_feedback": "the specific part of the input for this destination",
    //       "recipe_id": optional,
    //       "affects_plan": boolean
    //     }
    //   ]
    // }
}
```

### Confidence handling

| Confidence | Action |
|---|---|
| ≥ 0.8 | Route automatically. Include in confirmation. |
| 0.5 – 0.8 | Route automatically but flag in confirmation: "I think you meant X — correct me if wrong." |
| < 0.5 | Ask the user to clarify: "I'm not sure what to do with this. Did you mean: (a) the recipe needs changing, (b) your preferences need updating, (c) something about the cost?" |

The < 0.5 path is a **service call back to the user**, not an AI multi-turn conversation. The UI presents options based on the classifier's best guesses, the user picks one (or types a clarification), and the classifier runs again with the additional context.

### What the classifier does NOT do

- **Does not reason about the fix.** "Too salty" is classified as recipe feedback. The classifier does not decide whether to reduce salt or add acid — that's the Optimiser's job.
- **Does not update any data model.** It only calls update services. The update logic is in each destination module.
- **Does not have access to full recipe/plan data.** It receives minimal context (screen, IDs) to keep the classification fast and cheap. The destination modules load full data when processing the routed feedback.

---

## Confirmation and Misclassification Correction

After routing, the Feedback System confirms what was done. This is the misclassification detection mechanism — the user sees the routing and can correct it.

### Confirmation message

```json
{
  "feedback_id": "fb-042",
  "original_text": "This stir fry was too salty and really expensive",
  "routes": [
    {
      "destination": "recipe",
      "action_taken": "Proposed adaptation to Chicken Stir Fry: reduce soy sauce, add lime for balance",
      "confidence": 0.92,
      "status": "pending_approval"
    },
    {
      "destination": "provisions",
      "action_taken": "Recorded cost concern for this week's plan",
      "confidence": 0.85,
      "status": "applied"
    }
  ],
  "correction_available": true
}
```

The user sees: "Updated: Proposed recipe change for Chicken Stir Fry (reduce soy sauce, add lime). Noted cost concern for this week."

### Correction flow

If the user thinks the routing was wrong:

1. User taps "This isn't right" on a specific route
2. UI shows the classification and offers alternatives: "I routed 'too salty' to the recipe. Did you mean: (a) the recipe is too salty (correct), (b) you generally dislike salty food (→ preference), (c) something else?"
3. User selects the correct destination
4. The Feedback System re-routes: undoes the original write (if possible) and routes to the corrected destination
5. The correction is logged as ground truth for quality monitoring

### Correction limitations

- **Recipe adaptations that are already pending** can be cancelled (pending change deleted)
- **Preference updates from the last delta batch** can be partially rolled back (the delta is logged)
- **Provisions writes** (cost feedback logged, equipment updated) can be undone
- **Nutrition updates** are harder to undo if they triggered downstream effects (mid-week re-opt). The system logs the correction but may not fully reverse cascading effects.

In practice, most corrections are simple re-routes, not complex undo chains. The confirmation message appears immediately after feedback, so the user catches errors quickly.

---

## Quality Monitoring

Feedback classification accuracy is critical — misrouted feedback silently degrades the wrong data model. The system tracks quality through several signals:

### Metrics

| Metric | Source | What it tells you |
|---|---|---|
| **Correction rate** | User corrections on confirmations | Direct measure of misclassification. Target: < 10% of routes corrected. |
| **Confidence distribution** | Classifier output | If most classifications are < 0.8, the classifier is struggling. May need richer context or a better model. |
| **Destination distribution** | Routing log | If 90% of feedback goes to one destination, either the UI is biased or the classifier is stuck. |
| **Low-confidence clarification rate** | < 0.5 classifications | How often the classifier can't decide. Should decrease over time as prompts improve. |

### Ground truth from corrections

Every user correction is logged:

```json
{
  "feedback_id": "fb-042",
  "original_route": "recipe",
  "corrected_route": "preference",
  "original_confidence": 0.72,
  "feedback_text": "I generally don't like very salty food",
  "ui_context": "plan_meal_detail"
}
```

This builds a dataset of labelled examples. In v1, use this for manual prompt improvement — review corrections periodically, identify patterns, adjust the classifier's prompt. In v2, this dataset could be used for fine-tuning or few-shot examples in the classifier's context.

---

## Feedback Storage

Every feedback entry is stored regardless of routing:

```
feedback_entries:
    feedback_id          text (primary key)
    user_id              bigint
    text                 text
    ui_context           jsonb
    created_at           timestamp

feedback_routing_log:
    routing_id           text (primary key)
    feedback_id          text (foreign key)
    destination          text (recipe/preference/nutrition/provisions)
    confidence           float
    extracted_feedback   text
    action_taken         text
    status               text (applied/pending_approval/failed/corrected)
    correction           jsonb (null if not corrected)
    created_at           timestamp
```

The routing log is the audit trail. Every route is traceable from the original text through classification to the action taken. Corrections are recorded alongside the original route, not as replacements — the full history is preserved.

---

## How It Gets Used

### By the User

Single conversational input from anywhere in the UI. The user types (or speaks) natural language feedback. The system confirms what it understood and what it did. The user corrects if needed.

The Feedback System is designed to feel effortless — the user never needs to know about data models or routing destinations. They just say what they think, and the system figures out what to do.

### By the Recipe Optimiser

Receives recipe-specific feedback via `OptimiserService.handleRecipeFeedback()`. The Optimiser gets the classified, extracted feedback text plus the recipe context. It does the culinary/nutritional reasoning and returns an `AdaptationResult`.

### By the Preference Model

Receives preference feedback via `PreferenceUpdateService.applyFeedback()`. Strong signals ("I don't like coriander") may trigger an immediate taste profile delta. Weaker signals ("this week was a bit boring") are batched with other feedback for the next scheduled update.

### By the Nutrition Model

Receives nutrition feedback via `NutritionUpdateService.applyFeedback()`. Portion complaints adjust per-meal distribution. Energy/mood observations are logged to the food/mood journal. Explicit target changes ("I want more protein") adjust targets directly.

### By the Provision Model

Receives provisions feedback via `ProvisionUpdateService.applyFeedback()`. Cost complaints are logged and may prompt a budget review. Availability issues update supplier cache. Equipment feedback updates the equipment list. Waste observations create waste log entries.

---

## Service Interface

### FeedbackService

```java
public interface FeedbackService {
    FeedbackResult submitFeedback(FeedbackRequest request);
    FeedbackResult correctRoute(String feedbackId, String routingId, String newDestination);
    List<FeedbackEntry> getRecentFeedback(Long userId, int limit);
    ClassificationMetrics getClassificationMetrics(Long userId, DateRange range);
}

public class FeedbackRequest {
    private final String text;
    private final UIContext context;  // screen, recipeId, mealSlotId, planId
}

public class FeedbackResult {
    private final String feedbackId;
    private final List<RouteResult> routes;
}

public class RouteResult {
    private final String destination;
    private final float confidence;
    private final String actionTaken;
    private final String status;       // applied, pending_approval, failed, clarification_needed
    private final Object destinationResult;  // AdaptationResult for recipe, etc.
}
```

### Dependencies

```
FeedbackService injects:
    OptimiserService            ← recipe feedback (Trigger 2)
    PreferenceUpdateService     ← preference feedback
    NutritionUpdateService      ← nutrition feedback
    ProvisionUpdateService      ← provisions feedback
    AiService                   ← classification
```

Note: the Feedback System does **not** inject `RecipeUpdateService` directly. All recipe modifications go through the Optimiser, which owns the culinary/nutritional reasoning. The Optimiser calls `RecipeUpdateService` internally.

### Events published

- `FeedbackProcessedEvent(feedbackId, destinations, userId)` — listened by Notification System. Payload includes which destinations were updated so the notification can confirm to the user.

### API Endpoint

```
POST /api/v1/feedback
    Request:  { text: "...", context: { screen: "...", recipeId: ..., ... } }
    Response: { feedbackId: "...", routes: [ { destination, confidence, actionTaken, status } ] }

POST /api/v1/feedback/{feedbackId}/routes/{routingId}/correct
    Request:  { newDestination: "preference" }
    Response: { feedbackId: "...", routes: [ updated routes ] }

GET /api/v1/feedback?limit=20
    Response: { data: [ recent feedback entries with routing info ] }
```

---

## Interaction with the Logger

The Feedback System and the Nutrition Logger are distinct entry points with different purposes:

- **Logger:** Records *what you ate* — intake correction. "I had a burrito instead of the stir fry." Writes directly to the Nutrition Model's intake tracking.
- **Feedback System:** Records *what you thought about what you ate* — quality and preference signals. "That stir fry was too salty." Routes through the classifier to the appropriate destination(s).

Both accept free-text and involve AI interpretation, but they write to different places and serve different feedback loops. The UI should make it clear which mode the user is in — logging what they ate vs giving feedback on what they ate.

---

## Boundaries with Other Components

| Concern | Lives in | Not in Feedback System because |
|---|---|---|
| Recipe adaptation logic | Recipe Optimiser | Feedback System routes recipe feedback to the Optimiser. The Optimiser reasons about what to change. |
| Taste profile update logic | Preference Model | The delta update pipeline, evidence tracking, and experiment management are preference concerns. The Feedback System just delivers the signal. |
| Nutrition target adjustments | Nutrition Model | The Nutrition Model decides how to interpret "portions too small." The Feedback System just delivers the signal. |
| Provision state changes | Provision Model | Equipment updates, waste logging, budget review logic are provision concerns. |
| Intake tracking (what you ate) | Nutrition Logger | Different purpose, different data flow. The Feedback System handles opinions, the Logger handles facts. |
| Hard constraint changes | Preference Model (user-only) | The Feedback System never modifies hard constraints. "I'm now allergic to nuts" must go through the user's manual hard constraint edit flow, not through AI interpretation. |

---

## Open Questions

- **Feedback on feedback.** Should the system learn from which confirmations the user *doesn't* correct? If the user sees "Routed 'too salty' to recipe" and doesn't correct it, that's a weak positive signal that the classification was correct. This implicit signal is noisy (the user might not have read the confirmation) but at volume it could improve classification.
- **Proactive feedback prompts.** Should the system prompt for feedback at specific moments (after cooking, after eating, end of week)? The current design is purely reactive — the user initiates all feedback. Prompts could increase feedback volume (better taste profile learning) but risk being annoying. Could be a user setting.
