# Key Flows

Sequence diagrams for the critical user journeys.

---

## 1. Weekly Plan Generation

The most complex flow in the system.

```
User                    Frontend            PlannerService         AiService           Other Services
  │                        │                     │                     │                     │
  │  "Generate plan"       │                     │                     │                     │
  │  + overrides           │                     │                     │                     │
  │───────────────────────►│                     │                     │                     │
  │                        │  POST /plans/gen    │                     │                     │
  │                        │────────────────────►│                     │                     │
  │                        │                     │                     │                     │
  │                        │                     │── getProfile() ───────────────────────────►│ ProfileService
  │                        │                     │◄── profile ──────────────────────────────── │
  │                        │                     │── getPreferenceModel() ───────────────────►│ FeedbackService
  │                        │                     │◄── preferences ──────────────────────────── │
  │                        │                     │── getAvailableItems() ────────────────────►│ PantryService
  │                        │                     │◄── pantry items ─────────────────────────── │
  │                        │                     │── getRecipeIndex() ───────────────────────►│ RecipeService
  │                        │                     │◄── recipe index (lightweight) ────────────  │
  │                        │                     │                     │                     │
  │                        │                     │  PASS 1: Select     │                     │
  │                        │                     │  recipes            │                     │
  │                        │                     │────────────────────►│                     │
  │                        │                     │  (profile+prefs+   │                     │
  │                        │                     │   pantry+index+    │                     │
  │                        │                     │   overrides)       │                     │
  │                        │                     │◄────────────────────│                     │
  │                        │                     │  [15-20 recipe IDs] │                     │
  │                        │                     │                     │                     │
  │                        │                     │── getRecipe(id) ──────────────────────────►│ RecipeService
  │                        │                     │   (for each selected)                      │ (full details)
  │                        │                     │◄──────────────────────────────────────────  │
  │                        │                     │                     │                     │
  │                        │                     │  PASS 2: Assemble   │                     │
  │                        │                     │  plan               │                     │
  │                        │                     │────────────────────►│                     │
  │                        │                     │  (profile+prefs+   │                     │
  │                        │                     │   pantry+full      │                     │
  │                        │                     │   recipes+overrides)│                     │
  │                        │                     │◄────────────────────│                     │
  │                        │                     │  [7-day plan JSON]  │                     │
  │                        │                     │                     │                     │
  │                        │                     │── store plan ──────►│ DB                  │
  │                        │                     │── store slots ─────►│ DB                  │
  │                        │                     │── store ing. flow ─►│ DB                  │
  │                        │                     │                     │                     │
  │                        │◄────────────────────│                     │                     │
  │◄───────────────────────│  full plan response │                     │                     │
  │  display plan          │                     │                     │                     │
```

---

## 2. Mark Meal as Cooked

Triggers multiple side effects.

```
User                    Frontend            PlannerService         PantryService       RecipeService
  │                        │                     │                     │                     │
  │  tap "Cooked" on       │                     │                     │                     │
  │  today's dinner        │                     │                     │                     │
  │───────────────────────►│                     │                     │                     │
  │                        │  PUT .../status     │                     │                     │
  │                        │  { "cooked" }       │                     │                     │
  │                        │────────────────────►│                     │                     │
  │                        │                     │                     │                     │
  │                        │                     │── update slot ─────►│ DB (status=cooked)  │
  │                        │                     │                     │                     │
  │                        │                     │── deductIngredients()──────────────────────►│
  │                        │                     │   (recipe ingredients│list)                │
  │                        │                     │                     │── subtract from      │
  │                        │                     │                     │   pantry_items       │
  │                        │                     │                     │── remove empty items │
  │                        │                     │◄─────────────────────│                     │
  │                        │                     │                     │                     │
  │                        │                     │── incrementTimesCooked() ─────────────────►│
  │                        │                     │                     │                     │
  │                        │                     │── populate nutrition log ──►│ DB           │
  │                        │                     │                     │                     │
  │                        │◄────────────────────│                     │                     │
  │◄───────────────────────│  updated slot       │                     │                     │
  │                        │                     │                     │                     │
  │  prompt feedback?      │                     │                     │                     │
  │◄───────────────────────│  "How was dinner?"  │                     │                     │
```

---

## 3. Recipe Import from URL

```
User                    Frontend            RecipeService          AiService           NutritionEngine
  │                        │                     │                     │                     │
  │  paste URL             │                     │                     │                     │
  │───────────────────────►│                     │                     │                     │
  │                        │  POST /recipes/     │                     │                     │
  │                        │  import             │                     │                     │
  │                        │────────────────────►│                     │                     │
  │                        │                     │                     │                     │
  │                        │                     │── fetch URL HTML ──►│ (HTTP client)       │
  │                        │                     │◄── raw HTML ────────│                     │
  │                        │                     │                     │                     │
  │                        │                     │── RECIPE_IMPORT ───►│                     │
  │                        │                     │   (raw HTML)        │                     │
  │                        │                     │◄────────────────────│                     │
  │                        │                     │   {name, ingredients│                     │
  │                        │                     │    steps, servings} │                     │
  │                        │                     │                     │                     │
  │                        │                     │── calculateForRecipe() ───────────────────►│
  │                        │                     │   (raw ingredients)  │                     │
  │                        │                     │                     │  for each ingredient:│
  │                        │                     │                     │  ├─ AI parse         │
  │                        │                     │                     │  ├─ cache check      │
  │                        │                     │                     │  ├─ USDA API (miss)  │
  │                        │                     │                     │  ├─ AI match         │
  │                        │                     │                     │  └─ calculate macros │
  │                        │                     │◄──────────────────────────────────────────  │
  │                        │                     │   {calories, protein,│macros, parsed ings} │
  │                        │                     │                     │                     │
  │                        │                     │── store recipe + version + ingredients ──►│ DB
  │                        │                     │                     │                     │
  │                        │◄────────────────────│                     │                     │
  │◄───────────────────────│  full recipe with   │                     │                     │
  │  review imported recipe│  calculated nutrition│                     │                     │
```

---

## 4. Feedback → Recipe Evolution

```
User                    Frontend            FeedbackService        AiService           RecipeService
  │                        │                     │                     │                     │
  │  "The sauce was too    │                     │                     │                     │
  │   sweet but I loved    │                     │                     │                     │
  │   the texture"         │                     │                     │                     │
  │───────────────────────►│                     │                     │                     │
  │                        │  POST /feedback     │                     │                     │
  │                        │────────────────────►│                     │                     │
  │                        │                     │                     │                     │
  │                        │                     │── FEEDBACK_INTERPRET─►                    │
  │                        │                     │   (feedback text +   │                     │
  │                        │                     │    recipe details +  │                     │
  │                        │                     │    rubric template)  │                     │
  │                        │                     │◄─────────────────────│                     │
  │                        │                     │   {scores, interpret,│                     │
  │                        │                     │    suggested changes}│                     │
  │                        │                     │                     │                     │
  │                        │                     │── store feedback ───►│ DB                  │
  │                        │                     │── updateAvgRating() ──────────────────────►│
  │                        │                     │                     │                     │
  │                        │                     │── check: feedback    │                     │
  │                        │                     │   count % 5 == 0?   │                     │
  │                        │                     │   YES → regenerate  │                     │
  │                        │                     │   preference model  │                     │
  │                        │                     │──────────────────────►                    │
  │                        │                     │◄─────────────────────│                     │
  │                        │                     │── store new pref ───►│ DB                  │
  │                        │                     │   model version      │                     │
  │                        │                     │                     │                     │
  │                        │◄────────────────────│                     │                     │
  │◄───────────────────────│  feedback saved +   │                     │                     │
  │  see AI interpretation │  AI suggestions     │                     │                     │
  │  and suggested changes │                     │                     │                     │
```

---

## 5. Shopping List → Tesco Order → Pantry Update

```
User                    Frontend            ShoppingService        AiService           PantryService
  │                        │                     │                     │                     │
  │  "Order from Tesco"    │                     │                     │                     │
  │───────────────────────►│                     │                     │                     │
  │                        │  POST .../order     │                     │                     │
  │                        │────────────────────►│                     │                     │
  │                        │                     │                     │                     │
  │                        │                     │  for each item:     │                     │
  │                        │                     │── TESCO_PRODUCT ───►│                     │
  │                        │                     │   MATCH              │                     │
  │                        │                     │   ("500g chicken    │                     │
  │                        │                     │    breast")         │                     │
  │                        │                     │◄─────────────────────│                     │
  │                        │                     │   {product, price}  │                     │
  │                        │                     │                     │                     │
  │                        │                     │── Claude browser ───►│                     │
  │                        │                     │   control: search,  │                     │
  │                        │                     │   add to basket     │                     │
  │                        │                     │◄─────────────────────│                     │
  │                        │                     │                     │                     │
  │                        │◄────────────────────│  "Basket ready for  │                     │
  │◄───────────────────────│   review"           │                     │                     │
  │                        │                     │                     │                     │
  │  reviews basket        │                     │                     │                     │
  │  confirms checkout     │                     │                     │                     │
  │───────────────────────►│                     │                     │                     │
  │                        │  POST .../complete  │                     │                     │
  │                        │────────────────────►│                     │                     │
  │                        │                     │── addFromShoppingList() ──────────────────►│
  │                        │                     │   (all purchased items)                    │
  │                        │                     │                     │  create pantry_items │
  │                        │                     │◄──────────────────────────────────────────  │
  │                        │◄────────────────────│                     │                     │
  │◄───────────────────────│  "Order complete,   │                     │                     │
  │                        │   pantry updated"   │                     │                     │
```

---

## 6. Mid-Week Plan Adjustment (Skip with Rebalance)

```
User                    Frontend            PlannerService         AiService           PantryService
  │                        │                     │                     │                     │
  │  Skip Wednesday dinner │                     │                     │                     │
  │  → "Adjust the week"   │                     │                     │                     │
  │───────────────────────►│                     │                     │                     │
  │                        │  POST .../skip      │                     │                     │
  │                        │  {intent:           │                     │                     │
  │                        │   "adjust_week"}    │                     │                     │
  │                        │────────────────────►│                     │                     │
  │                        │                     │                     │                     │
  │                        │                     │── load remaining    │                     │
  │                        │                     │   plan (Thu-Sun)    │                     │
  │                        │                     │── get pantry state ──────────────────────►│
  │                        │                     │◄──────────────────────────────────────────│
  │                        │                     │                     │                     │
  │                        │                     │── PLAN_ADJUSTMENT ─►│                     │
  │                        │                     │   (current plan +   │                     │
  │                        │                     │    skipped meal +   │                     │
  │                        │                     │    remaining pantry+│                     │
  │                        │                     │    "rebalance Thu-  │                     │
  │                        │                     │     Sun to use up  │                     │
  │                        │                     │     Wed ingredients")                     │
  │                        │                     │◄─────────────────────                     │
  │                        │                     │   [adjusted plan]   │                     │
  │                        │                     │                     │                     │
  │                        │                     │── update meal_slots ►│ DB                 │
  │                        │                     │── update ing. flow ─►│ DB                 │
  │                        │                     │── recalc nutrition ─►│ DB                 │
  │                        │                     │                     │                     │
  │                        │◄────────────────────│                     │                     │
  │◄───────────────────────│  updated plan       │                     │                     │
  │  see adjusted Thu-Sun  │  (shows what        │                     │                     │
  │                        │   changed + why)    │                     │                     │
```

---

## 7. Health Check-in + Symptom Correlation

```
User                    Frontend            HealthService          AiService
  │                        │                     │                     │
  │  Evening check-in:     │                     │                     │
  │  mood=3, energy=2,     │                     │                     │
  │  symptoms=[bloating]   │                     │                     │
  │  "felt bloated after   │                     │                     │
  │   lunch"               │                     │                     │
  │───────────────────────►│                     │                     │
  │                        │  POST /health/log   │                     │
  │                        │────────────────────►│                     │
  │                        │                     │── store log ────────►│ DB
  │                        │                     │                     │
  │                        │                     │── check for pattern:│
  │                        │                     │   query recent logs │
  │                        │                     │   with "bloating" + │
  │                        │                     │   what was eaten    │
  │                        │                     │                     │
  │                        │                     │   (if pattern found │
  │                        │                     │    e.g. bloating    │
  │                        │                     │    after chickpeas  │
  │                        │                     │    3/4 times)       │
  │                        │                     │                     │
  │                        │                     │── create notification:
  │                        │                     │   "You've reported  │
  │                        │                     │   bloating after 3  │
  │                        │                     │   of 4 meals with   │
  │                        │                     │   chickpeas"        │
  │                        │                     │                     │
  │                        │◄────────────────────│                     │
  │◄───────────────────────│  logged + insight   │                     │
```
